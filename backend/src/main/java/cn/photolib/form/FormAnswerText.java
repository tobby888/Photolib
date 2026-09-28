package cn.photolib.form;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把一个存档里的答案变成一段给人看的文字：多选用分隔符连起来，上传文件题显示文件名。
 * 招募导出、招募详情 Markdown 和问卷导出共用，保证三处写出来的是同一个东西。
 */
public final class FormAnswerText {
    private FormAnswerText() {
    }

    /** 空答案返回空字符串。 */
    public static String of(Object answer, String separator) {
        if (answer == null) return "";
        if (answer instanceof List<?> values) {
            return values.stream()
                    .filter(Objects::nonNull)
                    .map(FormAnswerText::item)
                    .filter(text -> !text.isEmpty())
                    .collect(Collectors.joining(separator));
        }
        return item(answer);
    }

    private static String item(Object value) {
        if (value instanceof Map<?, ?> file) {
            Object name = file.get("fileName");
            return name == null ? "" : String.valueOf(name);
        }
        return String.valueOf(value);
    }
}
