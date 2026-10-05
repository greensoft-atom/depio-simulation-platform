package com.jredis.common;

/**
 * Redis-style glob matching over bytes: {@code *}, {@code ?}, {@code [abc]}, {@code [^abc]},
 * {@code [a-z]} and backslash escapes.
 *
 * <p>Patterns come from clients (PSUBSCRIBE, SCAN MATCH, KEYS), so matching must never be
 * exponential. This is the iterative algorithm that remembers only the most recent '*':
 * worst case O(pattern length x text length), no recursion.
 */
public final class Glob {

    private Glob() {
    }

    public static boolean match(byte[] pattern, byte[] text) {
        int plen = pattern.length;
        int tlen = text.length;
        int p = 0;
        int t = 0;
        int starP = -1;
        int starT = -1;
        while (t < tlen) {
            if (p < plen && pattern[p] == '*') {
                while (p < plen && pattern[p] == '*') {
                    p++;
                }
                if (p == plen) {
                    return true;
                }
                starP = p;
                starT = t;
                continue;
            }
            if (p < plen) {
                int r = matchOne(pattern, p, text[t]);
                if (r > 0) {
                    p += r;
                    t++;
                    continue;
                }
            }
            if (starP < 0) {
                return false;
            }
            starT++;                // let the last '*' absorb one more byte
            t = starT;
            p = starP;
        }
        while (p < plen && pattern[p] == '*') {
            p++;
        }
        return p == plen;
    }

    /** True when the pattern contains no special characters, so it can only match itself. */
    public static boolean isLiteral(byte[] pattern) {
        for (byte b : pattern) {
            if (b == '*' || b == '?' || b == '[' || b == '\\') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tries the single pattern token at {@code p} against byte {@code c}.
     *
     * @return the token length if it matches, or a value &lt;= 0 if it does not
     */
    private static int matchOne(byte[] pat, int p, byte c) {
        int plen = pat.length;
        byte pc = pat[p];
        switch (pc) {
            case '?':
                return 1;
            case '\\':
                if (p + 1 < plen) {
                    return pat[p + 1] == c ? 2 : 0;
                }
                return pc == c ? 1 : 0;       // trailing backslash matches itself
            case '[': {
                int i = p + 1;
                boolean not = i < plen && pat[i] == '^';
                if (not) {
                    i++;
                }
                boolean matched = false;
                int uc = c & 0xff;
                while (i < plen && pat[i] != ']') {
                    if (pat[i] == '\\' && i + 1 < plen) {
                        i++;
                        if ((pat[i] & 0xff) == uc) {
                            matched = true;
                        }
                        i++;
                    } else if (i + 2 < plen && pat[i + 1] == '-') {   // as Redis: "[a-]" is the range a..']'
                        int start = pat[i] & 0xff;
                        int end = pat[i + 2] & 0xff;
                        if (start > end) {
                            int tmp = start;
                            start = end;
                            end = tmp;
                        }
                        if (uc >= start && uc <= end) {
                            matched = true;
                        }
                        i += 3;
                    } else {
                        if ((pat[i] & 0xff) == uc) {
                            matched = true;
                        }
                        i++;
                    }
                }
                int tokenLen = (i < plen ? i + 1 : i) - p;   // include ']' when present
                if (not) {
                    matched = !matched;
                }
                return matched ? tokenLen : 0;
            }
            default:
                return pc == c ? 1 : 0;
        }
    }
}
