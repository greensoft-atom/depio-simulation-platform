-- The schema: every table, as a first launch makes it (docs/detailed-design/06-persistence-mysql.md §3).
--
-- Squashed on 2026-10-04 from V1 to V34, the steps by which it grew (D-75; they are in git, to commit
-- ce30aa7), and proved the same table by table but for two changes made on purpose: V17's board indexes not
-- made (D-41), and season_place.rated as wide as the counts it copies (D-42). V2__seed.sql writes the rows a
-- first launch needs.
-- From V3 on, migrations are forward-only again: an applied one is never edited (06 §8).
--
-- Conventions (06 §2): times are DATETIME(3), always UTC; money is BIGINT, never a float; every table
-- InnoDB, utf8mb4 with utf8mb4_0900_ai_ci, which ignores case and accents where a name is unique.
-- Codes stored as numbers are listed where their column is; the Java constants are the source.
-- Each column keeps its place from the steps, so a dump from before the squash restores as it was.
--
-- Tables, by domain:
--   identity and profile   account, player, player_stat, player_day
--   economy                ledger, inventory_item, equipment, boost, boost_activation, payment_order
--   match history          matches, match_player, match_team
--   seasons and boards     season, season_place, season_team_place, season_team_payee, season_pass
--   daily goals            daily_goal
--   teams                  team, team_member, team_invite, team_application
--   tournaments            tournament, tournament_entry, tournament_match, tournament_team_entry,
--                          tournament_roster
--   social                 friend, friend_request, block, inbox
--   operations             admin_audit, ha_epoch, ha_heartbeat, backup_run


-- ============================================================================================
-- Identity and profile
-- ============================================================================================

-- A login: a name and a password's argon2id hash, or a guest's device key. One account, one player.
CREATE TABLE account (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  username       VARCHAR(32)    NOT NULL,
  username_key   VARCHAR(32) AS (LOWER(username)) STORED,     -- unique whatever the case
  password_hash  VARBINARY(255) NOT NULL,
  status         TINYINT UNSIGNED NOT NULL DEFAULT 0,         -- 0 active, 1 suspended, 2 banned
  banned_until   DATETIME(3) NULL,                            -- a suspension's end
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_login_at  DATETIME(3) NULL,
  guest_key_hash BINARY(32) NULL,                             -- SHA-256 of a guest's device key; NULL once upgraded (D-46)
  UNIQUE KEY uq_account_username (username_key),
  UNIQUE KEY uq_account_guest (guest_key_hash),
  KEY ix_account_created (created_at)                         -- the funnel's cohorts (05 §11)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The player an account plays as: profile, progress, balances and ratings. Its id is the account's.
CREATE TABLE player (
  id              BIGINT UNSIGNED PRIMARY KEY,
  public_code     CHAR(12)    NOT NULL,                       -- what others see and search by
  display_name    VARCHAR(32) NOT NULL,
  level           SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  xp              BIGINT UNSIGNED   NOT NULL DEFAULT 0,
  coins           BIGINT NOT NULL DEFAULT 0,                  -- moved only through the ledger (06 §4)
  gems            BIGINT NOT NULL DEFAULT 0,                  -- likewise
  rating_duel     SMALLINT UNSIGNED NOT NULL DEFAULT 1200,
  team_id         BIGINT UNSIGNED NULL,
  version         INT UNSIGNED NOT NULL DEFAULT 0,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  rated_duels     INT UNSIGNED NOT NULL DEFAULT 0,            -- the duel's Elo K falls from 32 to 16 after 30
  rating_tvt      SMALLINT UNSIGNED NOT NULL DEFAULT 1200,    -- team-vs-team (D-26)
  rated_tvts      INT UNSIGNED NOT NULL DEFAULT 0,
  rating_rffa     SMALLINT UNSIGNED NOT NULL DEFAULT 1200,    -- ranked free-for-all (D-28)
  rated_rffas     INT UNSIGNED NOT NULL DEFAULT 0,
  team_left_at    DATETIME(3) NULL,                           -- the 24-hour cooldown counts from it
  first_played_on DATE NULL,                                  -- the earliest day in player_day, kept past its 90 days
  -- Each rating board's rating for a player listed, ten rated matches in its mode, else NULL: the
  -- unlisted are in no range a board reads (D-57). Virtual: computed into the index on write.
  board_duel      SMALLINT UNSIGNED AS (IF(rated_duels >= 10, rating_duel, NULL)) VIRTUAL,
  board_rffa      SMALLINT UNSIGNED AS (IF(rated_rffas >= 10, rating_rffa, NULL)) VIRTUAL,
  board_tvt       SMALLINT UNSIGNED AS (IF(rated_tvts >= 10, rating_tvt, NULL)) VIRTUAL,
  name_changed_at DATETIME(3) NULL,                           -- one rename in 30 days (04 §1)
  UNIQUE KEY uq_player_code (public_code),
  KEY ix_player_first_played (first_played_on),
  KEY idx_player_listed_duel (board_duel DESC, rated_duels DESC, id),   -- the boards, in their order
  KEY idx_player_listed_rffa (board_rffa DESC, rated_rffas DESC, id),
  KEY idx_player_listed_tvt (board_tvt DESC, rated_tvts DESC, id),
  CONSTRAINT fk_player_account FOREIGN KEY (id) REFERENCES account (id),
  -- Last line of defence. The one code path that moves a balance refuses to overdraw;
  -- this turns a bug into a failed transaction rather than a negative balance.
  CONSTRAINT ck_player_balances CHECK (coins >= 0 AND gems >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Lifetime totals, written by every result; apart from player so results do not contend with logins.
CREATE TABLE player_stat (
  player_id  BIGINT UNSIGNED PRIMARY KEY,
  matches    INT UNSIGNED NOT NULL DEFAULT 0,
  wins       INT UNSIGNED NOT NULL DEFAULT 0,
  kills      INT UNSIGNED NOT NULL DEFAULT 0,
  deaths     INT UNSIGNED NOT NULL DEFAULT 0,
  best_score INT UNSIGNED NOT NULL DEFAULT 0,
  playtime_s BIGINT UNSIGNED NOT NULL DEFAULT 0,
  assists    INT UNSIGNED NOT NULL DEFAULT 0,                 -- hits shortly before another's kill (01 §7)
  CONSTRAINT fk_stat_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Activity: a row a player a day a match of theirs ended, written by the result's transaction (D-47).
CREATE TABLE player_day (
  day       DATE NOT NULL,                                    -- UTC
  player_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (day, player_id)                                -- a day's players are one range; retention deletes by it
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Economy
-- ============================================================================================

-- Every move of a balance, and the only way one moves (EconomyRepository.move): the row and the
-- balance in one transaction, so the nightly reconciliation finds them agreeing.
CREATE TABLE ledger (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  player_id     BIGINT UNSIGNED NOT NULL,
  currency      TINYINT UNSIGNED NOT NULL,                    -- 0 coins, 1 gems
  delta         BIGINT NOT NULL,
  balance_after BIGINT NOT NULL,
  -- 0 match reward, 1 purchase, 2 refund, 3 tournament prize, 4 item level, 5 milestone, 6 season,
  -- 7 achievement, 8 daily goal, 9 payment, 10 payment refund, 11 pass tier (EconomyRepository)
  reason        TINYINT UNSIGNED NOT NULL,
  ref           VARCHAR(64) NOT NULL,
  idem_key      VARCHAR(80) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- The idempotency mechanism for currency: a retry violates this and is caught.
  UNIQUE KEY uq_ledger_idem (idem_key),
  KEY ix_ledger_player (player_id, created_at),
  CONSTRAINT fk_ledger_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What a player holds: equipment and boosts by the content's item id, with an equipment's level.
CREATE TABLE inventory_item (
  player_id   BIGINT UNSIGNED NOT NULL,
  item_id     VARCHAR(40) NOT NULL,
  qty         INT UNSIGNED NOT NULL DEFAULT 0,
  item_level  TINYINT UNSIGNED NOT NULL DEFAULT 1,
  acquired_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- Clusters a player's whole inventory together, so loading it is one sequential read.
  PRIMARY KEY (player_id, item_id),
  CONSTRAINT fk_inv_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What a player wears, a slot each.
CREATE TABLE equipment (
  player_id BIGINT UNSIGNED NOT NULL,
  slot      TINYINT UNSIGNED NOT NULL,                        -- 0 barrel, 1 armor, 2 core, 3 treads, 4 skin (Items)
  item_id   VARCHAR(40) NOT NULL,
  PRIMARY KEY (player_id, slot),
  CONSTRAINT fk_equip_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A boost's run, kept after it ends so a result applied late still finds the boost its match ended
-- in (D-38); retention deletes it later, by its end.
CREATE TABLE boost (
  player_id  BIGINT UNSIGNED NOT NULL,
  kind       TINYINT UNSIGNED NOT NULL,                       -- 0 experience, 1 coins
  item_id    VARCHAR(40) NOT NULL,
  percent    TINYINT UNSIGNED NOT NULL,
  started_at DATETIME(3) NOT NULL,
  ends_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (player_id, kind, started_at),
  KEY ix_boost_ends (ends_at),
  CONSTRAINT fk_boost_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A key used to activate a boost, so a retry takes no second item.
CREATE TABLE boost_activation (
  idem_key  VARCHAR(80) NOT NULL PRIMARY KEY,                 -- act:{playerId}:{key}
  player_id BIGINT UNSIGNED NOT NULL,
  item_id   VARCHAR(40) NOT NULL,
  at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY ix_activation_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Gems bought for money: an order its provider confirms, simulated until the owner chooses one
-- (D-68, Q-52); granted once, refunded once, what a refund could not take back kept as a debt.
CREATE TABLE payment_order (
  id           CHAR(36) PRIMARY KEY,
  player_id    BIGINT UNSIGNED NOT NULL,
  client_key   CHAR(36) NOT NULL,                             -- the client's idempotency key
  product_id   VARCHAR(64) NOT NULL,
  gems         INT UNSIGNED NOT NULL,
  price_cents  INT UNSIGNED NOT NULL,
  currency     CHAR(3) NOT NULL,
  provider     VARCHAR(16) NOT NULL,
  state        TINYINT UNSIGNED NOT NULL,                     -- 0 pending, 1 paid, 2 declined, 3 refunded, 4 expired
  bonus        INT UNSIGNED NOT NULL DEFAULT 0,               -- the first purchase's extra gems
  debt         INT UNSIGNED NOT NULL DEFAULT 0,               -- gems a refund could not take back
  created_at   DATETIME(3) NOT NULL,
  confirmed_at DATETIME(3) NULL,
  refunded_at  DATETIME(3) NULL,
  UNIQUE KEY uq_order_client_key (player_id, client_key),
  KEY ix_order_state (state, created_at),
  CONSTRAINT fk_order_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Match history
-- ============================================================================================

-- A match, or one player's stay in the open arena (kind). Not `match`: MATCH is reserved in MySQL
-- (MATCH ... AGAINST), which would force backticks in every query.
CREATE TABLE matches (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  match_uid     CHAR(26) NOT NULL,                            -- the arena's ULID
  -- 0 ffa, 1 duel, 2 tvt, 3 rffa, 4 coop, 5 teams, 6 domination, 7 tag, 8 maze, 9 sandbox (MatchMode)
  mode          TINYINT UNSIGNED NOT NULL,
  kind          TINYINT UNSIGNED NOT NULL DEFAULT 0,          -- 0 a stay in the open arena, 1 a timed match (D-15)
  arena         VARCHAR(32) NOT NULL,
  tournament_id BIGINT UNSIGNED NULL,
  started_at    DATETIME(3) NOT NULL,
  ended_at      DATETIME(3) NOT NULL,
  cut_short     BOOLEAN NOT NULL DEFAULT FALSE,               -- closed by an operator or a failed room: placements as they stood
  UNIQUE KEY uq_match_uid (match_uid),
  KEY ix_match_tournament (tournament_id),
  KEY ix_match_ended (ended_at)                               -- retention deletes by age, oldest first
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Each player's part of a match.
CREATE TABLE match_player (
  match_id     BIGINT UNSIGNED NOT NULL,
  player_id    BIGINT UNSIGNED NOT NULL,
  team         TINYINT UNSIGNED NOT NULL,
  placement    SMALLINT UNSIGNED NOT NULL,
  kills        SMALLINT UNSIGNED NOT NULL,
  deaths       SMALLINT UNSIGNED NOT NULL,
  score        INT UNSIGNED NOT NULL,
  xp_gained    INT UNSIGNED NOT NULL,
  rating_delta SMALLINT NOT NULL DEFAULT 0,
  assists      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  -- This composite key IS the idempotency mechanism for results: the insert is both the
  -- write and the duplicate check, so there is no window in which they disagree.
  PRIMARY KEY (match_id, player_id),
  KEY ix_mp_player (player_id, match_id DESC),
  CONSTRAINT fk_mp_match  FOREIGN KEY (match_id)  REFERENCES matches (id),
  CONSTRAINT fk_mp_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Teams (before match_team only for reading order; match_team has no key to team)
-- ============================================================================================

-- A team: its name, members' count, and its own rating and record from team matches (D-43).
CREATE TABLE team (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(64) NOT NULL,                         -- a display name's rules; unique by the collation,
  member_count  TINYINT UNSIGNED NOT NULL,                    -- which ignores case and accents
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  rating        SMALLINT UNSIGNED NOT NULL DEFAULT 1200,
  rated_matches INT UNSIGNED NOT NULL DEFAULT 0,              -- K 32, then 16 after 30
  wins          INT UNSIGNED NOT NULL DEFAULT 0,
  losses        INT UNSIGNED NOT NULL DEFAULT 0,
  draws         INT UNSIGNED NOT NULL DEFAULT 0,
  renamed_at    DATETIME(3) NULL,                             -- one rename in 30 days
  board_rating  SMALLINT UNSIGNED AS (IF(rated_matches >= 10, rating, NULL)) VIRTUAL,   -- listed, as player's (D-65)
  UNIQUE KEY uq_team_name (name),
  KEY idx_team_listed (board_rating DESC, rated_matches DESC, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_member (
  team_id   BIGINT UNSIGNED NOT NULL,
  player_id BIGINT UNSIGNED NOT NULL,
  role      TINYINT UNSIGNED NOT NULL,                        -- 0 member, 1 vice leader, 2 leader
  joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (team_id, player_id),
  UNIQUE KEY uq_member_player (player_id),                    -- one team a player, held by the schema too
  CONSTRAINT fk_member_team FOREIGN KEY (team_id) REFERENCES team (id),
  CONSTRAINT fk_member_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_invite (
  team_id    BIGINT UNSIGNED NOT NULL,
  player_id  BIGINT UNSIGNED NOT NULL,
  invited_by BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (team_id, player_id),
  KEY ix_invite_player (player_id),
  KEY ix_invite_expiry (expires_at),                          -- retention deletes the lapsed
  CONSTRAINT fk_invite_team FOREIGN KEY (team_id) REFERENCES team (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A player asks a team to take them (Q-49); a declined one is kept until it lapses, so it is not sent again.
CREATE TABLE team_application (
  team_id    BIGINT UNSIGNED NOT NULL,
  player_id  BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  declined   BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (team_id, player_id),
  KEY ix_application_player (player_id, expires_at),
  KEY ix_application_expiry (expires_at),
  CONSTRAINT fk_application_team FOREIGN KEY (team_id) REFERENCES team (id),
  CONSTRAINT fk_application_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A team's part of a team match.
CREATE TABLE match_team (
  match_id     BIGINT UNSIGNED NOT NULL,
  side         TINYINT UNSIGNED NOT NULL,                     -- 1 or 2, as match_player.team
  team_id      BIGINT UNSIGNED NOT NULL,                      -- no key to team: a disbanded team's rows stay
  placement    TINYINT UNSIGNED NOT NULL,                     -- 1 won or drew, 2 lost
  rating_delta SMALLINT NOT NULL,
  PRIMARY KEY (match_id, side),
  KEY ix_match_team_team (team_id, match_id),
  CONSTRAINT fk_mt_match FOREIGN KEY (match_id) REFERENCES matches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Seasons and boards
-- ============================================================================================

-- A season of the rating boards: two calendar months, ending at 00:00 UTC on the first of January,
-- March, May, July, September and November (SeasonRepository.endAfter). Its close places, pays and
-- resets the boards in steps, each recorded so a worker that stops resumes it (D-63).
CREATE TABLE season (
  id            SMALLINT UNSIGNED PRIMARY KEY,
  starts_at     DATETIME(3) NOT NULL,
  ends_at       DATETIME(3) NOT NULL,
  placed_at     DATETIME(3) NULL,
  paid_at       DATETIME(3) NULL,
  reset_bound   BIGINT UNSIGNED NULL,
  reset_through BIGINT UNSIGNED NOT NULL DEFAULT 0,
  reset_at      DATETIME(3) NULL,
  team_bound    BIGINT UNSIGNED NULL,                         -- the teams' reset, as the players' (D-65)
  team_reset_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A past season's places on each player board, and the gems each was paid.
CREATE TABLE season_place (
  season_id  SMALLINT UNSIGNED NOT NULL,
  board      TINYINT UNSIGNED NOT NULL,                       -- the mode's id: 1 duel, 2 tvt, 3 rffa
  place      INT UNSIGNED NOT NULL,
  player_id  BIGINT UNSIGNED NOT NULL,
  rating     SMALLINT UNSIGNED NOT NULL,
  rated      INT UNSIGNED NOT NULL,                           -- as the counts it copies (V27 had SMALLINT, D-42)
  gems       SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (season_id, board, place),
  UNIQUE KEY uq_season_board_player (season_id, board, player_id),
  KEY ix_season_place_player (player_id, season_id),
  CONSTRAINT fk_sp_season FOREIGN KEY (season_id) REFERENCES season (id),
  CONSTRAINT fk_sp_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A past season's places on the board of teams (D-65).
CREATE TABLE season_team_place (
  season_id SMALLINT UNSIGNED NOT NULL,
  place     INT UNSIGNED NOT NULL,
  team_id   BIGINT UNSIGNED NOT NULL,
  name      VARCHAR(64) NOT NULL,
  rating    SMALLINT UNSIGNED NOT NULL,
  rated     INT UNSIGNED NOT NULL,
  gems      SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (season_id, place),
  UNIQUE KEY uq_season_team (season_id, team_id),
  CONSTRAINT fk_stp_season FOREIGN KEY (season_id) REFERENCES season (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Whom a team's place paid: its members at the season's end who played a rated team match in it.
CREATE TABLE season_team_payee (
  season_id SMALLINT UNSIGNED NOT NULL,
  player_id BIGINT UNSIGNED NOT NULL,
  team_id   BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (season_id, player_id),
  CONSTRAINT fk_stpay_season FOREIGN KEY (season_id) REFERENCES season (id),
  CONSTRAINT fk_stpay_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The season pass (D-69): a player's points in a season and, for each track, the last tier paid.
CREATE TABLE season_pass (
  player_id    BIGINT UNSIGNED NOT NULL,
  season_id    SMALLINT UNSIGNED NOT NULL,
  points       INT UNSIGNED NOT NULL DEFAULT 0,
  premium      BOOLEAN NOT NULL DEFAULT FALSE,
  free_paid    TINYINT UNSIGNED NOT NULL DEFAULT 0,
  premium_paid TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (player_id, season_id),
  KEY ix_pass_season (season_id),
  CONSTRAINT fk_pass_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Daily goals
-- ============================================================================================

-- A player's progress on their day's goals, which are drawn, not stored (D-66); kept a week.
CREATE TABLE daily_goal (
  player_id BIGINT UNSIGNED NOT NULL,
  day       DATE NOT NULL,                                    -- the result's UTC day
  goal_id   VARCHAR(24) NOT NULL,                             -- DailyGoals' id, such as wins_1
  progress  INT UNSIGNED NOT NULL,
  PRIMARY KEY (player_id, day, goal_id),
  KEY ix_daily_goal_day (day),
  CONSTRAINT fk_dg_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Tournaments
-- ============================================================================================

-- A tournament (D-40, D-44): its state machine lives on this row, and every transition is a
-- conditional update on its state and version.
CREATE TABLE tournament (
  id                BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name              VARCHAR(64) NOT NULL,
  state             TINYINT UNSIGNED NOT NULL,                -- 0 registration, 1 seeded, 2 running, 3 finished, 4 cancelled
  version           INT UNSIGNED NOT NULL DEFAULT 0,
  max_entries       TINYINT UNSIGNED NOT NULL,
  registration_ends DATETIME(3) NOT NULL,
  starts_at         DATETIME(3) NOT NULL,
  round_minutes     TINYINT UNSIGNED NOT NULL,
  prize_1           BIGINT UNSIGNED NOT NULL,
  prize_2           BIGINT UNSIGNED NOT NULL,
  prize_3           BIGINT UNSIGNED NOT NULL,                 -- to each semi-final's loser
  current_round     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  round_ended_at    DATETIME(3) NULL,
  mode              TINYINT UNSIGNED NOT NULL DEFAULT 1,      -- MatchMode's id: 1 duel, 5 team match
  format            TINYINT UNSIGNED NOT NULL DEFAULT 0,      -- 0 single elimination, 1 round robin
  KEY ix_tournament_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tournament_entry (
  tournament_id BIGINT UNSIGNED NOT NULL,
  player_id     BIGINT UNSIGNED NOT NULL,
  seed          TINYINT UNSIGNED NULL,                        -- set at seeding
  registered_at DATETIME(3) NOT NULL,
  PRIMARY KEY (tournament_id, player_id),
  CONSTRAINT fk_entry_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id),
  CONSTRAINT fk_entry_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The bracket, or a round robin's pairings. Players' ids, or teams' when the mode is 5.
CREATE TABLE tournament_match (
  tournament_id BIGINT UNSIGNED NOT NULL,
  round         TINYINT UNSIGNED NOT NULL,                    -- 1 is the first
  slot          TINYINT UNSIGNED NOT NULL,                    -- 0.. within the round
  player_a      BIGINT UNSIGNED NULL,                         -- from the even slot of the round before
  player_b      BIGINT UNSIGNED NULL,                         -- from the odd; the seeds are the entries'
  state         TINYINT UNSIGNED NOT NULL,                    -- 0 pending, 1 ready, 2 done
  match_uid     CHAR(26) NULL,
  ready_at      DATETIME(3) NULL,
  winner        BIGINT UNSIGNED NULL,
  PRIMARY KEY (tournament_id, round, slot),
  KEY ix_tmatch_uid (match_uid),
  CONSTRAINT fk_tmatch_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tournament_team_entry (
  tournament_id BIGINT UNSIGNED NOT NULL,
  team_id       BIGINT UNSIGNED NOT NULL,                     -- no key to team: a disbanded team's entry stays
  seed          TINYINT UNSIGNED NULL,
  registered_at DATETIME(3) NOT NULL,
  PRIMARY KEY (tournament_id, team_id),
  CONSTRAINT fk_tentry_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tournament_roster (
  tournament_id BIGINT UNSIGNED NOT NULL,
  team_id       BIGINT UNSIGNED NOT NULL,
  player_id     BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (tournament_id, player_id),                     -- one roster a player a tournament
  KEY ix_roster_team (tournament_id, team_id),
  CONSTRAINT fk_roster_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id),
  CONSTRAINT fk_roster_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Social
-- ============================================================================================

CREATE TABLE friend (
  player_id BIGINT UNSIGNED NOT NULL,
  friend_id BIGINT UNSIGNED NOT NULL,
  since     DATETIME(3) NOT NULL,
  PRIMARY KEY (player_id, friend_id),                         -- both ways: a friendship is two rows
  CONSTRAINT fk_friend_player FOREIGN KEY (player_id) REFERENCES player (id),
  CONSTRAINT fk_friend_friend FOREIGN KEY (friend_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE friend_request (
  from_id    BIGINT UNSIGNED NOT NULL,
  to_id      BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,                            -- seven days
  PRIMARY KEY (from_id, to_id),
  KEY ix_request_to (to_id),
  KEY ix_request_expiry (expires_at),                         -- retention deletes the lapsed
  CONSTRAINT fk_request_from FOREIGN KEY (from_id) REFERENCES player (id),
  CONSTRAINT fk_request_to FOREIGN KEY (to_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE block (
  player_id  BIGINT UNSIGNED NOT NULL,
  blocked_id BIGINT UNSIGNED NOT NULL,
  since      DATETIME(3) NOT NULL,
  PRIMARY KEY (player_id, blocked_id),
  KEY ix_block_blocked (blocked_id),
  CONSTRAINT fk_block_player FOREIGN KEY (player_id) REFERENCES player (id),
  CONSTRAINT fk_block_blocked FOREIGN KEY (blocked_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inbox (
  id         BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  player_id  BIGINT UNSIGNED NOT NULL,
  -- 1 friend request, 2 accepted, 3 team invite, 4 prize, 5 team application, 6 season reward (InboxRepository)
  kind       TINYINT UNSIGNED NOT NULL,
  ref        BIGINT UNSIGNED NOT NULL,                        -- the other player's, team's or tournament's id; a reward's season x 10 + board
  created_at DATETIME(3) NOT NULL,
  read_at    DATETIME(3) NULL,
  UNIQUE KEY uq_inbox_item (player_id, kind, ref),
  KEY ix_inbox_created (created_at),                          -- retention
  CONSTRAINT fk_inbox_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================================
-- Operations
-- ============================================================================================

-- Every admin call, allowed or refused (04 §10, D-30): when, which call, its target, what it asked,
-- and what came of it; the change a call makes and its row here are written in one transaction.
CREATE TABLE admin_audit (
  id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  at       DATETIME(3)     NOT NULL,
  action   VARCHAR(32)     NOT NULL,
  target   VARCHAR(128)    NULL,
  request  VARCHAR(1024)   NULL,
  outcome  VARCHAR(32)     NOT NULL,
  PRIMARY KEY (id),
  KEY ix_admin_audit_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The failover epoch (06 §10, D-35): one row, raised only by a promotion, on the new primary;
-- replication carries it to the replica. A process given both hosts writes to the writable one with
-- the highest epoch, and never to one below the highest it has seen.
CREATE TABLE ha_epoch (
    id    TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    epoch BIGINT UNSIGNED  NOT NULL,
    CONSTRAINT ha_epoch_one_row CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A replica's lag, measured (D-58): every worker stamps this one row on the primary once a second,
-- and replication carries it; the primary's less a replica's is that replica's lag.
CREATE TABLE ha_heartbeat (
  id TINYINT UNSIGNED PRIMARY KEY,
  at DATETIME(6) NOT NULL,
  CONSTRAINT ck_heartbeat_one_row CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What each backup step did (06 §10, D-71, D-72), written by the scripts as the backup account; every
-- worker reports when each last succeeded.
CREATE TABLE backup_run (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  kind        TINYINT UNSIGNED NOT NULL,                      -- 1 dump, 2 restore proof, 3 copy off the site
  started_at  DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NOT NULL,
  ok          BOOLEAN NOT NULL,
  detail      VARCHAR(255) NOT NULL,
  seconds     DOUBLE NULL,                                    -- a proof's restore and replay; NULL for the others
  KEY ix_backup_kind (kind, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
