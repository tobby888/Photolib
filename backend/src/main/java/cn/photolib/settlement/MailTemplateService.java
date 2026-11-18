package cn.photolib.settlement;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.mail.MailTemplateStore;
import cn.photolib.mail.MailTemplateStore.Template;
import cn.photolib.mail.MailTemplateStore.View;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MailTemplateService {
    public static final String SETTLEMENT = "SETTLEMENT";
    static final String DEFAULT_SUBJECT = "【{{siteName}}】{{recipientName}}的工作清算单（{{periodFrom}} 至 {{periodTo}}）";
    private static final String RESOURCE = "settlement-default.html";

    private final MailTemplateStore store;

    public Template current() {
        return store.current(SETTLEMENT, DEFAULT_SUBJECT, RESOURCE);
    }

    public View view() {
        return new View(current(), DEFAULT_SUBJECT, defaultHtml(), SettlementMailRenderer.VARIABLES);
    }

    @Transactional
    public View save(String subject, String html, int version, AuthenticatedUser user) {
        store.save(SETTLEMENT, subject, html, version, user);
        return view();
    }

    @Transactional
    public View reset() {
        store.reset(SETTLEMENT);
        return view();
    }

    String defaultHtml() {
        return store.defaultHtml(RESOURCE);
    }
}
