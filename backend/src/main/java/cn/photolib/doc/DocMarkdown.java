package cn.photolib.doc;

/** 文档正文的纯文本投影，用于目录树上的一句话摘要。 */
public final class DocMarkdown {
    private DocMarkdown() {
    }

    /**
     * 摘要只用于列表展示，所以不追求还原 Markdown 语义，只求"看起来像人写的一句话"：
     * 图片换成占位符，链接留文字，标题、引用和强调符号去掉，空白折叠成单空格。
     * 前端 {@code markdownExcerpt} 做的是同一件事——两边保持一致，
     * 否则同一篇文档在读者目录和编辑器里会显示成两句不同的话。
     */
    public static String summary(String content, int maxChars) {
        if (content == null) return null;
        String text = content
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "[图片]")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")
                .replaceAll("[*_~`]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isEmpty()) return null;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
