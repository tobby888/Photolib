package cn.photolib.common.config;

import cn.photolib.common.util.UploadSizeLimitExceededException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class EndpointUploadLimitFilter extends OncePerRequestFilter {
    private static final long MULTIPART_OVERHEAD = 64 * 1024;
    private static final long SCHEDULED_ICON_MULTIPART_OVERHEAD = 1024 * 1024;
    private static final long BRAND_ICON_MAX_BYTES = 512L * 1024;
    private static final long AVATAR_MAX_BYTES = 1024L * 1024;
    private static final int SCHEDULED_ICON_MAX_COUNT = 20;

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
            return BRAND_ICON_MAX_BYTES + MULTIPART_OVERHEAD;
        }
        if ("PUT".equals(request.getMethod()) && path.endsWith("/branding/scheduled-icons")) {
            return SCHEDULED_ICON_MAX_COUNT * BRAND_ICON_MAX_BYTES
                    + SCHEDULED_ICON_MULTIPART_OVERHEAD;
        }
        if ("PUT".equals(request.getMethod()) && path.endsWith("/users/me/avatar")) {
            return AVATAR_MAX_BYTES + MULTIPART_OVERHEAD;
        }
        if ("POST".equals(request.getMethod())
                && (path.endsWith("/description-images") || path.endsWith("/notifications/images"))) {
            return 5L * 1024 * 1024 + MULTIPART_OVERHEAD;
        }
        return -1;
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
