-- The shop's offers in the database, edited by the admin API (04 §8, D-119, plan item 145), where they were
-- shop.json on the platform's classpath and changing one was a release. Every platform reads them at each listing
-- and each purchase. Their rules are the admin API's, checked when it writes (Catalogue); a sku and an item id are
-- lowercase letters, digits and _, so they are compared as bytes.
CREATE TABLE shop_offer (
  id             INT UNSIGNED NOT NULL AUTO_INCREMENT,           -- the order the shop lists them in: the order added
  sku            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  item_id        VARCHAR(40) NOT NULL,
  price          INT UNSIGNED NOT NULL,
  currency       TINYINT UNSIGNED NOT NULL,                      -- 0 coins, 1 gems, as the ledger's
  requires_level TINYINT UNSIGNED NOT NULL DEFAULT 1,
  available_from DATETIME(3) NULL,
  available_to   DATETIME(3) NULL,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_shop_offer_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The release's twelve offers, as shop.json had them (04 §8, plan items 68 and 75 (c)): the equipment for coins,
-- the boosts and the skins for gems.
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

-- What a player bought, newest first, read from the index alone however many other rows the player has
-- (GET /v1/purchases, plan item 145 (c)).
ALTER TABLE ledger ADD KEY ix_ledger_player_reason (player_id, reason, id);
