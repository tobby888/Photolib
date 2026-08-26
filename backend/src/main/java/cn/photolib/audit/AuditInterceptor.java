package cn.photolib.audit;

import cn.photolib.auth.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern RESOURCE_ID = Pattern.compile("(?:[0-9]+|[0-9A-HJKMNP-TV-Z]{26})");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    /** Request attribute a handler can set to enrich this request's audit detail. */
    public static final String DETAIL_ATTRIBUTE = "auditDetail";
    private final AuditLogMapper mapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("requestId", UUID.randomUUID().toString());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!WRITES.contains(request.getMethod())
                || request.getRequestURI().contains("/auth/refresh")) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = auth != null && auth.getPrincipal() instanceof AuthenticatedUser user ? user.id() : null;
        AuditLogEntity log = new AuditLogEntity();
        log.setOperatorId(userId);
        log.setAction(request.getMethod());
        String[] segments = request.getRequestURI().split("/");
        // Anonymous endpoints live one level deeper (/api/v1/public/<resource>/<id>/…),
        // so without this shift every public write would be filed as PUBLIC/null.
        int typeIndex = segments.length > 3 && "public".equals(segments[3]) ? 4 : 3;
        log.setResourceType(segments.length > typeIndex ? segments[typeIndex].toUpperCase() : "UNKNOWN");
        int idIndex = typeIndex + 1;
        String resourceId = segments.length > idIndex && RESOURCE_ID.matcher(segments[idIndex]).matches()
                ? segments[idIndex] : null;
        if (resourceId == null && segments.length > 4 && "users".equals(segments[3])
                && "me".equals(segments[4]) && userId != null) {
            resourceId = userId.toString();
        }
        log.setResourceId(resourceId);
        log.setRequestId(String.valueOf(request.getAttribute("requestId")));
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("path", request.getRequestURI());
            detail.put("status", response.getStatus());
            // Handlers may add context the URL cannot carry — the account a login
            // attempt targeted, for instance. Never credentials or request bodies.
            if (request.getAttribute(DETAIL_ATTRIBUTE) instanceof Map<?, ?> extra) {
                extra.forEach((key, value) -> detail.put(String.valueOf(key), value));
            }
            log.setDetailJson(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.setDetailJson("{}");
            AuditInterceptor.log.warn("审计日志详情序列化失败", e);
        }
        log.setIpAddress(request.getRemoteAddr());
        log.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(log);
        } catch (Exception e) {
            AuditInterceptor.log.warn("审计日志写入失败: {} {}", request.getMethod(), request.getRequestURI(), e);
        }
    }
}
