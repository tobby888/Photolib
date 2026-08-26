package cn.photolib.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikeFilterTests {
    @Test
    void wildcardsAndTheEscapeCharacterItselfAreNeutralized() {
        assertThat(LikeFilter.escape("100%")).isEqualTo("100!%");
        assertThat(LikeFilter.escape("a_b")).isEqualTo("a!_b");
        // The escape character must be escaped first, or "!%" would arrive as a
        // literal "!" followed by a live wildcard.
        assertThat(LikeFilter.escape("!")).isEqualTo("!!");
        assertThat(LikeFilter.escape("!%")).isEqualTo("!!!%");
        assertThat(LikeFilter.escape("2026_%")).isEqualTo("2026!_!%");
    }

    @Test
    void ordinaryInputAndNullPassThroughUnchanged() {
        assertThat(LikeFilter.escape("毕业典礼")).isEqualTo("毕业典礼");
        assertThat(LikeFilter.escape("")).isEmpty();
        assertThat(LikeFilter.escape(null)).isNull();
    }

    @Test
    void containsFragmentAlwaysCarriesItsOwnEscapeDeclaration() {
        // Escaping only works if the statement declares the same escape character,
        // so the two must be produced together rather than remembered separately.
        assertThat(LikeFilter.contains("title"))
                .isEqualTo("title LIKE CONCAT('%', {0}, '%') ESCAPE '!'");
        assertThat(LikeFilter.contains("display_name")).contains("ESCAPE '!'");
    }
}
