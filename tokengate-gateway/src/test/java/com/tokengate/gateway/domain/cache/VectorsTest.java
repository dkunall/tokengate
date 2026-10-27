package com.tokengate.gateway.domain.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VectorsTest {

    @Test
    void cosineBasics() {
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{2, 0})).isCloseTo(1.0, within(1e-9));
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{0, 3})).isCloseTo(0.0, within(1e-9));
        assertThat(Vectors.cosine(new float[]{1, 1}, new float[]{-1, -1})).isCloseTo(-1.0, within(1e-9));
        assertThat(Vectors.cosine(new float[]{0, 0}, new float[]{1, 1})).isZero();
    }

    @Test
    void normaliseGivesUnitLength() {
        assertThat(Vectors.norm(Vectors.normalise(new float[]{3, 4}))).isCloseTo(1.0, within(1e-6));
        assertThat(Vectors.normalise(new float[]{0, 0})).containsExactly(0, 0);
    }

    @Test
    void rejectsDimensionMismatch() {
        assertThatThrownBy(() -> Vectors.dot(new float[2], new float[3])).isInstanceOf(IllegalArgumentException.class);
    }
}
