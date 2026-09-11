package cn.photolib.photo;

import cn.photolib.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoTagsTests {
    @Test
    void normalizeTrimsDropsBlanksAndDeduplicatesInFirstSeenOrder() {
        assertThat(PhotoTags.normalize(Arrays.asList(" 合影 ", "合影", "", null, "  ", "毕业典礼", "合影")))
                .containsExactly("合影", "毕业典礼");
        assertThat(PhotoTags.normalize(null)).isEmpty();
    }

    @Test
    void normalizeRejectsTooManyOrTooLongTags() {
        List<String> many = IntStream.range(0, PhotoTags.MAX_TAGS + 1).mapToObj(i -> "标签" + i).toList();
        assertThatThrownBy(() -> PhotoTags.normalize(many))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 30 个");
        // 按字而不是 UTF-16 码元计数：50 个 emoji 是 100 个码元，仍然合法。
        assertThat(PhotoTags.normalize(List.of("😀".repeat(PhotoTags.MAX_LENGTH)))).hasSize(1);
        assertThatThrownBy(() -> PhotoTags.normalize(List.of("长".repeat(PhotoTags.MAX_LENGTH + 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过 50 个字");
    }

    @Test
    void jsonRoundTripKeepsQuotesBackslashesAndChinese() {
        List<String> tags = List.of("引号\"里", "反斜杠\\", "中文", "a,b");
        assertThat(PhotoTags.parse(PhotoTags.toJson(tags))).containsExactlyElementsOf(tags);
    }

    @Test
    void parseToleratesDoubleEncodedBlankAndBrokenValues() {
        // H2 把字符串写进 JSON 列时会再包一层 JSON 字符串；MySQL 不会。两种都要读得回来。
        assertThat(PhotoTags.parse("\"[\\\"合影\\\",\\\"校园\\\"]\"")).containsExactly("合影", "校园");
        assertThat(PhotoTags.parse(null)).isEmpty();
        assertThat(PhotoTags.parse("")).isEmpty();
        assertThat(PhotoTags.parse("not json")).isEmpty();
        assertThat(PhotoTags.parse("{\"a\":1}")).isEmpty();
        assertThat(PhotoTags.parse("[\"合影\",1,\"\",\"合影\"]")).containsExactly("合影");
        assertThat(new ArrayList<>(PhotoTags.parse("[]"))).isEmpty();
    }
}
