using System;
using System.Collections.Generic;

namespace Backend.Client.Core
{
    /// <summary>The own tank's position, in world units, and its velocity, in world units a tick.</summary>
    public struct MotionState
    {
        public float X, Y, Vx, Vy;

        public override string ToString() => $"({X}, {Y}) moving ({Vx}, {Vy})";
    }

    /// <summary>
    /// The room's step of a driven tank (com.backend.sim.Room.updateTanks and RoomThread.applyInputs),
    /// in C#, for the own tank's prediction (docs 02 §9, D-62). Every operation is the server's, in
    /// its order and in single precision, so the bits come out the same: held to the server's by a
    /// trajectory its own Room wrote (motion-2026.txt). What else moves a tank, recoil and knocks, is
    /// not here: the server's velocity brings it in at the next frame.
    /// <para>Each operation's result is cast to float, comparisons' operands too (D-118, P-65). Java
    /// rounds every float operation; C# lets a runtime keep a float in double between operations, a
    /// local or an argument too, and only an explicit conversion is sure to round. A Mono without
    /// float32 keeps them in double, and gave other last bits. Rounded at each step, a result computed
    /// in double is the float one exactly, so the bits are the room's on either runtime.</para>
    /// </summary>
    public static class TankMotion
    {
        public const float Friction = 0.90f;
        /// <summary>A diagonal must not be faster than straight.</summary>
        public const float Diagonal = 0.70710678f;

        /// <summary>The move bits as the room reads them: a direction, a diagonal scaled down.</summary>
        public static void Direction(int moveMask, out float ax, out float ay)
        {
            ax = 0f;
            ay = 0f;
            if ((moveMask & Wire.MoveLeft) != 0) ax -= 1f;
            if ((moveMask & Wire.MoveRight) != 0) ax += 1f;
            if ((moveMask & Wire.MoveUp) != 0) ay -= 1f;
            if ((moveMask & Wire.MoveDown) != 0) ay += 1f;
            if (ax != 0f && ay != 0f)
            {
                ax = (float)(ax * Diagonal);
                ay = (float)(ay * Diagonal);
            }
        }

        /// <summary>One tick: the input's push, the friction, the map's edge, then a maze's walls.</summary>
        /// <param name="walls">a maze's, or null</param>
        public static void Step(ref MotionState s, int moveMask, float accel, float radius,
                                float mapWidth, float mapHeight, WallGrid walls)
        {
            Direction(moveMask, out float ax, out float ay);
            s.Vx = (float)((float)(s.Vx + (float)(ax * accel)) * Friction);
            s.Vy = (float)((float)(s.Vy + (float)(ay * accel)) * Friction);
            s.X = (float)(s.X + s.Vx);
            s.Y = (float)(s.Y + s.Vy);
            float right = (float)(mapWidth - radius), bottom = (float)(mapHeight - radius);
            if (s.X < radius)
            {
                s.X = radius;
                s.Vx = (float)(-s.Vx * 0.5f);
            }
            else if (s.X > right)
            {
                s.X = right;
                s.Vx = (float)(-s.Vx * 0.5f);
            }
            if (s.Y < radius)
            {
                s.Y = radius;
                s.Vy = (float)(-s.Vy * 0.5f);
            }
            else if (s.Y > bottom)
            {
                s.Y = bottom;
                s.Vy = (float)(-s.Vy * 0.5f);
            }
            walls?.PushOut(ref s, radius);
        }
    }

    /// <summary>
    /// The server's com.backend.sim.Walls, in C# (docs 01 §8.9): a maze's walls by the cells they
    /// cover, a wall listed in every cell it touches, so a tank against one spanning two cells is
    /// pushed by it twice, as on the server.
    /// </summary>
    public sealed class WallGrid
    {
        private readonly float _cell;
        private readonly int _columns, _rows;
        private readonly List<Wall>[] _byCell;

        public WallGrid(IReadOnlyList<Wall> walls, float cell)
        {
            _cell = cell;
            float right = 0f, bottom = 0f;
            foreach (Wall w in walls)
            {
                right = Math.Max(right, w.MaxX);
                bottom = Math.Max(bottom, w.MaxY);
            }
            _columns = (int)(right / cell) + 1;
            _rows = (int)(bottom / cell) + 1;
            _byCell = new List<Wall>[_columns * _rows];
            for (int i = 0; i < _byCell.Length; i++) _byCell[i] = new List<Wall>();
            foreach (Wall w in walls)
                for (int cx = Column(w.MinX); cx <= Column(w.MaxX); cx++)
                    for (int cy = Row(w.MinY); cy <= Row(w.MaxY); cy++)
                        _byCell[cy * _columns + cx].Add(w);
        }

        private int Column(float x) => Math.Max(0, Math.Min(_columns - 1, (int)Math.Floor((float)(x / _cell))));
        private int Row(float y) => Math.Max(0, Math.Min(_rows - 1, (int)Math.Floor((float)(y / _cell))));

        /// <summary>Out of every wall it overlaps, the shorter way, its velocity into the wall dropped.</summary>
        public void PushOut(ref MotionState s, float r)
        {
            for (int cx = Column((float)(s.X - r)); cx <= Column((float)(s.X + r)); cx++)
                for (int cy = Row((float)(s.Y - r)); cy <= Row((float)(s.Y + r)); cy++)
                    foreach (Wall w in _byCell[cy * _columns + cx])
                        PushOut(ref s, r, w);
        }

        private static void PushOut(ref MotionState s, float r, Wall w)
        {
            float nearX = Math.Max(w.MinX, Math.Min(s.X, w.MaxX));
            float nearY = Math.Max(w.MinY, Math.Min(s.Y, w.MaxY));
            float dx = (float)(s.X - nearX);
            float dy = (float)(s.Y - nearY);
            float d2 = (float)((float)(dx * dx) + (float)(dy * dy));
            if (d2 > 0f)
            {
                if (d2 >= (float)(r * r)) return;                          // not touching
                float d = (float)Math.Sqrt(d2);
                float nx = (float)(dx / d);
                float ny = (float)(dy / d);
                float depth = (float)(r - d);
                s.X = (float)(s.X + (float)(nx * depth));
                s.Y = (float)(s.Y + (float)(ny * depth));
                float into = (float)((float)(s.Vx * nx) + (float)(s.Vy * ny));
                if (into < 0f)
                {
                    s.Vx = (float)(s.Vx - (float)(into * nx));
                    s.Vy = (float)(s.Vy - (float)(into * ny));
                }
                return;
            }
            // The centre inside: out through the nearest face.
            float left = (float)(s.X - w.MinX);
            float right = (float)(w.MaxX - s.X);
            float up = (float)(s.Y - w.MinY);
            float down = (float)(w.MaxY - s.Y);
            float least = Math.Min(Math.Min(left, right), Math.Min(up, down));
            if (least == left)
            {
                s.X = (float)(w.MinX - r);
                s.Vx = Math.Min(s.Vx, 0f);
            }
            else if (least == right)
            {
                s.X = (float)(w.MaxX + r);
                s.Vx = Math.Max(s.Vx, 0f);
            }
            else if (least == up)
            {
                s.Y = (float)(w.MinY - r);
                s.Vy = Math.Min(s.Vy, 0f);
            }
            else
            {
                s.Y = (float)(w.MaxY + r);
                s.Vy = Math.Max(s.Vy, 0f);
            }
        }
    }
}
