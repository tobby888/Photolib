package cn.photolib.notification;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dm.model.v20151123.SingleSendMailRequest;
import com.aliyuncs.profile.DefaultProfile;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DirectMailProperties.class)
public class DirectMailConfig {
    @Bean
    MailGateway mailGateway(DirectMailProperties properties) {
        if (!properties.configured()) {
            return (to, subject, html) -> {
                throw new IllegalStateException("DirectMail 尚未配置");
            };
        }
        IAcsClient client = new DefaultAcsClient(DefaultProfile.getProfile(properties.regionId(),
                properties.accessKeyId(), properties.accessKeySecret()));
        return (to, subject, html) -> {
            SingleSendMailRequest request = new SingleSendMailRequest();
            request.setAccountName(properties.accountName());
            request.setAddressType(1);
            request.setReplyToAddress(false);
            request.setToAddress(to);
            request.setSubject(subject);
            request.setHtmlBody(html);
            try {
                client.getAcsResponse(request);
            } catch (Exception ex) {
                throw new IllegalStateException("邮件投递失败", ex);
            }
        };
    }
}
