package cn.photolib.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails fast when production-like profiles retain unsafe authentication defaults. */
@Component
@Profile("!local & !test")
@RequiredArgsConstructor
public class DeploymentSecurityValidator {
    private final AuthProperties authProperties;

    @Value("${photolib.bootstrap.enabled:true}")
    private boolean bootstrapEnabled;
    @Value("${photolib.bootstrap.admin-password:}")
    private String adminPassword;

    @PostConstruct
    void validate() {
        validate(authProperties.secureCookie(), bootstrapEnabled, adminPassword);
    }

    static void validate(boolean secureCookie, boolean bootstrapEnabled, String adminPassword) {
        if (!secureCookie) {
            throw new IllegalStateException("非本地环境必须启用 refresh cookie 的 Secure 属性");
        }
        if (bootstrapEnabled && (adminPassword == null || adminPassword.isBlank()
                || "ChangeMe123!".equals(adminPassword))) {
            throw new IllegalStateException("非本地环境必须显式配置安全的管理员初始密码");
        }
    }
}
