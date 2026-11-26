using System;

namespace Backend.Client.Simple.Examples
{
    // The JSON the examples send and read, as Unity's JsonUtility maps it: a [Serializable] class whose public fields
    // are named as the JSON's. A field the JSON lacks keeps its default; JsonUtility writes every field, a null string
    // as "", so a class sent holds only what the request takes. Shapes: docs/api/01-http-api.md, 02-lobby-websocket.md.

    // ---- The account service (its address the site's with /account; docs/detailed-design/10-accounts.md §3.2)

    /// <summary>POST /v1/accounts (sign up) and POST /v1/sessions (log in) at the account service: a name and a password.</summary>
    [Serializable] public class Credentials { public string username; public string password; }

    /// <summary>
    /// What the account service answers a sign-up, a login and a refresh, in RFC 6749's names: keep refresh_token on the
    /// device (it signs the device in again), and take access_token to the API once, for a session (TokenLogin).
    /// </summary>
    [Serializable] public class AccountTokens { public string access_token; public string token_type; public int expires_in; public string refresh_token; public string account_id; }

    /// <summary>POST /v1/sessions/refresh (new tokens for it, it spent) and DELETE /v1/sessions (logged out) at the account service.</summary>
    [Serializable] public class RefreshRequest { public string refresh_token; }

    // ---- The API

    /// <summary>POST /v1/sessions with the account service's access token, taken once for a session.</summary>
    [Serializable] public class TokenLogin { public string accessToken; }

    /// <summary>POST /v1/sessions for a guest, by its key.</summary>
    [Serializable] public class GuestLogin { public string guestKey; }

    /// <summary>POST /v1/guests answers it: keep guestKey on the device, it is the guest.</summary>
    [Serializable] public class Guest { public long playerId; public string guestKey; public string displayName; }

    /// <summary>
    /// POST /v1/sessions answers it: send token as Bearer, and in the lobby's auth. newPlayer: an account's first session
    /// made its player.
    /// </summary>
    [Serializable] public class Session { public string token; public long playerId; public int expiresInSeconds; public bool newPlayer; }

    /// <summary>POST /v1/accounts/link, with the guest's session: the guest becomes the account's player, its progress kept.</summary>
    [Serializable] public class LinkRequest { public string accessToken; }

    /// <summary>GET /v1/inventory.</summary>
    [Serializable] public class Inventory { public long coins; public long gems; public Item[] items; }
    [Serializable] public class Item { public string itemId; public int qty; public int level; }

    /// <summary>GET /v1/shop.</summary>
    [Serializable] public class Shop { public Offer[] offers; }
    [Serializable] public class Offer { public string sku; public string itemId; public int price; public int requiresLevel; public string currency; }

    // ---- The lobby's d

    /// <summary>A seat in a match: match.request's reply, evt.match.found. ArenaExample.Play takes it.</summary>
    [Serializable] public class Grant { public string arenaHost; public int arenaPort; public string ticketId; public bool tls; public string tlsName; public string mode; }

    /// <summary>queue.join's d: "duel", "teams", "ranked"...</summary>
    [Serializable] public class QueueRequest { public string mode; }

    /// <summary>evt.match.ready: answer match.accept (or match.decline) within seconds.</summary>
    [Serializable] public class MatchReady { public string matchUid; public string mode; public int seconds; }

    /// <summary>match.accept's and match.decline's d.</summary>
    [Serializable] public class MatchAnswer { public string matchUid; }

    /// <summary>evt.notice: an operator's message to everyone.</summary>
    [Serializable] public class Notice { public string text; }
}
