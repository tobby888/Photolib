package cn.photolib.common.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 上传限额由管理员设，报错里的数字必须对任何取值都读得通；规则与前端 describeBytes 一致。 */
class ImageUploadPolicyDescribeTests {
    private static final long MIB = 1024L * 1024;

    @Test
    void exactBinaryMultiplesUseBinaryUnits() {
        assertThat(ImageUploadPolicy.describe(100 * MIB)).isEqualTo("100 MiB");
        assertThat(ImageUploadPolicy.describe(2 * 1024 * MIB)).isEqualTo("2 GiB");
        assertThat(ImageUploadPolicy.describe(512 * 1024)).isEqualTo("512 KiB");
    }

    @Test
    void roundDecimalValuesReadAsDecimalUnits() {
        assertThat(ImageUploadPolicy.describe(1_500_000_000L)).isEqualTo("1.5 GB");
        assertThat(ImageUploadPolicy.describe(3_000_000_000L)).isEqualTo("3 GB");
        assertThat(ImageUploadPolicy.describe(20_000_000L)).isEqualTo("20 MB");
    }

    @Test
    void everythingElseGetsAtMostTwoDecimals() {
        assertThat(ImageUploadPolicy.describe(Math.round(2.5 * MIB))).isEqualTo("2.5 MiB");
        assertThat(ImageUploadPolicy.describe(Math.round(1430.51 * MIB))).isEqualTo("1.4 GiB");
        assertThat(ImageUploadPolicy.describe(1500)).isEqualTo("1500 字节");
    }
}
