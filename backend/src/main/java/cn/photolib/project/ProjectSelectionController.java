package cn.photolib.project;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.photo.PhotoImageEditService;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.PhotoTags;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.share.ProjectShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** 清理预演。筛选参数与选题详情页那一排一致，全部省略时按「不可用」标签预演。 */
    @GetMapping("/selection/cleanup")
    ApiResponse<ProjectSelectionService.CleanupPlan> cleanupPlan(
            @PathVariable Long projectId,
            @RequestParam(required = false) @Size(max = PhotoTags.MAX_TAGS) List<String> tags,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenTo,
            @RequestParam(required = false) @Size(max = 200) List<String> photographers,
            @RequestParam(required = false) ProjectShareService.AdoptionFilter adoption,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(selection.plan(projectId, new ProjectSelectionService.CleanupFilter(
                tags, takenFrom, takenTo, photographers, adoption), user));
    }

    /**
     * 按筛选条件把图片从图库和 OSS 里删掉。不可撤销，前端必须先预演、再让人确认；
     * {@code planToken} 取自预演，结果变了就拒绝。不带请求体等于旧行为：只删「不可用」的。
     */
    @PostMapping("/selection/cleanup")
    ApiResponse<ProjectSelectionService.CleanupResult> cleanup(
            @PathVariable Long projectId, @Valid @RequestBody(required = false) CleanupRequest request,
            @AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest servletRequest) {
        CleanupRequest body = request == null ? CleanupRequest.EMPTY : request;
        ProjectSelectionService.CleanupFilter filter = new ProjectSelectionService.CleanupFilter(
                body.tags(), body.takenFrom(), body.takenTo(), body.photographers(), body.adoption());
        ProjectSelectionService.CleanupResult result = selection.cleanup(projectId, filter, body.planToken(), user);
        // 批量删除要能在审计里查到是按什么条件删的、删了几张。审计用的 ObjectMapper 不认 java.time，
        // 日期和枚举先转成字符串，否则整条明细会退化成 {}。
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("tags", filter.tags());
        audit.put("takenFrom", Objects.toString(filter.takenFrom(), null));
        audit.put("takenTo", Objects.toString(filter.takenTo(), null));
        audit.put("photographers", filter.photographers());
        audit.put("adoption", Objects.toString(filter.adoption(), null));
        audit.put("deletedCount", result.deletedCount());
        audit.put("skippedAdoptedCount", result.skippedAdoptedCount());
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, audit);
        return ApiResponse.ok(result);
    }

    record CleanupRequest(@Size(max = PhotoTags.MAX_TAGS) List<String> tags,
                          LocalDate takenFrom, LocalDate takenTo,
                          @Size(max = 200) List<String> photographers,
                          ProjectShareService.AdoptionFilter adoption,
                          @Size(max = 64) String planToken) {
        static final CleanupRequest EMPTY = new CleanupRequest(null, null, null, null, null, null);
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
