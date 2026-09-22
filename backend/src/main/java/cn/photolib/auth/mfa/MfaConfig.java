package cn.photolib.auth.mfa;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MfaProperties.class)
public class MfaConfig implements WebMvcConfigurer {
    private final StepUpInterceptor stepUpInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stepUpInterceptor).addPathPatterns("/api/v1/**");
    }
}
