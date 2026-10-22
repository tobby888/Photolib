package cn.photolib.audit;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.error.GlobalExceptionHandler;
import cn.photolib.common.util.ClientIp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 操作日志。记什么：所有写接口（标了 {@link AuditSkip} 的除外），以及标了 {@link AuditAction}
 * 的读接口（导出、下载备份）。每条记下谁、做了什么（操作名）、对谁（模块 + ID + 当时的名字）、
 * 成功与否和失败原因、打码后的请求参数、上传的文件、IP、设备和耗时。
 *
 * <p>写日志失败只打服务端日志，绝不影响请求本身。</p>
 */
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern RESOURCE_ID = Pattern.compile("(?:[0-9]+|[0-9A-HJKMNP-TV-Z]{26})");
    /** 处理器附加的详情里可能有日期时间，按 ISO 字符串写出（项目里的 Jackson 2 没带 JSR-310 模块）。 */
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(TemporalAccessor.class, ToStringSerializer.instance));
    /** Request attribute a handler can set to enrich this request's audit detail. */
    public static final String DETAIL_ATTRIBUTE = "auditDetail";
    /** 这个请求要记日志；值是操作名（没标 {@link AuditAction} 的写接口为空串）。 */
    static final String AUDITED_ATTRIBUTE = AuditInterceptor.class.getName() + ".audited";
    private static final String STARTED_ATTRIBUTE = AuditInterceptor.class.getName() + ".started";
    private static final String TARGET_NAME_ATTRIBUTE = AuditInterceptor.class.getName() + ".targetName";
    static final int MAX_USER_AGENT = 512;
    static final int MAX_ERROR_MESSAGE = 500;
    private static final List<String> NAME_PROPERTIES = List.of("title", "name", "displayName", "fileName");

    private final AuditLogMapper mapper;
    private final AuditTargets targets;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("requestId", UUID.randomUUID().toString());
        String actionName = actionName(request, handler);
        if (actionName == null) return true;
        request.setAttribute(AUDITED_ATTRIBUTE, actionName);
        request.setAttribute(STARTED_ATTRIBUTE, System.nanoTime());
        // 在处理之前查对象名：删除之后就查不到了，改名之后查到的是新名字。
        Target target = target(request);
        if (target != null) {
            String name = targets.nameOf(target.segment(), target.value());
            if (name != null) request.setAttribute(TARGET_NAME_ATTRIBUTE, name);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!(request.getAttribute(AUDITED_ATTRIBUTE) instanceof String actionName)) return;
        try {
            mapper.insert(entry(request, response, actionName, ex));
        } catch (Exception e) {
            AuditInterceptor.log.warn("审计日志写入失败: {} {}", request.getMethod(), request.getRequestURI(), e);
        }
    }

    /** {@code null} 表示不记；空串表示要记但接口没有标操作名。 */
    static String actionName(HttpServletRequest request, Object handler) {
        boolean write = WRITES.contains(request.getMethod());
        if (handler instanceof HandlerMethod method) {
            if (method.hasMethodAnnotation(AuditSkip.class)) return null;
            AuditAction action = method.getMethodAnnotation(AuditAction.class);
            if (action != null) return action.value();
        }
        return write ? "" : null;
    }

    private AuditLogEntity entry(HttpServletRequest request, HttpServletResponse response,
                                 String actionName, Exception ex) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = auth != null && auth.getPrincipal() instanceof AuthenticatedUser user ? user.id() : null;
        AuditLogEntity entry = new AuditLogEntity();
        entry.setOperatorId(userId);
        entry.setAction(request.getMethod());
        entry.setActionName(actionName.isEmpty() ? null : actionName);
        String uri = request.getRequestURI();
        entry.setResourceType(AuditResources.typeOf(uri));

        Target target = target(request);
        String resourceId = target != null ? target.value() : legacyResourceId(uri);
        String resourceName = request.getAttribute(TARGET_NAME_ATTRIBUTE) instanceof String name ? name : null;
        if (resourceId != null && "me".equals(resourceId)) resourceId = null;
        if (resourceId == null && uri.contains("/users/me") && userId != null) {
            resourceId = userId.toString();
            resourceName = targets.nameOf("users", resourceId);
        }
        Object created = request.getAttribute(AuditResponseAdvice.RESPONSE_DATA_ATTRIBUTE);
        if (resourceId == null && created != null) {
            Object id = property(created, "id");
            if (id instanceof Number || id instanceof String) resourceId = String.valueOf(id);
        }
        if (resourceName == null && created != null && resourceId != null
                && resourceId.equals(String.valueOf(property(created, "id")))) {
            resourceName = createdName(created);
        }
        entry.setResourceId(clip(resourceId, 64));
        entry.setResourceName(AuditTargets.clip(resourceName));
        entry.setRequestId(String.valueOf(request.getAttribute("requestId")));

        int status = response.getStatus();
        if (ex != null && status < 400) status = 500;
        entry.setHttpStatus(status);
        entry.setSuccess(status < 400);
        Object errorCode = request.getAttribute(GlobalExceptionHandler.ERROR_CODE_ATTRIBUTE);
        Object errorMessage = request.getAttribute(GlobalExceptionHandler.ERROR_MESSAGE_ATTRIBUTE);
        if (errorCode == null && ex != null) {
            errorCode = ErrorCode.INTERNAL_ERROR.name();
            errorMessage = "服务内部错误（" + ex.getClass().getSimpleName() + "）";
        }
        if (status >= 400) {
            entry.setErrorCode(errorCode == null ? null : clip(errorCode.toString(), 64));
            entry.setErrorMessage(errorMessage == null ? null : clip(errorMessage.toString(), MAX_ERROR_MESSAGE));
        }

        entry.setDetailJson(detailJson(request, status));
        entry.setIpAddress(ClientIp.of(request));
        entry.setUserAgent(clip(request.getHeader("User-Agent"), MAX_USER_AGENT));
        if (request.getAttribute(STARTED_ATTRIBUTE) instanceof Long started) {
            long millis = (System.nanoTime() - started) / 1_000_000;
            entry.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, millis)));
        }
        entry.setCreatedAt(LocalDateTime.now());
        return entry;
    }

    private String detailJson(HttpServletRequest request, int status) {
        String path = request.getRequestURI();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", path);
        detail.put("status", status);
        try {
            Map<String, Object> parameters = AuditPayloads.parameters(request.getParameterMap(), path);
            if (!parameters.isEmpty()) detail.put("params", parameters);
            ContentCachingRequestWrapper cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
            if (cached != null) {
                JsonNode body = AuditPayloads.body(cached.getContentAsByteArray(), request.getContentLengthLong(), path);
                if (body != null) detail.put("body", body);
            }
            List<Map<String, Object>> files = files(request);
            if (!files.isEmpty()) detail.put("files", files);
        } catch (RuntimeException e) {
            AuditInterceptor.log.debug("审计日志请求参数整理失败", e);
        }
        // Handlers may add context the URL cannot carry — the account a login
        // attempt targeted, for instance. Never credentials or request bodies.
        if (request.getAttribute(DETAIL_ATTRIBUTE) instanceof Map<?, ?> extra) {
            extra.forEach((key, value) -> detail.put(String.valueOf(key), value));
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            AuditInterceptor.log.warn("审计日志详情序列化失败", e);
            detail.keySet().retainAll(Set.of("path", "status"));
            try {
                return objectMapper.writeValueAsString(detail);
            } catch (Exception again) {
                return "{}";
            }
        }
    }

    private static List<Map<String, Object>> files(HttpServletRequest request) {
        MultipartRequest multipart = WebUtils.getNativeRequest(request, MultipartRequest.class);
        if (multipart == null) return List.of();
        List<Map<String, Object>> files = new ArrayList<>();
        multipart.getMultiFileMap().forEach((field, parts) -> {
            for (MultipartFile part : parts) {
                if (files.size() >= AuditPayloads.MAX_ARRAY) return;
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("field", field);
                file.put("name", AuditPayloads.clip(part.getOriginalFilename()));
                file.put("size", part.getSize());
                file.put("contentType", part.getContentType());
                files.add(file);
            }
        });
        return files;
    }

    /** 对象在 URL 里的位置：第一个路径变量，和紧挨着它前面的那一段（{@code /projects/{id}} → projects）。 */
    record Target(String segment, String value) {
    }

    @SuppressWarnings("unchecked")
    static Target target(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(pattern instanceof String text) || !(variables instanceof Map<?, ?> values) || values.isEmpty()) {
            return null;
        }
        String[] segments = text.split("/");
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (!segment.startsWith("{") || !segment.endsWith("}")) continue;
            String name = segment.substring(1, segment.length() - 1);
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(0, colon);
            Object value = ((Map<String, Object>) values).get(name);
            if (value == null) return null;
            return new Target(segments[i - 1], value.toString());
        }
        return null;
    }

    /** 没有映射模式可用时（不是控制器处理的请求）沿用老办法：模块后面那段像 ID 就当 ID。 */
    private static String legacyResourceId(String uri) {
        String[] segments = uri.split("/");
        int typeIndex = segments.length > 3 && "public".equals(segments[3]) ? 4 : 3;
        int idIndex = typeIndex + 1;
        return segments.length > idIndex && RESOURCE_ID.matcher(segments[idIndex]).matches()
                ? segments[idIndex] : null;
    }

    private static String createdName(Object data) {
        for (String property : NAME_PROPERTIES) {
            if (property(data, property) instanceof String name && !name.isBlank()) return name;
        }
        return null;
    }

    /** 读返回值上的一个属性：record 的访问器、getter，或者 Map 的键。读不到就是 {@code null}。 */
    static Object property(Object data, String name) {
        if (data == null || data instanceof Collection<?> || data instanceof CharSequence
                || data instanceof Number || data.getClass().isArray()) return null;
        if (data instanceof Map<?, ?> map) return map.get(name);
        for (String candidate : List.of(name, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1))) {
            try {
                Method method = data.getClass().getMethod(candidate);
                if (method.getParameterCount() != 0) continue;
                if (!method.trySetAccessible()) continue;
                return method.invoke(data);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 没有这个属性，换下一种写法。
            }
        }
        return null;
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
