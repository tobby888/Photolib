package cn.photolib.performance;

import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditResources;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 性能分析的总入口：一次功能调用（HTTP 接口、图片处理）结束时交到这里，再分发给三处——
 * 实时窗口（{@link LiveCallBuffer}）、一周分位数（{@link LatencyStatsStore}）、
 * 正在进行的性能快照（{@link SnapshotRecorder}，没开就跳过）。
 *
 * <p>功能名取接口上的 {@code @AuditAction}（「登录」「上传图片」…），没有标的读接口显示路由模板；
 * 模块名与操作日志同一套（{@link AuditResources}）。</p>
 *
 * <p>性能分析自己的接口（{@code /api/v1/performance/**}）不进实时窗口和分位数：面板每几秒轮询
 * 一次，算进去就成了"观察者在看自己"。快照里照样记，它是真实负载。</p>
 */
@Component
public class PerformanceMonitor {
    public static final String KIND_REQUEST = "REQUEST";
    public static final String KIND_IMAGE = "IMAGE";
    public static final String IMAGE_THUMBNAIL = "THUMBNAIL";
    public static final String IMAGE_COMPRESS = "COMPRESS";
    public static final String IMAGE_MODULE = "图片处理";
    private static final String SELF_PREFIX = "/api/v1/performance";

    private final LiveCallBuffer live;
    private final LatencyStatsStore stats;
    private final SnapshotRecorder snapshots;
    private final Clock clock;
    private final ConcurrentMap<Method, Optional<String>> labels = new ConcurrentHashMap<>();

    public PerformanceMonitor(LatencyStatsStore stats, SnapshotRecorder snapshots, Clock clock,
                              PerformanceProperties properties) {
        this.live = new LiveCallBuffer(properties.liveCapacity());
        this.stats = stats;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    LiveCallBuffer live() {
        return live;
    }

    public static String imageLabel(String key) {
        return switch (key) {
            case IMAGE_THUMBNAIL -> "生成缩略图";
            case IMAGE_COMPRESS -> "压缩原图";
            default -> key;
        };
    }

    /**
     * 一次 HTTP 请求结束。{@code endpoint} 是「方法 + 路由模板」，静态资源和没匹配上路由的请求为 null，
     * 它们只进快照（用打过码的实际路径）。
     */
    public void recordRequest(HttpServletRequest request, String endpoint, int status, long nanos) {
        long micros = nanos / 1_000;
        long now = clock.millis();
        if (snapshots.active()) {
            String target = endpoint != null ? endpoint.substring(endpoint.indexOf(' ') + 1)
                    : PerformanceContext.maskPath(request.getRequestURI());
            snapshots.record(new SnapshotRecorder.Event(now, SnapshotRecorder.CATEGORY_HTTP, request.getMethod(),
                    micros, status < 500, target, (long) status, labelOf(request), requestId(request),
                    Thread.currentThread().getName(), null));
        }
        if (endpoint == null) return;
        String template = endpoint.substring(endpoint.indexOf(' ') + 1);
        if (template.startsWith(SELF_PREFIX)) return;
        String label = labelOf(request);
        String module = AuditResources.label(AuditResources.typeOf(template));
        live.add(new LiveCallBuffer.Call(now, LiveCallBuffer.Kind.REQUEST, endpoint, label, module, status, micros,
                null));
        stats.record(KIND_REQUEST, endpoint, label, module, micros, status < 500, now);
    }

    /** 一次图片处理结束（生成缩略图 / 压缩原图），不论是新上传、重建预览还是编辑图片触发的。 */
    public void recordImage(String key, long nanos, boolean success, String detail) {
        long micros = nanos / 1_000;
        long now = clock.millis();
        String label = imageLabel(key);
        live.add(new LiveCallBuffer.Call(now, LiveCallBuffer.Kind.IMAGE, key, label, IMAGE_MODULE,
                success ? 200 : 500, micros, detail));
        stats.record(KIND_IMAGE, key, label, IMAGE_MODULE, micros, success, now);
        if (snapshots.active()) {
            snapshots.record(new SnapshotRecorder.Event(now, SnapshotRecorder.CATEGORY_IMAGE, key, micros, success,
                    detail, null, PerformanceContext.operation(), PerformanceContext.requestId(),
                    Thread.currentThread().getName(), null));
        }
    }

    private String labelOf(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod method)) return null;
        return labels.computeIfAbsent(method.getMethod(), key -> {
            AuditAction action = method.getMethodAnnotation(AuditAction.class);
            return Optional.ofNullable(action == null ? null : action.value());
        }).orElse(null);
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute("requestId");
        return id == null ? null : id.toString();
    }
}
