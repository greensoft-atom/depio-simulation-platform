package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

import com.backend.sim.PhraseTable.Phrase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The phrase list (01 §9, D-14): what a player may say, as a device receives it. */
class PhraseTableTest {

    @Test
    @DisplayName("the shipped list is Q-8's sixteen, ids 1 to 16 in its order")
    void shipped() {
        PhraseTable table = PhraseTable.defaults();
        assertThat(table.size()).isEqualTo(16);
        assertThat(table.phrases()).extracting(Phrase::id)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        assertThat(table.phrases()).extracting(Phrase::text).containsExactly(
                "Hello!", "Good luck!", "Thanks!", "Sorry!", "Nice shot!", "Well played!", "Help!",
                "Follow me!", "Attack!", "Fall back!", "Wait!", "On my way!", "Yes", "No", "Oops!", "Bye!");
        assertThat(table.phrases()).extracting(Phrase::key).doesNotHaveDuplicates();
        assertThat(PhraseTable.defaults()).as("one instance, shared").isSameAs(table);
    }

    @Test
    @DisplayName("an id is said only if the list has it: not 0, not past the end, not a removed one")
    void contains() {
        PhraseTable table = new PhraseTable(List.of(new Phrase(1, "hi", "Hi!"), new Phrase(3, "no", "No")));
        assertThat(table.contains(1)).isTrue();
        assertThat(table.contains(3)).isTrue();
        assertThat(table.contains(2)).as("a gap, where a removed phrase was").isFalse();
        assertThat(table.contains(0)).isFalse();
        assertThat(table.contains(4)).isFalse();
        assertThat(table.contains(-1)).isFalse();
        assertThat(table.contains(Integer.MAX_VALUE)).isFalse();
    }

    @Test
    @DisplayName("the JSON is fixed bytes for fixed content, and the version is their CRC-32")
    void jsonAndVersion() {
        PhraseTable table = new PhraseTable(List.of(new Phrase(1, "hi", "Hi!"), new Phrase(3, "no", "No")));
        String json = "[{\"id\":1,\"key\":\"hi\",\"text\":\"Hi!\"},{\"id\":3,\"key\":\"no\",\"text\":\"No\"}]";
        assertThat(table.json()).isEqualTo(json);
        CRC32 crc = new CRC32();
        crc.update(json.getBytes(StandardCharsets.UTF_8));
        assertThat(table.version()).isEqualTo(crc.getValue());

        PhraseTable reworded = new PhraseTable(List.of(new Phrase(1, "hi", "Hey!"), new Phrase(3, "no", "No")));
        assertThat(reworded.version()).as("the version moves with the words").isNotEqualTo(table.version());
    }

    @Test
    @DisplayName("a list that would mislead a device is refused")
    void refused() {
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(0, "hi", "Hi!"))))
                .as("ids start at 1").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(2, "a", "A"), new Phrase(1, "b", "B"))))
                .as("in order").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "a", "A"), new Phrase(1, "b", "B"))))
                .as("an id twice").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "a", "A"), new Phrase(2, "a", "B"))))
                .as("a key twice").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "Good Luck", "A"))))
                .as("a key is lower case and underscores").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "a", " "))))
                .as("words to show").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "a", "say \"hi\""))))
                .as("nothing the hand-written JSON would have to escape").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "a", "back\\slash"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of(new Phrase(1, "a", "two\nlines"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhraseTable(List.of()))
                .as("something to say").isInstanceOf(IllegalArgumentException.class);
    }
}
