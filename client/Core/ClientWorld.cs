using System;
using System.Collections.Generic;

namespace Backend.Client.Core
{
    /// <summary>One entity as the client understands it. One per handle, reused.</summary>
    public sealed class Entity
    {
        public bool Alive;
        public int Kind;
        /// <summary>World position in 1/<see cref="Wire.PosScale"/> units, absolute.</summary>
        public int X, Y;
        public int Angle, Hp, Level, ClassId, Team, Flags;
        public string Name = "";
        /// <summary>A tank's skin: its number in the skin table (<c>GET /v1/content/skins</c>), 0 for none (D-70).</summary>
        public int Skin;
        /// <summary>
        /// A tank's or a unit's sample before the newest, and the ticks of both (08 §4, plan item 77 (a)): what
        /// <see cref="Scene"/> draws between. A frame without its update is a sample where it was.
        /// </summary>
        public int PrevX, PrevY, PrevAngle;
        public long PrevTick, SampleTick;
        /// <summary>Predicted entities: heading, speed in half units a tick, owner, and the tick they die.</summary>
        public int Heading, Speed, OwnerHandle, DeathTick;
        /// <summary>A predicted entity's or a unit's radius in world units: bullets come in sizes (protocol 3),
        /// and so do traps and drones (protocol 4).</summary>
        public int Radius;

        // Where and when a predicted entity started. Its position is recomputed from here each
        // frame, not stepped: stepping rounds every tick and the error grows (over a world unit
        // in twelve ticks, measured on the server's reference).
        internal int BaseX, BaseY;
        internal long BaseTick;
        // The tick a maze's wall ends it (D-48), long.MaxValue for none: its path is known whole
        // from its create, so this is found once.
        internal long WallTick;

        /// <summary>Where a predicted entity is at a fractional tick, for drawing between frames.</summary>
        public void PredictedAt(double tick, out float x, out float y)
        {
            tick = Math.Min(tick, WallTick);                // never drawn into the wall that ends it
            float radians = Wire.AimToRadians(Heading);
            float travelled = (float)(Wire.SpeedOf(Speed) * Wire.PosScale * (tick - BaseTick));
            x = BaseX + (float)Math.Cos(radians) * travelled;
            y = BaseY + (float)Math.Sin(radians) * travelled;
        }

        internal void Clear()
        {
            Alive = false;
            Name = "";
            Kind = X = Y = Angle = Hp = Level = ClassId = Team = Flags = Skin = 0;
            PrevX = PrevY = PrevAngle = 0;
            PrevTick = SampleTick = 0;
            Heading = Speed = OwnerHandle = DeathTick = Radius = 0;
            BaseX = BaseY = 0;
            BaseTick = WallTick = 0;
        }
    }

    /// <summary>
    /// Something that happened, carried in a snapshot's event section (02 §4). One instance per
    /// slot, reused frame to frame. Fields not of this event's type are left at their defaults.
    /// </summary>
    public sealed class MatchEvent
    {
        public int Type;
        /// <summary>Death: the score the player had, and who killed them ("" for nobody).</summary>
        public long Score;
        public string Killer = "";
        /// <summary>Kill (01 §9): who was killed, by <see cref="Killer"/>; "" for a tank that is nobody's.</summary>
        public string Victim = "";
        /// <summary>Stats: the player's own progression.</summary>
        public long Level, Xp, XpForNextLevel;
        public int UnspentPoints;
        public readonly int[] PointsPerStat = new int[8];
        /// <summary>
        /// Phrase (docs 01 §9): what was said, by whom, and the speaker's handle in this view, 0 when
        /// it holds none (a teammate out of sight). The words are the phrase list's, by id.
        /// </summary>
        public int Speaker, PhraseId;
        public string SpeakerName = "";
    }

    /// <summary>
    /// What the server says of the own tank's motion (docs 02 §9, D-62): the last <c>MotionRule</c>, and
    /// this frame's <c>Motion</c>, if it had one. State for <see cref="OwnTank"/>, not news: the layer
    /// above is never handed these as events.
    /// </summary>
    public sealed class SelfMotion
    {
        /// <summary>Whether a rule has come: before one, nothing can be predicted.</summary>
        public bool HasRule;
        /// <summary>What the input's direction is multiplied by each tick, and the tank's radius: the server's floats.</summary>
        public float Accel, Radius;
        /// <summary>Whether the last frame applied carried a Motion.</summary>
        public bool InFrame;
        /// <summary>The ticks the input the frame echoes had driven the tank, through the frame's tick.</summary>
        public int InputTicks;
        /// <summary>Its velocity after the frame's tick, in world units a tick.</summary>
        public float Vx, Vy;
    }

    /// <summary>An entity removed by the last frame, and where it was then: draw a hit there.</summary>
    public struct Removal
    {
        public int Handle, Kind, X, Y;
    }

    /// <summary>
    /// What the client holds between snapshots: the server's com.backend.protocol.ClientWorld, in
    /// C#, and checked against the same golden vectors (docs 08 §4).
    ///
    /// World space throughout: a create is relative to the view origin, so it fits an i16 on any
    /// map, and is converted once as it arrives; every update after it is a world-space delta
    /// against the last frame sent, which accumulates correctly because TCP delivers every frame
    /// once and in order. Main thread only.
    /// </summary>
    public sealed class ClientWorld : ISnapshotSink
    {
        private readonly Entity[] _entities = new Entity[Wire.MaxHandles];
        private readonly List<MatchEvent> _events = new List<MatchEvent>();
        private int _eventCount;
        private readonly List<Removal> _removals = new List<Removal>();

        public long ServerTick { get; private set; }
        /// <summary>The seq of the input that drove the own tank in the last tick applied (02 §9).</summary>
        public int LastProcessedInputSeq { get; private set; }
        /// <summary>The own tank's motion, as the last frames told it (D-62).</summary>
        public SelfMotion Motion { get; } = new SelfMotion();
        /// <summary>Whether the last frame created the own tank afresh: a respawn, or the first frame.</summary>
        public bool SelfCreated { get; private set; }
        public int OriginX { get; private set; }
        public int OriginY { get; private set; }

        /// <summary>The maze's walls, none without one (01 §8.9).</summary>
        public IReadOnlyList<Wall> Walls => _walls;
        private readonly Wall[] _walls;

        /// <param name="mazeSeed">the Welcome's, whose walls this makes (D-48); 0 for no maze</param>
        public ClientWorld(long mazeSeed = 0)
        {
            for (int h = 0; h < _entities.Length; h++) _entities[h] = new Entity();
            _walls = mazeSeed == 0 ? Array.Empty<Wall>() : Maze.Walls(mazeSeed);
        }

        /// <summary>The entity behind a handle. Check <see cref="Entity.Alive"/> first.</summary>
        public Entity this[int handle] => _entities[handle];

        public int AliveCount
        {
            get
            {
                int n = 0;
                foreach (var e in _entities) if (e.Alive) n++;
                return n;
            }
        }

        /// <summary>The events of the last frame applied, in order.</summary>
        public int EventCount => _eventCount;
        public MatchEvent EventAt(int i)
        {
            if (i < 0 || i >= _eventCount) throw new ArgumentOutOfRangeException(nameof(i));
            return _events[i];
        }

        /// <summary>What the last frame removed.</summary>
        public IReadOnlyList<Removal> Removals => _removals;

        /// <summary>
        /// Applies one snapshot frame and returns the tick it advanced to, which is the tick to
        /// acknowledge. A <see cref="ProtocolException"/> leaves the world part-applied: drop it
        /// with the connection and resume (08 §3).
        /// </summary>
        public long Apply(ReadOnlySpan<byte> frame)
        {
            _eventCount = 0;
            _removals.Clear();
            Motion.InFrame = false;
            SelfCreated = false;
            SnapshotDecoder.Decode(frame, this);
            // Last, so a create in this frame starts where it was placed and everything older has
            // advanced to the same tick.
            ExtrapolatePredicted();
            return ServerTick;
        }

        /// <summary>Drops predicted entities whose lifetime has run out, if no remove came.</summary>
        public void ExpirePredicted()
        {
            foreach (var e in _entities)
                if (e.Alive && e.Kind == Wire.KindPredicted && ServerTick >= e.DeathTick) e.Clear();
        }

        void ISnapshotSink.Header(long tickDelta, long inputSeqDelta, long originDx, long originDy)
        {
            ServerTick += tickDelta;
            // Every tank and unit takes a sample at this frame's tick, before the frame's updates move it: the one
            // it had becomes the one before (08 §4).
            foreach (Entity e in _entities)
            {
                if (!e.Alive || (e.Kind != Wire.KindTank && e.Kind != Wire.KindUnit)) continue;
                e.PrevX = e.X;
                e.PrevY = e.Y;
                e.PrevAngle = e.Angle;
                e.PrevTick = e.SampleTick;
                e.SampleTick = ServerTick;
            }
            // Modulo 2^24: the server sends the advance, never negative, and the seq wraps.
            LastProcessedInputSeq = (int)((LastProcessedInputSeq + inputSeqDelta) & Wire.InputSeqMask);
            OriginX += (int)originDx;
            OriginY += (int)originDy;
        }

        void ISnapshotSink.Remove(int handle)
        {
            Entity e = _entities[handle];
            if (e.Alive)
            {
                if (e.Kind == Wire.KindPredicted) Extrapolate(e);   // where it is at this frame's tick
                _removals.Add(new Removal { Handle = handle, Kind = e.Kind, X = e.X, Y = e.Y });
            }
            e.Clear();
        }

        void ISnapshotSink.CreateTank(int handle, int x, int y, int angle, int hp, int classId, int team,
                                      int level, string name)
        {
            Entity e = Born(handle, Wire.KindTank, x, y);
            e.Angle = angle;
            e.Hp = hp;
            e.ClassId = classId;
            e.Team = team;
            e.Level = level;
            e.Name = name;
        }

        void ISnapshotSink.CreatePredicted(int handle, int x, int y, int heading, int speed,
                                           int spawnTickOffset, int lifetimeTicks, int ownerHandle, int radius)
        {
            Entity e = Born(handle, Wire.KindPredicted, x, y);
            e.Heading = heading;
            e.Speed = speed;
            e.OwnerHandle = ownerHandle;
            e.Radius = radius;
            // Its position is where it was spawnTickOffset ticks before this frame, and its life
            // counts from then (02 §4).
            e.BaseX = e.X;
            e.BaseY = e.Y;
            e.BaseTick = ServerTick - spawnTickOffset;
            e.DeathTick = (int)e.BaseTick + lifetimeTicks;
            e.WallTick = WallTickOf(e);
        }

        /// <summary>
        /// The first tick after its create that a predicted entity's centre is within its radius of
        /// a wall, as the server checks after each move (D-48); long.MaxValue for none in its life.
        /// </summary>
        private long WallTickOf(Entity e)
        {
            if (_walls.Length == 0) return long.MaxValue;
            for (long t = e.BaseTick + 1; t <= e.DeathTick; t++)
            {
                PositionAt(e, t, out int x, out int y);
                foreach (Wall w in _walls)
                    if (w.Hits(x / Wire.PosScale, y / Wire.PosScale, e.Radius)) return t;
            }
            return long.MaxValue;
        }

        void ISnapshotSink.CreateStatic(int handle, int x, int y, int subtype, int spawnAngle)
        {
            Entity e = Born(handle, Wire.KindStatic, x, y);
            e.ClassId = subtype;
            e.Angle = spawnAngle;
        }

        /// <summary>A trap or a drone: drawn where its updates put it, as a tank is; its subtype where a shape's is.</summary>
        void ISnapshotSink.CreateUnit(int handle, int x, int y, int angle, int hp, int subtype, int team, int ownerHandle, int radius)
        {
            Entity e = Born(handle, Wire.KindUnit, x, y);
            e.Angle = angle;
            e.Hp = hp;
            e.ClassId = subtype;
            e.Team = team;
            e.OwnerHandle = ownerHandle;
            e.Radius = radius;          // its own: a trap outlives the owner it could be looked up by
        }

        private Entity Born(int handle, int kind, int x, int y)
        {
            if (handle == Wire.SelfHandle) SelfCreated = true;
            Entity e = _entities[handle];
            e.Clear();
            e.Alive = true;
            e.Kind = kind;
            e.X = x + OriginX;          // relative to this frame's view origin on the wire
            e.Y = y + OriginY;
            e.PrevTick = e.SampleTick = ServerTick;     // born here: one sample, drawn where it is
            return e;
        }

        void ISnapshotSink.Update(in SnapshotUpdate u)
        {
            Entity e = _entities[u.Handle];
            // The two sides disagree about the handle table, which is not survivable by guessing.
            if (!e.Alive) throw new ProtocolException($"update for handle {u.Handle}, which is not alive here");
            if (u.Has(Wire.FPos))
            {
                e.X += (int)u.Dx;
                e.Y += (int)u.Dy;
            }
            if (u.Has(Wire.FAngle)) e.Angle = u.Angle;
            if (u.Has(Wire.FHp)) e.Hp = u.Hp;
            if (u.Has(Wire.FLevel)) e.Level = u.Level;
            if (u.Has(Wire.FClass)) e.ClassId = u.ClassId;
            if (u.Has(Wire.FTeam)) e.Team = u.Team;
            if (u.Has(Wire.FFlags)) e.Flags = u.Flags;
        }

        void ISnapshotSink.Event(int type, ReadOnlySpan<byte> payload)
        {
            // The own tank's motion: state for its prediction, kept here and handed to nobody (D-62).
            if (type == Wire.EvtMotion)
            {
                var m = new WireReader(payload);
                Motion.InFrame = true;
                Motion.InputTicks = m.U8();
                Motion.Vx = m.SVarint() / Wire.VelocityScale;
                Motion.Vy = m.SVarint() / Wire.VelocityScale;
                return;
            }
            if (type == Wire.EvtMotionRule)
            {
                var m = new WireReader(payload);
                Motion.Accel = BitConverter.Int32BitsToSingle(unchecked((int)m.U32BigEndian()));
                Motion.Radius = BitConverter.Int32BitsToSingle(unchecked((int)m.U32BigEndian()));
                Motion.HasRule = true;
                return;
            }
            // A tank's skin: state on the tank, as its name is, told with its create (D-70).
            if (type == Wire.EvtSkin)
            {
                var m = new WireReader(payload);
                Entity tank = _entities[m.U8()];
                int skin = m.U8();
                if (tank.Alive && tank.Kind == Wire.KindTank) tank.Skin = skin;
                return;
            }
            if (_eventCount == _events.Count) _events.Add(new MatchEvent());
            MatchEvent ev = _events[_eventCount++];
            ev.Type = type;
            ev.Score = ev.Level = ev.Xp = ev.XpForNextLevel = 0;
            ev.UnspentPoints = 0;
            ev.Killer = ev.Victim = "";
            Array.Clear(ev.PointsPerStat, 0, ev.PointsPerStat.Length);
            ev.Speaker = ev.PhraseId = 0;
            ev.SpeakerName = "";
            var r = new WireReader(payload);
            switch (type)
            {
                case Wire.EvtDeath:
                    ev.Score = r.Varint();
                    ev.Killer = r.Utf8(r.U8());
                    break;
                case Wire.EvtStats:
                    ev.Level = r.Varint();
                    ev.Xp = r.Varint();
                    ev.XpForNextLevel = r.Varint();
                    ev.UnspentPoints = r.U8();
                    for (int i = 0; i < ev.PointsPerStat.Length; i++) ev.PointsPerStat[i] = r.U8();
                    break;
                case Wire.EvtPhrase:
                    ev.Speaker = r.U8();
                    ev.PhraseId = (int)r.Varint();
                    ev.SpeakerName = r.Utf8(r.U8());
                    break;
                case Wire.EvtKill:
                    ev.Killer = r.Utf8(r.U8());
                    ev.Victim = r.Utf8(r.U8());
                    break;
                // Any other type is one this client has never heard of, stepped over whole by its
                // length. Bytes after the fields a known type defines are the same: a later
                // server's additions, ignored.
            }
        }

        private void ExtrapolatePredicted()
        {
            for (int h = 0; h < _entities.Length; h++)
            {
                Entity e = _entities[h];
                if (!e.Alive || e.Kind != Wire.KindPredicted) continue;
                Extrapolate(e);
                if (ServerTick >= e.WallTick)
                {
                    // A wall ended it (D-48): its hit is drawn now, not when the server's remove comes.
                    _removals.Add(new Removal { Handle = h, Kind = e.Kind, X = e.X, Y = e.Y });
                    e.Clear();
                }
            }
        }

        /// <summary>Where it is at this frame's tick, or at the wall that ended it.</summary>
        private void Extrapolate(Entity e) => PositionAt(e, Math.Min(ServerTick, e.WallTick), out e.X, out e.Y);

        /// <summary>Exactly the server's reference: the same single rounding, from the create.</summary>
        private static void PositionAt(Entity e, long tick, out int x, out int y)
        {
            float radians = Wire.AimToRadians(e.Heading);
            float travelled = Wire.SpeedOf(e.Speed) * Wire.PosScale * (tick - e.BaseTick);
            x = e.BaseX + Wire.JavaRound((float)Math.Cos(radians) * travelled);
            y = e.BaseY + Wire.JavaRound((float)Math.Sin(radians) * travelled);
        }
    }
}
