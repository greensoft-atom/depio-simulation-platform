using System;

namespace Backend.Client.Core
{
    /// <summary>
    /// The player's own tank, predicted (docs 02 §9, 08 §4, D-62): moved by the player's input at once,
    /// by the room's rule, and put right by every frame without a jump.
    ///
    /// It steps at the room's 25 Hz by the clock, each step stamped with the seq of the input that
    /// will carry it and holding the state after it; 64 are kept. A frame names the step that
    /// matches its tick, the first stamped with the echoed seq plus the input's ticks less one; the
    /// tank is put there as the server has it and the steps after it are stepped again. What that
    /// moves the present by is a correction, drawn away, halving every 50 ms, or taken at once when
    /// it is more than 64 units (a respawn, a resume, a teleport). Main thread, as the world is.
    /// </summary>
    public sealed class OwnTank
    {
        /// <summary>Steps kept: 2.56 s at 25 Hz, more than any round trip worth playing on.</summary>
        public const int Kept = 64;
        /// <summary>A correction further than this is not one, and is taken at once.</summary>
        public const float JumpUnits = 64f;
        public const double CorrectionHalfLifeMs = 50;
        public const int StepMs = 1000 / Wire.TickHz;

        private const int NoSeq = -1;

        private struct Stamped
        {
            public int Seq, Move;
            public MotionState After;
        }

        private readonly Stamped[] _steps = new Stamped[Kept];
        private long _taken;                    // the present's step, counted from the start
        private long _origin;                   // when step 0 was, in the clock's ms
        private MotionState _present, _previous;
        private float _accel, _radius, _mapWidth, _mapHeight;
        private ClientWorld _world;
        private WallGrid _walls;
        private float _offsetX, _offsetY;       // what is left of the corrections, as of _offsetAt
        private long _offsetAt;
        private float _serverX, _serverY;       // the newest sample, drawn when nothing is predicted

        /// <summary>Whether it is predicted: not while dead, joining, parked, or before a rule has come.</summary>
        public bool Predicting { get; private set; }
        /// <summary>The present, predicted, in world units: the step just taken.</summary>
        public float X => _present.X;
        public float Y => _present.Y;
        /// <summary>The aim held, in the wire's units: a driven tank's angle is its input's, every tick.</summary>
        public int Aim { get; private set; }
        public float Angle => Wire.AimToRadians(Aim);
        /// <summary>Frames whose step was found and compared.</summary>
        public long Compared { get; private set; }
        /// <summary>Corrections too far to draw away, taken at once.</summary>
        public int Jumps { get; private set; }

        /// <summary>
        /// Each frame reconciled: the prediction error, the matched step's predicted position against the
        /// frame's (NaN when there was no step to match), and the correction, how far the present moved;
        /// both in world units. For the drill and a development build's overlay.
        /// </summary>
        public Action<float, float> OnReconciled;

        /// <summary>After each frame is applied: compares, reconciles and replays, or starts or stops.</summary>
        public void OnFrame(ClientWorld world, float mapWidth, float mapHeight, long nowMs)
        {
            if (!ReferenceEquals(world, _world))
            {
                // A new connection's world (a join, a resume): its walls, and a fresh start.
                _world = world;
                _walls = world.Walls.Count == 0 ? null : new WallGrid(world.Walls, Maze.Cell);
                Predicting = false;
            }
            _mapWidth = mapWidth;
            _mapHeight = mapHeight;
            Entity self = world[Wire.SelfHandle];
            if (!self.Alive)
            {
                Predicting = false;
                return;
            }
            _serverX = self.X / Wire.PosScale;
            _serverY = self.Y / Wire.PosScale;
            SelfMotion m = world.Motion;
            if (!m.HasRule || !m.InFrame)
            {
                Predicting = false;                     // nothing to predict by: drawn at its newest sample
                return;
            }
            _accel = m.Accel;
            _radius = m.Radius;
            var server = new MotionState { X = _serverX, Y = _serverY, Vx = m.Vx, Vy = m.Vy };
            if (!Predicting || world.SelfCreated)
            {
                Begin(server, nowMs);
                return;
            }

            long matched = Matched(world.LastProcessedInputSeq, m.InputTicks);
            MotionState before = _present;
            float error = float.NaN;
            if (matched >= 0)
            {
                ref Stamped at = ref _steps[matched % Kept];
                error = Distance(at.After.X, at.After.Y, server.X, server.Y);
                Compared++;
                at.After = server;
                MotionState s = server, previous = server;
                for (long k = matched + 1; k <= _taken; k++)
                {
                    previous = s;
                    ref Stamped step = ref _steps[k % Kept];
                    TankMotion.Step(ref s, step.Move, _accel, _radius, _mapWidth, _mapHeight, _walls);
                    step.After = s;
                }
                _present = s;
                _previous = matched == _taken ? server : previous;
            }
            else
            {
                // Before any input, or an echo of steps not kept: the server's tank, from its tick on.
                _present = _previous = server;
                _steps[_taken % Kept].After = server;
            }

            float dx = before.X - _present.X, dy = before.Y - _present.Y;
            float correction = (float)Math.Sqrt(dx * dx + dy * dy);
            if (correction > JumpUnits)
            {
                Jumps++;
                _offsetX = _offsetY = 0f;
                _offsetAt = nowMs;
            }
            else
            {
                Decay(nowMs);
                _offsetX += dx;
                _offsetY += dy;
            }
            OnReconciled?.Invoke(error, correction);
        }

        /// <summary>
        /// Takes the steps the clock says are due, with the input held, each stamped with the seq that
        /// will carry it: the next to be sent. Once a frame, before the input goes out.
        /// </summary>
        public void Advance(long nowMs, int moveMask, int aim, int pendingSeq)
        {
            Aim = aim;
            if (!Predicting) return;
            long due = (nowMs - _origin) / StepMs;
            if (due - _taken > Kept)
            {
                // A stall: no more steps than are kept, the clock moved on to match.
                _origin += (due - _taken - Kept) * StepMs;
                due = _taken + Kept;
            }
            while (_taken < due)
            {
                _previous = _present;
                TankMotion.Step(ref _present, moveMask, _accel, _radius, _mapWidth, _mapHeight, _walls);
                _taken++;
                _steps[_taken % Kept] = new Stamped { Seq = pendingSeq, Move = moveMask, After = _present };
            }
        }

        /// <summary>
        /// An input went out: its seq's steps take the input sent, which is what the server will apply
        /// for all of them, whatever was held while they were taken.
        /// </summary>
        public void Sent(int seq, int moveMask)
        {
            if (!Predicting) return;
            for (long k = _taken; k >= 0 && k > _taken - Kept && _steps[k % Kept].Seq == seq; k--)
                _steps[k % Kept].Move = moveMask;
        }

        /// <summary>Parked or gone: nothing is predicted until a frame starts it again.</summary>
        public void Stop() => Predicting = false;

        /// <summary>How far the correction still drawn is, in world units.</summary>
        public float CorrectionAt(long nowMs)
        {
            float f = DecayFactor(nowMs);
            return (float)Math.Sqrt(_offsetX * f * (_offsetX * f) + _offsetY * f * (_offsetY * f));
        }

        /// <summary>
        /// Where to draw it: between the last two steps by the time since the last, plus what is left of
        /// the correction. Not predicted, at the server's newest sample.
        /// </summary>
        public void DrawPosition(long nowMs, out float x, out float y)
        {
            if (!Predicting)
            {
                x = _serverX;
                y = _serverY;
                return;
            }
            double into = (nowMs - _origin - _taken * (double)StepMs) / StepMs;
            float t = (float)Math.Max(0.0, Math.Min(1.0, into));
            float f = DecayFactor(nowMs);
            x = _previous.X + (_present.X - _previous.X) * t + _offsetX * f;
            y = _previous.Y + (_present.Y - _previous.Y) * t + _offsetY * f;
        }

        private void Begin(MotionState server, long nowMs)
        {
            Predicting = true;
            _origin = nowMs;
            _taken = 0;
            _present = _previous = server;
            Array.Clear(_steps, 0, _steps.Length);
            for (int i = 0; i < _steps.Length; i++) _steps[i].Seq = NoSeq;
            _steps[0].After = server;
            _offsetX = _offsetY = 0f;
            _offsetAt = nowMs;
        }

        /// <summary>The step a frame's tick matches, or -1: none before any input, or for steps not kept.</summary>
        private long Matched(int seq, int inputTicks)
        {
            if (inputTicks <= 0) return -1;
            for (long k = Math.Max(0, _taken - Kept + 1); k <= _taken; k++)
                if (_steps[k % Kept].Seq == seq) return Math.Min(_taken, k + inputTicks - 1);
            return -1;
        }

        private void Decay(long nowMs)
        {
            float f = DecayFactor(nowMs);
            _offsetX *= f;
            _offsetY *= f;
            _offsetAt = nowMs;
        }

        private float DecayFactor(long nowMs) =>
            nowMs <= _offsetAt ? 1f : (float)Math.Pow(0.5, (nowMs - _offsetAt) / CorrectionHalfLifeMs);

        private static float Distance(float ax, float ay, float bx, float by)
        {
            float dx = ax - bx, dy = ay - by;
            return (float)Math.Sqrt(dx * dx + dy * dy);
        }
    }
}
