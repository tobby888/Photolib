package cn.photolib.project;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService service;

    @PostMapping
    @PreAuthorize("hasAuthority('PROJECT_CREATE')")
    ApiResponse<ProjectEntity> create(@Valid @RequestBody CreateRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(request.title(), request.description(), request.status(),
                request.tags(), request.type(), user));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PROJECT_VIEW','PROJECT_VIEW_ALL')")
    ApiResponse<PageResponse<ProjectEntity>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProjectStatus status,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, keyword, status, user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PROJECT_VIEW','PROJECT_VIEW_ALL')")
    ApiResponse<ProjectService.ProjectDetail> get(@PathVariable Long id,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.getDetail(id, user));
    }

    @PostMapping("/{id}/photos")
    @PreAuthorize("hasAuthority('PROJECT_ADOPT')")
    ApiResponse<Void> addPhotos(@PathVariable Long id, @Valid @RequestBody PhotoIdsRequest request,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        service.addPhotos(id, request.photoIds(), user);
        return ApiResponse.ok();
    }

    /**
     * 移出选题相册。不用 {@code DELETE} 带请求体：批量的 id 放进 URL 会撞长度上限，
     * 而带体的 DELETE 有代理会直接丢掉请求体。审计记下移了哪些图片——URL 里只有选题 id。
     */
    @PostMapping("/{id}/photos/remove")
    @PreAuthorize("hasAuthority('PROJECT_ADOPT')")
    ApiResponse<ProjectService.RemovePhotosResult> removePhotos(
            @PathVariable Long id, @Valid @RequestBody PhotoIdsRequest request,
            @AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest http) {
        ProjectService.RemovePhotosResult result = service.removePhotos(id, request.photoIds(), user);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("photoIds", request.photoIds());
        audit.put("removed", result.removed());
        audit.put("skippedAdopted", result.skippedAdopted());
        audit.put("skippedUploadedHere", result.skippedUploadedHere());
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, audit);
        return ApiResponse.ok(result);
    }

    /** 置顶只影响当前账号自己的选题列表，凭据与查看选题一致。 */
    @PutMapping("/{id}/pin")
    @PreAuthorize("hasAnyAuthority('PROJECT_VIEW','PROJECT_VIEW_ALL')")
    ApiResponse<Void> pin(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.pin(id, user);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/pin")
    @PreAuthorize("hasAnyAuthority('PROJECT_VIEW','PROJECT_VIEW_ALL')")
    ApiResponse<Void> unpin(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.unpin(id, user);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PROJECT_CREATE')")
    ApiResponse<ProjectEntity> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.title(), request.description(), request.tags(),
                request.version(), user));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('PROJECT_CREATE','PROJECT_COMPLETE')")
    ApiResponse<ProjectEntity> status(@PathVariable Long id, @Valid @RequestBody StatusRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeStatus(id, request.status(), request.version(), user));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ProjectEntity> reopen(@PathVariable Long id, @Valid @RequestBody ReopenRequest request) {
        return ApiResponse.ok(service.reopen(id, request.version()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PROJECT_DELETE')")
    @RequiresStepUp
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    /** {@code type} 省略时按创作选题建立，与改动之前的行为一致。选题类型建立后不可改。 */
    record CreateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                         @NotNull ProjectStatus status, ProjectType type,
                         @Size(max = 30) List<@Size(max = 100) String> tags) {
    }

    /** {@code tags} 省略时保留原预设标签；传空数组表示取消限制。 */
    record UpdateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                         @Size(max = 30) List<@Size(max = 100) String> tags,
                         @Min(1) int version) {
    }

    record StatusRequest(@NotNull ProjectStatus status, @Min(1) int version) {
    }

    record ReopenRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }

    record PhotoIdsRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds) {
    }
}
