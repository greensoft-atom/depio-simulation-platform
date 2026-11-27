-- The rows a first launch needs (D-75, D-120). Nothing else is seeded on purpose: accounts are made by
-- players, the operator's access is the admin API's secret (04 §10), and the content (items, skins,
-- packs, levels) is the release's, in the platform's jar and the code, not the database. The shop's
-- offers are the exception: the admin API edits them (D-119), and these are the release's first.

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

-- The shop's twelve offers, as shop.json had them until plan item 145 (04 §8, plan items 68 and 75 (c)):
-- the equipment for coins, the boosts and the skins for gems.
INSERT INTO shop_offer (sku, item_id, price, currency, requires_level) VALUES
  ('treads_light',     'treads_light',     1200, 0, 1),
  ('barrel_steel',     'barrel_steel',     1500, 0, 1),
  ('armor_plate',      'armor_plate',      1500, 0, 1),
  ('barrel_rifled',    'barrel_rifled',    2000, 0, 5),
  ('core_capacitor',   'core_capacitor',   2000, 0, 5),
  ('boost_xp_hour',    'boost_xp_hour',      20, 1, 1),
  ('boost_coins_hour', 'boost_coins_hour',   20, 1, 1),
  ('skin_crimson',     'skin_crimson',      150, 1, 1),
  ('skin_azure',       'skin_azure',        150, 1, 1),
  ('skin_jade',        'skin_jade',         150, 1, 1),
  ('skin_gold',        'skin_gold',         400, 1, 1),
  ('skin_carbon',      'skin_carbon',       400, 1, 1);
