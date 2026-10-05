using System;

namespace Backend.Client.Core
{
    /// <summary>A maze's wall: an axis-aligned rectangle, in world units (docs 01 §8.9).</summary>
    public readonly struct Wall
    {
        public readonly float MinX, MinY, MaxX, MaxY;

        public Wall(float minX, float minY, float maxX, float maxY)
        {
            MinX = minX;
            MinY = minY;
            MaxX = maxX;
            MaxY = maxY;
        }

        /// <summary>Whether a circle's centre is within its radius of this wall: the server's rule (D-48).</summary>
        public bool Hits(float x, float y, float r)
        {
            float dx = x - Math.Max(MinX, Math.Min(x, MaxX));
            float dy = y - Math.Max(MinY, Math.Min(y, MaxY));
            return dx * dx + dy * dy <= r * r;
        }

        public override string ToString() => $"Wall({MinX}, {MinY}, {MaxX}, {MaxY})";
    }

    /// <summary>
    /// The server's com.backend.sim.MazeGenerator, in C# (docs 01 §8.9, D-48): the walls are never
    /// sent, only the seed, so this makes the same ones from it, number for number, held to the
    /// server's by a golden vector. The order the numbers are drawn in is the server's: a chamber
    /// is split before its two halves, the first half first; a split draws its orientation (only
    /// when the chamber is square), its line, then its gap; the walls are listed horizontal ones
    /// first, by row then column, then vertical ones, by column then row; and the ones taken out
    /// are the first of a partial shuffle of that list.
    /// </summary>
    public static class Maze
    {
        public const int Cells = 10;
        public const float Cell = 300f;
        public const float Thickness = 40f;
        /// <summary>One wall in this many is taken out after the division.</summary>
        private const int BraidDivisor = 5;

        public static Wall[] Walls(long seed)
        {
            var rng = new Xorshift(seed);
            // across[x, y]: a wall on the top edge of cell (x, y); down[x, y]: on its left edge.
            var across = new bool[Cells, Cells];
            var down = new bool[Cells, Cells];
            Divide(rng, across, down, 0, 0, Cells, Cells);

            var segments = new System.Collections.Generic.List<(bool across, int x, int y)>();
            for (int y = 0; y < Cells; y++)
                for (int x = 0; x < Cells; x++)
                    if (across[x, y]) segments.Add((true, x, y));
            for (int x = 0; x < Cells; x++)
                for (int y = 0; y < Cells; y++)
                    if (down[x, y]) segments.Add((false, x, y));
            int n = segments.Count;
            int removed = n / BraidDivisor;
            var order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
            for (int i = 0; i < removed; i++)
            {
                int j = i + rng.NextInt(n - i);
                (order[i], order[j]) = (order[j], order[i]);
            }
            var gone = new bool[n];
            for (int i = 0; i < removed; i++) gone[order[i]] = true;

            float half = Thickness / 2;
            var walls = new Wall[n - removed];
            int at = 0;
            for (int i = 0; i < n; i++)
            {
                if (gone[i]) continue;
                var s = segments[i];
                float x = s.x * Cell;
                float y = s.y * Cell;
                walls[at++] = s.across
                    ? new Wall(x - half, y - half, x + Cell + half, y + half)
                    : new Wall(x - half, y - half, x + half, y + Cell + half);
            }
            return walls;
        }

        /// <summary>Splits the chamber of w by h cells at (x, y) until every chamber is a cell.</summary>
        private static void Divide(Xorshift rng, bool[,] across, bool[,] down, int x, int y, int w, int h)
        {
            if (w < 2 && h < 2) return;
            bool vertical = w > h || (w == h && rng.NextInt(2) == 0);
            if (vertical)
            {
                int k = 1 + rng.NextInt(w - 1);
                int gap = rng.NextInt(h);
                for (int j = 0; j < h; j++)
                    if (j != gap) down[x + k, y + j] = true;
                Divide(rng, across, down, x, y, k, h);
                Divide(rng, across, down, x + k, y, w - k, h);
            }
            else
            {
                int k = 1 + rng.NextInt(h - 1);
                int gap = rng.NextInt(w);
                for (int i = 0; i < w; i++)
                    if (i != gap) across[x + i, y + k] = true;
                Divide(rng, across, down, x, y, w, k);
                Divide(rng, across, down, x, y + k, w, h - k);
            }
        }

        /// <summary>The server's com.backend.common.Xorshift: xorshift128+, seeded by SplitMix64.</summary>
        private sealed class Xorshift
        {
            private ulong _s0, _s1;

            public Xorshift(long seed)
            {
                _s0 = Mix(unchecked((ulong)seed));
                _s1 = Mix(_s0);
                if (_s0 == 0 && _s1 == 0) _s1 = 1;         // the all-zero state is a fixed point
            }

            private static ulong Mix(ulong z)
            {
                unchecked
                {
                    z += 0x9E3779B97F4A7C15UL;
                    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9UL;
                    z = (z ^ (z >> 27)) * 0x94D049BB133111EBUL;
                    return z ^ (z >> 31);
                }
            }

            private long NextLong()
            {
                unchecked
                {
                    ulong x = _s0;
                    ulong y = _s1;
                    _s0 = y;
                    x ^= x << 23;
                    _s1 = x ^ y ^ (x >> 17) ^ (y >> 26);
                    return (long)(_s1 + y);
                }
            }

            /// <summary>Uniform in [0, bound): Java's Math.floorMod of the next long.</summary>
            public int NextInt(int bound)
            {
                long m = NextLong() % bound;
                return (int)(m < 0 ? m + bound : m);
            }
        }
    }
}
