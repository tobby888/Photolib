package cn.photolib.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadsheetTextTests {
    @Test
    void prefixesEverySpreadsheetFormulaTrigger() {
        assertThat(SpreadsheetText.safe("=1+1")).isEqualTo("'=1+1");
        assertThat(SpreadsheetText.safe("+cmd")).isEqualTo("'+cmd");
        assertThat(SpreadsheetText.safe("-2")).isEqualTo("'-2");
        assertThat(SpreadsheetText.safe("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SpreadsheetText.safe("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(SpreadsheetText.safe("普通文本")).isEqualTo("普通文本");
        assertThat(SpreadsheetText.safe(null)).isEmpty();
    }
}
