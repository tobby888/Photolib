package cn.photolib.registration;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Set;

/** 注册码管理，仅系统管理员。和系统管理面板的其他接口一样要求近期做过两步验证。 */
@RestController
@RequestMapping("/registration-codes")
@PreAuthorize("hasRole('ADMIN')")
@RequiresStepUp
@RequiredArgsConstructor
public class RegistrationCodeController {
    static final int MAX_USES = 1000;

    private final RegistrationService registrations;

    @GetMapping
    ApiResponse<PageResponse<RegistrationService.CodeView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(registrations.listCodes(page, pageSize, keyword));
    }

    @PostMapping
    ApiResponse<RegistrationService.CodeView> create(@Valid @RequestBody CreateRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(registrations.createCode(new RegistrationService.CreateCode(request.name(),
                request.permissionGroupId(), request.campusIds(), request.maxUses(),
                request.validFrom(), request.validUntil()), user.id()));
    }

    @PutMapping("/{id}")
    ApiResponse<RegistrationService.CodeView> update(@PathVariable Long id,
                                                     @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(registrations.updateCode(id, new RegistrationService.UpdateCode(request.name(),
                request.maxUses(), request.validFrom(), request.validUntil(), request.enabled(),
                request.version())));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable Long id) {
        registrations.deleteCode(id);
        return ApiResponse.ok();
    }

    record CreateRequest(@NotBlank @Size(max = 100) String name,
                         @NotNull Long permissionGroupId,
                         Set<@NotNull Long> campusIds,
                         @Min(1) @Max(MAX_USES) int maxUses,
                         LocalDateTime validFrom,
                         @NotNull LocalDateTime validUntil) {
    }

    record UpdateRequest(@NotBlank @Size(max = 100) String name,
                         @Min(1) @Max(MAX_USES) int maxUses,
                         @NotNull LocalDateTime validFrom,
                         @NotNull LocalDateTime validUntil,
                         boolean enabled,
                         @Min(1) int version) {
    }
}
