using System;
using System.Collections.Generic;
using Backend.Client.Core;

namespace Backend.Client.Headless
{
    /// <summary>
    /// The maze scenario's shot (01 §8.9, D-48, T-66): one of the tanks fires at a wall and its bullets end at a wall the
    /// client made. The first of them with a wall <see cref="Nearest"/> to <see cref="Farthest"/> units off, its way
    /// clear, fires: from where its tank is, chosen again in any frame that makes a tank again, as a made match's start
    /// places every tank anew; and with its trigger held, pressed at every poll, since a press is sent once.
    /// </summary>
    internal sealed class MazeShot
    {
        /// <summary>A bullet is born 39 units out at level 1: one fired at a nearer wall ends in a tick, before any snapshot carries it (T-26).</summary>
        public const float Nearest = 100f;

        /// <summary>The farthest wall it fires at: a bullet at level 1 flies 75 ticks at 10 units, about 790 from the tank.</summary>
        public const float Farthest = 700f;

        private readonly IList<MatchConnection> _tanks;
        private bool _again;

        /// <summary>The tank that fires.</summary>
        public MatchConnection Shooter { get; private set; }
        /// <summary>How far the wall aimed at is from it, or <see cref="float.MaxValue"/> for none.</summary>
        public float Distance { get; private set; } = float.MaxValue;
        public int Aim { get; private set; }
        /// <summary>Its bullets in flight, summed over polls.</summary>
        public int Flying { get; private set; }
        public int Removed { get; private set; }
        /// <summary>The nearest a removed bullet was to any wall.</summary>
        public float Closest { get; private set; } = float.MaxValue;
        public bool EndedAtAWall { get; private set; }

        public MazeShot(IList<MatchConnection> tanks)
        {
            _tanks = tanks;
            // Every frame's, not the last polled frame's only: a poll applies every frame waiting (T-57).
            foreach (MatchConnection tank in tanks)
            {
                MatchConnection t = tank;
                t.OnFrame = () => OnFrame(t);
            }
            Choose();
            Press();
        }

        /// <summary>Once a poll: whether a bullet of its has ended at a wall.</summary>
        public bool Poll()
        {
            if (_again)
            {
                _again = false;
                Choose();
            }
            Press();
            for (int h = 0; h < Wire.MaxHandles; h++)
                if (Shooter.World[h].Alive && Shooter.World[h].Kind == Wire.KindPredicted) Flying++;
            return EndedAtAWall;
        }

        /// <summary>The trigger let go, and the frames no longer watched.</summary>
        public void Stop()
        {
            Shooter.SetInput(0, Aim, false, 0);
            foreach (MatchConnection tank in _tanks) tank.OnFrame = null;
        }

        /// <summary>A press is sent once (<see cref="MatchConnection.SetInput"/>): a trigger held is pressed again before each send.</summary>
        private void Press()
        {
            if (Distance < float.MaxValue) Shooter.SetInput(0, Aim, true, 0);
        }

        private void Choose()
        {
            Shooter = _tanks[0];
            Distance = float.MaxValue;
            foreach (MatchConnection tank in _tanks)
            {
                Entity self = tank.World[Wire.SelfHandle];
                float sx = self.X / Wire.PosScale, sy = self.Y / Wire.PosScale;
                float d = Target(tank.World.Walls, sx, sy, out float tx, out float ty);
                if (d == float.MaxValue) continue;
                Shooter = tank;
                Distance = d;
                Aim = Wire.QuantiseAim((float)Math.Atan2(ty - sy, tx - sx));
                break;
            }
        }

        private void OnFrame(MatchConnection tank)
        {
            // Made again: a made match's start resets the room and places every tank anew (startMadeMatch).
            if (tank.World.SelfCreated) _again = true;
            if (tank == Shooter) Removals(tank.World);
        }

        private void Removals(ClientWorld world)
        {
            // Nobody else fires: a bullet removed is the shooter's, by its own rule or the server's.
            foreach (Removal r in world.Removals)
                if (r.Kind == Wire.KindPredicted)
                {
                    Removed++;
                    float rx = r.X / Wire.PosScale, ry = r.Y / Wire.PosScale;
                    foreach (Wall w in world.Walls)
                    {
                        float px = Clamp(rx, w.MinX, w.MaxX), py = Clamp(ry, w.MinY, w.MaxY);
                        Closest = Math.Min(Closest, (float)Math.Sqrt((px - rx) * (px - rx) + (py - ry) * (py - ry)));
                        if (w.Hits(rx, ry, 20)) EndedAtAWall = true;
                    }
                }
        }

        /// <summary>
        /// The wall to fire at from (x, y): the nearest point of the nearest wall <see cref="Nearest"/> to <see cref="Farthest"/>
        /// off whose way is clear of every other wall (T-57); its distance, or <see cref="float.MaxValue"/> when none is.
        /// </summary>
        public static float Target(IReadOnlyList<Wall> walls, float x, float y, out float tx, out float ty)
        {
            float near = float.MaxValue;
            tx = 0;
            ty = 0;
            foreach (Wall w in walls)
            {
                float px = Clamp(x, w.MinX, w.MaxX), py = Clamp(y, w.MinY, w.MaxY);
                float d = (float)Math.Sqrt((px - x) * (px - x) + (py - y) * (py - y));
                if (d >= Nearest && d <= Farthest && d < near && ClearTo(walls, w, x, y, px, py, d))
                {
                    near = d;
                    tx = px;
                    ty = py;
                }
            }
            return near;
        }

        private static bool ClearTo(IReadOnlyList<Wall> walls, Wall target, float x, float y, float px, float py, float d)
        {
            for (float t = 39; t < d - 10; t += 5)
            {
                float cx = x + (px - x) * t / d, cy = y + (py - y) * t / d;
                foreach (Wall other in walls)
                    if (!other.Equals(target) && other.Hits(cx, cy, 10)) return false;
            }
            return true;
        }

        /// <summary>Math.Clamp, which .NET Framework has not.</summary>
        private static float Clamp(float v, float lo, float hi) => v < lo ? lo : v > hi ? hi : v;
    }
}
