package cn.photolib.auth.mfa;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统管理面板里的全站两步验证开关。各权限组的策略在权限组接口里改。 */
@RestController
@RequestMapping("/mfa-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequiresStepUp
public class MfaSettingsController {
    private final MfaService mfa;

    @GetMapping
    ApiResponse<MfaService.SettingsView> get() {
        return ApiResponse.ok(mfa.settings());
    }

    @PutMapping
    ApiResponse<MfaService.SettingsView> update(@AuthenticationPrincipal AuthenticatedUser admin,
                                                @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(mfa.updateSettings(admin, request.enabled()));
    }

    record UpdateRequest(@NotNull Boolean enabled) {
    }
}
