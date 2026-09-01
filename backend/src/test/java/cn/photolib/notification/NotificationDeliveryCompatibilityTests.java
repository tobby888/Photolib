package cn.photolib.notification;

import cn.photolib.notification.wecom.WeComGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationDeliveryCompatibilityTests {
    /** 用生产常量而不是复制一份：分隔符里有不可见字符，抄错了测试会假通过。 */
    private static final String SEPARATOR = NotificationService.PAYLOAD_SEPARATOR;

    private final NotificationLogMapper mapper = mock(NotificationLogMapper.class);
    private final MailGateway gateway = mock(MailGateway.class);
    private final WeComGateway wecom = mock(WeComGateway.class);
    private final NotificationService service = new NotificationService(
            mapper,
            mock(UserNotificationMapper.class),
            mock(cn.photolib.user.mapper.UserMapper.class),
            mock(cn.photolib.admin.AdminAlertMapper.class),
            gateway,
            wecom,
            mock(ApplicationEventPublisher.class));

    @Test
    void deliver_shouldSupportLegacyNewlinePayload() {
        NotificationLogEntity log = legacyLog();
        log.setPayloadJson("旧主题\n<p>旧正文</p>");

        service.deliver(log);

        verify(gateway).send("legacy@example.com", "旧主题", "<p>旧正文</p>");
        verify(mapper).updateById(log);
    }

    @Test
    void deliver_forLegacyEmailRow_shouldNotGoToWeCom() {
        service.deliver(legacyLog());

        verify(gateway).send("legacy@example.com", "主题", "<p>正文</p>");
        verifyNoInteractions(wecom);
    }

    /**
     * V38 之前写入的行没有 channel/recipient；迁移会回填 recipient，但从更早的快照恢复
     * 或手工插入的行仍可能只有 email，投递不应该因此把收件人发成 null。
     */
    @Test
    void deliver_forRowWithoutChannel_shouldFallBackToEmailRecipient() {
        NotificationLogEntity log = legacyLog();
        log.setChannel(null);
        log.setRecipient(null);

        service.deliver(log);

        verify(gateway).send("legacy@example.com", "主题", "<p>正文</p>");
        verifyNoInteractions(wecom);
    }

    @Test
    void deliver_forWeComRow_shouldSendWithTheStoredActionPath() {
        NotificationLogEntity log = new NotificationLogEntity();
        log.setChannel("WECOM");
        log.setRecipient("ZhangSan");
        log.setEventType("REQUEST_PUBLISHED");
        log.setActionPath("/requests");
        log.setPayloadJson("新的图片需求" + SEPARATOR + "<p>毕业典礼</p>");
        log.setRetryCount(0);

        service.deliver(log);

        verify(wecom).send("ZhangSan", "新的图片需求", "<p>毕业典礼</p>", "/requests");
        verifyNoInteractions(gateway);
    }

    /**
     * V39 之前的记录没有 action_path。跳到站内信列表由 WeComApiClient 兜底，
     * 投递本身不能因为少一个路径就失败。
     */
    @Test
    void deliver_forWeComRowWithoutActionPath_shouldStillSend() {
        NotificationLogEntity log = new NotificationLogEntity();
        log.setChannel("WECOM");
        log.setRecipient("ZhangSan");
        log.setEventType("REQUEST_PUBLISHED");
        log.setPayloadJson("新的图片需求" + SEPARATOR + "<p>毕业典礼</p>");
        log.setRetryCount(0);

        service.deliver(log);

        verify(wecom).send("ZhangSan", "新的图片需求", "<p>毕业典礼</p>", null);
    }

    @Test
    void parsePayload_shouldPreserveNewlinesInNewFormatBody() {
        NotificationService.MailPayload payload = NotificationService.parsePayload(
                "新主题" + SEPARATOR + "<p>第一行</p>\n<p>第二行</p>");

        assertThat(payload.subject()).isEqualTo("新主题");
        assertThat(payload.html()).isEqualTo("<p>第一行</p>\n<p>第二行</p>");
    }

    private NotificationLogEntity legacyLog() {
        NotificationLogEntity log = new NotificationLogEntity();
        log.setChannel("EMAIL");
        log.setEmail("legacy@example.com");
        log.setRecipient("legacy@example.com");
        log.setEventType("ACCOUNT_CREATED");
        log.setPayloadJson("主题" + SEPARATOR + "<p>正文</p>");
        log.setRetryCount(0);
        return log;
    }
}
