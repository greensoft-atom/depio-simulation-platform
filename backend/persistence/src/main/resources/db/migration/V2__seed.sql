-- The rows a first launch needs (D-75). Nothing else is seeded on purpose: accounts are made by
-- players, the operator's access is the admin API's secret (04 §10), and the content (shop, items,
-- skins, packs, levels) is the release's files under content/, not the database.

-- The failover epoch starts at 0; promote-mysql.sh raises it on the new primary (06 §10, D-35).
INSERT INTO ha_epoch (id, epoch) VALUES (1, 0);

-- The replica heartbeat's one row; every worker stamps it once a second (D-58).
INSERT INTO ha_heartbeat (id, at) VALUES (1, UTC_TIMESTAMP(6));

-- Season 1, from now to the end of the two calendar months it falls in: seasons end at 00:00 UTC on
-- the first of January, March, May, July, September and November (SeasonRepository.endAfter is the
-- same rule, and a test holds the two together).
INSERT INTO season (id, starts_at, ends_at)
VALUES (1, UTC_TIMESTAMP(3),
        TIMESTAMP(DATE_ADD(DATE_FORMAT(UTC_DATE(), '%Y-%m-01'), INTERVAL 2 - (MONTH(UTC_DATE()) - 1) % 2 MONTH)));
