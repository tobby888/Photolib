package cn.photolib.registration;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 持注册码的同学提交注册申请。不需要登录（{@code SecurityConfig} 放行），提交后等管理员审核，
 * 通过前账号并不存在。
 */
@RestController
@RequestMapping("/public/registrations")
@RequiredArgsConstructor
public class RegistrationPublicController {
    /** 与修改密码同一条规则，见 {@code AuthController.PasswordRequest}。 */
    static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).{10,72}$";

    private final RegistrationService registrations;
    private final RegistrationRateLimiter rateLimiter;

    @PostMapping
    ApiResponse<RegistrationService.SubmittedApplication> register(@Valid @RequestBody RegisterRequest request,
                                                                    HttpServletRequest servletRequest) {
        // 审计里记下想注册的账号，事后能看清是谁在灌申请；密码和注册码都不记。
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE,
                Map.of("username", request.username().trim()));
        rateLimiter.requireAllowed(servletRequest.getRemoteAddr());
        if (!request.password().equals(request.confirmPassword())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "两次输入的密码不一致");
        }
        return ApiResponse.ok(registrations.register(new RegistrationService.Register(request.code(),
                request.username(), request.displayName(), request.email(), request.password())));
    }

    record RegisterRequest(
            @NotBlank(message = "请输入注册码") @Size(max = 64) String code,
            @NotBlank(message = "请输入登录账号")
            @Pattern(regexp = "^\\s*[A-Za-z0-9_.-]{3,64}\\s*$", message = "登录账号为 3-64 位字母、数字或 ._-")
            String username,
            @NotBlank(message = "请输入真实姓名") @Size(max = 50, message = "姓名不能超过 50 个字符")
            String displayName,
            @NotBlank(message = "请输入邮箱") @Email(message = "请输入有效的邮箱地址") @Size(max = 255)
            String email,
            @NotBlank(message = "请输入密码")
            @Pattern(regexp = PASSWORD_PATTERN, message = "密码至少10位，且必须包含字母和数字")
            String password,
            @NotBlank(message = "请再次输入密码") String confirmPassword) {
    }
}
