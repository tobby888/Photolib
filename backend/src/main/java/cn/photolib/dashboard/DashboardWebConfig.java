package cn.photolib.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class DashboardWebConfig implements WebMvcConfigurer {
    private final ActiveUserInterceptor activeUserInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activeUserInterceptor).addPathPatterns("/api/v1/**");
    }
}
