package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import com.backend.persistence.AccountRepository;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.EconomyRepository.Holding;
import com.backend.persistence.EconomyRepository.Outcome;
import com.backend.persistence.EconomyRepository.Purchase;
import com.backend.persistence.EconomyRepository.Wallet;

/**
 * Buying from the catalogue (docs detailed-design/04-platform-services.md §8, "The shop, as
 * built"). The money moves in {@link EconomyRepository#purchase}, the one path that moves a
 * balance; this decides whether it may, and answers.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class ShopService {

    /**
     * The client's transaction id: a lowercase UUID fits. Lower case because the ledger compares
     * keys without regard to case, so {@code Ab} and {@code aB} would be one key; at least 16
     * so it is random, not a counter that restarts when the app is reinstalled and finds its
     * first purchases "already bought".
     */
    private static final Pattern KEY = Pattern.compile("[a-z0-9-]{16,48}");

    /** A tap's key as a purchase's, an item level's and a boost's must be (04 §8): fresh enough, and the same however cased. */
    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public enum Result {
        BOUGHT, ALREADY_BOUGHT, NO_SESSION, INVALID_KEY, UNKNOWN_SKU, NOT_AVAILABLE,
        LEVEL_REQUIRED, INSUFFICIENT_FUNDS
    }

    /**
     * {@code itemId}, {@code coins}, {@code gems} and {@code held} are set for BOUGHT and
     * ALREADY_BOUGHT; {@code requiredLevel} for LEVEL_REQUIRED.
     */
    public record Receipt(Result result, String itemId, long coins, int held, int requiredLevel, long gems) {
        static Receipt of(Result result) {
            return new Receipt(result, null, 0, 0, 0, 0);
        }
    }

    /** What a player holds. */
    public record Holdings(long coins, long gems, List<Holding> items) { }

    private final AuthService auth;
    private final AccountRepository accounts;
    private final EconomyRepository economy;
    private final Catalogue catalogue;
    private final Clock clock;

    public ShopService(AuthService auth, AccountRepository accounts, EconomyRepository economy,
                       Catalogue catalogue, Clock clock) {
        this.auth = auth;
        this.accounts = accounts;
        this.economy = economy;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    /** The offers on sale now. */
    public List<Catalogue.Offer> onSale() {
        return catalogue.onSaleAt(clock.instant());
    }

    /**
     * Buys one {@code sku} with the client's {@code key}.
     *
     * The key comes first: a retry is answered as its first attempt was, even when the offer
     * has ended since, its price has changed or the balance has run short. Checked in any
     * other order, a purchase that had succeeded could be retried into a refusal: charged once,
     * as promised, and told it had failed.
     */
    public Receipt buy(String sessionToken, String sku, String key) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Receipt.of(Result.NO_SESSION);
        }
        if (key == null || !KEY.matcher(key).matches()) {
            return Receipt.of(Result.INVALID_KEY);
        }
        Purchase earlier = economy.findPurchase(playerId, key);
        if (earlier != null) {
            return receipt(Result.ALREADY_BOUGHT, playerId, earlier.itemId());
        }
        Catalogue.Offer offer = sku == null ? null : catalogue.find(sku);
        if (offer == null) {
            return Receipt.of(Result.UNKNOWN_SKU);
        }
        if (!offer.onSaleAt(clock.instant())) {
            return Receipt.of(Result.NOT_AVAILABLE);
        }
        // A read, not under the purchase's lock: a level only rises, so a stale one can only
        // refuse a moment too long.
        if (accounts.level(playerId) < offer.requiresLevel()) {
            return new Receipt(Result.LEVEL_REQUIRED, null, 0, 0, offer.requiresLevel(), 0);
        }
        Outcome outcome = economy.purchase(playerId, offer.itemId(), offer.price(), key,
                offer.currency() == Catalogue.GEMS ? EconomyRepository.CURRENCY_GEMS : EconomyRepository.CURRENCY_COINS);
        return switch (outcome) {
            case APPLIED -> receipt(Result.BOUGHT, playerId, offer.itemId());
            // The same key, in flight at the same time as this one, got there first.
            case ALREADY_APPLIED -> receipt(Result.ALREADY_BOUGHT, playerId,
                    economy.findPurchase(playerId, key).itemId());
            case INSUFFICIENT_FUNDS -> Receipt.of(Result.INSUFFICIENT_FUNDS);
        };
    }

    /** A raise's answer (04 §8): {@code result} null when the session is not valid. */
    public record Leveled(EconomyRepository.Raised result, int level, long coins) { }

    /** Raises an item the player holds a level, once a key (04 §8, plan item 67). */
    public Leveled raise(String sessionToken, String itemId, String key) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return new Leveled(null, 0, 0);
        }
        EconomyRepository.Raise r = economy.raiseLevel(playerId, itemId, key);
        return new Leveled(r.outcome(), r.level(), r.coins());
    }

    /** @return what the player holds, or null for a session that is not valid */
    public Holdings holdings(String sessionToken) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return null;
        }
        Wallet wallet = economy.wallet(playerId);
        if (wallet == null) {
            return null;
        }
        return new Holdings(wallet.coins(), wallet.gems(), economy.inventory(playerId));
    }

    /** The balance and the count held now, after the purchase, as the answer to it. */
    private Receipt receipt(Result result, long playerId, String itemId) throws SQLException {
        Wallet wallet = economy.wallet(playerId);
        int held = 0;
        for (Holding h : economy.inventory(playerId)) {
            if (h.itemId().equals(itemId)) {
                held = h.qty();
            }
        }
        return new Receipt(result, itemId, wallet == null ? 0 : wallet.coins(), held, 0,
                wallet == null ? 0 : wallet.gems());
    }
}
