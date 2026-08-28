package cn.photolib.featured;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * 征集要求的受控富文本。
 *
 * <p>和管理消息一样，服务端必须自己清洗一遍，不能只依赖前端编辑器：编辑器产出的
 * HTML 只是"通常安全"，接口本身仍然接受任意字符串。清洗之外还额外收紧了图片来源，
 * 只允许编辑器上传得到的站内说明图片路径，避免要求正文变成外链跟踪像素的载体。</p>
 */
final class FeaturedRequirementHtml {
    /** 说明图片的稳定访问路径，26 位 Crockford Base32 的 PublicId。 */
    private static final String IMAGE_SOURCE = "^/api/v1/description-images/[0-9A-HJKMNP-TV-Z]{26}$";

    private static final Safelist REQUIREMENT = Safelist.basic()
            .addTags("p", "div", "h1", "h2", "h3", "img")
            .addAttributes("img", "src", "alt", "title")
            .preserveRelativeLinks(true);

    private FeaturedRequirementHtml() {
    }

    /** 清洗后的 HTML；输入为空时返回空串。 */
    static String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        String cleaned = Jsoup.clean(html, "", REQUIREMENT,
                new Document.OutputSettings().prettyPrint(false));
        Document document = Jsoup.parseBodyFragment(cleaned);
        document.select("img").removeIf(image -> !image.attr("src").matches(IMAGE_SOURCE));
        return document.body().html();
    }

    /**
     * 清洗后 HTML 的纯文本投影，块级元素之间保留换行。列表摘要、关键词检索和
     * Word 文档正文都读这一份，避免各自再解析一次 HTML 得到不同结果。
     */
    static String toPlainText(String safeHtml) {
        if (safeHtml == null || safeHtml.isBlank()) return "";
        Document document = Jsoup.parseBodyFragment(safeHtml);
        document.outputSettings().prettyPrint(false);
        document.select("br").before("\\n");
        document.select("p, div, h1, h2, h3, li").before("\\n");
        String text = document.body().text().replace("\\n", "\n");
        return text.lines().map(String::strip).filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    /** 正文是否真的有内容：纯文本为空且没有图片时视为空要求。 */
    static boolean isBlank(String safeHtml) {
        return toPlainText(safeHtml).isEmpty() && !safeHtml.contains("<img");
    }
}
