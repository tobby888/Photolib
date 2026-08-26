package cn.photolib.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final AuditInterceptor auditInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Login stays audited: without it a password-guessing run leaves no trace
        // anywhere, and "who tried to get in" is exactly what an incident asks.
        // Refresh remains excluded — it fires for every active session on a timer
        // and would bury the log without saying anything a login does not.
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/refresh");
    }
}
