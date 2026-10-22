package cn.photolib.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把请求参数整理成能放进操作日志的样子：密码、验证码、令牌一律打码，长文本截断，
 * 大数组只留前几项。日志是给管理员排查用的，要看得出"改了什么"，但绝不能成为
 * 凭据的第二个存放处。
 */
final class AuditPayloads {
    static final String MASK = "******";
    static final int MAX_STRING = 300;
    static final int MAX_ARRAY = 30;
    static final int MAX_DEPTH = 6;
    static final int MAX_BODY_CHARS = 8_000;

    /** 字段名里出现这些词就打码（不区分大小写）。宁可多遮，不可漏遮。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|passwd|pwd|passcode|secret|token|otp|credential|signature|cookie|authorization"
                    + "|assertion|attestation|authenticator|clientdata|privatekey|accesskey|challenge|ticket"
                    + "|devicecode|usercode|recoverycode).*");
    /**
     * 叫 {@code code} 的字段多数是验证码、注册码，默认打码；只有这几处的 {@code code}
     * 是公开的编号（权限组编码、校区编码），留着才看得出改的是哪个。
     */
    private static final List<String> PUBLIC_CODE_PATHS = List.of("/api/v1/permission-groups", "/api/v1/campuses");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditPayloads() {
    }

    static boolean sensitive(String key, String path) {
        if (key == null) return false;
        if (SENSITIVE.matcher(key).matches()) return true;
        if (!key.equalsIgnoreCase("code")) return false;
        return path == null || PUBLIC_CODE_PATHS.stream().noneMatch(path::startsWith);
    }

    /**
     * JSON 请求体 → 打码后的节点。体为空返回 {@code null}；解析不了（被缓存截断、不是 JSON）
     * 时返回一句说明，而不是半截原文。
     */
    static JsonNode body(byte[] content, long declaredLength, String path) {
        if (content == null || content.length == 0) {
            return declaredLength > 0 ? TextNode.valueOf("（请求体 " + declaredLength + " 字节，未记录）") : null;
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(content);
        } catch (Exception e) {
            return TextNode.valueOf("（请求体 " + Math.max(content.length, declaredLength) + " 字节，不是可解析的 JSON，未记录）");
        }
        if (parsed == null || parsed.isMissingNode()) return null;
        JsonNode cleaned = clean(parsed, null, path, 0);
        String text = cleaned.toString();
        if (text.length() > MAX_BODY_CHARS) {
            return TextNode.valueOf(text.substring(0, MAX_BODY_CHARS) + "…（共 " + text.length() + " 字，已截断）");
        }
        return cleaned;
    }

    /** 查询串 / 表单参数。单值的展开成字符串，多值的保留数组。 */
    static Map<String, Object> parameters(Map<String, String[]> parameters, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (parameters == null) return result;
        parameters.forEach((key, values) -> {
            if (sensitive(key, path)) {
                result.put(key, MASK);
            } else if (values == null || values.length == 0) {
                result.put(key, "");
            } else if (values.length == 1) {
                result.put(key, clip(values[0]));
            } else {
                int kept = Math.min(values.length, MAX_ARRAY);
                List<String> list = new java.util.ArrayList<>(kept + 1);
                for (int i = 0; i < kept; i++) list.add(clip(values[i]));
                if (values.length > kept) list.add("…共 " + values.length + " 项");
                result.put(key, list);
            }
        });
        return result;
    }

    private static JsonNode clean(JsonNode node, String key, String path, int depth) {
        if (key != null && sensitive(key, path) && !node.isNull()) return TextNode.valueOf(MASK);
        if (node.isTextual()) return TextNode.valueOf(clip(node.asText()));
        if (depth >= MAX_DEPTH && node.isContainerNode()) return TextNode.valueOf("…");
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            int index = 0;
            for (JsonNode item : node) {
                if (index++ >= MAX_ARRAY) {
                    copy.add("…共 " + node.size() + " 项");
                    break;
                }
                copy.add(clean(item, null, path, depth + 1));
            }
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), clean(field.getValue(), field.getKey(), path, depth + 1));
            }
            return copy;
        }
        return node;
    }

    static String clip(String value) {
        if (value == null) return null;
        if (value.length() <= MAX_STRING) return value;
        return value.substring(0, MAX_STRING) + "…（共 " + value.length() + " 字）";
    }
}
