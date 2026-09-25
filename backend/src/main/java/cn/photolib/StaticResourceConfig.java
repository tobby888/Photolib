package cn.photolib;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.EncodedResourceResolver;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 打进 JAR 的前端（{@code classpath:/static}）的缓存策略。
 *
 * <p>不配的话，Spring Security 会给每个没自带缓存头的响应补上
 * {@code no-cache, no-store, max-age=0, must-revalidate}，包括带内容哈希的 JS/CSS：
 * 浏览器每次打开页面都要把整个首屏（未压缩近 1 MB）重新下一遍，
 * {@code build/chunkStrategy.ts} 为长期缓存拆出来的 vendor chunk 也白拆了。
 * 这里自己写上缓存头，Security 看到已有 {@code Cache-Control} 就不再覆盖。
 *
 * <ul>
 *   <li>{@code /assets/**}：Vite 产物，文件名带内容哈希，内容变了名字就变，所以一年、
 *       {@code immutable}。同时优先返回构建时预压缩好的 {@code .br} / {@code .gz}
 *       （{@code build/precompress.ts}），Tomcat 不用每次现压。</li>
 *   <li>{@code /index.html}：引用着这一版的哈希文件名，必须每次回源确认，否则发版后
 *       旧入口会去要已经不存在的 chunk。用 {@code no-cache} 而不是 {@code no-store}：
 *       内容没变时只回一个 304。校验值取内容摘要而不是 Last-Modified——JAR 里条目的
 *       修改时间不可靠（可复现构建会把它钉成固定值），内容变了它却没变就会 304 出旧入口。</li>
 * </ul>
 *
 * <p>找不到的文件照常 404，不带这里的缓存头：旧页面去要被替换掉的 chunk 时，拿到的
 * 404 不会被缓存成"永久不存在"，{@code src/main.tsx} 的 {@code vite:preloadError} 兜底
 * 整页重载后就能拿到新入口。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    static final CacheControl HASHED_ASSETS = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    static final CacheControl ENTRY_DOCUMENT = CacheControl.noCache();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(HASHED_ASSETS)
                // 不缓存解析结果：缓存的键按原始 Accept-Encoding 算，会绕过下面对请求头的整理。
                // 哈希文件本身一年不回源，这一步每个文件每个用户基本只走一次。
                .resourceChain(false)
                .addResolver(new BrotliFirstEncodedResourceResolver())
                .addResolver(new PathResourceResolver());
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(ENTRY_DOCUMENT)
                .setUseLastModified(false)
                .setEtagGenerator(StaticResourceConfig::contentEtag);
    }

    private static String contentEtag(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return DigestUtils.md5DigestAsHex(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Spring 按请求头里的先后挑编码，而浏览器发的是 {@code gzip, deflate, br, zstd}——照原样
     * 挑，永远轮不到更小的 br。这里把客户端接受的 br 挪到最前面，其余编码顺序不动。
     *
     * <p>Spring 解析时还会把 {@code ;q=...} 整个丢掉，于是 {@code br;q=0}（明确拒绝）也会被
     * 当成接受。所以 q=0 的编码在交给它之前就从请求头里去掉。
     */
    static final class BrotliFirstEncodedResourceResolver extends EncodedResourceResolver {
        @Override
        protected @Nullable Resource resolveResourceInternal(@Nullable HttpServletRequest request, String requestPath,
                                                             List<? extends Resource> locations,
                                                             ResourceResolverChain chain) {
            return super.resolveResourceInternal(request == null ? null : brotliFirst(request),
                    requestPath, locations, chain);
        }
    }

    static HttpServletRequest brotliFirst(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.ACCEPT_ENCODING);
        if (!StringUtils.hasText(header)) {
            return request;
        }
        List<String> brotli = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String token : StringUtils.tokenizeToStringArray(header, ",")) {
            String[] parts = token.toLowerCase(Locale.ROOT).replace(" ", "").split(";");
            if (refused(parts)) {
                continue;
            }
            (parts[0].equals("br") ? brotli : others).add(token);
        }
        brotli.addAll(others);
        String reordered = String.join(", ", brotli);
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name) ? reordered : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(reordered)) : super.getHeaders(name);
            }
        };
    }

    /** {@code q=0}、{@code q=0.0}、{@code q=0.000} 都是"不接受"。 */
    private static boolean refused(String[] parts) {
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].matches("q=0(\\.0{0,3})?")) {
                return true;
            }
        }
        return false;
    }
}
