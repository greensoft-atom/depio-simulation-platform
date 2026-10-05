using System;
using System.Collections.Generic;
using System.Linq;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The bytes the client sends, against the layouts in 02 §3 and what the server's own test
    /// client writes. The real check is the server reading them, in the headless client's run.
    /// </summary>
    public class MessageTests
    {
        private static byte[] Bytes(ArraySegment<byte> s) => s.ToArray();

        [Test]
        public void JoinPutsTheVersionFirst()
        {
            var m = new ClientMessages();
            Assert.That(Bytes(m.Join("abc", -1)), Is.EqualTo(new byte[] { 6, Wire.MsgJoin, Wire.Version, 3, 97, 98, 99 }));
            Assert.That(Bytes(m.Join("abc", Wire.ProfileSaver)), Is.EqualTo(new byte[] { 7, 1, 4, 3, 97, 98, 99, 2 }));
            Assert.That(Bytes(m.Resume("s3cret", Wire.ProfileMobile)).Take(4), Is.EqualTo(new byte[] { 10, Wire.MsgResume, Wire.Version, 6 }));
            Assert.Throws<ArgumentException>(() => m.Join("tïcket", -1));
            Assert.Throws<ArgumentException>(() => m.Join(new string('a', 65), -1));
        }

        [Test]
        public void InputCarriesTheAckAndALittleEndianAim()
        {
            var m = new ClientMessages();
            // seq 5, ack 300 (a two-byte varint), move right, aim 0x1234, autofire
            Assert.That(Bytes(m.Input(5, 300, Wire.MoveRight, 0x1234, Wire.FlagAutofire)),
                Is.EqualTo(new byte[] { 8, Wire.MsgInput, 5, 0xAC, 0x02, 8, 0x34, 0x12, 2 }));
            Assert.That(Bytes(m.Input(0x1FFFFFF, 0, 0, 0, 0))[2..6], Is.EqualTo(new byte[] { 0xFF, 0xFF, 0xFF, 0x07 }),
                "the seq is taken modulo 2^24");
        }

        [Test]
        public void ThePingClockIsBigEndianAndTheRestAreOneOrTwoBytes()
        {
            var m = new ClientMessages();
            Assert.That(Bytes(m.Ping(0x01020304)), Is.EqualTo(new byte[] { 5, Wire.MsgPing, 1, 2, 3, 4 }));
            Assert.That(Bytes(m.Lifecycle(true)), Is.EqualTo(new byte[] { 2, Wire.MsgLifecycle, 1 }));
            Assert.That(Bytes(m.Lifecycle(false)), Is.EqualTo(new byte[] { 2, Wire.MsgLifecycle, 0 }));
            Assert.That(Bytes(m.Leave()), Is.EqualTo(new byte[] { 1, Wire.MsgLeave }));
            Assert.That(Bytes(m.Respawn()), Is.EqualTo(new byte[] { 1, Wire.MsgRespawn }));
            Assert.That(Bytes(m.UpgradeStat(6)), Is.EqualTo(new byte[] { 2, Wire.MsgUpgradeStat, 6 }));
            Assert.That(Bytes(m.ChooseClass(3)), Is.EqualTo(new byte[] { 2, Wire.MsgChooseClass, 3 }));
            Assert.That(Bytes(m.Phrase(200)), Is.EqualTo(new byte[] { 3, Wire.MsgPhrase, 0xC8, 0x01 }));
            // In numbers, as ClientMessage has them: the protocol is the numbers, not the names.
            Assert.That(Bytes(m.Sandbox(Wire.SandboxLevel, 45)), Is.EqualTo(new byte[] { 3, 11, 1, 45 }));
            Assert.That(Bytes(m.Sandbox(Wire.SandboxGuardian, 0)), Is.EqualTo(new byte[] { 3, 11, 2, 0 }));
        }

        [Test]
        public void ServerMessagesAreRead()
        {
            // As RoomThread.sendWelcome writes one.
            var b = new List<byte> { Wire.MsgWelcome, 1, 15 };
            FrameBuilder.Varint(b, 5700); FrameBuilder.Varint(b, 5700);
            b.Add(0);
            FrameBuilder.Varint(b, 1); FrameBuilder.Varint(b, 1); FrameBuilder.Varint(b, 4097);
            FrameBuilder.Varint(b, 4); b.AddRange(new byte[] { 97, 98, 45, 95 });
            Welcome w = ServerMessages.ReadWelcome(b.ToArray());
            Assert.That((w.SelfHandle, w.SnapshotHz, w.MapWidth, w.SelfEntityId, w.ResumeSecret),
                Is.EqualTo((1, 15, 5700L, 4097L, "ab-_")));
            Assert.That(w.MazeSeed, Is.Zero, "a server that sends no maze's seed: none");
            FrameBuilder.Varint(b, 4_000_000_000L);       // appended (D-48)
            Assert.That(ServerMessages.ReadWelcome(b.ToArray()).MazeSeed, Is.EqualTo(4_000_000_000L));

            Pong p = ServerMessages.ReadPong(new byte[] { Wire.MsgPong, 1, 2, 3, 4, 0xAC, 0x02 });
            Assert.That((p.ClientTimeMs, p.ServerTick), Is.EqualTo((0x01020304u, 300L)));
            Assert.That(ServerMessages.ReadKick(new byte[] { Wire.MsgKick, Wire.KickInternal }), Is.EqualTo(5));
            Assert.Throws<ProtocolException>(() => ServerMessages.ReadKick(new byte[] { Wire.MsgKick }));
        }

        [Test]
        public void FramesAreSplitHoweverTheBytesArrive()
        {
            var m = new ClientMessages();
            var stream = new List<byte>();
            stream.AddRange(Bytes(m.Ping(7)));
            byte[] big = new byte[300];                    // a two-byte length
            for (int i = 0; i < big.Length; i++) big[i] = (byte)i;
            FrameBuilder.Varint(stream, big.Length);
            stream.AddRange(big);
            stream.AddRange(Bytes(m.Leave()));
            byte[] all = stream.ToArray();

            // Every split point, one byte at a time included.
            foreach (int chunk in new[] { 1, 2, 3, 7, all.Length })
            {
                var reader = new FrameReader();
                var frames = new List<byte[]>();
                for (int at = 0; at < all.Length; at += chunk)
                    reader.Feed(all.AsSpan(at, Math.Min(chunk, all.Length - at)), frames.Add);
                Assert.That(frames.Select(f => f.Length), Is.EqualTo(new[] { 5, 300, 1 }), $"chunks of {chunk}");
                Assert.That(frames[1], Is.EqualTo(big));
            }
        }

        [Test]
        public void ABrokenStreamIsRefused()
        {
            Assert.Throws<ProtocolException>(() => new FrameReader().Feed(new byte[] { 0 }, _ => { }), "an empty frame");
            var tooLong = new List<byte>();
            FrameBuilder.Varint(tooLong, FrameReader.MaxFrame + 1);
            Assert.Throws<ProtocolException>(() => new FrameReader().Feed(tooLong.ToArray(), _ => { }));
        }
    }
}
