package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.backend.persistence.AccountRepository;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.EconomyRepository.Holding;
import com.backend.persistence.EconomyRepository.Outcome;
import com.backend.persistence.EconomyRepository.Purchase;
import com.backend.persistence.EconomyRepository.Wallet;
import com.backend.persistence.ShopRepository;
import com.backend.persistence.ShopRepository.Offer;

/**
 * Buying from the shop's offers (docs detailed-design/04-platform-services.md §8, "The shop, as built", and D-119).
 * The money moves in {@link EconomyRepository#purchase}, the one path that moves a balance; this decides whether it
 * may, and answers. The offers are read from the database at each listing and each purchase, so an edit by the admin
 * API is on sale at every platform once it commits.
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

    /** A page of what a player bought (plan item 145 (c)). */
    public static final int PURCHASES_PAGE = 50;

    /** A tap's key as a purchase's, an item level's and a boost's must be (04 §8): fresh enough, and the same however cased. */
    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public enum Result {
        BOUGHT, ALREADY_BOUGHT, NO_SESSION, INVALID_KEY, UNKNOWN_SKU, NOT_AVAILABLE,
        LEVEL_REQUIRED, INSUFFICIENT_FUNDS, ALREADY_OWNED, OFFER_CHANGED
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

    /** What the client showed the player for the offer it buys: its item, price and currency (D-119). */
    public record Shown(String itemId, long price, int currency) { }

    /** What a player holds. */
    public record Holdings(long coins, long gems, List<Holding> items) { }

    /** A page of what a player bought, newest first; {@code next} the {@code before} of the page after, or null. */
    public record Bought(List<EconomyRepository.Bought> rows, Long next) { }

    private final AuthService auth;
    private final AccountRepository accounts;
    private final EconomyRepository economy;
    private final ShopRepository offers;
    private final Items items;
    private final Clock clock;

    public ShopService(AuthService auth, AccountRepository accounts, EconomyRepository economy,
                       ShopRepository offers, Items items, Clock clock) {
        this.auth = auth;
        this.accounts = accounts;
        this.economy = economy;
        this.offers = offers;
        this.items = items;
        this.clock = clock;
    }

    /** The offers on sale now, in their order. */
    public List<Offer> onSale() throws SQLException {
        return Catalogue.onSale(offers.offers(), items, clock.instant());
    }

    /** Every offer, on sale or not, for the admin API. */
    public List<Offer> all() throws SQLException {
        return offers.offers();
    }

    /** Whether the release's item table has the item: an offer of one it does not is not on sale. */
    public boolean itemKnown(String itemId) {
        return items.find(itemId) != null;
    }

    /** Whether the offer is on sale now. */
    public boolean onSale(Offer offer) {
        return Catalogue.onSale(offer, items, clock.instant());
    }

    /** The release's item table, which an offer's item must be in. */
    public Items items() {
        return items;
    }

    /** The offers that sell an item the release's table does not have: not on sale, logged at start (D-119). */
    public List<Offer> withoutItem() throws SQLException {
        List<Offer> out = new ArrayList<>();
        for (Offer o : offers.offers()) {
            if (!itemKnown(o.itemId())) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * Buys one {@code sku} with the client's {@code key}, as {@code shown}.
     *
     * The key comes first: a retry is answered as its first attempt was, even when the offer
     * has ended since, its price has changed or the balance has run short. Checked in any
     * other order, a purchase that had succeeded could be retried into a refusal: charged once,
     * as promised, and told it had failed.
     */
    public Receipt buy(String sessionToken, String sku, Shown shown, String key) throws SQLException {
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
        Offer offer = sku == null ? null : offers.offer(sku);
        if (offer == null) {
            return Receipt.of(Result.UNKNOWN_SKU);
        }
        if (!onSale(offer)) {
            return Receipt.of(Result.NOT_AVAILABLE);
        }
        // What the player agreed to (D-119): an offer edited since the client listed it is listed again, not bought.
        if (!offer.itemId().equals(shown.itemId()) || offer.price() != shown.price() || offer.currency() != shown.currency()) {
            return Receipt.of(Result.OFFER_CHANGED);
        }
        // A read, not under the purchase's lock: a level only rises, so a stale one can only
        // refuse a moment too long.
        if (accounts.level(playerId) < offer.requiresLevel()) {
            return new Receipt(Result.LEVEL_REQUIRED, null, 0, 0, offer.requiresLevel(), 0);
        }
        Outcome outcome = economy.purchase(playerId, offer.itemId(), offer.price(), key, offer.currency(),
                heldOnce(offer.itemId()));
        return switch (outcome) {
            case APPLIED -> receipt(Result.BOUGHT, playerId, offer.itemId());
            // The same key, in flight at the same time as this one, got there first.
            case ALREADY_APPLIED -> receipt(Result.ALREADY_BOUGHT, playerId,
                    economy.findPurchase(playerId, key).itemId());
            case INSUFFICIENT_FUNDS -> Receipt.of(Result.INSUFFICIENT_FUNDS);
            case ALREADY_OWNED -> Receipt.of(Result.ALREADY_OWNED);
        };
    }

    /** Equipment and skins are held once; a boost, used up, as many as bought (04 §8, D-53). */
    private boolean heldOnce(String itemId) {
        Items.Item item = items.find(itemId);
        return item == null || item.boost() == null;
    }

    /** @return a page of what the session's player bought, before the ledger row {@code before}; null for no session */
    public Bought purchases(String sessionToken, long before) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        return playerId < 0 ? null : purchasesOf(playerId, before);
    }

    /** A page of what a player bought, for the player or for support (plan item 145 (c)). */
    public Bought purchasesOf(long playerId, long before) throws SQLException {
        List<EconomyRepository.Bought> rows = economy.purchases(playerId, before, PURCHASES_PAGE + 1);
        if (rows.size() <= PURCHASES_PAGE) {
            return new Bought(rows, null);
        }
        List<EconomyRepository.Bought> page = rows.subList(0, PURCHASES_PAGE);
        return new Bought(List.copyOf(page), page.get(PURCHASES_PAGE - 1).id());
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
