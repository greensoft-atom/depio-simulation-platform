package com.backend.platform;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * What a player may be called where other players can see it.
 *
 * <h2>Why this exists</h2>
 *
 * The username was validated — ASCII letters and digits — and the display name, the one piece
 * of text every other player actually sees in the kill feed and on the public leaderboard, was
 * not validated at all. It was trimmed and cut at 32 UTF-16 units. Demonstrated before this
 * class existed: a newline that forged a second line in logs and JSON, a right-to-left override
 * that reversed a name, zero-width padding that made two names identical on screen, terminal
 * escapes, a Cyrillic a (U+0430) posing as a Latin a, and a cut that split an emoji's surrogate pair
 * into broken text.
 *
 * <h2>The rules, and where they come from</h2>
 *
 * Built on RFC 8266 (PRECIS, the Nickname profile), which exists for exactly this: a
 * human-readable handle that is shown to other people and need not be unique.
 *
 * <ol>
 * <li><b>NFKC, then collapse spaces.</b> Two strings that look the same must be the same:
 *     "Renée" with a composed é and with e plus a combining accent render identically and
 *     compare unequal without it. NFKC rather than NFC because it also folds fullwidth letters
 *     and other compatibility forms — "Ａｄａ" becomes "Ada" — which is the lookalike NFC
 *     leaves standing.</li>
 * <li><b>Letters, combining marks and decimal digits in any script, plus space, underscore,
 *     hyphen and full stop.</b> Players are international; a Korean or Japanese name is a
 *     name. Everything else — symbols, emoji, other punctuation — is refused.</li>
 * <li><b>No control, format, private-use, unassigned or default-ignorable characters</b> —
 *     refused, not removed. Format characters are where the direction overrides and
 *     zero-width characters live. Default-ignorable characters render as nothing, and some of
 *     them are classed as letters or marks, so the category check alone misses them: the
 *     Hangul filler is a "letter", and a name made of it shows as blank. A name containing
 *     one was crafted, not mistyped. (UTS #39 gives these Identifier_Status=Restricted.)</li>
 * <li><b>Accents attach to letters, at most four deep and never the same one twice in a
 *     row</b> —
 *     the two combining-mark checks of UTS #39 section 5.4. Enough for every real script
 *     (Hindi puts a nukta, a vowel sign and a nasal on one consonant); not enough to stack
 *     fifty accents until they cover the rows above and below. A doubled accent usually
 *     renders as one, so it is a second spelling of a name that looks the same.</li>
 * <li><b>At most one of Latin, Cyrillic and Greek.</b> The scripts that share lookalike
 *     letters. This is a deliberate subset of Unicode TR39's confusable detection, not the
 *     whole of it: it stops "Ada" spelled with a Cyrillic a posing as "Ada", and it does not stop every possible
 *     lookalike. Other scripts mix freely.</li>
 * <li><b>1 to 16 characters, at least one a letter or digit.</b> Sixteen code points of at
 *     most four UTF-8 bytes each is 64 bytes, which is exactly what the kill feed carries — so
 *     every name that passes here is always shown there, never silently dropped. The column is
 *     wider; the wire is not.</li>
 * <li><b>A small reserved list</b>, compared after NFKC and case folding with separators
 *     removed, so "ＡＤＭＩＮ", "Ad-min" and "SYSTEM" are all refused. A kill feed that reads
 *     "killed by System" is staff impersonation. The list is a starting point.</li>
 * </ol>
 *
 * Emoji are refused as a product choice for now: they render differently on every device,
 * many are several code points joined by a zero-width character this class refuses anyway,
 * and "must be shown identically to everyone" is the property this class protects.
 */
public final class DisplayName {

    public static final int MAX_CODE_POINTS = 16;

    /** UTS #39 section 5.4: "Forbid sequences of more than 4 nonspacing marks". */
    private static final int MAX_CONSECUTIVE_MARKS = 4;

    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "moderator", "mod", "system", "server",
            "support", "staff", "official", "developer", "dev", "gm");

    /** The accepted form of a name, or why it was refused. Exactly one is non-null. */
    public record Result(String name, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    public static Result check(String raw) {
        if (raw == null) {
            return refuse("a name is required");
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);

        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        boolean substantive = false;
        boolean afterLetter = false;           // may an accent attach to what came before?
        int codePoints = 0;
        boolean latin = false;
        boolean cyrillic = false;
        boolean greek = false;

        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);

            if (invisibleLetterOrMark(cp)) {
                return refuse("contains an invisible or control character");
            }
            switch (type) {
                case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
                        Character.UNASSIGNED, Character.SURROGATE,
                        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> {
                    return refuse("contains an invisible or control character");
                }
                case Character.SPACE_SEPARATOR -> {
                    // NFKC has already turned wide and non-breaking spaces into U+0020; runs
                    // of them collapse to one, and the ends are trimmed below.
                    pendingSpace = out.length() > 0;
                    afterLetter = false;
                    continue;
                }
                default -> {
                    // handled below
                }
            }

            boolean mark = type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
            boolean letterOrDigit = Character.isLetter(cp)
                    || type == Character.DECIMAL_DIGIT_NUMBER;
            boolean separator = cp == '_' || cp == '-' || cp == '.';

            if (mark) {
                if (!afterLetter) {
                    return refuse("an accent must follow a letter");
                }
            } else if (letterOrDigit || separator) {
                // Letters only: no script puts accents on digits, and a digit plus the
                // enclosing keycap mark is an emoji.
                afterLetter = Character.isLetter(cp);
            } else {
                return refuse("letters, digits, spaces, _ - and . only");
            }

            if (letterOrDigit) {
                substantive = true;
                switch (Character.UnicodeScript.of(cp)) {
                    case LATIN -> latin = true;
                    case CYRILLIC -> cyrillic = true;
                    case GREEK -> greek = true;
                    default -> {
                        // other scripts mix freely
                    }
                }
            }

            if (pendingSpace) {
                out.append(' ');
                codePoints++;
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
            codePoints++;
        }

        if (!substantive) {
            return refuse("must contain a letter or a digit");
        }
        if (codePoints > MAX_CODE_POINTS) {
            return refuse("at most " + MAX_CODE_POINTS + " characters");
        }
        if ((latin ? 1 : 0) + (cyrillic ? 1 : 0) + (greek ? 1 : 0) > 1) {
            return refuse("mixes Latin, Cyrillic or Greek letters");
        }
        String name = out.toString();
        String markProblem = markProblem(Normalizer.normalize(name, Normalizer.Form.NFD));
        if (markProblem != null) {
            return refuse(markProblem);
        }
        if (RESERVED.contains(foldForReservedCheck(name))) {
            return refuse("that name is reserved");
        }
        return new Result(name, null);
    }

    /**
     * The UTS #39 section 5.4 checks on runs of nonspacing marks, or null if they pass.
     *
     * Counted in the <em>decomposed</em> form, not the stored one: NFKC composes a letter and
     * its first accent into one code point, which hides that accent from a count. Only
     * nonspacing and enclosing marks (Mn, Me) count, as the standard says: a spacing mark
     * (Mc) sits beside the letter rather than on it, so it cannot stack, and it ends the run.
     */
    private static String markProblem(String decomposed) {
        int run = 0;
        int previous = -1;
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                run = 0;
                previous = -1;
                continue;
            }
            if (cp == previous) {
                return "the same accent twice on one letter";
            }
            if (++run > MAX_CONSECUTIVE_MARKS) {
                return "too many accents on one letter";
            }
            previous = cp;
        }
        return null;
    }

    /**
     * The default-ignorable characters that the category check does not already refuse.
     *
     * Unicode 15.0 (the version Java 21 implements) lists Default_Ignorable_Code_Point in
     * DerivedCoreProperties.txt; Java has no query for the property. Most of that list is
     * format (Cf) or unassigned (Cn) and is refused by category. These are the rest: classed
     * as letters (Lo) or marks (Mn), and rendered as nothing.
     */
    private static boolean invisibleLetterOrMark(int cp) {
        return cp == 0x034F                         // combining grapheme joiner
                || cp == 0x115F || cp == 0x1160     // Hangul choseong, jungseong filler
                || cp == 0x3164 || cp == 0xFFA0     // Hangul filler, halfwidth Hangul filler
                || cp == 0x17B4 || cp == 0x17B5     // Khmer inherent vowels
                || (cp >= 0x180B && cp <= 0x180D) || cp == 0x180F   // Mongolian variation selectors
                || (cp >= 0xFE00 && cp <= 0xFE0F)   // variation selectors 1-16
                || (cp >= 0xE0100 && cp <= 0xE01EF); // variation selectors 17-256
    }

    /** Case-folded, separators removed: "Ad-min", "ＡＤＭＩＮ" and "a d m i n" all fold to "admin". */
    private static String foldForReservedCheck(String name) {
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c != ' ' && c != '_' && c != '-' && c != '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The name to use when a player gives none: their username, cut to fit.
     *
     * Safe to cut because usernames are ASCII, so a cut cannot split a character — unlike the
     * old cut, which was applied to display names. It still goes through {@link #check},
     * because "admin" is a legal username and must not become a display name by default.
     */
    public static Result fromUsername(String username) {
        String cut = username.length() > MAX_CODE_POINTS
                ? username.substring(0, MAX_CODE_POINTS)
                : username;
        return check(cut);
    }

    private static Result refuse(String problem) {
        return new Result(null, problem);
    }

    private DisplayName() {
    }
}
