package com.backend.platform.net;

/**
 * The request and response bodies of the platform API.
 *
 * Deliberately separate types from the service records. An HTTP body is a contract with
 * something deployed on its own schedule, and letting a service's internal record double as
 * the wire shape means any refactor of the service is silently a protocol change.
 */
final class ApiMessages {

    record RegisterRequest(String username, String displayName, String password) { }

    record RenameRequest(String displayName) { }

    record RegisterResponse(long playerId) { }

    /** A username and password, or a guest's key alone (D-46). */
    record LoginRequest(String username, String password, String guestKey) { }

    /** A guest made (Q-22): the key for the device to keep, and its name. */
    record GuestResponse(long playerId, String guestKey, String displayName) { }

    record LoginResponse(String token, long playerId, int expiresInSeconds) { }

    record MatchResponse(String arenaHost, int arenaPort, String ticketId, boolean tls) { }

    /**
     * One row of a board.
     *
     * {@code rank} is 1-based here and 0-based in the store. A player is "#1", never "#0",
     * and converting once at this boundary is better than every client doing it and one of
     * them forgetting.
     */
    record LeaderboardEntry(long rank, long playerId, String name, long score) { }

    record LeaderboardResponse(String board, java.util.List<LeaderboardEntry> entries) { }

    /** A row of the board of teams (D-65): the team, not a player. */
    record TeamEntry(long rank, long teamId, String name, long score) { }

    record TeamBoardResponse(String board, java.util.List<TeamEntry> entries) { }

    record MyTeamRankResponse(String board, long rank, long score, java.util.List<TeamEntry> entries) { }

    /** A player's own position, with the rows on either side of it. */
    record MyRankResponse(String board, long rank, long score,
                          java.util.List<LeaderboardEntry> entries) { }

    /** One offer on sale. {@code availableTo} is when it stops, or null if it does not. */
    record OfferView(String sku, String itemId, long price, int requiresLevel, String availableTo, String currency) { }

    record ShopResponse(java.util.List<OfferView> offers) { }

    /** {@code key}: the client's transaction id, the same for every retry of one tap of Buy. */
    record PurchaseRequest(String sku, String key) { }

    /**
     * {@code result} is {@code bought} or {@code already_bought}: the second when the key had
     * bought already, and then {@code itemId} is what it bought. {@code held} is how many of
     * the item the player has now.
     */
    record PurchaseResponse(String result, String itemId, long coins, int held, long gems) { }

    record ItemView(String itemId, int qty, int level) { }

    record InventoryResponse(long coins, long gems, java.util.List<ItemView> items) { }

    record WearRequest(String itemId) { }

    /** Gems for money (04 §8, D-68): {@code key}, the client's, the same for every retry of one order. */
    record OrderRequest(String productId, String key) { }

    /** The simulated provider's outcome: {@code paid} or {@code declined}. */
    record SimulateRequest(String outcome) { }

    record PackView(String productId, int gems, int priceCents, String currency) { }

    record PacksResponse(java.util.List<PackView> packs) { }

    /** {@code bonus}: the first purchase's gems; {@code state} pending, paid, declined, refunded or expired. */
    record OrderView(String orderId, String productId, int gems, int bonus, int priceCents, String currency,
                     String state, String createdAt) { }

    record OrderResponse(OrderView order) { }

    /** {@code confirmed}: false when the order was no longer pending; {@code gems}: the balance after. */
    record ConfirmResponse(OrderView order, boolean confirmed, long gems) { }

    /** A tier's reward on one track (04 §8, D-69): {@code itemId} null when it gives none. */
    record RewardView(long coins, int gems, String itemId) { }

    record TierView(int tier, RewardView free, RewardView premium) { }

    /** A skin's number on the wire, and the item it is (04 §8, D-70). */
    record SkinView(int skin, String itemId) { }

    /** The season being played's pass: a tier is paid once reached, a premium one once bought too. */
    record PassResponse(int season, String endsAt, long points, int tier, boolean premium, int premiumGems,
                        int tierPoints, java.util.List<TierView> tiers) { }

    /** {@code result}: bought or already_bought; {@code gems}: the balance after. */
    record PremiumResponse(String result, long gems, PassResponse pass) { }

    record ActivateRequest(String itemId, String key) { }

    record TeamRequest(String name, Boolean accept, String role, Long playerId) { }

    /** A friend asked, or a player blocked (04 §9). */
    record PlayerRequest(Long playerId) { }

    /** The inbox read up to an item (04 §9). */
    record InboxRead(Long upTo) { }

    record MemberView(long playerId, String name, String role) { }

    /** A team, its members, and its record in team matches (Q-18). */
    record TeamView(long id, String name, java.util.List<MemberView> members, int rating, int wins, int losses,
                    int draws) { }

    record InviteView(long teamId, String teamName, String expiresAt) { }

    record InvitesResponse(java.util.List<InviteView> invites) { }

    record BoostView(String kind, String itemId, int percent, String endsAt) { }

    /** {@code result} for an activation only; the boosts running, either way. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record BoostsResponse(String result, java.util.List<BoostView> boosts) { }

    /** Each slot by name, an item id or null; the bonus a stat by name, only the stats it touches. */
    record LoadoutResponse(java.util.Map<String, String> slots, java.util.Map<String, Integer> bonus) { }

    /** {@code code} is for the client to branch on; {@code message} is for a human reading logs. */
    record ErrorResponse(String code, String message) { }

    record QueueRequest(String mode) { }

    /** An answer to a match found: {@code POST /v1/queue/accept} or {@code /decline}. */
    record AnswerRequest(String matchUid) { }

    /** A party request's body: the player invited or removed, or the party an invitation is to (04 §4). */
    record PartyRequest(Long playerId, String partyId, Integer phraseId) { }

    /** What {@code evt.match.found} carries, and {@code GET /v1/queue} when matched (04 §4). */
    record FoundView(String arenaHost, int arenaPort, String ticketId, boolean tls, String mode) { }

    /** {@code mode} and {@code grant} are left out when they do not apply. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record QueueResponse(String state, String mode, Long waitedSeconds, FoundView grant, String matchUid,
                         Long secondsLeft) { }

    private ApiMessages() {
    }
}
