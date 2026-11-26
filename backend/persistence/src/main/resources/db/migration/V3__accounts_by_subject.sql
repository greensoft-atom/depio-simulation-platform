-- Accounts by the account service's subject (D-117, docs/detailed-design/10-accounts.md §4.4).
--
-- A registered player is the account service's account, found by its subject, the tokens' `sub`; a guest is an
-- account with a key and no subject (D-46); a linked guest has a subject and no key; an erased one neither. The
-- username and the password leave the backend for the account service: nothing has launched, and no password is
-- kept (the owner, 2026-11-26). Forward-only, as every migration from V3 on (06 §8).
ALTER TABLE account
  ADD COLUMN subject VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER id,
  ADD UNIQUE KEY uq_account_subject (subject),
  DROP INDEX uq_account_username,
  DROP COLUMN username_key,
  DROP COLUMN username,
  DROP COLUMN password_hash;

-- The subjects whose accounts were deleted at the account service, told by its event: an access token of one,
-- exchanged in the minutes it is still good, makes no new player.
CREATE TABLE erased_subject (
  subject   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  erased_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
