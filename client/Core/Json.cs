using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Backend.Client.Core
{
    /// <summary>
    /// A JSON value (RFC 8259), for the API's and the lobby's small messages. Written here rather
    /// than taken from a package: the core has no dependencies (D-19), and what it reads is a
    /// handful of flat objects, a few hundred bytes each. Not for anything large or hot.
    /// </summary>
    public sealed class JsonValue
    {
        public enum Kinds { Null, Bool, Number, String, Array, Object }

        public Kinds Kind { get; private set; }
        private bool _bool;
        private double _number;
        private string _string;
        private List<JsonValue> _array;
        private Dictionary<string, JsonValue> _object;

        public static readonly JsonValue Null = new JsonValue { Kind = Kinds.Null };

        public static JsonValue Of(string s) => s == null ? Null : new JsonValue { Kind = Kinds.String, _string = s };
        public static JsonValue Of(long n) => new JsonValue { Kind = Kinds.Number, _number = n };
        public static JsonValue Of(bool b) => new JsonValue { Kind = Kinds.Bool, _bool = b };
        public static JsonValue NewObject() => new JsonValue { Kind = Kinds.Object, _object = new Dictionary<string, JsonValue>() };

        /// <summary>A member of an object, or <see cref="Null"/> when absent or not an object.</summary>
        public JsonValue this[string name] =>
            Kind == Kinds.Object && _object.TryGetValue(name, out JsonValue v) ? v : Null;

        public int Count => Kind == Kinds.Array ? _array.Count : Kind == Kinds.Object ? _object.Count : 0;
        public JsonValue this[int i] => _array[i];

        public bool IsNull => Kind == Kinds.Null;
        public string AsString => Kind == Kinds.String ? _string : null;
        public bool AsBool => Kind == Kinds.Bool && _bool;
        public long AsLong => Kind == Kinds.Number ? (long)_number : 0;
        public int AsInt => (int)AsLong;
        public float AsFloat => Kind == Kinds.Number ? (float)_number : 0f;
        public IEnumerable<string> Names => Kind == Kinds.Object ? _object.Keys : Array.Empty<string>();

        public JsonValue Set(string name, JsonValue value)
        {
            if (Kind != Kinds.Object) throw new InvalidOperationException("not an object");
            _object[name] = value ?? Null;
            return this;
        }

        public JsonValue Set(string name, string value) => Set(name, Of(value));
        public JsonValue Set(string name, long value) => Set(name, Of(value));

        // ---- writing ---------------------------------------------------------------------------

        public override string ToString()
        {
            var sb = new StringBuilder();
            Write(sb);
            return sb.ToString();
        }

        private void Write(StringBuilder sb)
        {
            switch (Kind)
            {
                case Kinds.Null: sb.Append("null"); break;
                case Kinds.Bool: sb.Append(_bool ? "true" : "false"); break;
                case Kinds.Number:
                    if (_number == Math.Floor(_number) && Math.Abs(_number) < 1e15)
                        sb.Append(((long)_number).ToString(CultureInfo.InvariantCulture));
                    else
                        sb.Append(_number.ToString("R", CultureInfo.InvariantCulture));
                    break;
                case Kinds.String: WriteString(sb, _string); break;
                case Kinds.Array:
                    sb.Append('[');
                    for (int i = 0; i < _array.Count; i++)
                    {
                        if (i > 0) sb.Append(',');
                        _array[i].Write(sb);
                    }
                    sb.Append(']');
                    break;
                default:
                    sb.Append('{');
                    bool first = true;
                    foreach (var kv in _object)
                    {
                        if (!first) sb.Append(',');
                        first = false;
                        WriteString(sb, kv.Key);
                        sb.Append(':');
                        kv.Value.Write(sb);
                    }
                    sb.Append('}');
                    break;
            }
        }

        private static void WriteString(StringBuilder sb, string s)
        {
            sb.Append('"');
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                        else sb.Append(c);
                        break;
                }
            }
            sb.Append('"');
        }

        // ---- reading ---------------------------------------------------------------------------

        /// <summary>Parses one JSON text. Anything else, trailing content included, is refused.</summary>
        public static JsonValue Parse(string text)
        {
            if (text == null) throw new FormatException("no JSON");
            int at = 0;
            JsonValue v = ReadValue(text, ref at, 0);
            SkipSpace(text, ref at);
            if (at != text.Length) throw new FormatException($"JSON has trailing content at {at}");
            return v;
        }

        private const int MaxDepth = 32;

        private static JsonValue ReadValue(string s, ref int at, int depth)
        {
            if (depth > MaxDepth) throw new FormatException("JSON nested too deeply");
            SkipSpace(s, ref at);
            if (at >= s.Length) throw new FormatException("JSON ends early");
            char c = s[at];
            switch (c)
            {
                case '{':
                {
                    at++;
                    var o = NewObject();
                    SkipSpace(s, ref at);
                    if (Peek(s, at) == '}') { at++; return o; }
                    while (true)
                    {
                        SkipSpace(s, ref at);
                        if (Peek(s, at) != '"') throw new FormatException($"expected a name at {at}");
                        string name = ReadString(s, ref at);
                        SkipSpace(s, ref at);
                        Expect(s, ref at, ':');
                        o._object[name] = ReadValue(s, ref at, depth + 1);
                        SkipSpace(s, ref at);
                        if (Peek(s, at) == ',') { at++; continue; }
                        Expect(s, ref at, '}');
                        return o;
                    }
                }
                case '[':
                {
                    at++;
                    var a = new JsonValue { Kind = Kinds.Array, _array = new List<JsonValue>() };
                    SkipSpace(s, ref at);
                    if (Peek(s, at) == ']') { at++; return a; }
                    while (true)
                    {
                        a._array.Add(ReadValue(s, ref at, depth + 1));
                        SkipSpace(s, ref at);
                        if (Peek(s, at) == ',') { at++; continue; }
                        Expect(s, ref at, ']');
                        return a;
                    }
                }
                case '"': return Of(ReadString(s, ref at));
                case 't': Literal(s, ref at, "true"); return Of(true);
                case 'f': Literal(s, ref at, "false"); return Of(false);
                case 'n': Literal(s, ref at, "null"); return Null;
                default: return ReadNumber(s, ref at);
            }
        }

        private static char Peek(string s, int at) => at < s.Length ? s[at] : '\0';

        /// <summary>ASCII only: char.IsDigit also takes other scripts' digits, which JSON does not.</summary>
        private static bool IsDigit(char c) => c >= '0' && c <= '9';

        private static bool IsHex(char c) => IsDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');

        private static void Expect(string s, ref int at, char c)
        {
            if (Peek(s, at) != c) throw new FormatException($"expected '{c}' at {at}");
            at++;
        }

        private static void Literal(string s, ref int at, string word)
        {
            if (string.CompareOrdinal(s, at, word, 0, word.Length) != 0) throw new FormatException($"expected {word} at {at}");
            at += word.Length;
        }

        private static void SkipSpace(string s, ref int at)
        {
            while (at < s.Length && (s[at] == ' ' || s[at] == '\t' || s[at] == '\n' || s[at] == '\r')) at++;
        }

        private static JsonValue ReadNumber(string s, ref int at)
        {
            int start = at;
            if (Peek(s, at) == '-') at++;
            if (!IsDigit(Peek(s, at))) throw new FormatException($"unexpected '{Peek(s, start)}' at {start}");
            if (Peek(s, at) == '0') at++;
            else while (IsDigit(Peek(s, at))) at++;
            if (Peek(s, at) == '.')
            {
                at++;
                if (!IsDigit(Peek(s, at))) throw new FormatException($"a fraction needs digits at {at}");
                while (IsDigit(Peek(s, at))) at++;
            }
            if (Peek(s, at) == 'e' || Peek(s, at) == 'E')
            {
                at++;
                if (Peek(s, at) == '+' || Peek(s, at) == '-') at++;
                if (!IsDigit(Peek(s, at))) throw new FormatException($"an exponent needs digits at {at}");
                while (IsDigit(Peek(s, at))) at++;
            }
            double v = double.Parse(s.Substring(start, at - start), NumberStyles.Float, CultureInfo.InvariantCulture);
            return new JsonValue { Kind = Kinds.Number, _number = v };
        }

        private static string ReadString(string s, ref int at)
        {
            Expect(s, ref at, '"');
            var sb = new StringBuilder();
            while (true)
            {
                if (at >= s.Length) throw new FormatException("a string does not end");
                char c = s[at++];
                if (c == '"') return sb.ToString();
                if (c < 0x20) throw new FormatException($"a control character in a string at {at - 1}");
                if (c != '\\') { sb.Append(c); continue; }
                char e = Peek(s, at++);
                switch (e)
                {
                    case '"': sb.Append('"'); break;
                    case '\\': sb.Append('\\'); break;
                    case '/': sb.Append('/'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'u':
                        if (at + 4 > s.Length || !IsHex(s[at]) || !IsHex(s[at + 1]) || !IsHex(s[at + 2]) || !IsHex(s[at + 3]))
                            throw new FormatException($"a \\u escape needs four hex digits at {at}");
                        sb.Append((char)int.Parse(s.Substring(at, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
                        at += 4;
                        break;
                    default: throw new FormatException($"an unknown escape at {at - 1}");
                }
            }
        }
    }
}
