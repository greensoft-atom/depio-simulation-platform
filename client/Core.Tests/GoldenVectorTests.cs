using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The contract between the server's Java encoder and this decoder
    /// (protocol-spike/vectors/vectors.txt): every decoded field of every case, printed in the
    /// format the Java codec printed when it wrote the file, and compared line for line.
    /// </summary>
    public class GoldenVectorTests
    {
        private sealed class Case
        {
            public string Name;
            public byte[] Bytes;
            public int DeclaredLength;
            public List<string> Expected = new List<string>();
        }

        private static List<Case> Cases()
        {
            string path = Path.Combine(TestContext.CurrentContext.TestDirectory, "vectors.txt");
            var cases = new List<Case>();
            Case current = null;
            foreach (string line in File.ReadAllLines(path))
            {
                if (line.StartsWith("#") || line.Length == 0) continue;
                if (line.StartsWith("case ")) cases.Add(current = new Case { Name = line.Substring(5) });
                else if (line.StartsWith("bytes ")) current.DeclaredLength = int.Parse(line.Substring(6));
                else if (line.StartsWith("hex ")) current.Bytes = FromHex(line.Substring(4));
                else current.Expected.Add(line);
            }
            return cases;
        }

        private static byte[] FromHex(string hex)
        {
            var b = new byte[hex.Length / 2];
            for (int i = 0; i < b.Length; i++) b[i] = Convert.ToByte(hex.Substring(i * 2, 2), 16);
            return b;
        }

        /// <summary>Prints what it is given, as SnapshotCodec.java printed the vectors.</summary>
        private sealed class Printer : ISnapshotSink
        {
            public readonly List<string> Lines = new List<string>();
            private readonly List<int> _removes = new List<int>();
            private bool _removesPrinted;

            public void Header(long tickDelta, long inputSeqDelta, long originDx, long originDy)
            {
                Lines.Add($"tickDelta {tickDelta}");
                Lines.Add($"inputSeqDelta {inputSeqDelta}");
                Lines.Add($"viewOrigin {originDx} {originDy}");
            }

            public void Remove(int handle) => _removes.Add(handle);

            private void FlushRemoves()
            {
                if (_removesPrinted) return;
                Lines.Add("removes [" + string.Join(", ", _removes) + "]");
                _removesPrinted = true;
            }

            public void CreateTank(int handle, int x, int y, int angle, int hp, int classId, int team, int level, string name)
            {
                FlushRemoves();
                Lines.Add($"create tank h={handle} x={x} y={y} angle={angle} hp={hp} cls={classId} team={team} lvl={level} name={name}");
            }

            public void CreatePredicted(int handle, int x, int y, int heading, int speed, int spawnTickOffset, int lifetimeTicks, int ownerHandle, int radius)
            {
                FlushRemoves();
                Lines.Add($"create predicted h={handle} x={x} y={y} heading={heading} speed={speed} spawnOff={spawnTickOffset} life={lifetimeTicks} owner={ownerHandle} radius={radius}");
            }

            public void CreateUnit(int handle, int x, int y, int angle, int hp, int subtype, int team, int ownerHandle, int radius)
            {
                FlushRemoves();
                Lines.Add($"create unit h={handle} x={x} y={y} angle={angle} hp={hp} subtype={subtype} team={team} owner={ownerHandle} radius={radius}");
            }

            public void CreateStatic(int handle, int x, int y, int subtype, int spawnAngle)
            {
                FlushRemoves();
                Lines.Add($"create static h={handle} x={x} y={y} subtype={subtype} rot={spawnAngle}");
            }

            public void Update(in SnapshotUpdate u)
            {
                FlushRemoves();
                Lines.Add($"update h={u.Handle} mask={u.Mask} dx={u.Dx} dy={u.Dy} angle={u.Angle} hp={u.Hp}");
            }

            public void Event(int type, ReadOnlySpan<byte> payload)
            {
                FlushRemoves();
                var r = new WireReader(payload);
                if (type == Wire.EvtStats)
                {
                    long level = r.Varint(), xp = r.Varint(), next = r.Varint();
                    int unspent = r.U8();
                    var points = new int[8];
                    for (int i = 0; i < 8; i++) points[i] = r.U8();
                    Lines.Add($"event stats level={level} xp={xp} next={next} unspent={unspent} points=[{string.Join(",", points)}]");
                }
                else if (type == Wire.EvtDeath)
                {
                    long score = r.Varint();
                    Lines.Add($"event death score={score} killer={r.Utf8(r.U8())}");
                }
                else if (type == Wire.EvtMotion)
                {
                    int ticks = r.U8();
                    long vx = r.SVarint(), vy = r.SVarint();
                    Lines.Add($"event motion inputTicks={ticks} vx={vx} vy={vy}");
                }
                else if (type == Wire.EvtMotionRule)
                {
                    uint accel = r.U32BigEndian(), radius = r.U32BigEndian();
                    Lines.Add($"event motion_rule accel={accel:x8} radius={radius:x8}");
                }
                else
                {
                    Lines.Add($"event type={type} payloadBytes=[{string.Join(", ", payload.ToArray().Select(b => (sbyte)b))}]");
                }
            }

            public List<string> Done()
            {
                FlushRemoves();
                return Lines;
            }
        }

        [Test]
        public void TheVectorFileHasTheSixCases()
        {
            Assert.That(Cases().Select(c => c.Name),
                Is.EqualTo(new[] { "minimal", "join_burst", "realistic_mobile", "progression", "second_tier", "motion" }));
        }

        [TestCaseSource(nameof(CaseNames))]
        public void EveryDecodedFieldMatches(string name)
        {
            Case c = Cases().Single(k => k.Name == name);
            Assert.That(c.Bytes.Length, Is.EqualTo(c.DeclaredLength), "the frame's length");
            var printer = new Printer();
            SnapshotDecoder.Decode(c.Bytes, printer);
            Assert.That(printer.Done(), Is.EqualTo(c.Expected));
        }

        private static IEnumerable<string> CaseNames() => new[] { "minimal", "join_burst", "realistic_mobile", "progression", "second_tier", "motion" };

        [TestCaseSource(nameof(CaseNames))]
        public void ATruncatedFrameIsRefusedNotMisread(string name)
        {
            byte[] whole = Cases().Single(k => k.Name == name).Bytes;
            for (int cut = 1; cut < whole.Length; cut++)
            {
                byte[] part = whole.Take(cut).ToArray();
                Assert.Throws<ProtocolException>(() => SnapshotDecoder.Decode(part, new Printer()),
                    $"cut at {cut} of {whole.Length}");
            }
        }

        [Test]
        public void TrailingBytesAreRefused()
        {
            byte[] whole = Cases().Single(k => k.Name == "progression").Bytes;
            byte[] longer = whole.Concat(new byte[] { 0 }).ToArray();
            var ex = Assert.Throws<ProtocolException>(() => SnapshotDecoder.Decode(longer, new Printer()));
            Assert.That(ex.Message, Does.Contain("trailing"));
        }

        [Test]
        public void TheWorldAppliesEveryVectorThatStartsFromNothing()
        {
            // realistic_mobile and second_tier update handles their own frames do not create: they
            // are frames from the middle of a stream, which no fresh world could apply without refusing.
            foreach (var c in Cases().Where(k => k.Name != "realistic_mobile" && k.Name != "second_tier"))
            {
                var world = new ClientWorld();
                long tick = world.Apply(c.Bytes);
                Assert.That(tick, Is.EqualTo(long.Parse(c.Expected[0].Split(' ')[1])), c.Name);
            }
            var fresh = new ClientWorld();
            var refused = Assert.Throws<ProtocolException>(
                () => fresh.Apply(Cases().Single(k => k.Name == "realistic_mobile").Bytes));
            Assert.That(refused.Message, Does.Contain("not alive here"));
        }
    }
}
