// The match protocol's numbers (docs/detailed-design/02-networking.md §2–§4).
//
// Each constant mirrors the server's com.backend.protocol.Wire or ClientMessage, under the same
// meaning. A number changed on one side only is the costliest bug this project can have; the
// golden vectors and the headless client against the real server are what catch it.

namespace Backend.Client.Core
{
    public static class Wire
    {
        /// <summary>Sent first in Join and Resume, so a mismatch is refused, not mis-parsed.</summary>
        public const int Version = 4;

        // Server to client.
        public const int MsgWelcome = 1;
        public const int MsgSnapshot = 2;
        public const int MsgPong = 3;
        public const int MsgKick = 4;

        // Client to server.
        public const int MsgJoin = 1;
        public const int MsgInput = 2;
        public const int MsgUpgradeStat = 3;
        public const int MsgChooseClass = 4;
        public const int MsgRespawn = 5;
        public const int MsgPhrase = 6;
        public const int MsgPing = 7;
        public const int MsgLifecycle = 8;
        public const int MsgLeave = 9;
        public const int MsgResume = 10;
        /// <summary>A sandbox's power (01 §8.10): u8 action, varint value. Sent only in a sandbox.</summary>
        public const int MsgSandbox = 11;
        /// <summary>Sandbox actions: rebuild the own tank at a level (1 to 45), or summon a Guardian (value 0).</summary>
        public const int SandboxLevel = 1, SandboxGuardian = 2;

        public const int LifecycleForeground = 0;
        public const int LifecycleBackground = 1;

        // The most a client wants sent (§8): absent or unknown means Mobile.
        public const int ProfileMobile = 0;
        public const int ProfileHigh = 1;
        public const int ProfileSaver = 2;

        // Kick reasons (§3), each with its own answer: see docs 08 §3.
        public const int KickBadTicket = 1;
        public const int KickRoomFull = 2;
        public const int KickProtocolVersion = 3;
        public const int KickRateLimit = 4;
        public const int KickInternal = 5;
        /// <summary>A made match has ended (04 §4): back to the lobby, as after Leave. Not an error.</summary>
        public const int KickMatchOver = 6;
        /// <summary>Removed by an operator: the room closed, or the player taken out (04 §10).</summary>
        public const int KickRemoved = 7;

        // Input.
        public const int MoveUp = 1;
        public const int MoveDown = 2;
        public const int MoveLeft = 4;
        public const int MoveRight = 8;
        public const int FlagFire = 1;
        public const int FlagAutofire = 2;
        public const int FlagAutospin = 4;
        /// <summary>Held: a class with a zoom (the Predator) sees ahead along its aim.</summary>
        public const int FlagZoom = 8;
        /// <summary>Input seq is 24 bits and wraps: compare with <see cref="SeqNewer"/>, never &gt;.</summary>
        public const int InputSeqMask = 0xFFFFFF;

        // Snapshot.
        public const int KindTank = 0;
        public const int KindPredicted = 1;
        public const int KindStatic = 2;
        /// <summary>A trap or a drone: not predictable, updated as a tank is (protocol 3), carrying its radius (protocol 4).</summary>
        public const int KindUnit = 3;
        public const int UnitTrap = 1;
        public const int UnitDrone = 2;
        /// <summary>A drone that shoots, the Factory's; its bullets arrive as ordinary predicted creates.</summary>
        public const int UnitMinion = 3;
        /// <summary>Missiles that fire as they fly: a Rocketeer's, and a Skimmer's, which turns.</summary>
        public const int UnitRocket = 4;
        public const int UnitSkimmer = 5;

        public const int FPos = 1;
        public const int FAngle = 2;
        public const int FHp = 4;
        public const int FLevel = 8;
        public const int FClass = 16;
        public const int FTeam = 32;
        public const int FFlags = 64;

        /// <summary>A tank in its spawn protection: it cannot be hurt and cannot shoot.</summary>
        public const int TankFlagProtected = 1;
        /// <summary>The player's own tank is hidden: nobody else is sent it while this lasts (protocol 4).</summary>
        public const int TankFlagHidden = 2;

        public const int EvtDeath = 1;
        public const int EvtStats = 2;
        public const int EvtPhrase = 3;
        /// <summary>A tank killed: killer's name, victim's name, either empty for nobody's (01 §9).</summary>
        public const int EvtKill = 4;
        /// <summary>The own tank's motion, every frame while it lives (02 §9, D-62): state, not news.</summary>
        public const int EvtMotion = 5;
        /// <summary>The own tank's acceleration and radius, when either changes (02 §9, D-62).</summary>
        public const int EvtMotionRule = 6;
        /// <summary>A tank's skin, with its create (04 §8, D-70): <c>u8 handle, u8 skin</c>. State on the tank, not news.</summary>
        public const int EvtSkin = 7;
        /// <summary>A velocity on the wire is in 1/256 of a world unit a tick.</summary>
        public const float VelocityScale = 256f;
        /// <summary>The room's rate (D-10): a step of the own tank's prediction is one of its ticks.</summary>
        public const int TickHz = 25;

        /// <summary>Positions are world units times this.</summary>
        public const float PosScale = 4f;

        public const int MaxHandles = 256;
        /// <summary>The handle of the client's own tank, always.</summary>
        public const int SelfHandle = 1;

        /// <summary>
        /// A predicted create's speed, carried in half units a tick, in world units a tick:
        /// Wire.speedOf. Exact, as the server fires it (protocol 2).
        /// </summary>
        public static float SpeedOf(int speed) => speed / 2f;

        /// <summary>A u16 heading or aim to radians in [-π, π): ClientMessage.aimToRadians.</summary>
        public static float AimToRadians(int aim)
        {
            return (float)(aim / 65536.0 * 2 * System.Math.PI - System.Math.PI);
        }

        /// <summary>Radians to the u16 the wire carries for an aim: Wire.quantiseHeading.</summary>
        public static int QuantiseAim(float radians)
        {
            double normalised = (radians + System.Math.PI) / (2 * System.Math.PI);
            return (int)JavaRound(normalised * 65536.0) & 0xFFFF;
        }

        /// <summary>Whether seq a is newer than b, modulo 2^24 (02 §9).</summary>
        public static bool SeqNewer(int a, int b)
        {
            int d = (a - b) & InputSeqMask;
            return d >= 1 && d < (1 << 23);
        }

        /// <summary>
        /// Java's Math.round: half rounds up. .NET's Math.Round rounds half to even, and the two
        /// sides must agree about where an extrapolated bullet is. Not Floor(v + 0.5): the sum
        /// rounds first, and 0.49999999999999994 would come out as 1 where Java says 0.
        /// </summary>
        public static long JavaRound(double v)
        {
            double f = System.Math.Floor(v);
            return (long)(v - f >= 0.5 ? f + 1 : f);   // v - f is exact at these magnitudes
        }

        /// <summary>Java's Math.round(float), for the single-precision paths; exact in double.</summary>
        public static int JavaRound(float v) => (int)JavaRound((double)v);
    }
}
