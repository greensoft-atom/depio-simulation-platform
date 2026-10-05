// Client-side decoder for the match snapshot (docs/detailed-design/02-networking.md §4).
//
// NOT COMPILER-VERIFIED: it was written on a machine with no C# toolchain. Run it against
// protocol-spike/vectors/vectors.txt first; that file is the contract, and every decoded
// field there must match exactly.
//
// It has also never been RUN, and that hid a real defect until 2026-09-23: the event
// payload length is a count of BYTES, and this skipped that many VARINTS instead — a
// leftover from the draft in which payloads were varint lists. Every vector carrying an
// event would have desynchronised. Fixed here, still unverified by a compiler, and the
// reason the first thing anybody with a toolchain should do is run the vectors.
//
// Allocation discipline matters as much here as on the server: this runs 15 times a second
// on a phone, and garbage shows up as frame hitches. Nothing in the hot path allocates —
// the reader is a struct over a caller-owned buffer, and entities live in a pooled array.

using System;
using System.Collections.Generic;
using System.Text;

namespace Backend.Match
{
    public enum EntityKind : byte { Tank = 0, Predicted = 1, Static = 2, Unit = 3 }

    [Flags]
    public enum UpdateFields : byte
    {
        None = 0, Pos = 1, Angle = 2, Hp = 4, Level = 8,
        Class = 16, Team = 32, Flags = 64
    }

    /// <summary>Little-endian reader with LEB128 varints. Mirrors the server's com.backend.protocol.WireReader.</summary>
    public ref struct WireReader
    {
        private readonly ReadOnlySpan<byte> _a;
        private int _p;

        public WireReader(ReadOnlySpan<byte> a)
        {
            _a = a;
            _p = 0;
        }

        public int Position => _p;
        public bool Done => _p == _a.Length;

        public byte U8() => _a[_p++];

        public ushort U16()
        {
            ushort v = (ushort)(_a[_p] | (_a[_p + 1] << 8));
            _p += 2;
            return v;
        }

        public short I16() => unchecked((short)U16());

        /// <summary>Steps over <paramref name="n"/> bytes without decoding them.</summary>
        public void Skip(int n)
        {
            if (n < 0 || _p + n > _a.Length)
                throw new FormatException($"event payload of {n} bytes runs past the frame");
            _p += n;
        }

        public ulong Varint()
        {
            ulong v = 0;
            int shift = 0;
            while (true)
            {
                byte c = _a[_p++];
                v |= (ulong)(c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new FormatException("varint too long");
            }
        }

        public long SVarint()
        {
            ulong v = Varint();
            return (long)(v >> 1) ^ -(long)(v & 1);   // zigzag
        }

        /// <summary>Only used for names, which arrive once per tank create.</summary>
        public string Str()
        {
            int n = (int)Varint();
            string s = Encoding.UTF8.GetString(_a.Slice(_p, n));
            _p += n;
            return s;
        }
    }

    public struct Entity
    {
        public bool Alive;
        public EntityKind Kind;

        // Common. Positions are world units x4, in world space: a create's view-relative
        // position is converted once, as it arrives.
        public int X, Y;

        // Tank. Flags carries TANK_FLAG_PROTECTED (1): draw a newly arrived tank as untouchable.
        public byte Angle, Hp, ClassId, Team, Level, Flags;
        public string Name;

        // Predicted: extrapolated locally, never updated.
        public float Vx, Vy;              // world units x4 per tick, derived from heading+speed
        public byte Radius;               // world units: bullets (protocol 3) and units (4) come in sizes
        public int SpawnTick;
        public int DeathTick;             // SpawnTick + lifetimeTicks; a hard expiry backstop

        // Interpolation for tanks: previous sample and the tick it came from.
        public int PrevX, PrevY, PrevTick, CurTick;
    }

    public sealed class SnapshotReader
    {
        public const int MaxHandles = 256;

        /// <summary>Quantisation of a position: world units x this. Matches Wire.PosScale.</summary>
        public const float PosScale = 4f;

        /// <summary>The handle this client's own tank always has. Matches Wire.SELF_HANDLE.</summary>
        public const int SelfHandle = 1;

        private readonly Entity[] _entities = new Entity[MaxHandles];
        public IReadOnlyList<Entity> Entities => _entities;

        public long ServerTick { get; private set; }
        public long LastProcessedInputSeq { get; private set; }
        public int ViewOriginX { get; private set; }
        public int ViewOriginY { get; private set; }

        /// <summary>Raised for hit effects. Draw the effect here, not where the bullet was drawn.</summary>
        public event Action<int, int, int> OnDestroyed;   // handle, x, y

        /// <summary>Applies one snapshot frame. Returns the tick to acknowledge.</summary>
        public long Apply(ReadOnlySpan<byte> frame)
        {
            var r = new WireReader(frame);

            byte type = r.U8();
            if (type != 2) throw new FormatException($"not a snapshot: type {type}");

            ServerTick += (long)r.Varint();
            // 24 bits, wrapping (02 §9): compare seqs modulo 2^24, never with < or >.
            LastProcessedInputSeq = (LastProcessedInputSeq + (long)r.Varint()) & 0xFFFFFF;
            ViewOriginX += (int)r.SVarint();
            ViewOriginY += (int)r.SVarint();

            // Removes first: they free handles that the creates below may immediately reuse.
            int removeCount = (int)r.Varint();
            for (int i = 0; i < removeCount; i++)
            {
                int h = r.U8();
                ref Entity e = ref _entities[h];
                if (e.Alive)
                {
                    // A remove carries only a handle. A bullet's X, Y are where it was fired,
                    // so its hit is drawn where it has travelled to by this frame's tick.
                    int hx = e.X, hy = e.Y;
                    if (e.Kind == EntityKind.Predicted)
                    {
                        float dt = ServerTick - e.SpawnTick;
                        hx = (int)(e.X + e.Vx * dt);
                        hy = (int)(e.Y + e.Vy * dt);
                    }
                    OnDestroyed?.Invoke(h, hx, hy);
                    e.Alive = false;
                    e.Name = null;
                }
            }

            int createCount = (int)r.Varint();
            for (int i = 0; i < createCount; i++)
            {
                int h = r.U8();
                var kind = (EntityKind)r.U8();
                int x = r.I16();
                int y = r.I16();

                ref Entity e = ref _entities[h];
                e = default;
                e.Alive = true;
                e.Kind = kind;
                // A create is relative to this frame's view origin, so that it fits an i16
                // on any map size. Everything stored here is world space, converted once, on
                // the way in — an entity that receives no further updates (a shape sitting
                // still) would otherwise keep its create-time offset while the origin moved
                // beneath it, and appear glued to the camera.
                e.X = e.PrevX = x + ViewOriginX;
                e.Y = e.PrevY = y + ViewOriginY;
                e.PrevTick = e.CurTick = (int)ServerTick;

                switch (kind)
                {
                    case EntityKind.Tank:
                        e.Angle = r.U8();
                        e.Hp = r.U8();
                        e.ClassId = r.U8();
                        e.Team = r.U8();
                        e.Level = r.U8();
                        e.Name = r.Str();
                        break;

                    case EntityKind.Predicted:
                    {
                        // Polar on the wire: speed is exact, in half units a tick (protocol 2),
                        // only the heading is quantised. Measured drift over a full bullet life
                        // is ~0.3 px.
                        ushort heading = r.U16();
                        byte speed = r.U8();
                        byte spawnOffset = r.U8();
                        byte lifetime = r.U8();
                        r.U8();                        // owner handle: unused for rendering
                        e.Radius = r.U8();

                        float radians = heading * (2f * MathF.PI / 65536f) - MathF.PI;
                        float step = speed / 2f * PosScale;
                        e.Vx = MathF.Cos(radians) * step;
                        e.Vy = MathF.Sin(radians) * step;
                        e.SpawnTick = (int)ServerTick - spawnOffset;
                        e.DeathTick = e.SpawnTick + lifetime;
                        break;
                    }

                    case EntityKind.Static:
                        e.ClassId = r.U8();            // subtype: which shape to draw
                        // The spawn ORIENTATION, not a rate. Spin it locally at whatever rate
                        // looks right — nothing collides with a shape's facing, so the two
                        // sides do not have to agree on it.
                        e.Angle = r.U8();
                        break;

                    case EntityKind.Unit:
                        // A trap or a drone (protocol 3): not extrapolated, updated as a tank is.
                        e.Angle = r.U8();
                        e.Hp = r.U8();
                        e.ClassId = r.U8();            // subtype: 1 a trap, 2 a drone
                        e.Team = r.U8();
                        r.U8();                        // owner handle
                        e.Radius = r.U8();             // its own, since protocol 4: its owner may be gone
                        break;

                    default:
                        throw new FormatException($"unknown kind {(byte)kind}");
                }
            }

            int updateCount = (int)r.Varint();
            for (int i = 0; i < updateCount; i++)
            {
                int h = r.U8();
                var mask = (UpdateFields)r.U8();
                ref Entity e = ref _entities[h];

                if ((mask & UpdateFields.Pos) != 0)
                {
                    e.PrevX = e.X;
                    e.PrevY = e.Y;
                    e.PrevTick = e.CurTick;
                    // A WORLD-SPACE delta against the last frame the server sent. Accumulating
                    // it is correct because match traffic is TCP: every frame arrives exactly
                    // once, in order, so "last sent" and "last received" are the same
                    // sequence. Acknowledgement plays no part in the baseline — its remaining
                    // job is that the server will not reuse a handle until the client has
                    // confirmed its removal.
                    e.X += (int)r.SVarint();
                    e.Y += (int)r.SVarint();
                    e.CurTick = (int)ServerTick;
                }
                if ((mask & UpdateFields.Angle) != 0) e.Angle = r.U8();
                if ((mask & UpdateFields.Hp) != 0) e.Hp = r.U8();
                if ((mask & UpdateFields.Level) != 0) e.Level = r.U8();
                if ((mask & UpdateFields.Class) != 0) e.ClassId = r.U8();
                if ((mask & UpdateFields.Team) != 0) e.Team = r.U8();
                if ((mask & UpdateFields.Flags) != 0) e.Flags = r.U8();
            }

            int eventCount = (int)r.Varint();
            for (int i = 0; i < eventCount; i++)
            {
                r.U8();                                 // event type
                int n = (int)r.Varint();
                // BYTES, not varints. The length is a byte count precisely so that a client
                // can step over a type it has never heard of; decoding it as a count of
                // varints reads a different distance and desynchronises the rest of the
                // frame. This is where a real client switches on the type and reads the
                // payload it understands, then skips whatever it does not.
                r.Skip(n);
            }

            if (!r.Done)
                throw new FormatException($"trailing bytes: read {r.Position} of {frame.Length}");

            return ServerTick;
        }

        /// <summary>
        /// Render position at a fractional tick. Tanks interpolate between the last two samples;
        /// predicted entities extrapolate in a straight line — they never collide on the client,
        /// because the server decides what they hit.
        /// </summary>
        public void RenderPosition(int handle, float atTick, out float x, out float y)
        {
            ref Entity e = ref _entities[handle];
            if (!e.Alive) { x = y = 0; return; }

            if (e.Kind == EntityKind.Predicted)
            {
                float dt = atTick - e.SpawnTick;
                x = e.X + e.Vx * dt;
                y = e.Y + e.Vy * dt;
                return;
            }

            int span = e.CurTick - e.PrevTick;
            if (span <= 0) { x = e.X; y = e.Y; return; }
            float t = Math.Clamp((atTick - e.PrevTick) / span, 0f, 1f);
            x = e.PrevX + (e.X - e.PrevX) * t;
            y = e.PrevY + (e.Y - e.PrevY) * t;
        }

        /// <summary>
        /// Backstop for a lost destroy: a predicted entity disappears at its stated lifetime even
        /// if no destroy arrived. Bounds the damage from a dropped event to one bullet.
        /// </summary>
        public void ExpirePredicted(int atTick)
        {
            for (int h = 0; h < _entities.Length; h++)
            {
                ref Entity e = ref _entities[h];
                if (e.Alive && e.Kind == EntityKind.Predicted && atTick >= e.DeathTick)
                {
                    e.Alive = false;
                }
            }
        }
    }
}
