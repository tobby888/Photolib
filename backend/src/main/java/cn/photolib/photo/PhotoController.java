package cn.photolib.photo;

import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditSkip;
import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.photo.model.PhotoStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/photos")
@RequiredArgsConstructor
public class PhotoController {
    private final PhotoService service;

    @AuditSkip("每张图先签上传票据再完成上传，完成那一步另有记录")
    @PostMapping("/upload-tickets")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<PhotoService.UploadTicket> ticket(@Valid @RequestBody TicketRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.createTicket(request.command(), user));
    }

    @AuditAction("上传图片")
    @PostMapping("/{id}/complete-upload")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<PhotoService.PhotoView> complete(@PathVariable Long id,
                                                 @Valid @RequestBody CompleteRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.complete(id,
                new PhotoService.CompleteUpload(request.title(), request.description(), request.tags()), user));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PHOTO_VIEW','PROJECT_VIEW','PROJECT_VIEW_ALL','REQUEST_VIEW','REQUEST_PHOTO_MANAGE')")
    ApiResponse<PageResponse<PhotoService.PhotoView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long requestId,
            @RequestParam(required = false) String photographerStudentId,
            @RequestParam(required = false) String photographerName,
            @RequestParam(required = false) Long uploadedBy,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) PhotoStatus status,
            @RequestParam(defaultValue = "false") boolean includeAllStatuses,
            @RequestParam(defaultValue = "false") boolean favoritesOnly,
            @RequestParam(defaultValue = "false") boolean selectableOnly,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) @Size(max = 200) List<String> photographers,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenTo,
            @RequestParam(required = false) Boolean adopted,
            @RequestParam(required = false) Boolean inProject,
            // 多个标签时的匹配方式：ALL 同时包含（默认，与改动前一致），ANY 任意其一。
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|ANY") String tagMatch,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, keyword, projectId, requestId,
                photographerStudentId, photographerName, uploadedBy, campusId, status,
                includeAllStatuses, favoritesOnly, selectableOnly, tags, photographers,
                new PhotoService.LibraryFilter(takenFrom, takenTo, adopted, inProject, "ANY".equals(tagMatch)),
                user));
    }

    /** 图库「按拍摄者筛选」的候选。范围与 {@code GET /photos} 完全一致，见 PhotoService.photographers。 */
    @GetMapping("/photographers")
    @PreAuthorize("hasAuthority('PHOTO_VIEW')")
    ApiResponse<List<String>> photographers(
            @RequestParam(defaultValue = "false") boolean favoritesOnly,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.photographers(favoritesOnly, user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PHOTO_VIEW','PROJECT_VIEW','PROJECT_VIEW_ALL','REQUEST_VIEW','REQUEST_PHOTO_MANAGE')")
    ApiResponse<PhotoService.PhotoView> get(@PathVariable Long id,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @AuditAction("收藏图片")
    @PutMapping("/{id}/favorite")
    @PreAuthorize("hasAuthority('PHOTO_VIEW')")
    ApiResponse<Void> favorite(@PathVariable Long id,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        service.favorite(id, user);
        return ApiResponse.ok();
    }

    @AuditAction("取消收藏图片")
    @DeleteMapping("/{id}/favorite")
    @PreAuthorize("hasAuthority('PHOTO_VIEW')")
    ApiResponse<Void> unfavorite(@PathVariable Long id,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        service.unfavorite(id, user);
        return ApiResponse.ok();
    }

    @AuditAction("修改图片信息")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<PhotoService.PhotoView> update(@PathVariable Long id, @Valid @RequestBody MetadataRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.command(), user));
    }

    /**
     * 批量增删标签。在选题详情页里操作时带上 {@code projectId}，新增标签就要受该选题的预设约束。
     * 审计详情里记下选题、张数和增删了哪些标签，否则一条 POST /photos/batch-tags 事后看不出做了什么。
     */
    @AuditAction("批量修改图片标签")
    @PostMapping("/batch-tags")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<List<PhotoService.TaggedPhoto>> batchTags(@Valid @RequestBody BatchTagsRequest request,
                                                          HttpServletRequest servletRequest,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("projectId", request.projectId());
        audit.put("photoCount", request.photoIds().size());
        audit.put("addTags", request.addTags() == null ? List.of() : request.addTags());
        audit.put("removeTags", request.removeTags() == null ? List.of() : request.removeTags());
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, audit);
        return ApiResponse.ok(service.batchTags(new PhotoService.BatchTags(request.photoIds(),
                request.addTags(), request.removeTags(), request.projectId()), user));
    }

    @AuditAction("修改图片校区")
    @PatchMapping("/{id}/campus")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PhotoService.PhotoView> updateCampus(@PathVariable Long id,
                                                     @Valid @RequestBody CampusRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.updateCampus(id, request.campusId(), request.version(), user));
    }

    /**
     * 管理员直接改图片基本信息，拍摄者不经通讯录。审计详情里另记改之前的值和同步了几条被引记录。
     */
    @AuditAction("管理员修改图片信息")
    @PutMapping("/{id}/admin-metadata")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PhotoService.PhotoView> adminUpdate(@PathVariable Long id,
                                                    @Valid @RequestBody AdminMetadataRequest request,
                                                    HttpServletRequest servletRequest,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        PhotoService.AdminEdit result = service.adminUpdate(id, request.command(), user);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("previous", result.previous());
        audit.put("adoptionsUpdated", result.adoptionsUpdated());
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, audit);
        return ApiResponse.ok(result.photo());
    }

    /** 管理员批量改基本信息（拍摄者、校区、标题、说明），只改请求里给了的项。 */
    @AuditAction("管理员批量修改图片信息")
    @PostMapping("/batch-metadata")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PhotoService.BatchMetadataResult> adminBatchUpdate(
            @Valid @RequestBody BatchMetadataRequest request,
            HttpServletRequest servletRequest,
            @AuthenticationPrincipal AuthenticatedUser user) {
        PhotoService.BatchMetadataResult result = service.adminBatchUpdate(request.command(), user);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("photoCount", result.photoCount());
        audit.put("changedCount", result.changedCount());
        audit.put("adoptionsUpdated", result.adoptionsUpdated());
        audit.put("previous", result.previous());
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, audit);
        return ApiResponse.ok(result);
    }

    @AuditAction("下载原图")
    @PostMapping("/{id}/download-url")
    @PreAuthorize("hasAnyAuthority('PHOTO_DOWNLOAD','PROJECT_DOWNLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<PhotoService.DownloadUrl> download(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.download(id, user));
    }

    @AuditAction("归档图片")
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('PHOTO_ARCHIVE')")
    ApiResponse<PhotoService.PhotoView> archive(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeArchive(id, true, user));
    }

    @AuditAction("恢复归档图片")
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PHOTO_ARCHIVE')")
    ApiResponse<PhotoService.PhotoView> restore(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeArchive(id, false, user));
    }

    @AuditAction("删除图片")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PHOTO_DELETE','REQUEST_PHOTO_MANAGE')")
    @RequiresStepUp
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    @AuditAction("批量删除图片")
    @PostMapping("/batch-delete")
    @PreAuthorize("hasAnyAuthority('PHOTO_DELETE','REQUEST_PHOTO_MANAGE')")
    @RequiresStepUp
    ApiResponse<Void> batchDelete(@Valid @RequestBody BatchDeleteRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        service.batchDelete(request.photoIds(), user);
        return ApiResponse.ok();
    }

    record TicketRequest(Long requestId, Long projectId,
                         @NotBlank String fileName,
                         @NotBlank String contentType,
                         @Positive long size,
                         @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256,
                         @NotNull Long photographerContactId,
                         @NotNull @PastOrPresent LocalDateTime takenAt) {
        PhotoService.CreateTicket command() {
            return new PhotoService.CreateTicket(requestId, projectId, fileName, contentType, size, sha256,
                    photographerContactId, takenAt);
        }
    }

    record CompleteRequest(@NotBlank @Size(max = 200) String title,
                           @Size(max = 5000) String description,
                           @Size(max = 30) List<@Size(max = 100) String> tags) {}

    record MetadataRequest(@NotBlank @Size(max = 200) String title,
                           @Size(max = 5000) String description,
                           @NotNull Long photographerContactId,
                           @NotNull @PastOrPresent LocalDateTime takenAt,
                           @Size(max = 30) List<@Size(max = 100) String> tags,
                           @Min(1) int version) {
        PhotoService.Metadata command() {
            return new PhotoService.Metadata(title, description, photographerContactId,
                    takenAt, tags, version);
        }
    }

    record AdminMetadataRequest(@NotBlank @Size(max = 200) String title,
                                @Size(max = 5000) String description,
                                @NotBlank @Size(max = 100) String photographerName,
                                @Size(max = 64) String photographerStudentId,
                                @NotNull @PastOrPresent LocalDateTime takenAt,
                                Long campusId,
                                @Size(max = 30) List<@Size(max = 100) String> tags,
                                @NotNull @Min(1) Integer version,
                                // 包装类型：Jackson 3 默认 null 不能落到基本类型上，缺省按「同步」处理。
                                Boolean syncAdoptions) {
        PhotoService.AdminMetadata command() {
            return new PhotoService.AdminMetadata(title, description, photographerName, photographerStudentId,
                    takenAt, campusId, tags, version, !Boolean.FALSE.equals(syncAdoptions));
        }
    }

    record PhotographerChangeRequest(@NotBlank @Size(max = 100) String name,
                                     @Size(max = 64) String studentId) {}

    record BatchMetadataRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds,
                                @Size(max = 200) String title,
                                @Size(max = 5000) String description,
                                @Valid PhotographerChangeRequest photographer,
                                PhotoService.CampusChange campus,
                                Boolean syncAdoptions) {
        PhotoService.BatchMetadata command() {
            return new PhotoService.BatchMetadata(photoIds, title, description,
                    photographer == null ? null
                            : new PhotoService.PhotographerChange(photographer.name(), photographer.studentId()),
                    campus, !Boolean.FALSE.equals(syncAdoptions));
        }
    }

    record CampusRequest(Long campusId, @Min(1) int version) {}
    record BatchTagsRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds,
                            @Size(max = 30) List<@Size(max = 100) String> addTags,
                            @Size(max = 30) List<@Size(max = 100) String> removeTags,
                            Long projectId) {}
    record BatchDeleteRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds) {}
}
