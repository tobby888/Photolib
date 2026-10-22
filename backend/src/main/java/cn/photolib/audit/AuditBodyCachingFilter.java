package cn.photolib.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * 让操作日志看得到 JSON 请求体：控制器读请求体时顺手留一份副本（最多 {@link #CACHE_LIMIT}
 * 字节），{@link AuditInterceptor} 写日志时从副本里取，打码后存进详情。
 *
 * <p>只包写请求里的 JSON；上传文件走 multipart，那种请求只记文件名和大小，不缓存内容。
 * 超过上限的部分照常交给控制器，只是不再复制。</p>
 */
@Component
public class AuditBodyCachingFilter extends OncePerRequestFilter {
    static final int CACHE_LIMIT = 64 * 1024;
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!WRITES.contains(request.getMethod())) return true;
        if (!request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/")) return true;
        String contentType = request.getContentType();
        if (contentType == null) return true;
        try {
            return !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new CachingRequest(request), response);
    }

    /**
     * Spring 的包装类在请求没声明字符集时会报 ISO-8859-1，消息转换器随即按它解码 JSON，
     * 所有中文都会变成乱码（前端发的就是不带 charset 的 {@code application/json}）。
     * 这里原样转交底层请求的字符集，没声明就是没声明，由转换器按 JSON 的 UTF-8 处理。
     */
    static final class CachingRequest extends ContentCachingRequestWrapper {
        CachingRequest(HttpServletRequest request) {
            super(request, CACHE_LIMIT);
        }

        @Override
        public String getCharacterEncoding() {
            return getRequest().getCharacterEncoding();
        }
    }
}
