using System;

namespace Backend.Client.Core
{
    /// <summary>One update: only the fields whose bits are set in <see cref="Mask"/> were sent.</summary>
    public struct SnapshotUpdate
    {
        public int Handle, Mask;
        /// <summary>A world-space delta against the last frame the server sent (D-16).</summary>
        public long Dx, Dy;
        public int Angle, Hp, Level, ClassId, Team, Flags;

        public bool Has(int field) => (Mask & field) != 0;
    }

    /// <summary>
    /// Receives a snapshot as it is decoded, in the frame's order: header, removes, creates,
    /// updates, events. The world is one; a test that prints what was decoded is another.
    /// </summary>
    public interface ISnapshotSink
    {
        void Header(long tickDelta, long inputSeqDelta, long originDx, long originDy);
        void Remove(int handle);
        void CreateTank(int handle, int x, int y, int angle, int hp, int classId, int team, int level, string name);
        void CreatePredicted(int handle, int x, int y, int heading, int speed, int spawnTickOffset,
                             int lifetimeTicks, int ownerHandle, int radius);
        void CreateStatic(int handle, int x, int y, int subtype, int spawnAngle);
        void CreateUnit(int handle, int x, int y, int angle, int hp, int subtype, int team, int ownerHandle, int radius);
        void Update(in SnapshotUpdate update);
        /// <summary>The payload is only valid during the call.</summary>
        void Event(int type, ReadOnlySpan<byte> payload);
    }

    /// <summary>
    /// Decodes one snapshot frame (02 §4): com.backend.protocol.SnapshotReader, streaming into a
    /// sink instead of building a frame object, so decoding allocates nothing but tank names.
    ///
    /// Reads every field the format defines, TEAM included although the arena does not send it yet:
    /// a decoder that handled only today's encoder would desynchronise the day it is switched on. A frame that fails part way has been partly delivered to the sink; the
    /// client's answer to that is to drop the world with the connection and resume (08 §3).
    /// </summary>
    public static class SnapshotDecoder
    {
        public static void Decode(ReadOnlySpan<byte> frame, ISnapshotSink sink)
        {
            var r = new WireReader(frame);
            int type = r.U8();
            if (type != Wire.MsgSnapshot) throw new ProtocolException($"not a snapshot: message type {type}");

            long tickDelta = r.Varint();
            long inputSeqDelta = r.Varint();
            long originDx = r.SVarint();
            long originDy = r.SVarint();
            sink.Header(tickDelta, inputSeqDelta, originDx, originDy);

            // Removes first: they free handles the creates below may take at once.
            int removes = r.LengthOf(r.Varint());
            for (int i = 0; i < removes; i++) sink.Remove(r.U8());

            int creates = r.LengthOf(r.Varint());
            for (int i = 0; i < creates; i++) ReadCreate(ref r, sink);

            int updates = r.LengthOf(r.Varint());
            var u = new SnapshotUpdate();
            for (int i = 0; i < updates; i++)
            {
                u.Handle = r.U8();
                u.Mask = r.U8();
                u.Dx = u.Dy = 0;
                u.Angle = u.Hp = u.Level = u.ClassId = u.Team = u.Flags = 0;
                if (u.Has(Wire.FPos))
                {
                    u.Dx = r.SVarint();
                    u.Dy = r.SVarint();
                }
                if (u.Has(Wire.FAngle)) u.Angle = r.U8();
                if (u.Has(Wire.FHp)) u.Hp = r.U8();
                if (u.Has(Wire.FLevel)) u.Level = r.U8();
                if (u.Has(Wire.FClass)) u.ClassId = r.U8();
                if (u.Has(Wire.FTeam)) u.Team = r.U8();
                if (u.Has(Wire.FFlags)) u.Flags = r.U8();
                sink.Update(in u);
            }

            int events = r.LengthOf(r.Varint());
            for (int i = 0; i < events; i++)
            {
                int eventType = r.U8();
                // A byte length, so an unknown type can be stepped over: read as anything else,
                // it desynchronises every frame that carries an event.
                int bytes = r.LengthOf(r.Varint());
                sink.Event(eventType, r.Take(bytes));
            }

            // Trailing bytes mean the two sides disagree about some field's width, and the next
            // frame would be read from the wrong place.
            if (!r.Done) throw new ProtocolException($"trailing bytes: read {r.Position} of {frame.Length}");
        }

        private static void ReadCreate(ref WireReader r, ISnapshotSink sink)
        {
            int handle = r.U8();
            int kind = r.U8();
            int x = r.I16();
            int y = r.I16();
            switch (kind)
            {
                case Wire.KindTank:
                {
                    int angle = r.U8(), hp = r.U8(), classId = r.U8(), team = r.U8(), level = r.U8();
                    sink.CreateTank(handle, x, y, angle, hp, classId, team, level, r.Str());
                    break;
                }
                case Wire.KindPredicted:
                {
                    int heading = r.U16(), speed = r.U8(), offset = r.U8(), life = r.U8(), owner = r.U8(), radius = r.U8();
                    sink.CreatePredicted(handle, x, y, heading, speed, offset, life, owner, radius);
                    break;
                }
                case Wire.KindStatic:
                {
                    int subtype = r.U8(), spawnAngle = r.U8();
                    sink.CreateStatic(handle, x, y, subtype, spawnAngle);
                    break;
                }
                case Wire.KindUnit:
                {
                    int angle = r.U8(), hp = r.U8(), subtype = r.U8(), team = r.U8(), owner = r.U8(), radius = r.U8();
                    sink.CreateUnit(handle, x, y, angle, hp, subtype, team, owner, radius);
                    break;
                }
                default:
                    throw new ProtocolException($"unknown entity kind {kind}");
            }
        }
    }
}
