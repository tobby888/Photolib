package cn.photolib.storage;

import com.aliyun.oss.OSSException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class OssAccessMonitor {
    public static final String CREDENTIAL_ALERT = "OSS_ACCESS_INVALID";
    public static final String CONVERSION_ALERT = "OSS_AVIF_ACCESS_DENIED";
    private static final Set<String> AUTHENTICATION_ERRORS = Set.of(
            "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidSecurityToken",
            "SecurityTokenExpired", "InvalidToken", "ExpiredToken");

    private final StorageProperties properties;
    private final ObjectStorageService storage;
    private final JdbcClient jdbc;

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    public void check() {
        if (properties.local()) return;
        if (!properties.ossConfigured()) {
            alert(CREDENTIAL_ALERT, "OSS 配置不完整。请检查 Bucket、Endpoint 和实例注入的 AccessKey ID / Secret。");
            return;
        }
        try {
            storage.checkAccess();
            alert(CREDENTIAL_ALERT, null);
        } catch (OSSException exception) {
            String code = exception.getErrorCode();
            if (authenticationFailed(code)) {
                reportInvalidCredentials();
            } else if ("AccessDenied".equals(code)) {
                alert(CREDENTIAL_ALERT, "OSS 拒绝访问 Bucket。请检查 Bucket 所属地域及 RAM 用户的访问权限。");
            } else {
                log.warn("OSS Bucket 检查未完成，错误类型 {}", exception.getClass().getSimpleName());
            }
        } catch (IllegalStateException exception) {
            alert(CREDENTIAL_ALERT, "无法访问配置的 OSS Bucket。请检查 Bucket 名称和 Endpoint。");
        } catch (RuntimeException exception) {
            // Transport errors do not establish that the credentials are invalid.
            log.warn("OSS Bucket 检查未完成，错误类型 {}", exception.getClass().getSimpleName());
        }
    }

    public void conversionFailed(RuntimeException failure) {
        if (failure instanceof OSSException exception) {
            if (authenticationFailed(exception.getErrorCode())) reportInvalidCredentials();
            if ("AccessDenied".equals(exception.getErrorCode())) {
                alert(CONVERSION_ALERT, "OSS 拒绝 AVIF 转存。请检查 RAM 的 oss:PostProcessTask、oss:PutBucket 和目标对象的 oss:PutObject 权限。");
            }
        }
    }

    public void conversionSucceeded() {
        alert(CONVERSION_ALERT, null);
    }

    private static boolean authenticationFailed(String code) {
        return code != null && AUTHENTICATION_ERRORS.contains(code);
    }

    private void reportInvalidCredentials() {
        alert(CREDENTIAL_ALERT, "OSS 凭据无效。请检查实例注入的 OSS_ACCESS_KEY_ID 和 OSS_ACCESS_KEY_SECRET。");
    }

    private synchronized void alert(String type, String message) {
        try {
            if (message == null) {
                jdbc.sql("""
                        UPDATE admin_alert SET resolved=1, resolved_at=CURRENT_TIMESTAMP
                        WHERE type=:type AND resolved=0
                        """).param("type", type).update();
                return;
            }
            int updated = jdbc.sql("""
                    UPDATE admin_alert SET message=:message WHERE type=:type AND resolved=0
                    """).param("type", type).param("message", message).update();
            if (updated == 0) {
                jdbc.sql("""
                        INSERT INTO admin_alert (type, message, resource_type, resolved, created_at)
                        VALUES (:type, :message, 'STORAGE', 0, CURRENT_TIMESTAMP)
                        """).param("type", type).param("message", message).update();
            }
        } catch (RuntimeException exception) {
            log.warn("写入 OSS 管理员告警失败，告警类型 {}", type, exception);
        }
    }
}
