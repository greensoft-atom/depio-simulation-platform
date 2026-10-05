using System;

namespace Backend.Client.Core
{
    /// <summary>
    /// Two sticks to an input (08 §8, plan item 77 (a)): a stick's offset as a fraction of its reach, its y growing
    /// upward as a screen's does in Unity, while the world's y grows downward (MoveUp takes y down).
    /// </summary>
    public static class TouchSticks
    {
        /// <summary>Inside this much of its reach, a move stick is still.</summary>
        public const float DeadZone = 0.2f;
        /// <summary>Past this much of its reach, the aim stick fires.</summary>
        public const float FireAt = 0.5f;

        private static readonly int[] Sectors =
        {
            Wire.MoveRight, Wire.MoveUp | Wire.MoveRight, Wire.MoveUp, Wire.MoveUp | Wire.MoveLeft,
            Wire.MoveLeft, Wire.MoveDown | Wire.MoveLeft, Wire.MoveDown, Wire.MoveDown | Wire.MoveRight,
        };

        /// <summary>The move mask: eight directions, by 45° sectors, past the dead zone.</summary>
        public static int MoveMask(float x, float y)
        {
            if (x * x + y * y < DeadZone * DeadZone) return 0;
            int sector = (int)Math.Round(Math.Atan2(y, x) / (Math.PI / 4));
            return Sectors[(sector % 8 + 8) % 8];
        }

        /// <summary>Keys: opposites cancel, as the server's rule makes them.</summary>
        public static int MoveMask(bool up, bool down, bool left, bool right)
        {
            int mask = 0;
            if (up && !down) mask |= Wire.MoveUp;
            if (down && !up) mask |= Wire.MoveDown;
            if (left && !right) mask |= Wire.MoveLeft;
            if (right && !left) mask |= Wire.MoveRight;
            return mask;
        }

        /// <summary>The aim stick's direction as the wire's aim; up the screen is the world's -y.</summary>
        public static int Aim(float x, float y) => Wire.QuantiseAim((float)Math.Atan2(-y, x));

        public static bool Fires(float x, float y) => x * x + y * y >= FireAt * FireAt;

        /// <summary>From one world position to another, as a mouse aims: world coordinates, y down.</summary>
        public static int AimAt(float fromX, float fromY, float toX, float toY) =>
            Wire.QuantiseAim((float)Math.Atan2(toY - fromY, toX - fromX));
    }
}
