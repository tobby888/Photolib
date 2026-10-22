package cn.photolib.audit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 所有标了 {@link AuditAction} 的接口：给筛选框列出可选的操作名，也给升级前的老记录
 * （没有 {@code action_name}）按 HTTP 方法 + 路径反推出操作名。
 *
 * <p>启动后第一次用到时才扫描映射表：拦截器注册在映射表里，这里再反过来依赖映射表，
 * 提前取会成环。</p>
 */
@Component
public class AuditCatalog {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private volatile List<Entry> entries;

    public AuditCatalog(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    /** 一个接口的一条映射。 */
    public record Entry(String method, PathPattern pattern, String actionName, String resourceType) {
    }

    /** 筛选框里的一个选项。 */
    public record ActionOption(String value, String resourceType, String resourceLabel) {
    }

    public record ResourceOption(String value, String label) {
    }

    List<Entry> entries() {
        List<Entry> current = entries;
        if (current != null) return current;
        List<Entry> scanned = new ArrayList<>();
        RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
        if (mapping != null) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> handler : mapping.getHandlerMethods().entrySet()) {
                AuditAction action = handler.getValue().getMethodAnnotation(AuditAction.class);
                if (action == null || handler.getKey().getPathPatternsCondition() == null) continue;
                Set<RequestMethod> methods = handler.getKey().getMethodsCondition().getMethods();
                for (PathPattern pattern : handler.getKey().getPathPatternsCondition().getPatterns()) {
                    String type = AuditResources.typeOf(pattern.getPatternString());
                    for (RequestMethod method : methods) {
                        scanned.add(new Entry(method.name(), pattern, action.value(), type));
                    }
                }
            }
        }
        // 字面路径优先于带变量的路径（/photos/batch-delete 不能被 /photos/{id} 抢走）。
        scanned.sort(Comparator.comparing(Entry::pattern, PathPattern.SPECIFICITY_COMPARATOR));
        entries = List.copyOf(scanned);
        return entries;
    }

    /** 按方法和实际路径找操作名；找不到返回 {@code null}。 */
    public String describe(String method, String path) {
        if (method == null || path == null) return null;
        PathContainer container;
        try {
            container = PathContainer.parsePath(path);
        } catch (RuntimeException invalid) {
            return null;
        }
        for (Entry entry : entries()) {
            if (entry.method().equals(method) && entry.pattern().matches(container)) return entry.actionName();
        }
        return null;
    }

    /** 筛选框的操作名，按模块分组、模块内按名字排；{@code extra} 是库里出现过但目录里没有的名字。 */
    public List<ActionOption> actions(List<String> extra) {
        Map<String, ActionOption> options = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            options.putIfAbsent(entry.actionName(), new ActionOption(entry.actionName(), entry.resourceType(),
                    AuditResources.label(entry.resourceType())));
        }
        for (String name : extra) {
            options.putIfAbsent(name, new ActionOption(name, AuditResources.UNKNOWN,
                    AuditResources.label(AuditResources.UNKNOWN)));
        }
        List<ActionOption> sorted = new ArrayList<>(options.values());
        sorted.sort(Comparator.comparing(ActionOption::resourceLabel).thenComparing(ActionOption::value));
        return sorted;
    }

    public List<ResourceOption> resourceTypes() {
        Map<String, ResourceOption> options = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            options.putIfAbsent(entry.resourceType(),
                    new ResourceOption(entry.resourceType(), AuditResources.label(entry.resourceType())));
        }
        List<ResourceOption> sorted = new ArrayList<>(options.values());
        sorted.sort(Comparator.comparing(ResourceOption::label));
        return sorted;
    }
}
