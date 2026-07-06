package cn.photolib.notification;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationDeliveryCompatibilityTests {

    @Test
    void deliver_shouldSupportLegacyNewlinePayload() {
        NotificationLogMapper mapper = mock(NotificationLogMapper.class);
        MailGateway gateway = mock(MailGateway.class);
        NotificationService service = new NotificationService(
                mapper,
                mock(UserNotificationMapper.class),
                mock(cn.photolib.user.mapper.UserMapper.class),
                mock(cn.photolib.admin.AdminAlertMapper.class),
                gateway,
                mock(ApplicationEventPublisher.class));
        NotificationLogEntity log = new NotificationLogEntity();
        log.setEmail("legacy@example.com");
        log.setPayloadJson("旧主题\n<p>旧正文</p>");
        log.setRetryCount(0);

        service.deliver(log);

        verify(gateway).send("legacy@example.com", "旧主题", "<p>旧正文</p>");
        verify(mapper).updateById(log);
    }

    @Test
    void parsePayload_shouldPreserveNewlinesInNewFormatBody() {
        NotificationService.MailPayload payload = NotificationService.parsePayload(
                "新主题\n\u0000MAIL_BODY\u0000\n<p>第一行</p>\n<p>第二行</p>");

        org.assertj.core.api.Assertions.assertThat(payload.subject()).isEqualTo("新主题");
        org.assertj.core.api.Assertions.assertThat(payload.html())
                .isEqualTo("<p>第一行</p>\n<p>第二行</p>");
    }
}
