-- The account service's schema (D-117, docs/detailed-design/10-accounts.md §3.6), in its own database, `account`.
-- Forward-only from the first: an applied migration is never edited, the next one is added (as the backend's, 06 §8).
--
-- Conventions, the backend's (06 §2): times are DATETIME(3), always UTC (Database forces the session's zone);
-- every table InnoDB. A username is ASCII by its rules, so its column is ASCII and compares without case: one
-- account whatever the case of its name, and no accent folding to make two names one.

-- An account: a username and its password, and the subject the tokens carry. A deletion keeps the row, clears the
-- name and the hash, so the name is free again and the subject is never given to anyone else.
CREATE TABLE account (
  id                  BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  subject             CHAR(22) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,         -- the tokens' sub: 16 random bytes, base64url
  username            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NULL,   -- as typed; NULL once deleted
  password_hash       VARBINARY(255) NULL,                                             -- Argon2id, its parameters within; NULL once deleted
  status              TINYINT UNSIGNED NOT NULL DEFAULT 0,                             -- 0 active, 1 deleted
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_login_at       DATETIME(3) NULL,
  password_changed_at DATETIME(3) NULL,
  deleted_at          DATETIME(3) NULL,
  UNIQUE KEY uq_account_subject (subject),
  UNIQUE KEY uq_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Refresh tokens, by their SHA-256: the token itself is never kept. A family is one login's line of tokens, each
-- spent by the next (rotation); a spent one used again past the overlap revokes the family (RFC 9700 §4.14.2).
CREATE TABLE refresh_token (
  token_hash        BINARY(32) PRIMARY KEY,
  family            BINARY(16) NOT NULL,
  account_id        BIGINT UNSIGNED NOT NULL,
  issued_at         DATETIME(3) NOT NULL,
  expires_at        DATETIME(3) NOT NULL,          -- 60 days from its issue
  family_expires_at DATETIME(3) NOT NULL,          -- a year from the login
  auth_time         DATETIME(3) NOT NULL,          -- when the password was last given: the tokens' auth_time
  used_at           DATETIME(3) NULL,              -- spent, by the token that followed it
  revoked_at        DATETIME(3) NULL,
  KEY ix_refresh_family (family),
  KEY ix_refresh_account (account_id),
  KEY ix_refresh_expires (expires_at),
  CONSTRAINT fk_refresh_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The login limits' fixed windows (10 §3.3): one row a key and window, raised by one statement. A key is
-- 'a:' and an address (IPv6 by its /64), or 'u:' and a username in lower case.
CREATE TABLE attempt (
  k            VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  window_start BIGINT NOT NULL,                    -- the window's first second, since the epoch
  n            INT UNSIGNED NOT NULL,
  PRIMARY KEY (k, window_start),
  KEY ix_attempt_window (window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The events for the backend (10 §3.5): written in the transaction that makes the change, so no change goes
-- without its event; delivered until taken, then kept a week.
CREATE TABLE event_outbox (
  id              BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  jti             CHAR(22) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,   -- deleted, sessions-revoked
  token           TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,          -- the signed event, as it is delivered
  created_at      DATETIME(3) NOT NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  attempts        INT UNSIGNED NOT NULL DEFAULT 0,
  taken_at        DATETIME(3) NULL,
  UNIQUE KEY uq_event_jti (jti),
  KEY ix_event_due (taken_at, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The failover epoch, the backend's (06 §10, D-35): one row, raised only by a promotion, on the new primary, with the
-- backend's own; the account service, given both hosts, writes to the writable one with the highest epoch.
CREATE TABLE ha_epoch (
    id    TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    epoch BIGINT UNSIGNED  NOT NULL,
    CONSTRAINT ha_epoch_one_row CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ha_epoch (id, epoch) VALUES (1, 0);
