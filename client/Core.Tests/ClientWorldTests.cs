using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>Writes snapshot frames the way the server's SnapshotWriter does, for tests only.</summary>
    internal sealed class FrameBuilder
    {
        private readonly List<byte> _b = new List<byte>();
        private readonly List<byte[]> _removes = new List<byte[]>();
        private readonly List<byte[]> _creates = new List<byte[]>();
        private readonly List<byte[]> _updates = new List<byte[]>();
        private readonly List<byte[]> _events = new List<byte[]>();
        private readonly long _tickDelta, _seqDelta, _odx, _ody;

        public FrameBuilder(long tickDelta, long seqDelta = 0, long originDx = 0, long originDy = 0)
        {
            _tickDelta = tickDelta;
            _seqDelta = seqDelta;
            _odx = originDx;
            _ody = originDy;
        }

        public FrameBuilder Remove(int h) { _removes.Add(new[] { (byte)h }); return this; }

        public FrameBuilder Tank(int h, int x, int y, string name, int hp = 255, int level = 1)
        {
            var c = new List<byte> { (byte)h, Wire.KindTank };
            I16(c, x); I16(c, y);
            c.AddRange(new byte[] { 0, (byte)hp, 0, 0, (byte)level });
            byte[] n = Encoding.UTF8.GetBytes(name);
            Varint(c, n.Length);
            c.AddRange(n);
            _creates.Add(c.ToArray());
            return this;
        }

        public FrameBuilder Bullet(int h, int x, int y, int heading, int speed, int life, int offset = 0, int radius = 8)
        {
            var c = new List<byte> { (byte)h, Wire.KindPredicted };
            I16(c, x); I16(c, y);
            c.Add((byte)heading); c.Add((byte)(heading >> 8));
            c.AddRange(new byte[] { (byte)speed, (byte)offset, (byte)life, 0, (byte)radius });   // owner none
            _creates.Add(c.ToArray());
            return this;
        }

        /// <summary>A trap (1) or a drone (2), owned by <paramref name="owner"/>.</summary>
        public FrameBuilder Unit(int h, int x, int y, int subtype, int owner, int hp = 255, int radius = 10)
        {
            var c = new List<byte> { (byte)h, Wire.KindUnit };
            I16(c, x); I16(c, y);
            c.AddRange(new byte[] { 10, (byte)hp, (byte)subtype, 1, (byte)owner, (byte)radius });
            _creates.Add(c.ToArray());
            return this;
        }

        public FrameBuilder Shape(int h, int x, int y, int subtype)
        {
            var c = new List<byte> { (byte)h, Wire.KindStatic };
            I16(c, x); I16(c, y);
            c.AddRange(new byte[] { (byte)subtype, 7 });
            _creates.Add(c.ToArray());
            return this;
        }

        public FrameBuilder Move(int h, int dx, int dy)
        {
            var u = new List<byte> { (byte)h, Wire.FPos };
            SVarint(u, dx); SVarint(u, dy);
            _updates.Add(u.ToArray());
            return this;
        }

        /// <summary>Every field, the ones the arena does not send yet included.</summary>
        public FrameBuilder Everything(int h, int angle, int hp, int level, int cls, int team, int flags)
        {
            _updates.Add(new byte[] { (byte)h, 126, (byte)angle, (byte)hp, (byte)level, (byte)cls, (byte)team, (byte)flags });
            return this;
        }

        /// <summary>The own tank's motion (D-62): the input's ticks, and its velocity in 1/256 of a unit a tick.</summary>
        public FrameBuilder Motion(int inputTicks, int vx, int vy)
        {
            var p = new List<byte> { (byte)inputTicks };
            SVarint(p, vx); SVarint(p, vy);
            return Event(Wire.EvtMotion, p.ToArray());
        }

        /// <summary>The own tank's motion rule (D-62): each float's bits, big-endian.</summary>
        public FrameBuilder MotionRule(float accel, float radius)
        {
            var p = new List<byte>();
            foreach (float f in new[] { accel, radius })
            {
                int bits = BitConverter.SingleToInt32Bits(f);
                p.AddRange(new[] { (byte)(bits >> 24), (byte)(bits >> 16), (byte)(bits >> 8), (byte)bits });
            }
            return Event(Wire.EvtMotionRule, p.ToArray());
        }

        public FrameBuilder Event(int type, byte[] payload)
        {
            var e = new List<byte> { (byte)type };
            Varint(e, payload.Length);
            e.AddRange(payload);
            _events.Add(e.ToArray());
            return this;
        }

        public byte[] Build()
        {
            _b.Clear();
            _b.Add(Wire.MsgSnapshot);
            Varint(_b, _tickDelta); Varint(_b, _seqDelta); SVarint(_b, _odx); SVarint(_b, _ody);
            foreach (var section in new[] { _removes, _creates, _updates, _events })
            {
                Varint(_b, section.Count);
                foreach (var item in section) _b.AddRange(item);
            }
            return _b.ToArray();
        }

        private static void I16(List<byte> b, int v) { b.Add((byte)v); b.Add((byte)(v >> 8)); }

        internal static void Varint(List<byte> b, long v)
        {
            ulong u = (ulong)v;
            while (u >= 0x80) { b.Add((byte)(u | 0x80)); u >>= 7; }
            b.Add((byte)u);
        }

        private static void SVarint(List<byte> b, long v) => Varint(b, (v << 1) ^ (v >> 63));
    }

    public class ClientWorldTests
    {
        [Test]
        public void ACreateIsPlacedInWorldSpaceAndUpdatesAccumulate()
        {
            var w = new ClientWorld();
            // The view origin is at (1000, -400): the create is relative to it on the wire.
            w.Apply(new FrameBuilder(1, originDx: 1000, originDy: -400).Tank(1, 20, 30, "Ada").Shape(5, -8, 8, 2).Build());
            Assert.That((w[1].X, w[1].Y), Is.EqualTo((1020, -370)));
            Assert.That(w[1].Name, Is.EqualTo("Ada"));

            // The camera moves; a world-space delta moves the tank, and the shape, updated by
            // nothing, stays where it was put rather than following the camera.
            w.Apply(new FrameBuilder(1, originDx: 50, originDy: 50).Move(1, 4, -2).Build());
            w.Apply(new FrameBuilder(2).Move(1, 1, 1).Build());
            Assert.That((w[1].X, w[1].Y), Is.EqualTo((1025, -371)));
            Assert.That((w[5].X, w[5].Y), Is.EqualTo((992, -392)));
            Assert.That(w.ServerTick, Is.EqualTo(4));
            Assert.That((w.OriginX, w.OriginY), Is.EqualTo((1050, -350)));
        }

        [Test]
        public void EveryUpdateFieldIsApplied()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(1).Tank(1, 0, 0, "Ada").Build());
            w.Apply(new FrameBuilder(1).Everything(1, angle: 9, hp: 80, level: 12, cls: 3, team: 2, flags: Wire.TankFlagProtected).Build());
            Entity e = w[1];
            Assert.That((e.Angle, e.Hp, e.Level, e.ClassId, e.Team, e.Flags), Is.EqualTo((9, 80, 12, 3, 2, 1)));
        }

        [Test]
        public void ABulletIsExtrapolatedFromItsCreateAndExpiresAtItsLifetime()
        {
            var w = new ClientWorld();
            // Heading 32768 is zero radians: straight along +x. Speed 20 is in half units: 10 units
            // a tick, 40 on the wire's scale.
            w.Apply(new FrameBuilder(10).Bullet(7, 100, 0, heading: 32768, speed: 20, life: 5).Build());
            Assert.That((w[7].X, w[7].Y), Is.EqualTo((100, 0)));
            w.Apply(new FrameBuilder(3).Build());
            Assert.That((w[7].X, w[7].Y), Is.EqualTo((220, 0)));
            w.ExpirePredicted();
            Assert.That(w[7].Alive, Is.True, "three ticks into five");
            w.Apply(new FrameBuilder(2).Build());
            w.ExpirePredicted();
            Assert.That(w[7].Alive, Is.False, "its lifetime is up and no remove came");
        }

        [Test]
        public void ABulletLandsWhereTheServersReferenceClientPutsIt()
        {
            // Found by search: at heading 400, 10 units a tick, seventeen ticks on, x is exactly -679.5.
            // Java rounds half up, to -679; .NET's default rounds half to even, to -680. The
            // expected values were computed by com.backend.protocol.ClientWorld's own arithmetic.
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(0).Bullet(7, 0, 0, heading: 400, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(17).Build());
            Assert.That((w[7].X, w[7].Y), Is.EqualTo((-679, -26)));
        }

        [Test]
        public void ABulletCreatedLateStartsWhereItWouldBeByNow()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(10).Bullet(7, 0, 0, heading: 32768, speed: 20, life: 20, offset: 2).Build());
            // It was fired two ticks before this frame: 80 on the wire's scale along.
            Assert.That(w[7].X, Is.EqualTo(80));
            Assert.That(w[7].DeathTick, Is.EqualTo(28));
        }

        // Seed 2026's first wall (maze-2026.txt): 2080..2420 across, 280..320 down, in world units.
        private const int MazeSeed = 2026;

        [Test]
        public void ABulletEndsTheFirstTickItsCentreIsWithinItsRadiusOfAWall()
        {
            var w = new ClientWorld(MazeSeed);
            Assert.That(w.Walls[0], Is.EqualTo(new Wall(2080, 280, 2420, 320)));
            // Straight down (heading 49152 is a quarter turn), 10 units a tick, radius 8, from
            // y = 200: within 8 of the wall's top at y = 280, eight ticks on, not at y = 270.
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 200 * 4, heading: 49152, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(7).Build());
            Assert.That((w[7].Alive, w[7].Y), Is.EqualTo((true, 270 * 4)), "two units short of touching");
            w[7].PredictedAt(19.5, out _, out float drawnY);
            Assert.That(drawnY, Is.EqualTo(280 * 4), "drawn no further than where it ends");

            w.Apply(new FrameBuilder(1).Build());
            Assert.That(w[7].Alive, Is.False);
            Assert.That(w.Removals, Has.Count.EqualTo(1), "a hit to draw, before the server's remove");
            Assert.That((w.Removals[0].Handle, w.Removals[0].Kind, w.Removals[0].X, w.Removals[0].Y),
                Is.EqualTo((7, Wire.KindPredicted, 2250 * 4, 280 * 4)));

            // The server's remove comes after: nothing more to draw.
            w.Apply(new FrameBuilder(1).Remove(7).Build());
            Assert.That(w.Removals, Is.Empty);
        }

        [Test]
        public void ItsRadiusCounts()
        {
            var w = new ClientWorld(MazeSeed);
            // From y = 205: at y = 275 it is 5 from the wall, within its radius of 8.
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 205 * 4, heading: 49152, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(10).Build());
            Assert.That(w.Removals.Single().Y, Is.EqualTo(275 * 4));
        }

        [Test]
        public void AWallIsCheckedAfterAMoveNotWhereABulletIsFired()
        {
            var w = new ClientWorld(MazeSeed);
            // Fired from 5 under the wall, away from it: as on the server, it has moved off by its
            // first check.
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 325 * 4, heading: 49152, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(3).Build());
            Assert.That((w[7].Alive, w[7].Y), Is.EqualTo((true, 355 * 4)));
        }

        [Test]
        public void AWallMetOnItsLastTickIsAWallsHit()
        {
            var w = new ClientWorld(MazeSeed);
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 200 * 4, heading: 49152, speed: 20, life: 8).Build());
            w.Apply(new FrameBuilder(8).Build());
            Assert.That(w.Removals.Single().Y, Is.EqualTo(280 * 4), "a hit to draw, not a bullet that fades");
        }

        [Test]
        public void ABulletDoesNotPassThroughAWallBetweenFrames()
        {
            var w = new ClientWorld(MazeSeed);
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 200 * 4, heading: 49152, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(30).Build());           // to y = 500, well past the wall
            Assert.That(w[7].Alive, Is.False);
            Assert.That(w.Removals.Single().Y, Is.EqualTo(280 * 4), "where it met the wall");
        }

        [Test]
        public void ARemoveInTheFrameAfterAWallSaysWhereTheWallStoppedIt()
        {
            var w = new ClientWorld(MazeSeed);
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 200 * 4, heading: 49152, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(12).Remove(7).Build());
            Assert.That(w.Removals.Single().Y, Is.EqualTo(280 * 4));
        }

        [Test]
        public void ABulletCreatedPastItsWallTickEndsInItsFirstFrame()
        {
            var w = new ClientWorld(MazeSeed);
            // Fired nine ticks before this frame, from y = 200: it met the wall a tick ago.
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 200 * 4, heading: 49152, speed: 20, life: 50, offset: 9).Build());
            Assert.That(w[7].Alive, Is.False);
            Assert.That(w.Removals.Single().Y, Is.EqualTo(280 * 4));
        }

        [Test]
        public void WithoutAMazeTheSameBulletFliesOn()
        {
            var w = new ClientWorld();
            Assert.That(w.Walls, Is.Empty);
            w.Apply(new FrameBuilder(10).Bullet(7, 2250 * 4, 200 * 4, heading: 49152, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(30).Build());
            Assert.That((w[7].Alive, w[7].Y), Is.EqualTo((true, 500 * 4)));
            Assert.That(w.Removals, Is.Empty);
        }

        [Test]
        public void ARemoveSaysWhereItWasAndFreesTheHandleForACreateInTheSameFrame()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(1).Bullet(7, 0, 0, heading: 32768, speed: 20, life: 50).Build());
            w.Apply(new FrameBuilder(2).Remove(7).Tank(7, 5, 5, "Bob").Build());
            Assert.That(w.Removals, Has.Count.EqualTo(1));
            Assert.That((w.Removals[0].Handle, w.Removals[0].Kind, w.Removals[0].X), Is.EqualTo((7, Wire.KindPredicted, 80)));
            Assert.That((w[7].Kind, w[7].Name), Is.EqualTo((Wire.KindTank, "Bob")));
        }

        [Test]
        public void AUnitIsHeldAndMovedAsATankIsAndBothKnowTheirSize()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(1).Tank(1, 0, 0, "me").Unit(9, 40, 40, Wire.UnitDrone, owner: 1, radius: 20)
                .Bullet(7, 0, 0, heading: 32768, speed: 14, life: 75, radius: 16).Build());
            Assert.That((w[9].Kind, w[9].ClassId, w[9].OwnerHandle, w[9].Hp), Is.EqualTo((Wire.KindUnit, Wire.UnitDrone, 1, 255)));
            Assert.That(w[7].Radius, Is.EqualTo(16), "a Destroyer's bullet, drawn at its size");
            Assert.That(w[9].Radius, Is.EqualTo(20), "a unit carries its own size: its owner may be gone (protocol 4)");
            w.Apply(new FrameBuilder(1).Move(9, 12, -4).Build());
            Assert.That((w[9].X, w[9].Y), Is.EqualTo((52, 36)), "a drone is not extrapolated: it goes where it is told");
            w.Apply(new FrameBuilder(3).Build());
            Assert.That((w[9].X, w[9].Y), Is.EqualTo((52, 36)), "and stays there until told again");
        }

        [Test]
        public void AnUpdateForAHandleNotHeldIsADesync()
        {
            var w = new ClientWorld();
            Assert.Throws<ProtocolException>(() => w.Apply(new FrameBuilder(1).Move(9, 1, 1).Build()));
        }

        [Test]
        public void EventsAreReadAndAnUnknownOneIsSteppedOver()
        {
            var w = new ClientWorld();
            var death = new List<byte>();
            FrameBuilder.Varint(death, 1240);
            byte[] name = Encoding.UTF8.GetBytes("Zoë");
            death.Add((byte)name.Length);
            death.AddRange(name);
            byte[] stats = { 17, 0xD8, 0x09, 0xAC, 0x0C, 3, 2, 0, 1, 0, 4, 7, 3, 0, 99 };   // a byte of a later field on the end
            w.Apply(new FrameBuilder(1)
                .Event(Wire.EvtDeath, death.ToArray())
                .Event(99, new byte[] { 1, 2, 3 })
                .Event(Wire.EvtStats, stats).Build());
            Assert.That(w.EventCount, Is.EqualTo(3));
            Assert.That((w.EventAt(0).Type, w.EventAt(0).Score, w.EventAt(0).Killer), Is.EqualTo((1, 1240L, "Zoë")));
            Assert.That(w.EventAt(1).Type, Is.EqualTo(99));
            MatchEvent s = w.EventAt(2);
            Assert.That((s.Level, s.Xp, s.XpForNextLevel, s.UnspentPoints), Is.EqualTo((17L, 1240L, 1580L, 3)));
            Assert.That(s.PointsPerStat, Is.EqualTo(new[] { 2, 0, 1, 0, 4, 7, 3, 0 }));

            w.Apply(new FrameBuilder(1).Build());
            Assert.That(w.EventCount, Is.Zero, "events are the last frame's only");
        }

        [Test]
        public void AKillIsReadWithBothNamesAndTheNextEventForgetsIt()
        {
            var w = new ClientWorld();
            var kill = new List<byte>();
            byte[] killer = Encoding.UTF8.GetBytes("Zoë"), victim = Encoding.UTF8.GetBytes("bo");
            kill.Add((byte)killer.Length);
            kill.AddRange(killer);
            kill.Add((byte)victim.Length);
            kill.AddRange(victim);
            var nobodys = new List<byte> { 3 };              // the arena's own tank, killed by Ada
            nobodys.AddRange(Encoding.UTF8.GetBytes("Ada"));
            nobodys.Add(0);
            // As the arena sends it: type 4, in numbers (02 §4).
            w.Apply(new FrameBuilder(1).Event(4, kill.ToArray()).Event(4, nobodys.ToArray()).Build());
            Assert.That(w.EventCount, Is.EqualTo(2));
            MatchEvent a = w.EventAt(0), b = w.EventAt(1);
            Assert.That((a.Type, a.Killer, a.Victim), Is.EqualTo((Wire.EvtKill, "Zoë", "bo")));
            Assert.That((b.Killer, b.Victim), Is.EqualTo(("Ada", "")));

            w.Apply(new FrameBuilder(1).Event(Wire.EvtPhrase, new byte[] { 0, 1, 0 }).Build());
            Assert.That((w.EventAt(0).Killer, w.EventAt(0).Victim), Is.EqualTo(("", "")), "a reused event keeps nothing of the last");
        }

        [Test]
        public void APhraseIsReadWithItsSpeakerAndTheNextEventForgetsIt()
        {
            var w = new ClientWorld();
            var seen = new List<byte> { 3 };             // the speaker's handle in this view
            FrameBuilder.Varint(seen, 12);
            byte[] name = Encoding.UTF8.GetBytes("Zoë");
            seen.Add((byte)name.Length);
            seen.AddRange(name);
            var unseen = new List<byte> { 0 };           // a teammate out of sight
            FrameBuilder.Varint(unseen, 200);
            unseen.Add(2);
            unseen.AddRange(Encoding.UTF8.GetBytes("bo"));
            w.Apply(new FrameBuilder(1)
                .Event(Wire.EvtPhrase, seen.ToArray())
                .Event(Wire.EvtPhrase, unseen.ToArray()).Build());
            Assert.That(w.EventCount, Is.EqualTo(2));
            MatchEvent a = w.EventAt(0), b = w.EventAt(1);
            Assert.That((a.Type, a.Speaker, a.PhraseId, a.SpeakerName), Is.EqualTo((Wire.EvtPhrase, 3, 12, "Zoë")));
            Assert.That((b.Speaker, b.PhraseId, b.SpeakerName), Is.EqualTo((0, 200, "bo")));

            var death = new List<byte>();
            FrameBuilder.Varint(death, 5);
            death.Add(0);
            w.Apply(new FrameBuilder(1).Event(Wire.EvtDeath, death.ToArray()).Build());
            MatchEvent d = w.EventAt(0);
            Assert.That((d.Speaker, d.PhraseId, d.SpeakerName), Is.EqualTo((0, 0, "")), "a reused event keeps nothing of the last");
        }

        [Test]
        public void TheInputSeqEchoWrapsAt24Bits()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(1, seqDelta: 0xFFFFFE).Build());
            w.Apply(new FrameBuilder(1, seqDelta: 3).Build());
            Assert.That(w.LastProcessedInputSeq, Is.EqualTo(1));
            Assert.That(Wire.SeqNewer(1, 0xFFFFFE), Is.True);
            Assert.That(Wire.SeqNewer(0xFFFFFE, 1), Is.False);
        }

        [Test]
        public void TheOwnTanksMotionIsStateForThePredictionNotAnEvent()
        {
            var w = new ClientWorld();
            Assert.That(w.Motion.HasRule, Is.False, "nothing can be predicted before a rule");
            w.Apply(new FrameBuilder(1).Tank(Wire.SelfHandle, 100, 200, "Ada").MotionRule(0.16f, 30f).Motion(3, 384, -64).Build());
            Assert.That(w.EventCount, Is.Zero, "the layer above is never handed them");
            Assert.That(w.Motion.HasRule, Is.True);
            Assert.That(BitConverter.SingleToInt32Bits(w.Motion.Accel), Is.EqualTo(BitConverter.SingleToInt32Bits(0.16f)));
            Assert.That(w.Motion.Radius, Is.EqualTo(30f));
            Assert.That(w.Motion.InFrame, Is.True);
            Assert.That(w.Motion.InputTicks, Is.EqualTo(3));
            Assert.That(w.Motion.Vx, Is.EqualTo(1.5f), "384 in 1/256 of a unit a tick");
            Assert.That(w.Motion.Vy, Is.EqualTo(-0.25f));
            Assert.That(w.SelfCreated, Is.True, "a frame that created the own tank afresh");

            w.Apply(new FrameBuilder(2).Tank(7, 0, 0, "Bob").Event(Wire.EvtStats, new byte[] { 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }).Build());
            Assert.That(w.Motion.InFrame, Is.False, "this frame had none");
            Assert.That(w.Motion.HasRule, Is.True, "a rule holds until the next");
            Assert.That(w.SelfCreated, Is.False, "another tank's create is not the own tank's");
            Assert.That(w.EventCount, Is.EqualTo(1), "other events are events still");
        }

        [Test]
        public void ATanksSkinIsStateOnTheTankNotAnEvent()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(1).Tank(Wire.SelfHandle, 0, 0, "Ada").Tank(7, 50, 0, "Bob").Tank(8, -50, 0, "Cy")
                .Event(Wire.EvtSkin, new byte[] { 7, 3 }).Event(Wire.EvtSkin, new byte[] { (byte)Wire.SelfHandle, 255 }).Build());
            Assert.That((w[7].Skin, w[Wire.SelfHandle].Skin, w[8].Skin), Is.EqualTo((3, 255, 0)),
                "each told its own; one never told has none");
            Assert.That(w.EventCount, Is.Zero, "the layer above reads it from the tank, as its name");

            w.Apply(new FrameBuilder(2).Remove(7).Build());
            w.Apply(new FrameBuilder(3).Tank(7, 60, 0, "Di").Build());
            Assert.That(w[7].Skin, Is.Zero, "a handle's next tank starts with none");
            w.Apply(new FrameBuilder(4).Event(Wire.EvtSkin, new byte[] { 9, 2 }).Build());
            Assert.That(w[9].Skin, Is.Zero, "a skin for a handle holding nothing is dropped");
        }

        [Test]
        public void RoundingIsJavas()
        {
            Assert.That(Wire.JavaRound(2.5f), Is.EqualTo(3), "half rounds up, not to even");
            Assert.That(Wire.JavaRound(-2.5f), Is.EqualTo(-2));
            Assert.That(Wire.JavaRound(0.49999997f), Is.EqualTo(0));
            Assert.That(Wire.JavaRound(0.49999999999999994), Is.EqualTo(0));
        }
    }
}
