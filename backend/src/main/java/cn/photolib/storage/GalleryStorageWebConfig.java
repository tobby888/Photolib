package cn.photolib.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class GalleryStorageWebConfig implements WebMvcConfigurer {
    private final GalleryStorageInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns(
                        "/api/v1/photos", "/api/v1/photos/*", "/api/v1/photos/*/download-url",
                        "/photos", "/photos/*", "/photos/*/download-url")
                .excludePathPatterns(
                        "/api/v1/photos/upload-tickets", "/api/v1/photos/*/complete-upload",
                        "/photos/upload-tickets", "/photos/*/complete-upload");
    }
}
