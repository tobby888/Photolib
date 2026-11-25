package cn.photolib.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Fails fast when production-like profiles retain unsafe authentication defaults. */
@Component
@RequiredArgsConstructor
public class DeploymentSecurityValidator {
    private final AuthProperties authProperties;
    private final Environment environment;

    @Value("${photolib.bootstrap.enabled:true}")
    private boolean bootstrapEnabled;
    @Value("${photolib.bootstrap.admin-password:}")
    private String adminPassword;

    @PostConstruct
    void validate() {
        // 不使用 @Profile，避免 Spring AOT 在构建原生镜像时把 profile 条件写死，
        // 导致运行时切 --spring.profiles.active=local 仍触发生产安全检查。
        if (environment.acceptsProfiles(Profiles.of("local | test"))) {
            return;
        }
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
