using System;

namespace Backend.Client.Simple.Examples
{
    // The JSON the examples send and read, as Unity's JsonUtility maps it: a [Serializable] class whose public fields
    // are named as the JSON's. A field the JSON lacks keeps its default; JsonUtility writes every field, a null string
    // as "", so a class sent holds only what the request takes. Shapes: docs/api/01-http-api.md, 02-lobby-websocket.md.

    // ---- The API

    /// <summary>POST /v1/accounts: register (then log in with Credentials).</summary>
    [Serializable] public class Registration { public string username; public string password; public string displayName; }

    /// <summary>POST /v1/sessions with a name and password.</summary>
    [Serializable] public class Credentials { public string username; public string password; }

    /// <summary>POST /v1/sessions for a guest, by its key.</summary>
    [Serializable] public class GuestLogin { public string guestKey; }

    /// <summary>POST /v1/guests answers it: keep guestKey on the device, it is the account.</summary>
    [Serializable] public class Guest { public long playerId; public string guestKey; public string displayName; }

    /// <summary>POST /v1/sessions answers it: send token as Bearer, and in the lobby's auth.</summary>
    [Serializable] public class Session { public string token; public long playerId; public int expiresInSeconds; }

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
