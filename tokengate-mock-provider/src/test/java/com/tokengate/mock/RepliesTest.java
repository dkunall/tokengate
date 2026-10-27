package com.tokengate.mock;

import com.tokengate.mock.domain.Replies;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RepliesTest {

    @Test
    void chunksConcatenateBackToTheOriginal() {
        String text = "[alpha/x] You asked:  two  spaces";
        assertThat(String.join("", Replies.chunks(text))).isEqualTo(text);
        assertThat(Replies.chunks("one")).containsExactly("one");
        assertThat(Replies.chunks("")).isEmpty();
    }

    @Test
    void embeddingsAreDeterministicUnitVectors() {
        float[] a = Replies.embedding("hello", 384);
        float[] b = Replies.embedding("hello", 384);
        assertThat(a).containsExactly(b);
        double n = 0;
        for (float f : a) {
            n += f * f;
        }
        assertThat(Math.sqrt(n)).isCloseTo(1.0, within(1e-4));
    }
}
