-- A limited offer's stock, held on its row in the purchase's own transaction (04 §8, D-121, plan item 146): the
-- limit, null for none, and the purchases counted while the offer has one. The admin API's PUT sets the limit and
-- keeps what was sold.
ALTER TABLE shop_offer
  ADD COLUMN stock INT UNSIGNED NULL AFTER available_to,
  ADD COLUMN sold  INT UNSIGNED NOT NULL DEFAULT 0 AFTER stock;
