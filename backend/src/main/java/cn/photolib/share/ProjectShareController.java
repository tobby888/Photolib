package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分享链接的管理端。整个控制器一条 {@code PROJECT_SHARE}，
 * 访客那半边在 {@link ProjectSharePublicController}，两套规则不要合并。
 */
@RestController
@RequestMapping("/projects/{projectId}/share-links")
@PreAuthorize("hasAuthority('PROJECT_SHARE')")
@RequiredArgsConstructor
public class ProjectShareController {
    private final ProjectShareService service;

    @GetMapping
    ApiResponse<List<ProjectShareService.ShareLinkView>> list(
            @PathVariable Long projectId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(projectId, user));
    }

    @PostMapping
    ApiResponse<ProjectShareService.CreatedShareLink> create(
            @PathVariable Long projectId, @Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(projectId, request.command(), user));
    }

    @PutMapping("/{linkId}")
    ApiResponse<ProjectShareService.ShareLinkView> update(
            @PathVariable Long projectId, @PathVariable Long linkId,
            @Valid @RequestBody UpdateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(projectId, linkId, request.command(), user));
    }

    @PostMapping("/{linkId}/password")
    ApiResponse<ProjectShareService.ResetPassword> resetPassword(
            @PathVariable Long projectId, @PathVariable Long linkId,
            @Valid @RequestBody PasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.resetPassword(projectId, linkId, request.password(), user));
    }

    @DeleteMapping("/{linkId}")
    ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long linkId,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(projectId, linkId, user);
        return ApiResponse.ok();
    }

    record CreateRequest(@Size(max = 100) String name,
                         @Size(max = ProjectShareService.MAX_PASSWORD_LENGTH) String password,
                         boolean allowDownload, boolean allowAdoption,
                         LocalDateTime expiresAt) {
        ProjectShareService.CreateCommand command() {
            return new ProjectShareService.CreateCommand(name, password, allowDownload,
                    allowAdoption, expiresAt);
        }
    }

    record UpdateRequest(@Size(max = 100) String name, boolean allowDownload, boolean allowAdoption,
                         LocalDateTime expiresAt, @Min(1) int version) {
        ProjectShareService.UpdateCommand command() {
            return new ProjectShareService.UpdateCommand(name, allowDownload, allowAdoption,
                    expiresAt, version);
        }
    }

    /** 密码留空表示"让系统重新生成一个"。 */
    record PasswordRequest(@Size(max = ProjectShareService.MAX_PASSWORD_LENGTH) String password) {}
}
