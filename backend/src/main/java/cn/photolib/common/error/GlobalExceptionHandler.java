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

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getCode().status())
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage(), List.of(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldDetail)
                .toList();
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.VALIDATION_ERROR.name(), "请求参数不合法", details, requestId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.VALIDATION_ERROR.name(), "请求内容无法解析", List.of(), requestId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleMissingResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status()).body(new ErrorResponse(
                ErrorCode.RESOURCE_NOT_FOUND.name(), "资源不存在", List.of(), requestId(request)));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException ex, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.DUPLICATE_RESOURCE.status()).body(new ErrorResponse(
                ErrorCode.DUPLICATE_RESOURCE.name(), "资源已存在", List.of(), requestId(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status()).body(new ErrorResponse(
                ErrorCode.FORBIDDEN.name(), "无权执行该操作", List.of(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request error, requestId={}", requestId(request), ex);
        return ResponseEntity.internalServerError().body(new ErrorResponse(
                ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", List.of(), requestId(request)));
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
