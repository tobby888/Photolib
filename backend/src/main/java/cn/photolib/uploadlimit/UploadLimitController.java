package cn.photolib.uploadlimit;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/upload-limits")
@RequiredArgsConstructor
public class UploadLimitController {
    private final UploadLimitService service;

    /**
     * 当前生效的全部限额。匿名也能读：选题上传链接和公开招募的访客页面都要按它提示和预检，
     * 这些数字本来就会出现在报错信息里，不算敏感信息。真正的拦截在各上传接口的服务端。
     */
    @GetMapping
    ApiResponse<Map<String, Long>> current() {
        return ApiResponse.ok(service.currentValues());
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresStepUp
    ApiResponse<List<UploadLimitService.LimitView>> settings() {
        return ApiResponse.ok(service.settings());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresStepUp
    ApiResponse<List<UploadLimitService.LimitView>> update(@Valid @RequestBody UpdateRequest request,
                                                           @AuthenticationPrincipal AuthenticatedUser user,
                                                           HttpServletRequest http) {
        Map<String, UploadLimitService.Change> changes = service.update(request.values(), user.id());
        // 审计日志只看得到路径；把改了哪几项、从多少改成多少记进详情，事后才查得清。
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("changes", changes));
        return ApiResponse.ok(service.settings());
    }

    record UpdateRequest(@NotEmpty Map<String, Long> values) {
    }
}
