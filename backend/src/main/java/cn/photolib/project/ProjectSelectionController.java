package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.photo.PhotoImageEditService;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.model.PhotoEntity;
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

import java.util.List;

/**
 * 活动选题的选片入口（issue #94）。
 *
 * <p>整个控制器只有一条方法级权限 {@code PROJECT_VIEW|PROJECT_VIEW_ALL}——那只是
 * 「能进选题模块」的门槛。真正的授权在 Service 里：
 * {@code ProjectService.requireSelectionAccess} 认的是 {@code project_selector}
 * 里的那一行（或选题创建者/管理员），而不是任何图库权限。选片人常常连
 * {@code PHOTO_VIEW} 都没有，用图库那套权限判等于把该干活的人挡在门外。</p>
 *
 * <p>选片人名单的增删（{@code /selectors}）走另一条规则：{@code PROJECT_CREATE}
 * 加「本人创建或管理员」，与编辑选题一致。</p>
 */
@RestController
@RequestMapping("/projects/{projectId}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('PROJECT_VIEW','PROJECT_VIEW_ALL')")
public class ProjectSelectionController {
    private final ProjectService projects;
    private final ProjectSelectionService selection;
    private final PhotoImageEditService imageEdits;

    @GetMapping("/selectors")
    ApiResponse<List<ProjectService.Selector>> selectors(@PathVariable Long projectId,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        projects.getVisible(projectId, user);
        return ApiResponse.ok(projects.selectors(projectId));
    }

    /** 可指派为选片人的账号。凭据与「改这个选题」一致，不是图库/管理员权限。 */
    @GetMapping("/selector-candidates")
    ApiResponse<List<ProjectService.Selector>> selectorCandidates(
            @PathVariable Long projectId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(projects.selectorCandidates(projectId, user));
    }

    /** 整组替换选片人；传空数组表示取消全部指派。 */
    @PutMapping("/selectors")
    ApiResponse<List<ProjectService.Selector>> replaceSelectors(
            @PathVariable Long projectId, @Valid @RequestBody SelectorsRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(projects.replaceSelectors(projectId, request.userIds(), user));
    }

    @GetMapping("/selection/photos")
    ApiResponse<PageResponse<ProjectSelectionService.SelectionPhoto>> photos(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "60") @Min(1) @Max(200) int pageSize,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(selection.photos(projectId, page, pageSize, user));
    }

    /** 选片页展示大图用的签名地址（成品图，内联渲染，不是下载）。 */
    @GetMapping("/selection/photos/{photoId}/image-url")
    ApiResponse<PhotoService.DownloadUrl> imageUrl(@PathVariable Long projectId, @PathVariable Long photoId,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(selection.imageUrl(projectId, photoId, user));
    }

    @PostMapping("/selection/tags")
    ApiResponse<List<PhotoService.TaggedPhoto>> tag(@PathVariable Long projectId,
                                                    @Valid @RequestBody TagRequest request,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(selection.tag(projectId, request.photoIds(), request.addTags(),
                request.removeTags(), user));
    }

    /** 裁切/旋转结果的直传地址。 */
    @PostMapping("/selection/photos/{photoId}/edit-tickets")
    ApiResponse<PhotoImageEditService.EditTicket> editTicket(
            @PathVariable Long projectId, @PathVariable Long photoId,
            @Valid @RequestBody EditTicketRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        PhotoEntity photo = selection.requireEditablePhoto(projectId, photoId, user);
        return ApiResponse.ok(imageEdits.ticket(photo.getId(), request.contentType(), request.size(), user));
    }

    /** 用已经直传上去的编辑结果替换成品图。 */
    @PostMapping("/selection/photos/{photoId}/apply-edit")
    ApiResponse<PhotoService.PhotoView> applyEdit(
            @PathVariable Long projectId, @PathVariable Long photoId,
            @Valid @RequestBody ApplyEditRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        PhotoEntity photo = selection.requireEditablePhoto(projectId, photoId, user);
        return ApiResponse.ok(imageEdits.apply(photo.getId(), new PhotoImageEditService.ApplyEdit(
                request.sourceObjectKey(), request.contentType(), request.size(), request.sha256()), user));
    }

    @GetMapping("/selection/cleanup")
    ApiResponse<ProjectSelectionService.CleanupPlan> cleanupPlan(
            @PathVariable Long projectId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(selection.plan(projectId, user));
    }

    /** 把打了 deprecated 的图片从图库和 OSS 里删掉。不可撤销，前端必须先让人确认。 */
    @PostMapping("/selection/cleanup")
    ApiResponse<ProjectSelectionService.CleanupResult> cleanup(
            @PathVariable Long projectId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(selection.cleanup(projectId, user));
    }

    record SelectorsRequest(@NotNull @Size(max = 50) List<@NotNull Long> userIds) {
    }

    record TagRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds,
                      @Size(max = 30) List<@NotBlank @Size(max = 100) String> addTags,
                      @Size(max = 30) List<@NotBlank @Size(max = 100) String> removeTags) {
    }

    record EditTicketRequest(@NotBlank String contentType, @Min(1) long size) {
    }

    record ApplyEditRequest(@NotBlank @Size(max = 500) String sourceObjectKey,
                            @NotBlank String contentType, @Min(1) long size,
                            @NotBlank @Size(max = 64) String sha256) {
    }
}
