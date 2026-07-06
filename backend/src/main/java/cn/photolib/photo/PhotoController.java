package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.photo.model.PhotoStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/photos")
@RequiredArgsConstructor
public class PhotoController {
    private final PhotoService service;

    @PostMapping("/upload-tickets")
    ApiResponse<PhotoService.UploadTicket> ticket(@Valid @RequestBody TicketRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.createTicket(request.command(), user));
    }

    @PostMapping("/{id}/complete-upload")
    ApiResponse<PhotoService.PhotoView> complete(@PathVariable Long id,
                                                 @Valid @RequestBody CompleteRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.complete(id,
                new PhotoService.CompleteUpload(request.title(), request.description(), request.tags()), user));
    }

    @GetMapping
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
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, keyword, projectId, requestId,
                photographerStudentId, photographerName, uploadedBy, campusId, status, user));
    }

    @GetMapping("/{id}")
    ApiResponse<PhotoService.PhotoView> get(@PathVariable Long id,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @PutMapping("/{id}")
    ApiResponse<PhotoService.PhotoView> update(@PathVariable Long id, @Valid @RequestBody MetadataRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.command(), user));
    }

    @PatchMapping("/{id}/campus")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PhotoService.PhotoView> updateCampus(@PathVariable Long id,
                                                     @Valid @RequestBody CampusRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.updateCampus(id, request.campusId(), request.version(), user));
    }

    @PostMapping("/{id}/download-url")
    ApiResponse<PhotoService.DownloadUrl> download(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.download(id, user));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoService.PhotoView> archive(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeArchive(id, true, user));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoService.PhotoView> restore(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeArchive(id, false, user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    record TicketRequest(Long requestId, Long projectId,
                         @NotBlank String fileName,
                         @NotBlank String contentType,
                         @Positive long size,
                         @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256,
                         @NotBlank @Size(max = 64) String photographerStudentId,
                         @NotBlank @Size(max = 100) String photographerName,
                         @NotNull @PastOrPresent LocalDateTime takenAt) {
        PhotoService.CreateTicket command() {
            return new PhotoService.CreateTicket(requestId, projectId, fileName, contentType, size, sha256,
                    photographerStudentId, photographerName, takenAt);
        }
    }

    record CompleteRequest(@NotBlank @Size(max = 200) String title,
                           @Size(max = 5000) String description,
                           @Size(max = 30) List<@Size(max = 50) String> tags) {}

    record MetadataRequest(@NotBlank @Size(max = 200) String title,
                           @Size(max = 5000) String description,
                           @NotBlank @Size(max = 64) String photographerStudentId,
                           @NotBlank @Size(max = 100) String photographerName,
                           @NotNull @PastOrPresent LocalDateTime takenAt,
                           @Size(max = 30) List<@Size(max = 50) String> tags,
                           @Min(1) int version) {
        PhotoService.Metadata command() {
            return new PhotoService.Metadata(title, description, photographerStudentId,
                    photographerName, takenAt, tags, version);
        }
    }

    record CampusRequest(Long campusId, @Min(1) int version) {}
}
