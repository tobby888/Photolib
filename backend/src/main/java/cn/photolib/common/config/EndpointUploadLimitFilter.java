package cn.photolib.common.config;

import cn.photolib.common.util.UploadSizeLimitExceededException;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 按端点早筛 multipart 请求体的大小：在 Spring 把整个正文落盘之前就按管理员设的上传限额
 * （{@link UploadLimitService}）拒绝，服务层还会按同一个数再判一次。没列在这里的端点只受
 * {@code spring.servlet.multipart.max-file-size} 约束。
 */
@Component
@RequiredArgsConstructor
public class EndpointUploadLimitFilter extends OncePerRequestFilter {
    private static final long MULTIPART_OVERHEAD = 64 * 1024;
    private static final long SCHEDULED_ICON_MULTIPART_OVERHEAD = 1024 * 1024;
    private static final int SCHEDULED_ICON_MAX_COUNT = 20;
    private final UploadLimitService uploadLimits;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    jakarta.servlet.FilterChain chain) throws ServletException, IOException {
        long limit = limitFor(request);
        if (limit < 0) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, limit), response);
        } catch (Exception exception) {
            if (hasLimitCause(exception) && !response.isCommitted()) {
                response.reset();
                reject(response);
                return;
            }
            if (exception instanceof IOException io) throw io;
            if (exception instanceof ServletException servlet) throw servlet;
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new ServletException(exception);
        }
    }

    private long limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && path.endsWith("/branding/icon")) {
            return limit(UploadLimit.BRAND_ICON_MAX_BYTES) + MULTIPART_OVERHEAD;
        }
        if ("PUT".equals(request.getMethod()) && path.endsWith("/branding/scheduled-icons")) {
            return SCHEDULED_ICON_MAX_COUNT * limit(UploadLimit.BRAND_ICON_MAX_BYTES)
                    + SCHEDULED_ICON_MULTIPART_OVERHEAD;
        }
        if ("PUT".equals(request.getMethod()) && path.endsWith("/users/me/avatar")) {
            return limit(UploadLimit.AVATAR_MAX_BYTES) + MULTIPART_OVERHEAD;
        }
        // 三个正文插图端点共用 INLINE_IMAGE_MAX_BYTES 这一个上限；
        // 文档插图的路径形如 /api/v1/docs/{id}/assets，所以要连 /docs/ 一起判，
        // 否则将来任何以 /assets 结尾的端点都会悄悄套上这条插图限制。
        if ("POST".equals(request.getMethod())
                && (path.endsWith("/description-images") || path.endsWith("/notifications/images")
                    || (path.contains("/docs/") && path.endsWith("/assets")))) {
            return limit(UploadLimit.INLINE_IMAGE_MAX_BYTES) + MULTIPART_OVERHEAD;
        }
        // 直接作为文档上传的 PDF。POST /api/v1/docs/pdf 建新的，PUT /api/v1/docs/{id}/pdf 换文件。
        if (("POST".equals(request.getMethod()) && path.endsWith("/docs/pdf"))
                || ("PUT".equals(request.getMethod()) && path.contains("/docs/")
                    && path.endsWith("/pdf"))) {
            return limit(UploadLimit.DOC_PDF_MAX_BYTES) + MULTIPART_OVERHEAD;
        }
        // 教学资料收 PDF/Word/PPT，早筛上限取三种格式上限的最大值，
        // 服务层再按嗅探出的格式细分（各自的 TEACHING_*_MAX_BYTES）。
        // POST /api/v1/teaching 建新的，PUT /api/v1/teaching/{id}/file 换文件。
        // 少了这条，Spring 会先把整个 multipart 正文落盘再交给 PdfUpload 拒绝——
        // 上限只剩 spring.servlet.multipart.max-file-size（1.5 GiB），白等一场还占磁盘。
        if (("POST".equals(request.getMethod()) && path.endsWith("/teaching"))
                || ("PUT".equals(request.getMethod()) && path.contains("/teaching/")
                    && path.endsWith("/file"))) {
            return Math.max(limit(UploadLimit.TEACHING_PDF_MAX_BYTES),
                    Math.max(limit(UploadLimit.TEACHING_WORD_MAX_BYTES),
                            limit(UploadLimit.TEACHING_PPT_MAX_BYTES))) + MULTIPART_OVERHEAD;
        }
        // 文件库上传：POST /api/v1/doc-files。只认集合路径本身，/public/doc-files/** 是下载。
        if ("POST".equals(request.getMethod()) && path.endsWith("/api/v1/doc-files")) {
            return limit(UploadLimit.FILE_MAX_BYTES) + MULTIPART_OVERHEAD;
        }
        if ("POST".equals(request.getMethod()) && path.endsWith("/database-backups/upload")) {
            return limit(UploadLimit.DATABASE_BACKUP_MAX_BYTES) + MULTIPART_OVERHEAD;
        }
        return -1;
    }

    private long limit(UploadLimit limit) {
        return uploadLimits.value(limit);
    }

    private boolean hasLimitCause(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof UploadSizeLimitExceededException) return true;
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"FILE_TOO_LARGE\",\"message\":\"上传内容超过允许大小\",\"details\":[]}");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maximum;
        private ServletInputStream inputStream;

        private LimitedRequest(HttpServletRequest request, long maximum) {
            super(request);
            this.maximum = maximum;
        }

        @Override
        public synchronized ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedServletInputStream(super.getInputStream(), maximum);
            }
            return inputStream;
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maximum;
        private long count;

        private LimitedServletInputStream(ServletInputStream delegate, long maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(long amount) throws UploadSizeLimitExceededException {
            count += amount;
            if (count > maximum) throw new UploadSizeLimitExceededException();
        }
    }
}
