using System;
using System.IO;
using System.Text;

namespace Backend.Client.Simple
{
    /// <summary>What a Welcome tells the client (02-networking §3).</summary>
    public sealed class ArenaWelcome
    {
        /// <summary>Snapshots a second at the start; they are timed by their ticks, not by this.</summary>
        public int SnapshotHz;
        public long MapWidth, MapHeight;
        /// <summary>0 the public arena, 1 a duel, 2 team-vs-team, 3 ranked free-for-all, 4 co-op, 5 a team match,
        /// 6 domination, 7 tag, 8 maze, 9 a sandbox.</summary>
        public int Mode;
        /// <summary>The class table's and the phrase list's versions, as GET /v1/content/... answers them.</summary>
        public long ContentVersion, PhraseListVersion;
        public long SelfEntityId;
        /// <summary>What ArenaClient.Resume() sends after a lost connection. Each Welcome has a new one.</summary>
        public string ResumeSecret;
        /// <summary>A maze's seed, 0 for none.</summary>
        public long MazeSeed;
    }

    /// <summary>
    /// The match protocol's bytes (docs/detailed-design/02-networking.md §1-§3). Every message travels as a frame: its
    /// length as a varint, then the message, whose first byte is its type. Numbers are little-endian, but for a Ping's
    /// time. The encoders return whole frames, ready to write.
    /// </summary>
    public static class ArenaMessages
    {
        /// <summary>The protocol's version, sent first in Join and Resume. Another one is refused with KickProtocolVersion.</summary>
        public const int Version = 4;

        // The client's messages.
        public const int MsgJoin = 1, MsgInput = 2, MsgUpgradeStat = 3, MsgChooseClass = 4, MsgRespawn = 5, MsgPhrase = 6,
            MsgPing = 7, MsgLifecycle = 8, MsgLeave = 9, MsgResume = 10;

        // The arena's messages.
        public const int MsgWelcome = 1, MsgSnapshot = 2, MsgPong = 3, MsgKick = 4;

        // An Input's move, any of them together, and its flags.
        public const int MoveUp = 1, MoveDown = 2, MoveLeft = 4, MoveRight = 8;
        public const int FlagFire = 1, FlagAutofire = 2, FlagZoom = 8;

        // The most the client wants sent (§8).
        public const int ProfileMobile = 0, ProfileHigh = 1, ProfileSaver = 2;

        // Why the arena sent the client away, and what to do.
        /// <summary>The ticket is unknown, used or expired: back to the lobby for a new one.</summary>
        public const int KickBadTicket = 1;
        /// <summary>No room has space: queue again.</summary>
        public const int KickRoomFull = 2;
        /// <summary>The app is too old for the server: ask the player to update it; never retry.</summary>
        public const int KickProtocolVersion = 3;
        /// <summary>Too many messages: a bug of the client's; log it, do not loop.</summary>
        public const int KickRateLimit = 4;
        /// <summary>A fault of the server's: back to the lobby, try again after a while.</summary>
        public const int KickInternal = 5;
        /// <summary>The match is over: back to the lobby. Not an error.</summary>
        public const int KickMatchOver = 6;
        /// <summary>An operator closed the room or took the player out: back to the lobby.</summary>
        public const int KickRemoved = 7;

        /// <summary>Longer than any frame the arena sends; a longer length means the stream is broken.</summary>
        public const int MaxFrame = 64 * 1024;

        public static byte[] Join(string ticketId, int profile) => Admission(MsgJoin, ticketId, profile);

        /// <summary>In place of Join after a lost connection, with the last Welcome's ResumeSecret.</summary>
        public static byte[] Resume(string resumeSecret, int profile) => Admission(MsgResume, resumeSecret, profile);

        /// <param name="seq">counts up by one each Input, modulo 2^24</param>
        /// <param name="ackTick">the server's tick of the last snapshot applied</param>
        /// <param name="aim">from <see cref="Aim"/></param>
        public static byte[] Input(int seq, long ackTick, int moveMask, int aim, int flags)
        {
            var m = Begin(MsgInput);
            Varint(m, seq & 0xFFFFFF);
            Varint(m, ackTick);
            m.WriteByte((byte)moveMask);
            m.WriteByte((byte)aim);                    // u16, little-endian
            m.WriteByte((byte)(aim >> 8));
            m.WriteByte((byte)flags);
            return End(m);
        }

        /// <summary>An angle in radians, 0 along +x, to the u16 an Input carries.</summary>
        public static int Aim(float radians)
        {
            double turn = (radians + Math.PI) / (2 * Math.PI);
            return (int)Math.Floor(turn * 65536.0 + 0.5) & 0xFFFF;
        }

        /// <summary>The time is the client's own clock in ms; the Pong brings it back to measure the round trip.</summary>
        public static byte[] Ping(uint clientTimeMs)
        {
            var m = Begin(MsgPing);
            m.WriteByte((byte)(clientTimeMs >> 24));    // big-endian, the one field that is
            m.WriteByte((byte)(clientTimeMs >> 16));
            m.WriteByte((byte)(clientTimeMs >> 8));
            m.WriteByte((byte)clientTimeMs);
            return End(m);
        }

        public static byte[] Lifecycle(bool background)
        {
            var m = Begin(MsgLifecycle);
            m.WriteByte((byte)(background ? 1 : 0));
            return End(m);
        }

        public static byte[] Leave() => End(Begin(MsgLeave));

        public static byte[] Respawn() => End(Begin(MsgRespawn));

        public static byte[] Phrase(int phraseId)
        {
            var m = Begin(MsgPhrase);
            Varint(m, phraseId);
            return End(m);
        }

        public static byte[] UpgradeStat(int stat)
        {
            var m = Begin(MsgUpgradeStat);
            m.WriteByte((byte)stat);
            return End(m);
        }

        public static byte[] ChooseClass(int classId)
        {
            var m = Begin(MsgChooseClass);
            m.WriteByte((byte)classId);
            return End(m);
        }

        public static ArenaWelcome ReadWelcome(byte[] message)
        {
            int at = 1;                                                   // the type
            at++;                                                         // the client's own handle, always the same
            var w = new ArenaWelcome { SnapshotHz = message[at++] };
            w.MapWidth = ReadVarint(message, ref at);
            w.MapHeight = ReadVarint(message, ref at);
            w.Mode = message[at++];
            w.ContentVersion = ReadVarint(message, ref at);
            w.PhraseListVersion = ReadVarint(message, ref at);
            w.SelfEntityId = ReadVarint(message, ref at);
            int length = (int)ReadVarint(message, ref at);
            w.ResumeSecret = Encoding.UTF8.GetString(message, at, length);
            at += length;
            if (at < message.Length) w.MazeSeed = ReadVarint(message, ref at);   // an older server sends none
            return w;
        }

        public static void ReadPong(byte[] message, out uint clientTimeMs, out long serverTick)
        {
            clientTimeMs = (uint)(message[1] << 24 | message[2] << 16 | message[3] << 8 | message[4]);
            int at = 5;
            serverTick = ReadVarint(message, ref at);
        }

        public static int ReadKick(byte[] message) => message[1];

        /// <summary>A snapshot's first field: the ticks since the last snapshot on this connection.</summary>
        public static long SnapshotTickDelta(byte[] message)
        {
            int at = 1;
            return ReadVarint(message, ref at);
        }

        /// <summary>The next frame's message, waiting for it; null when the stream ends between frames.</summary>
        public static byte[] ReadFrame(Stream stream)
        {
            long length = 0;
            for (int shift = 0; ; shift += 7)
            {
                int b = stream.ReadByte();
                if (b < 0)
                {
                    if (shift == 0) return null;
                    throw new EndOfStreamException("the connection ended inside a frame");
                }
                length |= (long)(b & 0x7F) << shift;
                if (b < 0x80) break;
                if (shift > 28) throw new IOException("a frame's length is not a varint");
            }
            if (length == 0 || length > MaxFrame) throw new IOException("a frame of " + length + " bytes: the stream is broken");
            var message = new byte[length];
            for (int got = 0; got < message.Length;)
            {
                int n = stream.Read(message, got, message.Length - got);
                if (n <= 0) throw new EndOfStreamException("the connection ended inside a frame");
                got += n;
            }
            return message;
        }

        private static byte[] Admission(int type, string credential, int profile)
        {
            if (string.IsNullOrEmpty(credential) || credential.Length > 64) throw new ArgumentException("a ticket or secret is 1 to 64 characters");
            foreach (char c in credential)
                if (c < 0x21 || c > 0x7E) throw new ArgumentException("a ticket or secret is printable ASCII");
            var m = Begin(type);
            m.WriteByte(Version);                       // first, so a mismatch is refused rather than misread
            Varint(m, credential.Length);
            foreach (char c in credential) m.WriteByte((byte)c);
            m.WriteByte((byte)profile);
            return End(m);
        }

        private static MemoryStream Begin(int type)
        {
            var m = new MemoryStream();
            m.WriteByte((byte)type);
            return m;
        }

        /// <summary>The frame: the message's length as a varint, then the message.</summary>
        private static byte[] End(MemoryStream message)
        {
            var frame = new MemoryStream();
            Varint(frame, message.Length);
            message.WriteTo(frame);
            return frame.ToArray();
        }

        private static void Varint(Stream s, long value)
        {
            ulong v = (ulong)value;
            while (v >= 0x80)
            {
                s.WriteByte((byte)(v & 0x7F | 0x80));
                v >>= 7;
            }
            s.WriteByte((byte)v);
        }

        private static long ReadVarint(byte[] b, ref int at)
        {
            long value = 0;
            for (int shift = 0; ; shift += 7)
            {
                int x = b[at++];
                value |= (long)(x & 0x7F) << shift;
                if (x < 0x80) return value;
            }
        }
    }
}
