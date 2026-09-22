package cn.photolib.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESOURCE_STATE_CONFLICT(HttpStatus.CONFLICT),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** 两步验证的验证码 / 安全密钥没通过。用 400 而不是 401：前端遇到 401 会去续期会话。 */
    MFA_INVALID_CODE(HttpStatus.BAD_REQUEST),
    /** 敏感操作需要先再验证一次两步验证，前端据此弹出验证框、验证后重试原请求。 */
    STEP_UP_REQUIRED(HttpStatus.FORBIDDEN),
    /** 所在权限组强制两步验证而账号还没绑定，会话只能走绑定流程。 */
    MFA_ENROLLMENT_REQUIRED(HttpStatus.FORBIDDEN),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
