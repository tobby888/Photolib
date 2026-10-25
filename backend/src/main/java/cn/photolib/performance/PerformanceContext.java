package cn.photolib.performance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 当前线程在为谁干活：快照里每条 SQL / 存储操作都记下它属于哪个接口，开发看到一条慢 SQL
 * 才知道是哪个页面触发的。
 *
 * <p>请求线程由 {@link cn.photolib.dashboard.RequestMetricsFilter} 在进入时挂上请求，出来时摘掉。
 * 后台线程（图片处理、定时任务）没有请求，快照里留空，靠线程名区分。</p>
 *
 * <p>另有一个"静默"开关：快照记录器自己往库里写事件时打开，那些 INSERT 不再被记成事件，
 * 否则每写一条就多出一条，越记越多。</p>
 */
public final class PerformanceContext {
    private static final ThreadLocal<HttpServletRequest> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SILENT = new ThreadLocal<>();

    private PerformanceContext() {
    }

    public static void enter(HttpServletRequest request) {
        REQUEST.set(request);
    }

    public static void exit() {
        REQUEST.remove();
    }

    static boolean silent() {
        return Boolean.TRUE.equals(SILENT.get());
    }

    /** 在静默状态下执行 {@code action}：期间产生的 SQL 不记入快照。 */
    static void silently(Runnable action) {
        Boolean previous = SILENT.get();
        SILENT.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            if (previous == null) SILENT.remove();
            else SILENT.set(previous);
        }
    }

    /** {@code GET /api/v1/photos/{photoId}}；路由还没匹配上（过滤器阶段）时用打过码的实际路径。 */
    static String operation() {
        HttpServletRequest request = REQUEST.get();
        if (request == null) return null;
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern instanceof String template) return request.getMethod() + " " + template;
            return request.getMethod() + " " + maskPath(request.getRequestURI());
        } catch (RuntimeException ignored) {
            // 请求已经结束、对象被容器回收时读属性会抛异常；归属不明不影响记录本身。
            return null;
        }
    }

    static String requestId() {
        HttpServletRequest request = REQUEST.get();
        if (request == null) return null;
        try {
            Object id = request.getAttribute("requestId");
            return id == null ? null : id.toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 分享链接、本地存储签名之类的令牌就在路径里（{@code /public/shares/{token}}），
     * 原样写进 CSV 等于把能用的令牌交给每个拿到文件的人。16 位以上的段一律换成 {@code {…}}。
     */
    static String maskPath(String path) {
        if (path == null) return null;
        String[] segments = path.split("/", -1);
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) masked.append('/');
            masked.append(segments[i].length() >= 16 ? "{…}" : segments[i]);
        }
        return masked.toString();
    }
}
