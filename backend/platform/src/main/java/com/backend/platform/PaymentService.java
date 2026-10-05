package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.backend.common.RefusedConfiguration;
import com.backend.persistence.PaymentRepository;

/**
 * Gems for money (docs detailed-design/04-platform-services.md §8, revenue, D-68): a player's order for a
 * pack, and the provider's word on it. The provider is simulated, the player's own call standing in for
 * its page and notification, and runs only where the operator names it: it grants gems to whoever asks.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class PaymentService {

    public static final String SIMULATED = "simulated";

    /** The client's key: a lowercase UUID fits, as the shop's; 36 is the column's width. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9-]{16,36}");

    public enum Result { OK, NO_SESSION, INVALID_KEY, UNKNOWN_PRODUCT, REFUND_DEBT, NO_SUCH_ORDER }

    /** {@code order} for OK; {@code confirmed} and {@code gems}, the balance after, for a confirm. */
    public record Answer(Result result, PaymentRepository.Order order, boolean confirmed, long gems) {
        static Answer of(Result result) {
            return new Answer(result, null, false, 0);
        }
    }

    private final AuthService auth;
    private final PaymentRepository orders;
    private final Packs packs;
    private final String provider;
    private final Clock clock;

    /** @param provider {@link #SIMULATED}, or null for no payments */
    public PaymentService(AuthService auth, PaymentRepository orders, Packs packs, String provider, Clock clock) {
        this.auth = auth;
        this.orders = orders;
        this.packs = packs;
        this.provider = provider;
        this.clock = clock;
    }

    /** {@code BACKEND_PAYMENT_PROVIDER}: unset or blank, none; {@code simulated}; anything else stops the start. */
    public static String providerFrom(Map<String, String> env) {
        String named = env.get("BACKEND_PAYMENT_PROVIDER");
        if (named == null || named.isBlank()) {
            return null;
        }
        if (named.equals(SIMULATED)) {
            return SIMULATED;
        }
        throw new RefusedConfiguration("BACKEND_PAYMENT_PROVIDER names " + named
                + "; the only provider is simulated (D-68, Q-52)");
    }

    /** Whether a provider is named: if not, nothing is sold. */
    public boolean on() {
        return provider != null;
    }

    public List<Packs.Pack> packs() {
        return packs.all();
    }

    /** An order for a pack, pending; the same order for a key used, whatever is asked for with it. */
    public Answer order(String sessionToken, String productId, String key) throws SQLException {
        long player = auth.playerIdOf(sessionToken);
        if (player < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        if (key == null || !KEY.matcher(key).matches()) {
            return Answer.of(Result.INVALID_KEY);
        }
        Packs.Pack pack = packs.find(productId);
        if (pack == null) {
            return Answer.of(Result.UNKNOWN_PRODUCT);
        }
        PaymentRepository.Placed placed = orders.place(player, key, pack.productId(), pack.gems(), pack.priceCents(),
                Packs.CURRENCY, provider, clock.instant());
        return placed.inDebt() ? Answer.of(Result.REFUND_DEBT) : new Answer(Result.OK, placed.order(), false, 0);
    }

    /** The player's own order; another's is no such order. */
    public Answer get(String sessionToken, String orderId) throws SQLException {
        long player = auth.playerIdOf(sessionToken);
        if (player < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        PaymentRepository.Order order = orders.get(orderId);
        return order == null || order.playerId() != player ? Answer.of(Result.NO_SUCH_ORDER)
                : new Answer(Result.OK, order, false, 0);
    }

    /** The simulated provider's word on the player's own order: the one confirm a real provider's would make. */
    public Answer simulate(String sessionToken, String orderId, boolean paid) throws SQLException {
        Answer mine = get(sessionToken, orderId);
        if (mine.result() != Result.OK) {
            return mine;
        }
        PaymentRepository.Confirmed confirmed = orders.confirm(orderId, paid, clock.instant());
        return new Answer(Result.OK, confirmed.order(), confirmed.now(), confirmed.gemsBalance());
    }
}
