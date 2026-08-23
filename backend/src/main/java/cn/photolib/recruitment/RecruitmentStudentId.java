package cn.photolib.recruitment;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RecruitmentStudentId {
    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9_-]{2,64}");

    private RecruitmentStudentId() {
    }

    public static Normalized normalize(String input) {
        if (input == null) {
            throw invalid();
        }
        String display = Normalizer.normalize(input, Normalizer.Form.NFKC).trim();
        if (display.codePointCount(0, display.length()) > 128) {
            throw invalid();
        }
        StringBuilder compact = new StringBuilder(display.length());
        display.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(compact::appendCodePoint);
        String normalized = compact.toString().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            throw invalid();
        }
        return new Normalized(display, normalized);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                "学号只能包含字母、数字、下划线或连字符，移除空白后长度须为 2 至 64 位");
    }

    public record Normalized(String display, String value) {
    }
}
