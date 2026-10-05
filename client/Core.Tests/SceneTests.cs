using System;
using System.Linq;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>What is drawn where, at a render tick (08 §4, §8; plan item 77 (a)).</summary>
    public class SceneTests
    {
        private static DrawItem Item(Scene scene, int handle) => scene.Items.Single(i => i.Handle == handle);

        [Test]
        public void ATankIsDrawnBetweenItsLastTwoSamplesAndNeverBeyondThem()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(10).Tank(7, 400, 0, "Bob").Build());         // 100 units, at tick 10
            w.Apply(new FrameBuilder(2).Move(7, 32, 0).Build());                   // 108 units, at tick 12
            Assert.That((w[7].PrevX, w[7].PrevTick, w[7].X, w[7].SampleTick), Is.EqualTo((400, 10L, 432, 12L)));

            var scene = new Scene();
            var born = new ClientWorld();
            born.Apply(new FrameBuilder(10).Tank(7, 400, 0, "Bob").Build());
            scene.Build(born, 5.0);
            Assert.That(Item(scene, 7).X, Is.EqualTo(100f), "born this frame: where it is, at any render tick");
            scene.Build(w, 11.0);
            Assert.That(Item(scene, 7).X, Is.EqualTo(104f), "half way, at the tick half way");
            scene.Build(w, 12.5);
            Assert.That(Item(scene, 7).X, Is.EqualTo(108f), "not past the newest sample");
            scene.Build(w, 9.0);
            Assert.That(Item(scene, 7).X, Is.EqualTo(100f), "nor before the one before");

            w.Apply(new FrameBuilder(2).Build());                                   // no update: it stood still
            scene.Build(w, 13.0);
            Assert.That(Item(scene, 7).X, Is.EqualTo(108f), "a frame without its update is a sample where it was");
        }

        [Test]
        public void ATankTurnsTheShorterWayRound()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(10).Tank(7, 0, 0, "Bob").Build());
            w.Apply(new FrameBuilder(2).Everything(7, 250, 255, 1, 0, 0, 0).Build());     // facing 250 of 256
            w.Apply(new FrameBuilder(2).Everything(7, 6, 255, 1, 0, 0, 0).Build());       // 6: twelve steps on, through 0
            var scene = new Scene();
            scene.Build(w, 13.0);
            float angle = Item(scene, 7).Angle;                                             // half way: 256, which is 0, so π
            Assert.That((Math.Cos(angle), Math.Sin(angle)), Is.EqualTo((-1.0, 0.0)).Within(1e-4), "not back round through 128");
        }

        [Test]
        public void EachKindIsDrawnByItsOwnRule()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(10).Tank(Wire.SelfHandle, 0, 0, "Ada").Bullet(9, 0, 0, 16384 * 2, 20, 50)
                .Shape(11, 40, 40, 2).Unit(12, 80, 0, 2, Wire.SelfHandle).Event(Wire.EvtSkin, new byte[] { (byte)Wire.SelfHandle, 4 }).Build());
            w.Apply(new FrameBuilder(2).Move(12, 8, 0).Move(Wire.SelfHandle, 16, 0).Build());
            var scene = new Scene();
            scene.Build(w, 11.0);
            Assert.That(scene.Items.Select(i => i.Kind).OrderBy(k => k),
                Is.EqualTo(new[] { Wire.KindTank, Wire.KindPredicted, Wire.KindStatic, Wire.KindUnit }.OrderBy(k => k)));
            DrawItem bullet = Item(scene, 9);
            w[9].PredictedAt(11.0, out float bx, out float by);
            Assert.That((bullet.X, bullet.Y), Is.EqualTo((bx / Wire.PosScale, by / Wire.PosScale)), "a bullet extrapolated to the render tick");
            Assert.That((Item(scene, 11).X, Item(scene, 11).Y, Item(scene, 11).Subtype), Is.EqualTo((10f, 10f, 2)), "a shape where it is");
            Assert.That(Item(scene, 12).X, Is.EqualTo(21f), "a unit between its samples, as a tank");
            scene.Build(w, 5.0);
            Assert.That((Item(scene, 9).X, Item(scene, 9).Y), Is.EqualTo((0f, 0f)), "a bullet before its spawn: where it spawns");
            DrawItem self = Item(scene, Wire.SelfHandle);
            Assert.That((self.Self, self.X, self.Skin, self.Name, self.Health), Is.EqualTo((true, 4f, 4, "Ada", 1f)),
                "the own tank, with nothing predicting it, at its newest sample: it is drawn in the present");
        }

        [Test]
        public void TheOwnTankIsDrawnWhereItIsPredicted()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(10).Tank(Wire.SelfHandle, 0, 0, "Ada").Build());
            var scene = new Scene();
            scene.Build(w, 10.0, true, 55.5f, -3f, 1.25f);
            DrawItem self = Item(scene, Wire.SelfHandle);
            Assert.That((self.X, self.Y, self.Angle), Is.EqualTo((55.5f, -3f, 1.25f)));
        }

        [Test]
        public void TheListIsReusedFrameToFrame()
        {
            var w = new ClientWorld();
            w.Apply(new FrameBuilder(10).Tank(7, 0, 0, "Bob").Tank(8, 40, 0, "Cy").Build());
            var scene = new Scene();
            scene.Build(w, 10.0);
            DrawItem first = scene.Items[0];
            scene.Build(w, 10.0);
            Assert.That(scene.Items[0], Is.SameAs(first), "nothing allocated a frame: the items are kept");
            w.Apply(new FrameBuilder(2).Remove(8).Build());
            scene.Build(w, 12.0);
            Assert.That(scene.Items.Count, Is.EqualTo(1), "a removed tank is not drawn");
        }

        [Test]
        public void TheRenderTickIsTwoFramesBehindTheNewestAndNeverAheadOfItNorBack()
        {
            var clock = new RenderClock();
            Assert.That(clock.RenderTick(0), Is.NaN, "nothing yet");
            clock.Frame(100, 1_000);
            clock.Frame(102, 1_080);
            Assert.That(clock.RenderTick(1_080), Is.EqualTo(98.0).Within(1e-9), "two frames of two ticks behind");
            Assert.That(clock.RenderTick(1_120), Is.EqualTo(99.0).Within(1e-9), "40 ms on: a tick on, at 25 a second");
            Assert.That(clock.RenderTick(9_000), Is.EqualTo(102.0), "never past the newest frame");
            clock.Frame(103, 9_010);
            Assert.That(clock.RenderTick(9_010), Is.GreaterThanOrEqualTo(102.0), "never back in time when a frame comes");
        }
    }
}
