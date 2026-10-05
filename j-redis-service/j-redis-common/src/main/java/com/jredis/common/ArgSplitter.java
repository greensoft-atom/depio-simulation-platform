package com.jredis.common;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a line into arguments the way Redis's inline protocol and redis-cli do: whitespace
 * separates words; double quotes allow \n \r \t \b \a \\ \" and \xHH escapes; single quotes allow
 * only \'. A closing quote must be followed by whitespace or the end of the line.
 */
public final class ArgSplitter {

    private ArgSplitter() {
    }

    /**
     * @return the arguments, or {@code null} if the quotes are unbalanced
     */
    public static List<byte[]> split(byte[] line) {
        List<byte[]> out = new ArrayList<>();
        int n = line.length;
        int p = 0;
        while (true) {
            while (p < n && isSpace(line[p])) {
                p++;
            }
            if (p >= n) {
                return out;
            }
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            boolean inDq = false;
            boolean inSq = false;
            boolean done = false;
            while (!done) {
                if (inDq) {
                    if (p >= n) {
                        return null;                            // unterminated quotes
                    }
                    byte c = line[p];
                    if (c == '\\' && p + 3 < n && line[p + 1] == 'x' && isHex(line[p + 2]) && isHex(line[p + 3])) {
                        cur.write((hex(line[p + 2]) << 4) | hex(line[p + 3]));
                        p += 3;
                    } else if (c == '\\' && p + 1 < n) {
                        p++;
                        byte e = line[p];
                        switch (e) {
                            case 'n': cur.write('\n'); break;
                            case 'r': cur.write('\r'); break;
                            case 't': cur.write('\t'); break;
                            case 'b': cur.write('\b'); break;
                            case 'a': cur.write(7); break;
                            default: cur.write(e); break;
                        }
                    } else if (c == '"') {
                        if (p + 1 < n && !isSpace(line[p + 1])) {
                            return null;                        // closing quote must end the word
                        }
                        done = true;
                    } else {
                        cur.write(c);
                    }
                } else if (inSq) {
                    if (p >= n) {
                        return null;
                    }
                    byte c = line[p];
                    if (c == '\\' && p + 1 < n && line[p + 1] == '\'') {
                        p++;
                        cur.write('\'');
                    } else if (c == '\'') {
                        if (p + 1 < n && !isSpace(line[p + 1])) {
                            return null;
                        }
                        done = true;
                    } else {
                        cur.write(c);
                    }
                } else {
                    if (p >= n) {
                        done = true;
                        continue;
                    }
                    byte c = line[p];
                    if (isSpace(c)) {
                        done = true;
                    } else if (c == '"') {
                        inDq = true;
                    } else if (c == '\'') {
                        inSq = true;
                    } else {
                        cur.write(c);
                    }
                }
                if (p < n) {
                    p++;
                }
            }
            out.add(cur.toByteArray());
        }
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    private static boolean isHex(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return c - 'A' + 10;
    }
}
