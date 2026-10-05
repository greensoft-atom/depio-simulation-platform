using System;
using System.Text;

namespace Backend.Client.Core
{
    /// <summary>
    /// The server sent something this client cannot read: a truncated frame, an unknown entity
    /// kind, an update for a handle it does not hold. Not survivable by guessing: the connection
    /// is closed and the stay resumed, which starts a fresh view (docs 08 §3).
    /// </summary>
    public sealed class ProtocolException : Exception
    {
        public ProtocolException(string message) : base(message) { }
    }

    /// <summary>
    /// Little-endian fields and LEB128 varints over one frame: com.backend.protocol.WireReader.
    /// A struct over the caller's bytes, so reading allocates nothing but the strings it returns.
    /// </summary>
    public ref struct WireReader
    {
        private readonly ReadOnlySpan<byte> _bytes;
        private int _at;

        public WireReader(ReadOnlySpan<byte> bytes)
        {
            _bytes = bytes;
            _at = 0;
        }

        public int Position => _at;
        public int Remaining => _bytes.Length - _at;
        public bool Done => _at == _bytes.Length;

        private void Need(int n)
        {
            if (n < 0 || n > _bytes.Length - _at)
                throw new ProtocolException($"frame ends at byte {_bytes.Length}; {n} more needed at {_at}");
        }

        public int U8()
        {
            Need(1);
            return _bytes[_at++];
        }

        public int U16()
        {
            Need(2);
            int v = _bytes[_at] | (_bytes[_at + 1] << 8);
            _at += 2;
            return v;
        }

        public int I16() => unchecked((short)U16());

        /// <summary>Big-endian, as Ping and Pong carry the client's clock.</summary>
        public uint U32BigEndian()
        {
            Need(4);
            uint v = (uint)(_bytes[_at] << 24 | _bytes[_at + 1] << 16 | _bytes[_at + 2] << 8 | _bytes[_at + 3]);
            _at += 4;
            return v;
        }

        public long Varint()
        {
            ulong v = 0;
            for (int shift = 0; shift < 64; shift += 7)
            {
                int b = U8();
                v |= (ulong)(b & 0x7F) << shift;
                if ((b & 0x80) == 0) return (long)v;
            }
            throw new ProtocolException("varint longer than 64 bits");
        }

        /// <summary>Zigzag: small negative numbers stay small.</summary>
        public long SVarint()
        {
            ulong v = (ulong)Varint();
            return (long)(v >> 1) ^ -(long)(v & 1);
        }

        /// <summary>A varint byte length, then UTF-8. Names only: once per tank create.</summary>
        public string Str()
        {
            int n = LengthOf(Varint());
            return Utf8(n);
        }

        /// <summary><paramref name="n"/> bytes of UTF-8, the length already read.</summary>
        public string Utf8(int n)
        {
            Need(n);
            string s = n == 0 ? "" : Encoding.UTF8.GetString(_bytes.Slice(_at, n));
            _at += n;
            return s;
        }

        /// <summary>The next <paramref name="n"/> bytes, read or not, and steps past them.</summary>
        public ReadOnlySpan<byte> Take(int n)
        {
            Need(n);
            ReadOnlySpan<byte> s = _bytes.Slice(_at, n);
            _at += n;
            return s;
        }

        /// <summary>A length off the wire, refused if it could not be one.</summary>
        public int LengthOf(long v)
        {
            if (v < 0 || v > _bytes.Length) throw new ProtocolException($"length {v} in a frame of {_bytes.Length} bytes");
            return (int)v;
        }
    }
}
