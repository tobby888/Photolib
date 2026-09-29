package cn.photolib.dashboard;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把每个请求记进 {@link RequestMetrics}：状态码、耗时、请求体和响应体的字节数，以及命中的
 * 路由模板（{@code GET /api/v1/photos/{photoId}}，不是带着具体 id 的地址——否则热门接口榜
 * 会被一张张图片刷满）。
 *
 * <p>排在 Spring Security 之前，被拒掉的 401 / 403 也算进流量：撞登录接口的那种流量正是
 * 管理员想在面板上看到的。</p>
 *
 * <p>响应字节靠包一层计数的输出流 / Writer 数出来，写入原样交给容器，不缓存正文，下载
 * 大文件时不多占内存。异步请求（{@code startAsync}）在异步完成时才记，耗时和字节数都是
 * 全程的。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final RequestMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        CountingResponse counting = new CountingResponse(response);
        AtomicBoolean recorded = new AtomicBoolean();
        try {
            chain.doFilter(request, counting);
        } finally {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { record(request, counting, started, recorded); }
                    @Override public void onTimeout(AsyncEvent event) { record(request, counting, started, recorded); }
                    @Override public void onError(AsyncEvent event) { record(request, counting, started, recorded); }
                    @Override public void onStartAsync(AsyncEvent event) { }
                });
            } else {
                record(request, counting, started, recorded);
            }
        }
    }

    private void record(HttpServletRequest request, CountingResponse response, long started, AtomicBoolean recorded) {
        if (!recorded.compareAndSet(false, true)) return;
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        metrics.record(endpointOf(request), response.getStatus(), latencyMs,
                Math.max(0, request.getContentLengthLong()), response.bytesWritten());
    }

    static String endpointOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String template) || !template.startsWith("/api/")) return null;
        return request.getMethod() + " " + template;
    }

    static final class CountingResponse extends HttpServletResponseWrapper {
        private final AtomicLong bytes = new AtomicLong();
        private ServletOutputStream stream;
        private PrintWriter writer;

        CountingResponse(HttpServletResponse response) {
            super(response);
        }

        long bytesWritten() {
            return bytes.get();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) stream = new CountingStream(super.getOutputStream(), bytes);
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) writer = new PrintWriter(new CountingWriter(super.getWriter(), bytes));
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) writer.flush();
            super.flushBuffer();
        }
    }

    private static final class CountingStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final AtomicLong bytes;

        CountingStream(ServletOutputStream delegate, AtomicLong bytes) {
            this.delegate = delegate;
            this.bytes = bytes;
        }

        @Override public void write(int b) throws IOException { delegate.write(b); bytes.incrementAndGet(); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            bytes.addAndGet(len);
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
    }

    /**
     * 字符按 UTF-8 折算字节（响应一律 UTF-8）。直接交给容器的 Writer，自己不缓冲——
     * 包成 {@code OutputStreamWriter} 会在我们这一层攒着没刷出去的字节。
     */
    private static final class CountingWriter extends Writer {
        private final Writer delegate;
        private final AtomicLong bytes;

        CountingWriter(Writer delegate, AtomicLong bytes) {
            this.delegate = delegate;
            this.bytes = bytes;
        }

        @Override
        public void write(char[] buffer, int off, int len) throws IOException {
            delegate.write(buffer, off, len);
            long size = 0;
            for (int i = off; i < off + len; i++) {
                char c = buffer[i];
                if (c < 0x80) size += 1;
                else if (c < 0x800) size += 2;
                // 代理对的两半各记 2 字节，合起来正好是 UTF-8 的 4 字节。
                else if (Character.isSurrogate(c)) size += 2;
                else size += 3;
            }
            bytes.addAndGet(size);
        }

        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
