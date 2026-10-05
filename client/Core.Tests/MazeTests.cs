using System;
using System.Globalization;
using System.IO;
using System.Linq;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The maze's generator, held to the server's (docs 01 §8.9, D-48): the walls the Java
    /// generator wrote for one seed (backend/sim/src/test/resources/maze-2026.txt), the same file
    /// the server's tests read, never a copy.
    /// </summary>
    public class MazeTests
    {
        [Test]
        public void TheWallsAreTheServersForTheSameSeed()
        {
            string path = Path.Combine(TestContext.CurrentContext.TestDirectory, "maze-2026.txt");
            string[] expected = File.ReadAllLines(path).Where(l => l.Length > 0 && !l.StartsWith("#")).ToArray();
            string[] actual = Maze.Walls(2026).Select(w => string.Join(" ",
                new[] { w.MinX, w.MinY, w.MaxX, w.MaxY }.Select(f => ((int)f).ToString(CultureInfo.InvariantCulture)))).ToArray();
            Assert.That(expected, Has.Length.EqualTo(65));
            Assert.That(actual, Is.EqualTo(expected));
        }

        [Test]
        public void ASeedOfTheArenasSizeMakesAMazeToo()
        {
            // The arena's seeds are a CRC-32: up to 2^32 - 1, past an int.
            Assert.That(Maze.Walls(4_000_000_000L), Has.Length.EqualTo(65));
            Assert.That(Maze.Walls(4_000_000_000L), Is.Not.EqualTo(Maze.Walls(2026)));
        }

        [Test]
        public void ACircleHitsAWallWithinItsRadiusAndNotBeyond()
        {
            var w = new Wall(100, 100, 200, 140);
            Assert.That(w.Hits(150, 95, 5), Is.True, "exactly its radius from the face");
            Assert.That(w.Hits(150, 94.75f, 5), Is.False, "a quarter unit further");
            Assert.That(w.Hits(150, 120, 1), Is.True, "inside");
            Assert.That(w.Hits(203, 144, 5), Is.True, "past the corner, 5 away");
            Assert.That(w.Hits(204, 144, 5), Is.False, "past the corner, more than 5 away");
            Assert.That(w.Hits(205, 120, 5), Is.True, "the right face");
            Assert.That(w.Hits(150, 145, 5), Is.True, "the bottom face");
            Assert.That(w.Hits(95, 120, 5), Is.True, "the left face");
        }
    }
}
