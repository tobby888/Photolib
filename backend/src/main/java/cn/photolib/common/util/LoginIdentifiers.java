package cn.photolib.common.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 登录标识（账号 / 邮箱）的统一清洗。登录查找、邮箱入库、登录失败计数都走这里，
 * 存进去的和拿来比的才是同一种写法。
 *
 * <p>只靠 {@link String#trim()} 不够：它只认 ASCII 控制符和半角空格。用户实际输进来的
 * 常见"看着一样、其实不一样"的写法有：
 * <ul>
 *   <li>中文输入法全角状态下的 {@code ＠}、{@code ．}、全角字母数字，和首尾的全角空格 U+3000；</li>
 *   <li>从微信 / QQ / 网页复制来的不换行空格 U+00A0、零宽空格 U+200B、BOM U+FEFF；</li>
 *   <li>中文输入法把 {@code .} 打成句号 {@code 。}（{@code qq。com}）。</li>
 * </ul>
 * NFKC 把全角字符和各种空格折回半角，格式类字符（零宽、BOM、软连字符）直接去掉，
 * 句号变体按 IDNA 的做法当成点。
 */
public final class LoginIdentifiers {
    private LoginIdentifiers() {
    }

    /**
     * 去掉不可见字符和首尾空白，并做 NFKC 折叠，不改大小写。账号按这个形式查找。
     *
     * @return 清洗后的标识；输入为 {@code null} 时返回空串
     */
    public static String clean(String raw) {
        if (raw == null) return "";
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder visible = new StringBuilder(folded.length());
        folded.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .forEach(visible::appendCodePoint);
        return stripUnicodeWhitespace(visible.toString());
    }

    /**
     * 邮箱的规范形式：{@link #clean} 之后把句号变体换成点、转小写；空白输入返回 {@code null}。
     * 入库和按邮箱登录都必须用它。
     */
    public static String email(String raw) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty()) return null;
        return cleaned.replace('。', '.').toLowerCase(Locale.ROOT);
    }

    /** NFKC 之后 U+3000、U+00A0 都已是半角空格，这里再兜住其余 Unicode 空白。 */
    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBlank(value.charAt(start))) start++;
        while (end > start && isBlank(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c);
    }
}
