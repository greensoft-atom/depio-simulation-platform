using System;
using System.Collections.Generic;

namespace Backend.Client.Core
{
    /// <summary>One thing to draw this frame (08 §8). World units and radians; reused frame to frame.</summary>
    public sealed class DrawItem
    {
        public int Handle, Kind;
        /// <summary>Where, in world units, and which way, in radians, at the render tick.</summary>
        public float X, Y, Angle;
        /// <summary>A bullet's or a unit's radius in world units; a tank's is its class's.</summary>
        public float Radius;
        /// <summary>A tank's class; a shape's or a unit's subtype is <see cref="Subtype"/>.</summary>
        public int ClassId, Subtype, Team, Skin, Level, Flags;
        /// <summary>Health as a fraction of full, 0 to 1.</summary>
        public float Health;
        public string Name = "";
        /// <summary>The player's own tank, drawn in the present.</summary>
        public bool Self;
    }

    /// <summary>
    /// What a frame draws, at a render tick (08 §4, §8; plan item 77 (a)): every live entity once, a tank or a
    /// unit between its two samples, a bullet extrapolated, a shape where it is, and the own tank in the present.
    /// The list and its items are kept from frame to frame, so drawing allocates nothing.
    /// </summary>
    public sealed class Scene
    {
        private readonly List<DrawItem> _items = new List<DrawItem>();
        private readonly List<DrawItem> _pool = new List<DrawItem>();

        public IReadOnlyList<DrawItem> Items => _items;

        /// <summary>The scene at <paramref name="renderTick"/>, the own tank at its newest sample.</summary>
        public void Build(ClientWorld world, double renderTick) => Build(world, renderTick, false, 0, 0, 0);

        /// <summary>
        /// The scene at <paramref name="renderTick"/>; while <paramref name="selfPredicted"/>, the own tank where
        /// its prediction draws it (<see cref="OwnTank.DrawPosition"/>, <see cref="OwnTank.Angle"/>).
        /// </summary>
        public void Build(ClientWorld world, double renderTick, bool selfPredicted, float selfX, float selfY, float selfAngle)
        {
            _items.Clear();
            for (int h = 0; h < Wire.MaxHandles; h++)
            {
                Entity e = world[h];
                if (!e.Alive) continue;
                DrawItem item = Next();
                item.Handle = h;
                item.Kind = e.Kind;
                item.Radius = e.Radius;
                item.ClassId = e.Kind == Wire.KindTank ? e.ClassId : 0;
                item.Subtype = e.Kind == Wire.KindTank ? 0 : e.ClassId;
                item.Team = e.Team;
                item.Skin = e.Skin;
                item.Level = e.Level;
                item.Flags = e.Flags;
                item.Health = e.Hp / 255f;
                item.Name = e.Name;
                item.Self = h == Wire.SelfHandle && e.Kind == Wire.KindTank;
                if (item.Self && selfPredicted)
                {
                    item.X = selfX;
                    item.Y = selfY;
                    item.Angle = selfAngle;
                }
                else if (item.Self || e.Kind == Wire.KindStatic)
                {
                    // The own tank is drawn in the present; a shape where it is, turned by the layer.
                    item.X = e.X / Wire.PosScale;
                    item.Y = e.Y / Wire.PosScale;
                    item.Angle = AngleToRadians(e.Angle);
                }
                else if (e.Kind == Wire.KindPredicted)
                {
                    e.PredictedAt(Math.Max(renderTick, e.BaseTick), out float x, out float y);
                    item.X = x / Wire.PosScale;
                    item.Y = y / Wire.PosScale;
                    item.Angle = Wire.AimToRadians(e.Heading);
                }
                else
                {
                    double f = e.SampleTick == e.PrevTick ? 1
                        : Math.Max(0, Math.Min(1, (renderTick - e.PrevTick) / (e.SampleTick - e.PrevTick)));
                    item.X = (float)((e.PrevX + (e.X - e.PrevX) * f) / Wire.PosScale);
                    item.Y = (float)((e.PrevY + (e.Y - e.PrevY) * f) / Wire.PosScale);
                    // The shorter way round: 250 to 6 is twelve steps through 0, not 244 back.
                    int turn = ((e.Angle - e.PrevAngle + 128) % 256 + 256) % 256 - 128;
                    item.Angle = AngleToRadians(e.PrevAngle + turn * f);
                }
            }
        }

        private DrawItem Next()
        {
            if (_items.Count == _pool.Count) _pool.Add(new DrawItem());
            DrawItem item = _pool[_items.Count];
            _items.Add(item);
            return item;
        }

        /// <summary>A u8 facing to radians: the server's quantiseAngle, read back.</summary>
        private static float AngleToRadians(double angle) => (float)(angle / 256 * 2 * Math.PI - Math.PI);
    }

    /// <summary>
    /// The render tick (08 §4): the newest frame's tick, plus the ticks since it came, less two frames' worth,
    /// never past the newest and never back. Frames' tick deltas are averaged, as at 15 a second of 25 ticks they
    /// alternate one and two.
    /// </summary>
    public sealed class RenderClock
    {
        private long _newest = -1, _arrivedAt;
        private double _delta, _last = double.NaN;

        /// <summary>A frame applied: its tick, and the clock's ms when it was.</summary>
        public void Frame(long tick, long nowMs)
        {
            if (_newest >= 0 && tick > _newest)
            {
                long delta = tick - _newest;
                _delta = _delta == 0 ? delta : _delta * 0.875 + delta * 0.125;
            }
            _newest = tick;
            _arrivedAt = nowMs;
        }

        /// <summary>The tick to draw at now; NaN before a frame.</summary>
        public double RenderTick(long nowMs)
        {
            if (_newest < 0) return double.NaN;
            double t = _newest + (nowMs - _arrivedAt) * Wire.TickHz / 1000.0 - 2 * _delta;
            t = Math.Min(t, _newest);
            if (!double.IsNaN(_last)) t = Math.Max(t, _last);
            _last = t;
            return t;
        }
    }
}
