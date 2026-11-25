using System.Globalization;
using System.Text;

namespace Backend.Client.Simple
{
    /// <summary>
    /// Reads one field at the top of a JSON object, for the few that are read by name: the lobby's envelope
    /// (<c>t</c>, <c>id</c>, <c>d</c>), an error's <c>code</c>, a token. Whole objects are better read with Unity's
    /// <c>JsonUtility</c> and a [Serializable] class (see Examples/). What is not JSON gives null, never an exception.
    /// </summary>
    public static class Json
    {
        /// <summary>The field's value as its JSON text (an object, an array, a string with its quotes, a number), or null.</summary>
        public static string GetRaw(string json, string field)
        {
            if (json == null) return null;
            int i = SkipSpace(json, 0);
            if (i >= json.Length || json[i] != '{') return null;
            i++;
            while (true)
            {
                i = SkipSpace(json, i);
                if (i >= json.Length || json[i] != '"') return null;
                int nameEnd = SkipValue(json, i);
                if (nameEnd < 0) return null;
                string name = Unquote(json, i, nameEnd);
                i = SkipSpace(json, nameEnd);
                if (i >= json.Length || json[i] != ':') return null;
                int start = SkipSpace(json, i + 1);
                int end = SkipValue(json, start);
                if (end < 0) return null;
                if (name == field) return json.Substring(start, end - start);
                i = SkipSpace(json, end);
                if (i >= json.Length || json[i] != ',') return null;
                i++;
            }
        }

        /// <summary>A string field's text, its escapes undone; null when it is not there or not a string.</summary>
        public static string GetString(string json, string field)
        {
            string raw = GetRaw(json, field);
            return raw != null && raw.Length >= 2 && raw[0] == '"' ? Unquote(raw, 0, raw.Length) : null;
        }

        /// <summary>A whole-number field, or <paramref name="fallback"/> when it is not there or not a whole number.</summary>
        public static long GetLong(string json, string field, long fallback = 0)
        {
            long value;
            return long.TryParse(GetRaw(json, field), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out value) ? value : fallback;
        }

        /// <summary>True when the field is <c>true</c>.</summary>
        public static bool GetBool(string json, string field) => GetRaw(json, field) == "true";

        /// <summary>A string as JSON writes it, with its quotes: <c>"{\"token\":" + Json.Quote(token) + "}"</c>; null is <c>null</c>.</summary>
        public static string Quote(string text)
        {
            if (text == null) return "null";
            var b = new StringBuilder(text.Length + 2).Append('"');
            foreach (char c in text)
            {
                if (c == '"' || c == '\\') b.Append('\\').Append(c);
                else if (c == '\n') b.Append("\\n");
                else if (c == '\r') b.Append("\\r");
                else if (c == '\t') b.Append("\\t");
                else if (c < 0x20) b.Append("\\u").Append(((int)c).ToString("x4"));
                else b.Append(c);
            }
            return b.Append('"').ToString();
        }

        private static int SkipSpace(string s, int i)
        {
            while (i < s.Length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
            return i;
        }

        /// <summary>Where the value starting at i ends (one past it), or -1 when it does not.</summary>
        private static int SkipValue(string s, int i)
        {
            if (i >= s.Length) return -1;
            if (s[i] == '"')
            {
                for (i++; i < s.Length; i++)
                {
                    if (s[i] == '\\') i++;
                    else if (s[i] == '"') return i + 1;
                }
                return -1;
            }
            if (s[i] == '{' || s[i] == '[')
            {
                int depth = 0;
                for (; i < s.Length; i++)
                {
                    char c = s[i];
                    if (c == '"') { i = SkipValue(s, i) - 1; if (i < 0) return -1; }
                    else if (c == '{' || c == '[') depth++;
                    else if ((c == '}' || c == ']') && --depth == 0) return i + 1;
                }
                return -1;
            }
            int start = i;                                  // a number, true, false or null
            while (i < s.Length && s[i] != ',' && s[i] != '}' && s[i] != ']' && s[i] != ' ' && s[i] != '\n' && s[i] != '\r' && s[i] != '\t') i++;
            return i > start ? i : -1;
        }

        /// <summary>The text of the string between from and to, its quotes included.</summary>
        private static string Unquote(string s, int from, int to)
        {
            var b = new StringBuilder(to - from);
            for (int i = from + 1; i < to - 1; i++)
            {
                char c = s[i];
                if (c != '\\') { b.Append(c); continue; }
                c = s[++i];
                switch (c)
                {
                    case 'n': b.Append('\n'); break;
                    case 'r': b.Append('\r'); break;
                    case 't': b.Append('\t'); break;
                    case 'b': b.Append('\b'); break;
                    case 'f': b.Append('\f'); break;
                    case 'u':
                        int code;
                        if (i + 4 < to - 1 && int.TryParse(s.Substring(i + 1, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out code))
                        {
                            b.Append((char)code);
                            i += 4;
                        }
                        break;
                    default: b.Append(c); break;      // \" \\ \/
                }
            }
            return b.ToString();
        }
    }
}
