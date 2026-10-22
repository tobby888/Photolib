package cn.photolib.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    /** 失败请求的错误码和给用户看的原因，操作日志从这两个请求属性里取。 */
    public static final String ERROR_CODE_ATTRIBUTE = GlobalExceptionHandler.class.getName() + ".code";
    public static final String ERROR_MESSAGE_ATTRIBUTE = GlobalExceptionHandler.class.getName() + ".message";

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        remember(request, ex.getCode().name(), ex.getMessage());
        return ResponseEntity.status(ex.getCode().status())
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage(), List.of(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldDetail)
                .toList();
        remember(request, ErrorCode.VALIDATION_ERROR.name(), "请求参数不合法：" + details.stream()
                .map(detail -> detail.get("field") + " " + detail.get("message"))
                .collect(java.util.stream.Collectors.joining("；")));
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.VALIDATION_ERROR.name(), "请求参数不合法", details, requestId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        remember(request, ErrorCode.VALIDATION_ERROR.name(), "请求内容无法解析");
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.VALIDATION_ERROR.name(), "请求内容无法解析", List.of(), requestId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleMissingResource(NoResourceFoundException ex, HttpServletRequest request) {
        remember(request, ErrorCode.RESOURCE_NOT_FOUND.name(), "资源不存在");
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status()).body(new ErrorResponse(
                ErrorCode.RESOURCE_NOT_FOUND.name(), "资源不存在", List.of(), requestId(request)));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException ex, HttpServletRequest request) {
        remember(request, ErrorCode.DUPLICATE_RESOURCE.name(), "资源已存在");
        return ResponseEntity.status(ErrorCode.DUPLICATE_RESOURCE.status()).body(new ErrorResponse(
                ErrorCode.DUPLICATE_RESOURCE.name(), "资源已存在", List.of(), requestId(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        remember(request, ErrorCode.FORBIDDEN.name(), "无权执行该操作");
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status()).body(new ErrorResponse(
                ErrorCode.FORBIDDEN.name(), "无权执行该操作", List.of(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request error, requestId={}", requestId(request), ex);
        // 日志里多带一个异常类名，管理员拿着请求 ID 去翻服务端日志时知道找什么。
        remember(request, ErrorCode.INTERNAL_ERROR.name(), "服务内部错误（" + ex.getClass().getSimpleName() + "）");
        return ResponseEntity.internalServerError().body(new ErrorResponse(
                ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", List.of(), requestId(request)));
    }

    private static void remember(HttpServletRequest request, String code, String message) {
        request.setAttribute(ERROR_CODE_ATTRIBUTE, code);
        request.setAttribute(ERROR_MESSAGE_ATTRIBUTE, message);
    }

    private Map<String, String> fieldDetail(FieldError error) {
        return Map.of("field", error.getField(), "message",
                error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage());
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? null : value.toString();
    }

    record ErrorResponse(String code, String message, List<?> details, String requestId) {
    }
}
