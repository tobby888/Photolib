package cn.photolib.common.util;

/** Prevents exported text from being interpreted as a spreadsheet formula. */
public final class SpreadsheetText {
    private SpreadsheetText() {
    }

    public static String safe(Object value) {
        String text = value == null ? "" : value.toString();
        return !text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }
}
