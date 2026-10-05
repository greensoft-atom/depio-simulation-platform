using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
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

        private static float F(string hex) => BitConverter.Int32BitsToSingle(int.Parse(hex, NumberStyles.HexNumber));

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
            if (BitConverter.SingleToInt32Bits(actual) != BitConverter.SingleToInt32Bits(expected))
                Assert.Fail($"{name}, step {step}: {what} {actual:R} where the room has {expected:R}");
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
