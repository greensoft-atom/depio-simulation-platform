package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each rule of {@link com.backend.platform.DisplayName}, pinned on its own.
 *
 * Two directions matter equally. Refusing a hostile name is the point; refusing an ordinary
 * name in someone's own script is the failure that looks like a policy and is really a bug \u2014
 * so the real scripts with combining marks are here too, not only Latin.
 */
class DisplayNameTest {

    private static String accepted(String raw) {
        com.backend.platform.DisplayName.Result r = com.backend.platform.DisplayName.check(raw);
        assertThat(r.problem()).as("expected '%s' to be accepted", raw).isNull();
        return r.name();
    }

    private static String refused(String raw) {
        com.backend.platform.DisplayName.Result r = com.backend.platform.DisplayName.check(raw);
        assertThat(r.name()).as("expected '%s' to be refused", raw).isNull();
        return r.problem();
    }

    @Test
    @DisplayName("ordinary names in many scripts, including ones built from combining marks")
    void realNamesAreAccepted() {
        assertThat(accepted("Ada")).isEqualTo("Ada");
        assertThat(accepted("\uae40\ucca0\uc218")).isEqualTo("\uae40\ucca0\uc218");
        assertThat(accepted("\u5c71\u7530 \u592a\u90ce")).isEqualTo("\u5c71\u7530 \u592a\u90ce");
        assertThat(accepted("\u041c\u0430\u0440\u0438\u044f")).as("Cyrillic on its own is fine").isEqualTo("\u041c\u0430\u0440\u0438\u044f");
        assertThat(accepted("\u0395\u03bb\u03ad\u03bd\u03b7")).isEqualTo("\u0395\u03bb\u03ad\u03bd\u03b7");
        assertThat(accepted("\u0645\u062d\u0645\u062f")).isEqualTo("\u0645\u062d\u0645\u062f");
        // Devanagari and Thai write vowels and viramas as combining marks. A rule that
        // refused marks outright would refuse these names, which is a bug with a policy's
        // face on it.
        assertThat(accepted("\u0928\u092e\u0938\u094d\u0924\u0947")).isEqualTo("\u0928\u092e\u0938\u094d\u0924\u0947");
        assertThat(accepted("\u0e2a\u0e27\u0e31\u0e2a\u0e14\u0e35")).isEqualTo("\u0e2a\u0e27\u0e31\u0e2a\u0e14\u0e35");
        // Hindi "zindagi": nukta, vowel sign and nasal on one consonant, three marks in a row.
        // Refused by the first version of this class, which allowed two.
        assertThat(accepted("\u091c\u093c\u093f\u0902\u0926\u0917\u0940"))
                .isEqualTo("\u091c\u093c\u093f\u0902\u0926\u0917\u0940");
        // Fully vocalised Arabic. Typed shadda-then-fatha, stored fatha-then-shadda:
        // normalisation puts marks in canonical order, so both typing orders are one name.
        assertThat(accepted("\u0645\u064f\u062d\u064e\u0645\u0651\u064e\u062f"))
                .isEqualTo("\u0645\u064f\u062d\u064e\u0645\u064e\u0651\u062f");
        // Hebrew with vowel points: likewise reordered, qamats before the shin dot.
        assertThat(accepted("\u05e9\u05c1\u05b8\u05dc\u05d5\u05b9\u05dd"))
                .isEqualTo("\u05e9\u05b8\u05c1\u05dc\u05d5\u05b9\u05dd");
        // Vietnamese stacks two accents on one vowel.
        assertThat(accepted("Nguy\u1ec5n")).isEqualTo("Nguy\u1ec5n");
        assertThat(accepted("Vi\u1ec7t")).isEqualTo("Vi\u1ec7t");
        assertThat(accepted("Ada\uae40")).as("Latin mixes with scripts it cannot be mistaken for")
                .isEqualTo("Ada\uae40");
        assertThat(accepted("tank_7-v2.0")).isEqualTo("tank_7-v2.0");
    }

    @Test
    @DisplayName("two spellings of the same visible name become one")
    void normalisation() {
        assertThat(accepted("Rene\u0301e")).as("decomposed accent, composed").isEqualTo("Ren\u00e9e");
        assertThat(accepted("\uff21\uff44\uff41")).as("fullwidth letters, folded").isEqualTo("Ada");
        assertThat(accepted("  Ada   the \u3000 Great ")).as("wide and repeated spaces collapse")
                .isEqualTo("Ada the Great");
        // No precomposed form exists for q-acute, so it stays a letter plus a mark \u2014 legal.
        assertThat(accepted("q\u0301")).isEqualTo("q\u0301");
    }

    @Test
    @DisplayName("invisible and control characters are refused, not quietly removed")
    void invisibleCharactersAreRefused() {
        assertThat(refused("Ada\nSYSTEM")).contains("invisible or control");
        assertThat(refused("Ada\u202enimda")).as("right-to-left override").contains("invisible");
        assertThat(refused("A\u200bda")).as("zero-width space").contains("invisible");
        assertThat(refused("A\u200dda")).as("zero-width joiner").contains("invisible");
        assertThat(refused("Ada\u001b[31m")).as("terminal escape").contains("invisible");
        assertThat(refused("Ada\ue000")).as("private use").contains("invisible");
        assertThat(refused("Ada\uD800")).as("an unpaired surrogate").contains("invisible");
    }

    @Test
    @DisplayName("characters that render as nothing are refused even when classed as letters or marks")
    void defaultIgnorableLettersAndMarksAreRefused() {
        assertThat(refused("\u3164")).as("Hangul filler: a 'letter' that shows as a blank name")
                .contains("invisible");
        assertThat(refused("\u3164\u3164\u3164")).contains("invisible");
        assertThat(refused("\uffa0")).as("halfwidth Hangul filler").contains("invisible");
        assertThat(refused("Ada\ufe0f")).as("variation selector 16").contains("invisible");
        assertThat(refused("A\u034fda")).as("combining grapheme joiner").contains("invisible");
        // Every member of the Unicode 15.0 Default_Ignorable_Code_Point list that is a letter
        // (Lo) or a mark (Mn), from DerivedCoreProperties.txt. The format and unassigned
        // members are refused by category and pinned above.
        int[][] ranges = {
                {0x034F, 0x034F}, {0x115F, 0x1160}, {0x17B4, 0x17B5}, {0x180B, 0x180D},
                {0x180F, 0x180F}, {0x3164, 0x3164}, {0xFE00, 0xFE0F}, {0xFFA0, 0xFFA0},
                {0xE0100, 0xE01EF}};
        int checked = 0;
        for (int[] range : ranges) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                assertThat(refused("Ada" + Character.toString(cp)))
                        .as("U+%04X", cp).contains("invisible");
                checked++;
            }
        }
        assertThat(checked).isEqualTo(267);
    }

    @Test
    @DisplayName("lookalikes across Latin, Cyrillic and Greek are refused")
    void mixedLookalikeScriptsAreRefused() {
        assertThat(refused("Ad\u0430")).as("Cyrillic a in a Latin name").contains("mixes");
        assertThat(refused("Ad\u03b1")).as("Greek alpha in a Latin name").contains("mixes");
        assertThat(refused("\u041c\u0430ri\u044f")).contains("mixes");
    }

    @Test
    @DisplayName("accents attach to letters, at most four deep, never the same one twice")
    void combiningMarks() {
        assertThat(accepted("a\u0301\u0302\u0303\u0304")).as("four: the UTS #39 limit");
        // Five accents on one letter. NFKC composes the first into the letter, so counting
        // marks in the stored form saw only four and accepted it: this case caught that.
        assertThat(refused("a\u0301\u0302\u0303\u0304\u0306")).contains("too many accents");
        assertThat(refused("\u00e1\u0302\u0303\u0304\u0306")).as("the same, arriving precomposed")
                .contains("too many accents");
        assertThat(refused("a\u0308\u0308")).as("a doubled accent renders as one")
                .contains("same accent twice");
        assertThat(refused("1\u20e3")).as("a digit plus the keycap mark is an emoji")
                .contains("follow a letter");
        assertThat(refused("7\u0301")).as("an accent on a digit").contains("follow a letter");
        assertThat(refused("\u0301Ada")).as("an accent on nothing").contains("follow a letter");
        assertThat(refused("Ada \u0301")).as("an accent on a space").contains("follow a letter");
        assertThat(refused("_\u0301")).as("an accent on a separator").contains("follow a letter");
    }

    @Test
    @DisplayName("symbols, emoji and empty-looking names are refused")
    void symbolsAndEmptinessAreRefused() {
        assertThat(refused("Ada\ud83d\ude00")).as("an emoji").contains("letters, digits");
        assertThat(refused("<Ada>")).contains("letters, digits");
        assertThat(refused("...")).contains("a letter or a digit");
        assertThat(refused("___")).contains("a letter or a digit");
        assertThat(refused("   ")).contains("a letter or a digit");
        assertThat(refused(null)).contains("required");
    }

    @Test
    @DisplayName("sixteen characters, and sixteen of any script always fit the kill feed")
    void length() {
        assertThat(accepted("a".repeat(16))).hasSize(16);
        assertThat(refused("a".repeat(17))).contains("16");
        // Four-byte characters are the worst case: 16 of them are exactly 64 UTF-8 bytes,
        // Wire.MAX_NAME_BYTES. So an accepted name is never dropped from a kill feed.
        String worst = "\ud840\udc00".repeat(16);         // U+20000, a supplementary Han
        assertThat(accepted(worst).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSize(64);
    }

    @Test
    @DisplayName("staff-sounding names are refused however they are dressed up")
    void reservedNames() {
        assertThat(refused("Admin")).contains("reserved");
        assertThat(refused("SYSTEM")).contains("reserved");
        assertThat(refused("Ad-min")).contains("reserved");
        assertThat(refused("a d m i n")).contains("reserved");
        assertThat(refused("\uff21\uff24\uff2d\uff29\uff2e")).as("fullwidth ADMIN").contains("reserved");
        assertThat(accepted("Adminton")).as("a name that merely starts the same is fine");
    }

    @Test
    @DisplayName("a missing display name falls back to the username, cut and still checked")
    void usernameFallback() {
        assertThat(com.backend.platform.DisplayName.fromUsername("a_very_long_username_of_32_chars").name())
                .isEqualTo("a_very_long_user");
        // "admin" is a legal username; it must not become a display name by default.
        assertThat(com.backend.platform.DisplayName.fromUsername("admin").ok()).isFalse();
    }
}
