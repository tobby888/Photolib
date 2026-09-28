package cn.photolib.registration;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.registration.model.RegistrationStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 注册申请的搜索与（批量）审核，仅系统管理员。 */
@RestController
@RequestMapping("/registration-applications")
@PreAuthorize("hasRole('ADMIN')")
@RequiresStepUp
@RequiredArgsConstructor
public class RegistrationApplicationController {
    static final int MAX_BATCH = 200;

    private final RegistrationService registrations;

    @GetMapping
    ApiResponse<PageResponse<RegistrationService.ApplicationView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RegistrationStatus status,
            @RequestParam(required = false) Long codeId) {
        return ApiResponse.ok(registrations.listApplications(page, pageSize, keyword, status, codeId));
    }

    @PostMapping("/approve")
    ApiResponse<RegistrationService.ReviewResult> approve(@Valid @RequestBody ApproveRequest request,
                                                          @AuthenticationPrincipal AuthenticatedUser user,
                                                          HttpServletRequest servletRequest) {
        RegistrationService.ReviewResult result = registrations.approve(request.ids(), user.id());
        audit(servletRequest, result);
        return ApiResponse.ok(result);
    }

    @PostMapping("/reject")
    ApiResponse<RegistrationService.ReviewResult> reject(@Valid @RequestBody RejectRequest request,
                                                         @AuthenticationPrincipal AuthenticatedUser user,
                                                         HttpServletRequest servletRequest) {
        RegistrationService.ReviewResult result = registrations.reject(request.ids(), request.reason(), user.id());
        audit(servletRequest, result);
        return ApiResponse.ok(result);
    }

    /** 批量接口的路径里没有资源 id，把这次实际处理了哪些申请写进审计详情。 */
    private static void audit(HttpServletRequest request, RegistrationService.ReviewResult result) {
        request.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of(
                "succeeded", result.succeeded(),
                "failed", result.failed().stream().map(RegistrationService.ReviewFailure::id).toList()));
    }

    record ApproveRequest(@NotEmpty @Size(max = MAX_BATCH) List<@NotNull Long> ids) {
    }

    record RejectRequest(@NotEmpty @Size(max = MAX_BATCH) List<@NotNull Long> ids,
                         @Size(max = 500) String reason) {
    }
}
