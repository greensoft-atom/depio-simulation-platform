using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>A float's bits and back, as .NET Framework has no BitConverter for (D-109): the same bytes either way.</summary>
    internal static class FloatBits
    {
        public static int Of(float f) => BitConverter.ToInt32(BitConverter.GetBytes(f), 0);
        public static float From(int bits) => BitConverter.ToSingle(BitConverter.GetBytes(bits), 0);
    }

    /// <summary>
    /// The movement rule ported, against the trajectory the server's own Room wrote
    /// (backend/arena/src/test/resources/motion-2026.txt, docs 02 §9, D-62): every step's position and
    /// velocity, bit for bit, on an open map's edges, along and round a maze's walls, and out of one.
    /// </summary>
    public class TankMotionTests
    {
        private sealed class Case
        {
            public string Name;
            public float Width, Height, Accel, Radius;
            public long MazeSeed;
            public MotionState Start;
            public readonly List<(int move, MotionState after)> Steps = new List<(int, MotionState)>();
        }

        private static float F(string hex) => FloatBits.From(int.Parse(hex, NumberStyles.HexNumber));

        private static List<Case> Cases()
        {
            var cases = new List<Case>();
            string path = Path.Combine(TestContext.CurrentContext.TestDirectory, "motion-2026.txt");
            foreach (string line in File.ReadAllLines(path))
            {
                string[] p = line.Split(' ');
                if (p[0] == "case")
                {
                    cases.Add(new Case
                    {
                        Name = p[1], Width = int.Parse(p[2]), Height = int.Parse(p[3]), MazeSeed = long.Parse(p[4]),
                        Accel = F(p[5]), Radius = F(p[6]),
                        Start = new MotionState { X = F(p[7]), Y = F(p[8]), Vx = F(p[9]), Vy = F(p[10]) },
                    });
                }
                else if (p[0] == "step")
                {
                    cases[cases.Count - 1].Steps.Add((int.Parse(p[1]),
                        new MotionState { X = F(p[2]), Y = F(p[3]), Vx = F(p[4]), Vy = F(p[5]) }));
                }
            }
            return cases;
        }

        [Test]
        public void TheVectorHasItsThreeCases()
        {
            Assert.That(Cases().ConvertAll(c => c.Name), Is.EqualTo(new[] { "open", "maze", "knock" }));
        }

        [TestCase("open")]
        [TestCase("maze")]
        [TestCase("knock")]
        public void EveryStepIsTheRoomsBitForBit(string name)
        {
            Case c = Cases().Find(k => k.Name == name);
            WallGrid walls = c.MazeSeed == 0 ? null : new WallGrid(Maze.Walls(c.MazeSeed), Maze.Cell);
            MotionState s = c.Start;
            for (int i = 0; i < c.Steps.Count; i++)
            {
                (int move, MotionState after) = c.Steps[i];
                TankMotion.Step(ref s, move, c.Accel, c.Radius, c.Width, c.Height, walls);
                Same(s.X, after.X, name, i, "x");
                Same(s.Y, after.Y, name, i, "y");
                Same(s.Vx, after.Vx, name, i, "vx");
                Same(s.Vy, after.Vy, name, i, "vy");
            }
        }

        private static void Same(float actual, float expected, string name, int step, string what)
        {
            // Bits, not a tolerance: the same operations in the same order give the same floats.
            if (FloatBits.Of(actual) != FloatBits.Of(expected))
                Assert.Fail($"{name}, step {step}: {what} {actual:R} where the room has {expected:R}");
        }

        // Cases one rounding of the step decides (D-118, P-65), which the recordings above never reach: each found where
        // that operation kept in double, as a Mono without float32 keeps it, gives other bits than Java's. The bits
        // expected are the server's own: its Walls.pushOut, and Room's expressions for the map's edge.

        [Test]
        public void TheMapsEdgeIsComparedAsTheRoomRoundsIt()
        {
            // 5700 - r rounds up, in float, to where this step lands: not past the edge, so the room does not clamp.
            // Compared in double, it is past it, and the tank would bounce back at half its speed.
            var s = new MotionState { X = F("45b071dd"), Y = 1000f, Vx = 1f, Vy = 0f };
            TankMotion.Step(ref s, 0, 0.16f, F("42537808"), 5700f, 5700f, null);
            Same(s.X, F("45b07910"), "the edge", 0, "x");
            Same(s.Vx, F("3f666666"), "the edge", 0, "vx");
        }

        [TestCase("at its radius", "4251839c", "431860e7", "42480000", "bfc00000", "3e800000",
                  "431860e7", "42480000", "bfc00000", "3e800000")]     // r * r rounds down: not touching, left as it is
        [TestCase("out of a corner", "41f90cf5", "42ce40d4", "42c8d497", "be9060d6", "bfc30126",
                  "4302dc42", "42d0324f", "3e4693fc", "bfbaea2c")]     // the move out, a product then a sum
        [TestCase("sliding off a corner", "41a24b86", "42ea6e67", "42dc9a14", "bf987901", "be6af6a9",
                  "42ead12b", "42dcd52d", "be5a0cd4", "3eb635ca")]     // the velocity into the wall taken off
        public void AWallPushesAsTheRoomRoundsIt(string what, string r, string x, string y, string vx, string vy,
                                                  string ex, string ey, string evx, string evy)
        {
            var walls = new WallGrid(new[] { new Wall(0f, 0f, 100f, 100f) }, Maze.Cell);
            var s = new MotionState { X = F(x), Y = F(y), Vx = F(vx), Vy = F(vy) };
            walls.PushOut(ref s, F(r));
            Same(s.X, F(ex), what, 0, "x");
            Same(s.Y, F(ey), what, 0, "y");
            Same(s.Vx, F(evx), what, 0, "vx");
            Same(s.Vy, F(evy), what, 0, "vy");
        }

        [Test]
        public void TheMoveBitsAsTheRoomReadsThem()
        {
            TankMotion.Direction(Wire.MoveUp | Wire.MoveLeft, out float ax, out float ay);
            Assert.That((ax, ay), Is.EqualTo((-0.70710678f, -0.70710678f)), "a diagonal no faster than straight");
            TankMotion.Direction(Wire.MoveLeft | Wire.MoveRight | Wire.MoveDown, out ax, out ay);
            Assert.That((ax, ay), Is.EqualTo((0f, 1f)), "opposites cancel: straight down");
        }
    }
}
