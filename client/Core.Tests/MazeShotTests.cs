using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using Backend.Client.Core;
using Backend.Client.Headless;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The headless driver's maze shot (T-66), whose file these tests compile in: what the drill's <c>maze</c> scenario
    /// checks is only as good as the shot it takes.
    /// </summary>
    public class MazeShotTests
    {
        /// <summary>A seed a drill failed with (T-66).</summary>
        private const long Seed = 1631528888;

        private static void PollUntil(MatchConnection m, Func<bool> done, string what)
        {
            for (int i = 0; i < 500; i++)
            {
                m.Poll();
                if (done()) return;
                Thread.Sleep(10);
            }
            Assert.Fail($"never: {what} (state {m.State}, {m.LastProblem})");
        }

        /// <summary>A connection in the maze, its own tank at (x, y) in world units.</summary>
        private static MatchConnection Tank(FakeArena arena, ManualClock clock, float x, float y)
        {
            var m = new MatchConnection(new MatchSettings { Host = "127.0.0.1", Port = arena.Port }, clock);
            m.Join("TicketNumberOne");
            PollUntil(m, () => m.State == MatchState.Joining, "joining");
            var join = arena.Next();
            while (join.frame[0] != Wire.MsgJoin) join = arena.Next();     // another tank's inputs come too
            arena.Send(join.connection, FakeArena.Welcome("secret", mazeSeed: Seed));
            arena.Send(join.connection, new FrameBuilder(5).Tank(Wire.SelfHandle, (int)(x * Wire.PosScale), (int)(y * Wire.PosScale), "Me").Build());
            PollUntil(m, () => m.Alive, "alive");
            return m;
        }

        /// <summary>Cell centres of the seed's maze from which a wall can be fired at, each with its aim.</summary>
        private static List<(float x, float y, int aim)> Places()
        {
            Wall[] walls = Maze.Walls(Seed);
            var places = new List<(float, float, int)>();
            for (int i = 0; i < 10; i++)
                for (int j = 0; j < 10; j++)
                {
                    float x = 150 + 300 * i, y = 150 + 300 * j;
                    if (MazeShot.Target(walls, x, y, out float tx, out float ty) == float.MaxValue) continue;
                    places.Add((x, y, Wire.QuantiseAim((float)Math.Atan2(ty - y, tx - x))));
                }
            return places;
        }

        private static (int aim, int flags) LastInput(List<byte[]> inputs)
        {
            var r = new WireReader(inputs.Last());
            r.U8();
            r.Varint();
            r.Varint();
            r.U8();
            return (r.U16(), r.U8());
        }

        [Test]
        public void TheShooterHoldsItsTriggerEveryInputItSendsCarriesFire()
        {
            using (var arena = new FakeArena())
            {
                var clock = new ManualClock();
                var place = Places()[0];
                using (var m = Tank(arena, clock, place.x, place.y))
                {
                    var shot = new MazeShot(new List<MatchConnection> { m });
                    arena.DrainAll();
                    for (int i = 0; i < 10; i++)
                    {
                        clock.NowMs += 100;
                        m.Poll();
                        shot.Poll();
                    }
                    var inputs = arena.Drain(Wire.MsgInput);
                    Assert.That(inputs, Has.Count.GreaterThanOrEqualTo(9), "ten a second");
                    // A press is sent once (MatchConnectionTests' latch): a trigger held is one pressed at every poll.
                    Assert.That(inputs.Count(f => (f[f.Length - 1] & Wire.FlagFire) != 0), Is.EqualTo(inputs.Count),
                        "every input carries fire, not the first alone");
                    Assert.That(LastInput(inputs).aim, Is.EqualTo(place.aim), "at its wall");
                    shot.Stop();
                    // The last poll's press goes out once, as a tap does; after it, none.
                    for (int i = 0; i < 2; i++)
                    {
                        clock.NowMs += 100;
                        m.Poll();
                    }
                    Assert.That(LastInput(arena.Drain(Wire.MsgInput)).flags & Wire.FlagFire, Is.EqualTo(0), "and let go");
                }
            }
        }

        /// <summary>
        /// A made match's start resets the room and places every tank again (startMadeMatch): the frame that moves the
        /// shooter's tank can come after the shot has aimed, from where the tank was.
        /// </summary>
        [Test]
        public void TheShooterAimsAgainFromWhereItsTankIsMadeAgain()
        {
            using (var arena = new FakeArena())
            {
                var clock = new ManualClock();
                var places = Places();
                var from = places[0];
                var to = places.First(p => p.aim != from.aim);
                using (var m = Tank(arena, clock, from.x, from.y))
                {
                    var shot = new MazeShot(new List<MatchConnection> { m });
                    Assert.That(shot.Aim, Is.EqualTo(from.aim), "from where it was");
                    arena.Send(0, new FrameBuilder(1).Remove(Wire.SelfHandle)
                        .Tank(Wire.SelfHandle, (int)(to.x * Wire.PosScale), (int)(to.y * Wire.PosScale), "Me").Build());
                    PollUntil(m, () => m.World[Wire.SelfHandle].X == (int)(to.x * Wire.PosScale), "the tank made again");
                    arena.DrainAll();
                    clock.NowMs += 100;
                    m.Poll();
                    shot.Poll();
                    clock.NowMs += 100;
                    m.Poll();
                    Assert.That(shot.Aim, Is.EqualTo(to.aim), "from where it is now");
                    Assert.That(LastInput(arena.Drain(Wire.MsgInput)).aim, Is.EqualTo(to.aim), "and sent");
                }
            }
        }

        [Test]
        public void AWallTooNearBeyondABulletsReachOrBehindAnotherIsNoTarget()
        {
            var near = new[] { new Wall(60, -20, 100, 20) };
            Assert.That(MazeShot.Target(near, 0, 0, out _, out _), Is.EqualTo(float.MaxValue), "60 off: ended in a tick (T-26)");
            var behind = new[] { new Wall(60, -20, 100, 20), new Wall(300, -20, 340, 20) };
            Assert.That(MazeShot.Target(behind, 0, 0, out _, out _), Is.EqualTo(float.MaxValue), "300 off, behind another (T-57)");
            // A bullet at level 1 flies 75 ticks at 10 units, about 790 from the tank's centre.
            var far = new[] { new Wall(800, -20, 840, 20) };
            Assert.That(MazeShot.Target(far, 0, 0, out _, out _), Is.EqualTo(float.MaxValue), "800 off: out of reach");
            var inReach = new[] { new Wall(600, -20, 640, 20) };
            Assert.That(MazeShot.Target(inReach, 0, 0, out float tx, out _), Is.EqualTo(600f), "600 off");
            Assert.That(tx, Is.EqualTo(600f));
            Assert.That(MazeShot.Farthest, Is.LessThan(39 + 75 * 10), "inside the reach");
        }

        [Test]
        public void TheFirstOfTheTanksWithAWallInReachFires()
        {
            using (var arena = new FakeArena())
            {
                var clock = new ManualClock();
                var place = Places()[0];
                // Beyond the maze's edge, every wall out of reach: no shot from there.
                using (var none = Tank(arena, clock, 4000, 1500))
                using (var some = Tank(arena, clock, place.x, place.y))
                {
                    var shot = new MazeShot(new List<MatchConnection> { none, some });
                    Assert.That(shot.Shooter, Is.SameAs(some));
                    Assert.That(shot.Aim, Is.EqualTo(place.aim));
                }
            }
        }

        /// <summary>Every frame's removals, read as each is applied (T-57), and the shooter's alone.</summary>
        [Test]
        public void ABulletEndingAtAWallIsSeenInTheShootersWorldAlone()
        {
            using (var arena = new FakeArena())
            {
                var clock = new ManualClock();
                var place = Places()[0];
                using (var none = Tank(arena, clock, 4000, 1500))
                using (var some = Tank(arena, clock, place.x, place.y))
                {
                    var shot = new MazeShot(new List<MatchConnection> { none, some });
                    Wall wall = Maze.Walls(Seed)[0];
                    int wx = (int)(wall.MinX * Wire.PosScale), wy = (int)(wall.MinY * Wire.PosScale);
                    // Born at the wall, removed in the next frame, and a frame after that: the poll applies both.
                    foreach (int c in new[] { 0, 1 })
                    {
                        arena.Send(c, new FrameBuilder(1).Bullet(40, wx, wy, 0, 0, 200).Build());
                        arena.Send(c, new FrameBuilder(1).Remove(40).Build());
                        arena.Send(c, new FrameBuilder(1).Build());
                    }
                    PollUntil(none, () => none.SnapshotsApplied == 4, "the other's frames");
                    Assert.That(shot.EndedAtAWall, Is.False, "another tank's world");
                    Assert.That(shot.Poll(), Is.False);
                    PollUntil(some, () => some.SnapshotsApplied == 4, "the shooter's frames");
                    Assert.That(shot.Poll(), Is.True, "the shooter's, in a frame before the poll's last");
                    Assert.That(shot.Removed, Is.EqualTo(1));
                    Assert.That(shot.Closest, Is.EqualTo(0f));
                }
            }
        }
    }
}
