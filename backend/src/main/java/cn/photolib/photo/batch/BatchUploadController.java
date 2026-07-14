package cn.photolib.photo.batch;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/photos")
@RequiredArgsConstructor
public class BatchUploadController {
    private final BatchUploadService service;

    @PostMapping("/batch-upload-tickets")
    ApiResponse<BatchUploadService.BatchTicket> create(@Valid @RequestBody CreateRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        List<BatchUploadService.FileSpec> files = request.files() == null ? null : request.files().stream()
                .map(f -> new BatchUploadService.FileSpec(f.fileName(), f.contentType(), f.size(), f.sha256()))
                .toList();
        return ApiResponse.ok(service.create(new BatchUploadService.CreateBatch(
                request.mode(), request.requestId(), request.projectId(), request.archiveFileName(),
                request.archiveSize(), files), user));
    }

    @PostMapping("/batches/{id}/complete-upload")
    ApiResponse<BatchUploadService.BatchView> complete(@PathVariable String id,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.complete(id, user));
    }

    @GetMapping("/batches/{id}")
    ApiResponse<BatchUploadService.BatchView> get(@PathVariable String id,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @PutMapping("/batches/{batchId}/items/{itemId}/metadata")
    ApiResponse<BatchUploadService.BatchView> metadata(
            @PathVariable String batchId, @PathVariable Long itemId,
            @Valid @RequestBody MetadataRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.setMetadata(batchId, itemId,
                new BatchUploadService.ItemMetadata(request.title(), request.description(),
                        request.photographerContactId(),
                        request.takenAt(), request.tags()), user));
    }

    @PutMapping("/batches/{batchId}/metadata")
    ApiResponse<BatchUploadService.BatchView> batchMetadata(
            @PathVariable String batchId,
            @Valid @RequestBody BatchMetadataRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.setMetadataForAll(batchId,
                new BatchUploadService.BatchMetadata(request.description(),
                        request.photographerContactId(), request.takenAt(), request.tags()), user));
    }

    record CreateRequest(@NotNull BatchMode mode, Long requestId, Long projectId,
                         String archiveFileName, @Positive Long archiveSize,
                         @Size(max = 100) List<@Valid FileRequest> files) {}
    record FileRequest(@NotBlank String fileName, @NotBlank String contentType,
                       @Positive long size,
                       @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) {}
    record MetadataRequest(@NotBlank @Size(max = 200) String title,
                           @Size(max = 5000) String description,
                           @NotNull Long photographerContactId,
                           @NotNull @PastOrPresent LocalDateTime takenAt,
                           @Size(max = 30) List<@Size(max = 50) String> tags) {}
    record BatchMetadataRequest(@Size(max = 5000) String description,
                                @NotNull Long photographerContactId,
                                @NotNull @PastOrPresent LocalDateTime takenAt,
                                @Size(max = 30) List<@Size(max = 50) String> tags) {}
}
