package cn.photolib.recruitment;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class RecruitmentFormSchemaValidator {
    private static final int MAX_FIELDS = 50;
    private static final int MAX_OPTIONS = 50;
    private static final int MAX_SCHEMA_JSON = 100_000;
    private static final int MAX_ANSWERS_JSON = 300_000;
    private static final Pattern FIELD_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Set<String> RESERVED_IDS = Set.of("student_id", "studentid", "uploads", "attachments");

    private final ObjectMapper objectMapper;

    public RecruitmentFormSchema validate(RecruitmentFormSchema schema) {
        if (schema == null || schema.fields() == null) {
            throw validation("表单结构不能为空");
        }
        if (schema.fields().size() > MAX_FIELDS) {
            throw validation("自定义表单字段最多 50 个");
        }
        Set<String> ids = new HashSet<>();
        List<RecruitmentFormSchema.Field> fields = new ArrayList<>(schema.fields().size());
        for (int index = 0; index < schema.fields().size(); index++) {
            RecruitmentFormSchema.Field input = schema.fields().get(index);
            if (input == null) {
                throw validation("第 " + (index + 1) + " 个表单字段不能为空");
            }
            String id = required(input.id(), 64, "字段 ID");
            if (!FIELD_ID.matcher(id).matches() || RESERVED_IDS.contains(id)) {
                throw validation("字段 ID 必须以小写字母开头，只能包含小写字母、数字、下划线或连字符");
            }
            if (!ids.add(id)) {
                throw validation("字段 ID 不能重复：" + id);
            }
            if (input.type() == null) {
                throw validation("字段类型不能为空：" + id);
            }
            String label = required(input.label(), 100, "字段标题");
            String helpText = optional(input.helpText(), 500, "字段提示");
            String placeholder = optional(input.placeholder(), 200, "字段占位提示");
            List<String> options = validateOptions(id, input.type(), input.options());
            fields.add(new RecruitmentFormSchema.Field(id, input.type(), label, helpText, placeholder,
                    input.required(), options));
        }
        RecruitmentFormSchema normalized = new RecruitmentFormSchema(fields);
        if (write(normalized, "表单结构").length() > MAX_SCHEMA_JSON) {
            throw validation("表单结构过大");
        }
        return normalized;
    }

    public Map<String, Object> validateAnswers(RecruitmentFormSchema schema, Map<String, Object> answers) {
        if (answers == null) {
            throw validation("表单答案不能为空");
        }
        Map<String, RecruitmentFormSchema.Field> fields = new LinkedHashMap<>();
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            fields.put(field.id(), field);
        }
        for (String key : answers.keySet()) {
            if (key == null || !fields.containsKey(key)) {
                throw validation("答案包含未知字段：" + String.valueOf(key));
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            Object value = answers.get(field.id());
            Object checked = validateAnswer(field, value);
            if (checked != null) {
                normalized.put(field.id(), checked);
            }
        }
        if (write(normalized, "表单答案").length() > MAX_ANSWERS_JSON) {
            throw validation("表单答案过大");
        }
        return Map.copyOf(normalized);
    }

    public String schemaJson(RecruitmentFormSchema schema) {
        return write(validate(schema), "表单结构");
    }

    public String answersJson(Map<String, Object> answers) {
        return write(answers, "表单答案");
    }

    public RecruitmentFormSchema readSchema(String json) {
        try {
            return validate(objectMapper.readValue(unwrapJsonString(json), RecruitmentFormSchema.class));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("数据库中的招募表单结构无法解析", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readAnswers(String json) {
        try {
            Object value = objectMapper.readValue(unwrapJsonString(json), Map.class);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalStateException("数据库中的招募答案不是对象");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, answer) -> result.put(String.valueOf(key), answer));
            return result;
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的招募答案无法解析", exception);
        }
    }

    /**
     * MySQL parses a bound JSON string as a JSON document, while H2's MySQL
     * compatibility mode may preserve it as a JSON string scalar. Accept both
     * representations so integration tests exercise the same persistence code.
     */
    private String unwrapJsonString(String json) throws JacksonException {
        String current = json;
        for (int depth = 0; depth < 3; depth++) {
            Object decoded = objectMapper.readValue(current, Object.class);
            if (!(decoded instanceof String nested)) return current;
            current = nested;
        }
        return current;
    }

    private List<String> validateOptions(String id, RecruitmentFieldType type, List<String> input) {
        boolean choice = type == RecruitmentFieldType.SINGLE_CHOICE
                || type == RecruitmentFieldType.MULTIPLE_CHOICE;
        List<String> options = input == null ? List.of() : input;
        if (!choice) {
            if (!options.isEmpty()) {
                throw validation("非选择字段不能设置选项：" + id);
            }
            return List.of();
        }
        if (options.size() < 2 || options.size() > MAX_OPTIONS) {
            throw validation("选择字段须设置 2 至 50 个选项：" + id);
        }
        Set<String> unique = new HashSet<>();
        List<String> normalized = new ArrayList<>(options.size());
        for (String option : options) {
            String value = required(option, 100, "选项");
            if (!unique.add(value)) {
                throw validation("选择字段的选项不能重复：" + id);
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private Object validateAnswer(RecruitmentFormSchema.Field field, Object value) {
        return switch (field.type()) {
            case SHORT_TEXT -> textAnswer(field, value, 500);
            case LONG_TEXT -> textAnswer(field, value, 20_000);
            case DATE -> dateAnswer(field, value);
            case SINGLE_CHOICE -> singleChoice(field, value);
            case MULTIPLE_CHOICE -> multipleChoice(field, value);
        };
    }

    private Object textAnswer(RecruitmentFormSchema.Field field, Object value, int maxLength) {
        if (value == null) {
            return requiredAnswer(field, null);
        }
        if (!(value instanceof String text)) {
            throw invalidAnswer(field, "必须是文本");
        }
        String normalized = normalizeLineEndings(text).trim();
        if (normalized.isEmpty()) {
            return requiredAnswer(field, null);
        }
        if (codePoints(normalized) > maxLength) {
            throw invalidAnswer(field, "最多 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private Object dateAnswer(RecruitmentFormSchema.Field field, Object value) {
        Object text = textAnswer(field, value, 10);
        if (text == null) return null;
        try {
            return LocalDate.parse((String) text).toString();
        } catch (DateTimeParseException exception) {
            throw invalidAnswer(field, "必须是 YYYY-MM-DD 格式的有效日期");
        }
    }

    private Object singleChoice(RecruitmentFormSchema.Field field, Object value) {
        Object text = textAnswer(field, value, 100);
        if (text == null) return null;
        if (!field.options().contains(text)) {
            throw invalidAnswer(field, "不是有效选项");
        }
        return text;
    }

    private Object multipleChoice(RecruitmentFormSchema.Field field, Object value) {
        if (value == null) return requiredAnswer(field, null);
        if (!(value instanceof List<?> values)) {
            throw invalidAnswer(field, "必须是选项数组");
        }
        if (values.isEmpty()) return requiredAnswer(field, null);
        Set<String> unique = new HashSet<>();
        List<String> checked = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text) || !field.options().contains(text)) {
                throw invalidAnswer(field, "包含无效选项");
            }
            if (!unique.add(text)) {
                throw invalidAnswer(field, "不能重复选择同一选项");
            }
            checked.add(text);
        }
        return List.copyOf(checked);
    }

    private Object requiredAnswer(RecruitmentFormSchema.Field field, Object value) {
        if (field.required()) {
            throw invalidAnswer(field, "为必填项");
        }
        return value;
    }

    private BusinessException invalidAnswer(RecruitmentFormSchema.Field field, String reason) {
        return validation("“" + field.label() + "”" + reason);
    }

    private String write(Object value, String name) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(name + "无法序列化", exception);
        }
    }

    private static String required(String value, int maxLength, String name) {
        String normalized = optional(value, maxLength, name);
        if (normalized == null || normalized.isEmpty()) {
            throw validation(name + "不能为空");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String name) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
        if (codePoints(normalized) > maxLength) {
            throw validation(name + "最多 " + maxLength + " 个字符");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
