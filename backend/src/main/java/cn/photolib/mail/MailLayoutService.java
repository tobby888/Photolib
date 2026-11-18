package cn.photolib.mail;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.mail.MailTemplateStore.Template;
import cn.photolib.mail.MailTemplateStore.View;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MailLayoutService {
    private static final String CODE = "SHARED_LAYOUT";
    private static final String RESOURCE = "shared-layout.html";
    private static final String SLOT = "{{content}}";
    private static final Pattern FULL_DOCUMENT = Pattern.compile("(?is)^\\s*(?:<!doctype\\s+html\\b|<html\\b)");
    private static final Pattern TABLE_ROWS = Pattern.compile("(?is)^\\s*<tr\\b");
    static final Map<String, String> VARIABLES = Map.of(
            "siteName", "品牌名称", "siteSlogan", "品牌标语", "logo", "品牌图标（内嵌图片）",
            "logoSrc", "品牌图标地址", "content", "邮件正文（必填，放在表格的行位置）");

    private final MailTemplateStore store;

    public Template current() {
        return store.current(CODE, "", RESOURCE);
    }

    public View view() {
        return new View(current(), "", store.defaultHtml(RESOURCE), VARIABLES);
    }

    @Transactional
    public View save(String html, int version, AuthenticatedUser user) {
        validate(html);
        store.save(CODE, CODE, html, version, user);
        return view();
    }

    @Transactional
    public View reset() {
        store.reset(CODE);
        return view();
    }

    public static String wrap(String layout, String content) {
        String rows = TABLE_ROWS.matcher(content).find() ? content : "<tr><td>" + content + "</td></tr>";
        return layout.replace(SLOT, rows);
    }

    public static String settlement(String layout, String content) {
        return FULL_DOCUMENT.matcher(content).find() ? content : wrap(layout, content);
    }

    static void validate(String html) {
        if (html == null || html.indexOf(SLOT) < 0 || html.indexOf(SLOT) != html.lastIndexOf(SLOT)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "公共模板必须包含且仅包含一个 {{content}} 正文占位符");
        }
    }
}
