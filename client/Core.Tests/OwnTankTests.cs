using System;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The own tank's prediction (docs 02 §9, 08 §4, D-62) on a frozen clock: frames written as the
    /// server writes them, and every expected position worked out with the same rule, so a test
    /// compares bits, not a tolerance.
    /// </summary>
    public class OwnTankTests
    {
        private const float Accel = 0.16f, Radius = 30f, Map = 6_000f;
        private const long T0 = 1_000_000;
        private const int Step = OwnTank.StepMs;

        /// <summary>A world whose first frame put the own tank at (1000, 1000), still, its rule told; and its prediction.</summary>
        private static ClientWorld Joined(out OwnTank tank)
        {
            var w = new ClientWorld();
            tank = new OwnTank();
            w.Apply(new FrameBuilder(1).Tank(Wire.SelfHandle, 4_000, 4_000, "Ada").MotionRule(Accel, Radius).Motion(0, 0, 0).Build());
            tank.OnFrame(w, Map, Map, T0);
            return w;
        }

        private static readonly MotionState Start = new MotionState { X = 1_000f, Y = 1_000f };

        private static MotionState Stepped(MotionState s, params int[] moves)
        {
            foreach (int m in moves) TankMotion.Step(ref s, m, Accel, Radius, Map, Map, null);
            return s;
        }

        private static int[] Times(int n, int move)
        {
            var moves = new int[n];
            for (int i = 0; i < n; i++) moves[i] = move;
            return moves;
        }

        private static int[] Then(int[] a, int[] b)
        {
            var all = new int[a.Length + b.Length];
            a.CopyTo(all, 0);
            b.CopyTo(all, a.Length);
            return all;
        }

        private static void AssertAt(OwnTank tank, MotionState expected, string what)
        {
            Assert.That(BitConverter.SingleToInt32Bits(tank.X), Is.EqualTo(BitConverter.SingleToInt32Bits(expected.X)), what + ": x " + tank.X + " against " + expected.X);
            Assert.That(BitConverter.SingleToInt32Bits(tank.Y), Is.EqualTo(BitConverter.SingleToInt32Bits(expected.Y)), what + ": y " + tank.Y + " against " + expected.Y);
        }

        /// <summary>
        /// A frame from the server: the own tank moved to where it has it (to the quarter unit, as the
        /// wire carries it), the echo advanced, and its motion.
        /// </summary>
        private static MotionState Frame(ClientWorld w, OwnTank tank, long now, int seqDelta, int inputTicks, float x, float y, float vx, float vy)
        {
            int qx = (int)Math.Round(x * Wire.PosScale), qy = (int)Math.Round(y * Wire.PosScale);
            int qvx = (int)Math.Round(vx * Wire.VelocityScale), qvy = (int)Math.Round(vy * Wire.VelocityScale);
            var self = w[Wire.SelfHandle];
            w.Apply(new FrameBuilder(2, seqDelta).Move(Wire.SelfHandle, qx - self.X, qy - self.Y).Motion(inputTicks, qvx, qvy).Build());
            tank.OnFrame(w, Map, Map, now);
            return new MotionState { X = qx / Wire.PosScale, Y = qy / Wire.PosScale, Vx = qvx / Wire.VelocityScale, Vy = qvy / Wire.VelocityScale };
        }

        [Test]
        public void ItStepsAtTheRoomsRateByTheClockWithTheInputHeld()
        {
            Joined(out OwnTank tank);
            Assert.That(tank.Predicting, Is.True);
            AssertAt(tank, Start, "where the server put it");
            tank.Advance(T0 + Step - 1, Wire.MoveRight, 0, 1);
            AssertAt(tank, Start, "no step before a tick's time");
            tank.Advance(T0 + 5 * Step, Wire.MoveRight, 0, 1);
            AssertAt(tank, Stepped(Start, Times(5, Wire.MoveRight)), "five ticks to the right, at once");
            tank.Advance(T0 + 7 * Step + 20, Wire.MoveDown | Wire.MoveRight, 0, 1);
            AssertAt(tank, Stepped(Start, Then(Times(5, Wire.MoveRight), Times(2, Wire.MoveDown | Wire.MoveRight))), "and two diagonally");
        }

        [Test]
        public void AFrameIsMatchedToItsStepAndTheStepsAfterItAreReplayed()
        {
            ClientWorld w = Joined(out OwnTank tank);
            float error = float.NaN, correction = float.NaN;
            tank.OnReconciled = (e, c) => { error = e; correction = c; };
            tank.Advance(T0 + 4 * Step, Wire.MoveRight, 0, 1);           // steps 1-4: seq 1's
            tank.Sent(1, Wire.MoveRight);
            tank.Advance(T0 + 8 * Step, Wire.MoveDown, 0, 2);            // steps 5-8: seq 2's

            // The server has applied seq 1 twice by the frame's tick: step 2. Something knocked it 10 units
            // to the right and added half a unit a tick: neither is the client's to know.
            MotionState two = Stepped(Start, Wire.MoveRight, Wire.MoveRight);
            MotionState server = Frame(w, tank, T0 + 8 * Step + 5, 1, 2, two.X + 10f, two.Y, two.Vx + 0.5f, two.Vy);

            MotionState expected = Stepped(server, Then(Times(2, Wire.MoveRight), Times(4, Wire.MoveDown)));
            AssertAt(tank, expected, "the server's step 2, then steps 3 to 8 again");
            Assert.That(error, Is.EqualTo(10f).Within(0.2f), "how wrong the prediction of step 2 was");
            Assert.That(tank.Compared, Is.EqualTo(1));
            MotionState before = Stepped(Start, Then(Times(4, Wire.MoveRight), Times(4, Wire.MoveDown)));
            float moved = (float)Math.Sqrt((before.X - expected.X) * (before.X - expected.X) + (before.Y - expected.Y) * (before.Y - expected.Y));
            Assert.That(correction, Is.EqualTo(moved).Within(1e-3f), "what the present moved by");
        }

        [Test]
        public void ACorrectionIsDrawnAwayHalvingEvery50Ms()
        {
            ClientWorld w = Joined(out OwnTank tank);
            tank.Advance(T0 + 4 * Step, Wire.MoveRight, 0, 1);
            tank.Sent(1, Wire.MoveRight);
            MotionState two = Stepped(Start, Wire.MoveRight, Wire.MoveRight);
            long now = T0 + 4 * Step;
            tank.DrawPosition(now, out float drawnX, out float drawnY);
            Frame(w, tank, now, 1, 2, two.X + 8f, two.Y, two.Vx, two.Vy);

            tank.DrawPosition(now, out float afterX, out float afterY);
            Assert.That((afterX, afterY), Is.EqualTo((drawnX, drawnY)).Using<(float, float)>((a, b) =>
                Math.Abs(a.Item1 - b.Item1) < 0.05f && Math.Abs(a.Item2 - b.Item2) < 0.05f), "drawn where it was: no jump on the screen");
            Assert.That(tank.X - drawnX, Is.GreaterThan(7f), "while the tank itself is 8 units on");
            Assert.That(tank.CorrectionAt(now), Is.EqualTo(8f).Within(0.2f), "nothing drawn away yet");
            Assert.That(tank.CorrectionAt(now + 50), Is.EqualTo(4f).Within(0.1f), "half, 50 ms on");
            Assert.That(tank.CorrectionAt(now + 100), Is.EqualTo(2f).Within(0.05f));
            tank.DrawPosition(now + 1_000, out float x, out float y);
            Assert.That(x, Is.EqualTo(tank.X).Within(0.01f), "a second on, drawn where it is");
            Assert.That(tank.Jumps, Is.Zero);
        }

        [Test]
        public void AJumpIsTakenAtOnce()
        {
            ClientWorld w = Joined(out OwnTank tank);
            tank.Advance(T0 + 4 * Step, Wire.MoveRight, 0, 1);
            tank.Sent(1, Wire.MoveRight);
            long now = T0 + 4 * Step;
            MotionState server = Frame(w, tank, now, 1, 4, 1_500f, 1_000f, 0f, 0f);    // 500 units off: a teleport
            Assert.That(tank.Jumps, Is.EqualTo(1));
            Assert.That(tank.CorrectionAt(now), Is.Zero, "not drawn away: taken");
            AssertAt(tank, server, "where the server has it, its step the last");
        }

        [Test]
        public void WithoutTheEchoedInputsStepsOrBeforeAnyInputNothingIsReplayed()
        {
            ClientWorld w = Joined(out OwnTank tank);
            float error = 0f;
            tank.OnReconciled = (e, c) => error = e;
            tank.Advance(T0 + 4 * Step, Wire.MoveRight, 0, 1);
            MotionState server = Frame(w, tank, T0 + 4 * Step, 0, 0, 1_000.5f, 1_000f, 0f, 0f);
            AssertAt(tank, server, "no input yet: the server's tank is where it is");
            Assert.That(error, Is.NaN, "and nothing to compare");

            server = Frame(w, tank, T0 + 4 * Step, 9, 3, 1_001f, 1_000f, 0.25f, 0f);      // seq 9: never stamped here
            AssertAt(tank, server, "an echo of steps not kept");
            Assert.That(tank.Compared, Is.Zero);
        }

        [Test]
        public void AnEchoOfNoTicksIsNoInputEvenWhereSeqZeroWasStamped()
        {
            // The seq runs modulo 2^24, so 0 is an ordinary seq every nine days of play; a fresh view's
            // echo, before any input reaches it, is 0 too, with no ticks. That is no input, not seq 0's.
            ClientWorld w = Joined(out OwnTank tank);
            tank.Advance(T0 + 4 * Step, Wire.MoveRight, 0, 0);
            MotionState server = Frame(w, tank, T0 + 4 * Step, 0, 0, 1_000f, 1_000f, 0f, 0f);
            AssertAt(tank, server, "nothing replayed");
            Assert.That(tank.Compared, Is.Zero);
        }

        [Test]
        public void ASendStampsItsStepsWithTheInputSent()
        {
            ClientWorld w = Joined(out OwnTank tank);
            tank.Advance(T0 + 2 * Step, Wire.MoveRight, 0, 1);
            tank.Advance(T0 + 4 * Step, Wire.MoveDown, 0, 1);          // a change of mind before the send
            tank.Sent(1, Wire.MoveDown);                               // the server applies this, for all of seq 1

            MotionState one = Stepped(Start, Wire.MoveDown);
            MotionState server = Frame(w, tank, T0 + 4 * Step, 1, 1, one.X, one.Y, one.Vx, one.Vy);
            AssertAt(tank, Stepped(server, Times(3, Wire.MoveDown)), "steps 2 to 4 replayed as sent");
        }

        [Test]
        public void NotPredictedWhileDeadOrWithoutARuleAndARespawnStartsAfresh()
        {
            var w = new ClientWorld();
            var tank = new OwnTank();
            w.Apply(new FrameBuilder(1).Tank(Wire.SelfHandle, 4_000, 4_000, "Ada").Motion(0, 0, 0).Build());
            tank.OnFrame(w, Map, Map, T0);
            Assert.That(tank.Predicting, Is.False, "an older server, or no rule yet");
            tank.DrawPosition(T0, out float x, out float y);
            Assert.That((x, y), Is.EqualTo((1_000f, 1_000f)), "drawn at its newest sample");

            w = Joined(out tank);
            tank.Advance(T0 + 3 * Step, Wire.MoveRight, 0, 1);
            w.Apply(new FrameBuilder(1).Remove(Wire.SelfHandle).Build());
            tank.OnFrame(w, Map, Map, T0 + 3 * Step);
            Assert.That(tank.Predicting, Is.False, "dead");
            tank.Advance(T0 + 6 * Step, Wire.MoveRight, 0, 1);

            w.Apply(new FrameBuilder(1).Tank(Wire.SelfHandle, 8_000, 2_000, "Ada").Motion(5, 0, 0).Build());
            tank.OnFrame(w, Map, Map, T0 + 7 * Step);
            Assert.That(tank.Predicting, Is.True, "a new tank");
            AssertAt(tank, new MotionState { X = 2_000f, Y = 500f }, "where it was put, without a correction");
            Assert.That(tank.CorrectionAt(T0 + 7 * Step), Is.Zero);
            Assert.That(tank.Jumps, Is.Zero, "a new tank is not a jump");
        }

        [Test]
        public void ARespawnInOneFrameStartsAfresh()
        {
            ClientWorld w = Joined(out OwnTank tank);
            tank.Advance(T0 + 4 * Step, Wire.MoveRight, 0, 1);
            tank.Sent(1, Wire.MoveRight);
            // As the server sends it: the dead tank's remove and the new one's create together (02 §5).
            w.Apply(new FrameBuilder(2, 1).Remove(Wire.SelfHandle).Tank(Wire.SelfHandle, 16_000, 16_000, "Ada").Motion(3, 0, 0).Build());
            tank.OnFrame(w, Map, Map, T0 + 4 * Step);
            AssertAt(tank, new MotionState { X = 4_000f, Y = 4_000f }, "where the new tank is");
            Assert.That(tank.Compared, Is.Zero, "nothing of the old tank's compared");
            Assert.That(tank.Jumps, Is.Zero, "and no jump: a new tank");
            Assert.That(tank.CorrectionAt(T0 + 4 * Step), Is.Zero);
        }

        [Test]
        public void AStallStepsNoMoreThanItKeeps()
        {
            Joined(out OwnTank tank);
            tank.Advance(T0 + 60_000, Wire.MoveRight, 0, 1);         // a minute in a lift
            AssertAt(tank, Stepped(Start, Times(OwnTank.Kept, Wire.MoveRight)), "the steps it keeps, and no more");
            tank.Advance(T0 + 60_000 + Step, Wire.MoveRight, 0, 1);
            AssertAt(tank, Stepped(Start, Times(OwnTank.Kept + 1, Wire.MoveRight)), "and on from there");
        }
    }
}
