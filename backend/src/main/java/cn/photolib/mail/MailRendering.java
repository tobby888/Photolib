package cn.photolib.mail;

import cn.photolib.admin.BrandSnapshotService.BrandSnapshot;
import org.springframework.web.util.HtmlUtils;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MailRendering {
    public static final String LOGO_CONTENT_ID = "brand-logo";
    private static final Pattern SECTION = Pattern.compile("\\{\\{#\\s*([A-Za-z]+)\\s*}}(.*?)\\{\\{/\\s*\\1\\s*}}", Pattern.DOTALL);
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*}}");

    private MailRendering() {
    }

    public static Map<String, String> branding(BrandSnapshot brand, boolean preview) {
        String logoSrc = preview ? dataUri(brand) : "cid:" + LOGO_CONTENT_ID;
        Map<String, String> values = new HashMap<>();
        values.put("siteName", brand.title());
        values.put("siteSlogan", brand.slogan());
        values.put("logoSrc", logoSrc);
        values.put("logo", "<img src=\"" + escape(logoSrc) + "\" width=\"44\" height=\"44\" alt=\"" + escape(brand.title())
                + "\" style=\"display:block;border:0;outline:none;border-radius:10px;width:44px;height:44px;\">");
        return values;
    }

    public static List<OutgoingMail.InlineImage> images(BrandSnapshot brand) {
        return List.of(new OutgoingMail.InlineImage(LOGO_CONTENT_ID, brand.icon(), brand.iconContentType()));
    }

    public static String inlineLogo(String html, BrandSnapshot brand) {
        return html == null ? "" : html.replace("cid:" + LOGO_CONTENT_ID, dataUri(brand));
    }

    public static String subject(String template, Map<String, String> values, Set<String> htmlVariables) {
        String subject = fill(template, values, false, htmlVariables).replaceAll("[\\r\\n]+", " ").trim();
        return subject.length() > MailTemplateStore.MAX_SUBJECT ? subject.substring(0, MailTemplateStore.MAX_SUBJECT) : subject;
    }

    public static String fill(String template, Map<String, String> values, boolean html, Set<String> htmlVariables) {
        if (template == null) return "";
        Matcher sections = SECTION.matcher(template);
        StringBuilder withSections = new StringBuilder();
        while (sections.find()) {
            String value = values.get(sections.group(1));
            sections.appendReplacement(withSections,
                    Matcher.quoteReplacement(value == null || value.isBlank() ? "" : sections.group(2)));
        }
        sections.appendTail(withSections);
        Matcher variables = VARIABLE.matcher(withSections);
        StringBuilder out = new StringBuilder();
        while (variables.find()) {
            String name = variables.group(1);
            String value = values.get(name);
            String replacement;
            if (value == null) replacement = variables.group(0);
            else if (!html) replacement = htmlVariables.contains(name) ? "" : value;
            else replacement = htmlVariables.contains(name) ? value : escape(value);
            variables.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        variables.appendTail(out);
        return out.toString();
    }

    public static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }

    private static String dataUri(BrandSnapshot brand) {
        return "data:" + brand.iconContentType() + ";base64," + Base64.getEncoder().encodeToString(brand.icon());
    }
}
