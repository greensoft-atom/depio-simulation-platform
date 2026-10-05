package com.backend.platform;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import com.backend.persistence.EquipmentRepository;
import com.backend.persistence.EquipmentRepository.Worn;
import com.backend.sim.Stat;

/**
 * Wearing items (docs detailed-design/04-platform-services.md §8, "Equipment"), and the bonus
 * what is worn gives, which a ticket carries to the arena (D-37).
 *
 * Blocking, and meant for a virtual thread.
 */
public final class EquipmentService implements Loadouts {

    public enum Result { OK, NO_SESSION, INVALID_SLOT, UNKNOWN_ITEM, WRONG_SLOT, NOT_OWNED }

    /** The slots, by {@link Items} slot, each an item id or null; and the capped bonus a stat. */
    public record Loadout(List<String> slots, byte[] bonus) { }

    /** {@code loadout} for OK only. */
    public record Answer(Result result, Loadout loadout) {
        static Answer of(Result result) {
            return new Answer(result, null);
        }
    }

    private final AuthService auth;
    private final EquipmentRepository repository;
    private final Items items;

    public EquipmentService(AuthService auth, EquipmentRepository repository, Items items) {
        this.auth = auth;
        this.repository = repository;
        this.items = items;
    }

    public Answer loadout(String sessionToken) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        return playerId < 0 ? Answer.of(Result.NO_SESSION) : new Answer(Result.OK, loadoutOf(playerId));
    }

    /** Wears {@code itemId} in the slot named, replacing what was there. */
    public Answer wear(String sessionToken, String slotName, String itemId) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        int slot = Items.slotOf(slotName);
        if (slot < 0) {
            return Answer.of(Result.INVALID_SLOT);
        }
        Items.Item item = items.find(itemId);
        if (item == null) {
            return Answer.of(Result.UNKNOWN_ITEM);
        }
        if (item.slot() != slot) {
            return Answer.of(Result.WRONG_SLOT);
        }
        if (!repository.wear(playerId, slot, itemId)) {
            return Answer.of(Result.NOT_OWNED);
        }
        return new Answer(Result.OK, loadoutOf(playerId));
    }

    public Answer takeOff(String sessionToken, String slotName) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        int slot = Items.slotOf(slotName);
        if (slot < 0) {
            return Answer.of(Result.INVALID_SLOT);
        }
        repository.takeOff(playerId, slot);
        return new Answer(Result.OK, loadoutOf(playerId));
    }

    /** What the player's tanks are given: the capped bonus of what they wear and hold (D-37). */
    @Override
    public byte[] bonusOf(long playerId) throws SQLException {
        return bonus(repository.worn(playerId), items);
    }

    private Loadout loadoutOf(long playerId) throws SQLException {
        List<Worn> worn = repository.worn(playerId);
        String[] slots = new String[Items.SLOTS];
        for (Worn w : worn) {
            slots[w.slot()] = w.itemId();                 // only wear writes a row, with a slot it checked
        }
        return new Loadout(Arrays.asList(slots), bonus(worn, items));
    }

    @Override
    public int skinOf(long playerId) throws SQLException {
        for (Worn w : repository.worn(playerId)) {
            if (w.slot() == Items.SKIN) {
                Items.Item item = w.owned() ? items.find(w.itemId()) : null;      // as a bonus: worn and held
                return item == null ? 0 : item.skin();
            }
        }
        return 0;
    }

    /** Every skin, by its number: the table a client draws by (D-70). */
    public List<Items.Skin> skins() {
        return items.skins();
    }

    /** Whether the item is one that gives a bonus, and so may be raised a level (04 §8, plan item 67). */
    public boolean isEquipment(String itemId) {
        Items.Item item = items.find(itemId);
        return item != null && item.boost() == null && item.slot() != Items.SKIN;
    }

    /**
     * Added a stat and capped at {@link Items#MAX_PERCENT}, each item's at its level: × (3 + level)
     * / 4, rounded down, level 5 twice level 1 (04 §8). An item worn and no longer held, or no
     * longer in the table, gives nothing.
     */
    static byte[] bonus(List<Worn> worn, Items items) {
        int[] sum = new int[Stat.COUNT];
        for (Worn w : worn) {
            Items.Item item = w.owned() ? items.find(w.itemId()) : null;
            if (item == null) {
                continue;
            }
            for (int s = 0; s < Stat.COUNT; s++) {
                sum[s] += item.percents()[s] * (3 + w.level()) / 4;
            }
        }
        byte[] out = new byte[Stat.COUNT];
        for (int s = 0; s < Stat.COUNT; s++) {
            out[s] = (byte) Math.min(sum[s], Items.MAX_PERCENT);
        }
        return out;
    }
}
