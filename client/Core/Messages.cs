using System;
using System.Text;

namespace Backend.Client.Core
{
    /// <summary>The first frame after an accepted Join or Resume (02 §3).</summary>
    public struct Welcome
    {
        /// <summary>Always <see cref="Wire.SelfHandle"/>.</summary>
        public int SelfHandle;
        /// <summary>The rate the profile starts at. Frames are timed by tickDelta, never by this.</summary>
        public int SnapshotHz;
        public long MapWidth, MapHeight;
        public int Mode;
        public long ContentVersion, PhraseListVersion, SelfEntityId;
        /// <summary>What to resume this stay with after a lost connection. Each Welcome has a new one.</summary>
        public string ResumeSecret;
        /// <summary>The maze's seed, 0 for none (01 §8.9, D-48): its walls are made from it, never sent.</summary>
        public long MazeSeed;
    }

    public struct Pong
    {
        /// <summary>The client's own clock, echoed: the round trip is measured against it alone.</summary>
        public uint ClientTimeMs;
        public long ServerTick;
    }

    /// <summary>Reads the three server messages that are not snapshots.</summary>
    public static class ServerMessages
    {
        /// <summary>The message type: the frame's first byte.</summary>
        public static int TypeOf(ReadOnlySpan<byte> frame)
        {
            if (frame.Length == 0) throw new ProtocolException("an empty frame");
            return frame[0];
        }

        public static Welcome ReadWelcome(ReadOnlySpan<byte> frame)
        {
            var r = new WireReader(frame);
            Expect(ref r, Wire.MsgWelcome);
            var w = new Welcome
            {
                SelfHandle = r.U8(),
                SnapshotHz = r.U8(),
                MapWidth = r.Varint(),
                MapHeight = r.Varint(),
                Mode = r.U8(),
                ContentVersion = r.Varint(),
                PhraseListVersion = r.Varint(),
                SelfEntityId = r.Varint(),
            };
            w.ResumeSecret = r.Str();
            if (!r.Done) w.MazeSeed = r.Varint();          // appended: a server without mazes sends none
            // Later fields are a later server's; this client reads what it knows and no further.
            return w;
        }

        public static Pong ReadPong(ReadOnlySpan<byte> frame)
        {
            var r = new WireReader(frame);
            Expect(ref r, Wire.MsgPong);
            return new Pong { ClientTimeMs = r.U32BigEndian(), ServerTick = r.Varint() };
        }

        public static int ReadKick(ReadOnlySpan<byte> frame)
        {
            var r = new WireReader(frame);
            Expect(ref r, Wire.MsgKick);
            return r.U8();
        }

        private static void Expect(ref WireReader r, int type)
        {
            int t = r.U8();
            if (t != type) throw new ProtocolException($"expected message type {type}, got {t}");
        }
    }

    /// <summary>
    /// Writes the client's messages (02 §3), each framed with its varint length, into one reused
    /// buffer. What a method returns is valid until the next call: send it before writing again.
    /// Main thread only.
    /// </summary>
    public sealed class ClientMessages
    {
        // The largest message is a Join or Resume: 1 + 1 + 1 + 64 + 1 bytes, framed.
        private readonly byte[] _buf = new byte[128];
        private const int BodyAt = 2;       // room for a two-byte length prefix before the body
        private int _at;

        /// <summary>
        /// <paramref name="profile"/>: the most this client wants sent, or -1 to leave it out and
        /// get mobile (§8).
        /// </summary>
        public ArraySegment<byte> Join(string ticketId, int profile)
            => Admission(Wire.MsgJoin, ticketId, profile);

        /// <summary>In place of Join after a lost connection, with the latest Welcome's secret (§10).</summary>
        public ArraySegment<byte> Resume(string resumeSecret, int profile)
            => Admission(Wire.MsgResume, resumeSecret, profile);

        /// <summary>A ticket or secret this client can send: 1 to 64 printable ASCII characters, else ArgumentException.</summary>
        public static void CheckCredential(string credential)
        {
            if (credential == null || credential.Length == 0 || credential.Length > 64)
                throw new ArgumentException("a ticket or secret is 1 to 64 ASCII characters");
            foreach (char c in credential)
                if (c > 0x7E || c < 0x21) throw new ArgumentException("a ticket or secret is printable ASCII");
        }

        private ArraySegment<byte> Admission(int type, string credential, int profile)
        {
            CheckCredential(credential);
            Begin(type);
            U8(Wire.Version);             // first, so a mismatch is refused rather than misread
            Varint(credential.Length);
            foreach (char c in credential) U8(c);
            if (profile >= 0) U8(profile);
            return End();
        }

        /// <summary>One input: the latest move and aim, fire OR-ed over the samples it stands for.</summary>
        public ArraySegment<byte> Input(int seq, long ackTick, int moveMask, int aim, int flags)
        {
            Begin(Wire.MsgInput);
            Varint(seq & Wire.InputSeqMask);
            Varint(ackTick);
            U8(moveMask);
            U8(aim & 0xFF);               // little-endian u16
            U8((aim >> 8) & 0xFF);
            U8(flags);
            return End();
        }

        public ArraySegment<byte> UpgradeStat(int statIndex)
        {
            Begin(Wire.MsgUpgradeStat);
            U8(statIndex);
            return End();
        }

        public ArraySegment<byte> ChooseClass(int classId)
        {
            Begin(Wire.MsgChooseClass);
            U8(classId);
            return End();
        }

        public ArraySegment<byte> Respawn()
        {
            Begin(Wire.MsgRespawn);
            return End();
        }

        public ArraySegment<byte> Phrase(int phraseId)
        {
            Begin(Wire.MsgPhrase);
            Varint(phraseId);
            return End();
        }

        /// <summary>A sandbox's power: <see cref="Wire.SandboxLevel"/> or <see cref="Wire.SandboxGuardian"/> (01 §8.10).</summary>
        public ArraySegment<byte> Sandbox(int action, int value)
        {
            Begin(Wire.MsgSandbox);
            U8(action);
            Varint(value);
            return End();
        }

        /// <summary>Every 10 s in every state: a connection silent for 30 s is closed (§1).</summary>
        public ArraySegment<byte> Ping(uint clientTimeMs)
        {
            Begin(Wire.MsgPing);
            U8((int)(clientTimeMs >> 24));   // big-endian, the one field that is
            U8((int)(clientTimeMs >> 16));
            U8((int)(clientTimeMs >> 8));
            U8((int)clientTimeMs);
            return End();
        }

        public ArraySegment<byte> Lifecycle(bool backgrounded)
        {
            Begin(Wire.MsgLifecycle);
            U8(backgrounded ? Wire.LifecycleBackground : Wire.LifecycleForeground);
            return End();
        }

        /// <summary>Ends the stay at once; without it the tank waits a minute for a resume (§10).</summary>
        public ArraySegment<byte> Leave()
        {
            Begin(Wire.MsgLeave);
            return End();
        }

        private void Begin(int type)
        {
            _at = BodyAt;
            U8(type);
        }

        private void U8(int v) => _buf[_at++] = (byte)v;

        private void Varint(long v)
        {
            ulong u = (ulong)v;
            while (u >= 0x80)
            {
                U8((int)(u & 0x7F) | 0x80);
                u >>= 7;
            }
            U8((int)u);
        }

        /// <summary>Writes the length in front of the body and returns the whole frame.</summary>
        private ArraySegment<byte> End()
        {
            int length = _at - BodyAt;           // under 128 for every message: one length byte
            if (length >= 0x80) throw new InvalidOperationException("message too long for its frame");
            _buf[BodyAt - 1] = (byte)length;
            return new ArraySegment<byte>(_buf, BodyAt - 1, length + 1);
        }
    }

    /// <summary>
    /// Splits a byte stream into frames: a varint length, then that many bytes (02 §1). Fed from
    /// the receive thread; each complete frame is handed on as a fresh array, which is the one
    /// allocation per frame the client makes, off the main thread.
    /// </summary>
    public sealed class FrameReader
    {
        /// <summary>Larger than any snapshot the server sends; a length beyond it is a broken stream.</summary>
        public const int MaxFrame = 64 * 1024;

        private readonly byte[] _pending = new byte[MaxFrame + 8];
        private int _count;

        /// <summary>Adds bytes read from the socket and calls <paramref name="onFrame"/> for each whole frame.</summary>
        public void Feed(ReadOnlySpan<byte> bytes, Action<byte[]> onFrame)
        {
            while (bytes.Length > 0)
            {
                int n = Math.Min(bytes.Length, _pending.Length - _count);
                bytes.Slice(0, n).CopyTo(new Span<byte>(_pending, _count, n));
                _count += n;
                bytes = bytes.Slice(n);
                Drain(onFrame);
            }
        }

        private void Drain(Action<byte[]> onFrame)
        {
            int at = 0;
            while (true)
            {
                int length = 0, shift = 0, p = at;
                bool whole = false;
                while (p < _count)
                {
                    int b = _pending[p++];
                    length |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) { whole = true; break; }
                    shift += 7;
                    if (shift > 21) throw new ProtocolException("a frame length longer than three bytes");
                }
                if (!whole) break;
                // An empty frame means the framing is already broken (02 §3), as the server
                // treats one; guessing where the next begins is worse than closing.
                if (length == 0) throw new ProtocolException("an empty frame");
                if (length > MaxFrame) throw new ProtocolException($"a frame of {length} bytes");
                if (_count - p < length) break;
                var frame = new byte[length];
                Buffer.BlockCopy(_pending, p, frame, 0, length);
                onFrame(frame);
                at = p + length;
            }
            if (at > 0)
            {
                Buffer.BlockCopy(_pending, at, _pending, 0, _count - at);
                _count -= at;
            }
        }
    }
}
