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
                ax *= Diagonal;
                ay *= Diagonal;
            }
        }

        /// <summary>One tick: the input's push, the friction, the map's edge, then a maze's walls.</summary>
        /// <param name="walls">a maze's, or null</param>
        public static void Step(ref MotionState s, int moveMask, float accel, float radius,
                                float mapWidth, float mapHeight, WallGrid walls)
        {
            Direction(moveMask, out float ax, out float ay);
            s.Vx = (s.Vx + ax * accel) * Friction;
            s.Vy = (s.Vy + ay * accel) * Friction;
            s.X += s.Vx;
            s.Y += s.Vy;
            if (s.X < radius)
            {
                s.X = radius;
                s.Vx = -s.Vx * 0.5f;
            }
            else if (s.X > mapWidth - radius)
            {
                s.X = mapWidth - radius;
                s.Vx = -s.Vx * 0.5f;
            }
            if (s.Y < radius)
            {
                s.Y = radius;
                s.Vy = -s.Vy * 0.5f;
            }
            else if (s.Y > mapHeight - radius)
            {
                s.Y = mapHeight - radius;
                s.Vy = -s.Vy * 0.5f;
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

        private int Column(float x) => Math.Max(0, Math.Min(_columns - 1, (int)Math.Floor(x / _cell)));
        private int Row(float y) => Math.Max(0, Math.Min(_rows - 1, (int)Math.Floor(y / _cell)));

        /// <summary>Out of every wall it overlaps, the shorter way, its velocity into the wall dropped.</summary>
        public void PushOut(ref MotionState s, float r)
        {
            for (int cx = Column(s.X - r); cx <= Column(s.X + r); cx++)
                for (int cy = Row(s.Y - r); cy <= Row(s.Y + r); cy++)
                    foreach (Wall w in _byCell[cy * _columns + cx])
                        PushOut(ref s, r, w);
        }

        private static void PushOut(ref MotionState s, float r, Wall w)
        {
            float nearX = Math.Max(w.MinX, Math.Min(s.X, w.MaxX));
            float nearY = Math.Max(w.MinY, Math.Min(s.Y, w.MaxY));
            float dx = s.X - nearX;
            float dy = s.Y - nearY;
            float d2 = dx * dx + dy * dy;
            if (d2 > 0f)
            {
                if (d2 >= r * r) return;                                   // not touching
                float d = (float)Math.Sqrt(d2);
                float nx = dx / d;
                float ny = dy / d;
                s.X += nx * (r - d);
                s.Y += ny * (r - d);
                float into = s.Vx * nx + s.Vy * ny;
                if (into < 0f)
                {
                    s.Vx -= into * nx;
                    s.Vy -= into * ny;
                }
                return;
            }
            // The centre inside: out through the nearest face.
            float left = s.X - w.MinX;
            float right = w.MaxX - s.X;
            float up = s.Y - w.MinY;
            float down = w.MaxY - s.Y;
            float least = Math.Min(Math.Min(left, right), Math.Min(up, down));
            if (least == left)
            {
                s.X = w.MinX - r;
                s.Vx = Math.Min(s.Vx, 0f);
            }
            else if (least == right)
            {
                s.X = w.MaxX + r;
                s.Vx = Math.Max(s.Vx, 0f);
            }
            else if (least == up)
            {
                s.Y = w.MinY - r;
                s.Vy = Math.Min(s.Vy, 0f);
            }
            else
            {
                s.Y = w.MaxY + r;
                s.Vy = Math.Max(s.Vy, 0f);
            }
        }
    }
}
