package cn.photolib.photo.batch;

import cn.photolib.audit.AuditAction;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/photos")
@RequiredArgsConstructor
public class BatchUploadController {
    private final BatchUploadService service;

    @AuditAction("开始批量上传")
    @PostMapping("/batch-upload-tickets")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<BatchUploadService.BatchTicket> create(@Valid @RequestBody CreateRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        List<BatchUploadService.FileSpec> files = request.files() == null ? null : request.files().stream()
                .map(f -> new BatchUploadService.FileSpec(f.fileName(), f.contentType(), f.size(), f.sha256()))
                .toList();
        return ApiResponse.ok(service.create(new BatchUploadService.CreateBatch(
                request.mode(), request.requestId(), request.projectId(), request.archiveFileName(),
                request.archiveSize(), files), user));
    }

    @AuditAction("完成批量上传")
    @PostMapping("/batches/{id}/complete-upload")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<BatchUploadService.BatchView> complete(@PathVariable String id,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.complete(id, user));
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<BatchUploadService.BatchView> get(@PathVariable String id,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @AuditAction("修改批量上传的图片信息")
    @PutMapping("/batches/{batchId}/items/{itemId}/metadata")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<BatchUploadService.BatchView> metadata(
            @PathVariable String batchId, @PathVariable Long itemId,
            @Valid @RequestBody MetadataRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.setMetadata(batchId, itemId,
                new BatchUploadService.ItemMetadata(request.title(), request.description(),
                        request.photographerContactId(),
                        request.takenAt(), request.tags(), request.publicPhoto()), user));
    }

    @AuditAction("批量填写上传图片信息")
    @PutMapping("/batches/{batchId}/metadata")
    @PreAuthorize("hasAnyAuthority('PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE')")
    ApiResponse<BatchUploadService.BatchView> batchMetadata(
            @PathVariable String batchId,
            @Valid @RequestBody BatchMetadataRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.setMetadataForAll(batchId,
                new BatchUploadService.BatchMetadata(request.description(),
                        request.photographerContactId(), request.takenAt(), request.tags(),
                        request.publicPhoto(), ItemTagsRequest.toMap(request.itemTags())), user));
    }

    record CreateRequest(@NotNull BatchMode mode, Long requestId, Long projectId,
                         String archiveFileName, @Positive Long archiveSize,
                         @Size(max = 100) List<@Valid FileRequest> files) {}
    record FileRequest(@NotBlank String fileName, @NotBlank String contentType,
                       @Positive long size,
                       @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) {}
    /** 拍摄者二选一：通讯录成员（{@code photographerContactId}）或公共照片（{@code publicPhoto=true}）。 */
    record MetadataRequest(@NotBlank @Size(max = 200) String title,
                           @Size(max = 5000) String description,
                           Long photographerContactId,
                           @NotNull @PastOrPresent LocalDateTime takenAt,
                           @Size(max = 30) List<@Size(max = 100) String> tags,
                           Boolean publicPhoto) {}
    /**
     * @param itemTags 逐张标签（「确认标签」对话框按压缩包文件夹识别、上传者改过的那份），
     *                 列出的图片用自己的标签替换 {@code tags}；不传时整批共用 {@code tags}
     */
    record BatchMetadataRequest(@Size(max = 5000) String description,
                                Long photographerContactId,
                                @NotNull @PastOrPresent LocalDateTime takenAt,
                                @Size(max = 30) List<@Size(max = 100) String> tags,
                                Boolean publicPhoto,
                                @Size(max = 1000) List<@Valid ItemTagsRequest> itemTags) {}

    /** 一张图的完整标签。上传链接的 finish 也用这个形状。 */
    public record ItemTagsRequest(@NotNull Long itemId,
                                  @NotNull @Size(max = 30) List<@Size(max = 100) String> tags) {
        /** 同一张图出现两次说明前端状态乱了，拒绝而不是挑一份。 */
        public static Map<Long, List<String>> toMap(List<ItemTagsRequest> requests) {
            if (requests == null || requests.isEmpty()) return Map.of();
            Map<Long, List<String>> result = new HashMap<>();
            for (ItemTagsRequest request : requests) {
                if (result.put(request.itemId(), request.tags()) != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一张图片的标签提交了两次");
                }
            }
            return result;
        }
    }
}
