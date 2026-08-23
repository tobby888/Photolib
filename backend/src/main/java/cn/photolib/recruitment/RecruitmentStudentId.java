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
        String normalized = compactUpperCase(display);
        if (!FORMAT.matcher(normalized).matches()) {
            throw invalid();
        }
        return new Normalized(display, normalized);
    }

    /**
     * Normalizes an operator's search input the same way stored identifiers are
     * normalized, so a fragment typed with full-width digits, spaces or lower case
     * still matches. Unlike {@link #normalize(String)} this accepts partial input
     * and never throws: a search box is a filter, not a submission, so an
     * unmatchable fragment must simply return no rows rather than an error.
     * Returns {@code null} when nothing searchable remains.
     */
    public static String normalizeSearchFragment(String input) {
        if (input == null) {
            return null;
        }
        String normalized = compactUpperCase(Normalizer.normalize(input, Normalizer.Form.NFKC));
        return normalized.isEmpty() ? null : normalized;
    }

    private static String compactUpperCase(String value) {
        StringBuilder compact = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(compact::appendCodePoint);
        return compact.toString().toUpperCase(Locale.ROOT);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                "学号只能包含字母、数字、下划线或连字符，移除空白后长度须为 2 至 64 位");
    }

    public record Normalized(String display, String value) {
    }
}
