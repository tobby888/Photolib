package cn.photolib.photo;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片标签与选题预设标签共用的规范化和序列化规则。
 *
 * <p>标签在库里是 JSON 数组字符串（{@code photo.tags_json}、{@code project.tags_json}）。
 * 所有写入都必须先经过 {@link #normalize}：去掉首尾空白、丢弃空标签、按首次出现去重，
 * 否则「风景」和「风景 」会被当成两个标签，预设标签校验和批量删除都会对不上。</p>
 *
 * <p>控制器上的 {@code @Size(max = 100)} 只是按 UTF-16 码元的粗上限；按「字」计的
 * 50 字规则只在这里判断，否则一个 emoji 会被当成两个字。</p>
 */
public final class PhotoTags {
    public static final int MAX_TAGS = 30;
    public static final int MAX_LENGTH = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private PhotoTags() {
    }

    /** 规范化并校验数量与长度；{@code null} 视为空列表。 */
    public static List<String> normalize(Collection<String> tags) {
        if (tags == null) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) continue;
            String value = tag.strip();
            if (value.isEmpty()) continue;
            if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "标签不能超过 " + MAX_LENGTH + " 个字：" + value);
            }
            result.add(value);
        }
        if (result.size() > MAX_TAGS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标签最多 " + MAX_TAGS + " 个");
        }
        return List.copyOf(result);
    }

    public static String toJson(List<String> tags) {
        try {
            return JSON.writeValueAsString(tags == null ? List.of() : tags);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("标签序列化失败", exception);
        }
    }

    /**
     * 读回标签。容忍三种历史形态：正常的 JSON 数组、被 H2 之类的驱动再包成 JSON 字符串的数组，
     * 以及空值/损坏数据（当成没有标签，而不是让整页列表 500）。
     */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = JSON.readTree(json);
            if (node.isTextual()) node = JSON.readTree(node.asText());
            if (node == null || !node.isArray()) return List.of();
            List<String> tags = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) tags.add(item.asText());
            });
            return List.copyOf(new LinkedHashSet<>(tags));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }
}
