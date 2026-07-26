package cn.photolib.request;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.request.model.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class RequestController {
    private final RequestService service;

    @PostMapping("/projects/{projectId}/requests")
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    ApiResponse<PhotoRequestEntity> create(@PathVariable Long projectId,
                                           @Valid @RequestBody CreateRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(projectId, new RequestService.CreateCommand(
                request.title(), request.description(), request.campusId(),
                request.requiredCount(), request.deadline()), user));
    }

    @PostMapping("/projects/{projectId}/requests/batch-publish")
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    ApiResponse<List<RequestService.BatchPublishResult>> batchPublish(
            @PathVariable Long projectId,
            @Valid @RequestBody BatchPublishRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.batchPublish(projectId, new RequestService.BatchPublishCommand(
                request.title(), request.description(), request.campusIds(),
                request.requiredCount(), request.deadline()), user));
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('REQUEST_VIEW')")
    ApiResponse<PageResponse<PhotoRequestEntity>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) Long participantId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, projectId, status, campusId, participantId, user));
    }

    @GetMapping("/requests/{id}")
    @PreAuthorize("hasAuthority('REQUEST_VIEW')")
    ApiResponse<PhotoRequestEntity> get(@PathVariable Long id,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @PutMapping("/requests/{id}")
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    ApiResponse<PhotoRequestEntity> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, new RequestService.CreateCommand(
                request.title(), request.description(), request.campusId(),
                request.requiredCount(), request.deadline()), request.version(), user));
    }

    @PostMapping("/requests/{id}/publish")
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    ApiResponse<PhotoRequestEntity> publish(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.publish(id, request.version(), user));
    }

    @PostMapping("/requests/{id}/accept")
    @PreAuthorize("hasAuthority('REQUEST_VIEW')")
    ApiResponse<PhotoRequestEntity> accept(@PathVariable Long id,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.accept(id, user));
    }

    @GetMapping("/requests/{id}/participants")
    @PreAuthorize("hasAuthority('REQUEST_VIEW')")
    ApiResponse<List<RequestParticipantEntity>> participants(
            @PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.participants(id, user));
    }

    @DeleteMapping("/requests/{id}/participants/me")
    @PreAuthorize("hasAuthority('REQUEST_VIEW')")
    ApiResponse<Void> leave(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.leave(id, user);
        return ApiResponse.ok();
    }

    @PostMapping("/requests/{id}/submit")
    @PreAuthorize("hasAuthority('REQUEST_VIEW')")
    ApiResponse<PhotoRequestEntity> submit(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.submit(id, request.version(), user));
    }

    @PostMapping("/requests/{id}/complete")
    @PreAuthorize("hasAuthority('REQUEST_CONFIRM')")
    ApiResponse<PhotoRequestEntity> complete(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.complete(id, request.version(), user));
    }

    @PostMapping("/requests/{id}/return")
    @PreAuthorize("hasAuthority('REQUEST_CONFIRM')")
    ApiResponse<PhotoRequestEntity> returnForRevision(
            @PathVariable Long id,
            @Valid @RequestBody ReturnRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.returnForRevision(id, request.reason(), request.version(), user));
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize("hasAuthority('REQUEST_CLOSE')")
    ApiResponse<PhotoRequestEntity> cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.cancel(id, request.reason(), request.version(), user));
    }

    @DeleteMapping("/requests/{id}")
    @PreAuthorize("hasAuthority('REQUEST_DELETE')")
    ApiResponse<Void> delete(@PathVariable Long id,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    record CreateRequest(@NotBlank @Size(max = 200) String title,
                         @Size(max = 5000) String description,
                         @NotNull Long campusId,
                         @Min(1) Integer requiredCount,
                         @NotNull @Future LocalDateTime deadline) {
    }

    record BatchPublishRequest(@NotBlank @Size(max = 200) String title,
                               @Size(max = 5000) String description,
                               @NotEmpty @Size(max = 50) List<@NotNull Long> campusIds,
                               @Min(1) Integer requiredCount,
                               @NotNull @Future LocalDateTime deadline) {
    }

    record VersionRequest(@Min(1) int version) {
    }

    record UpdateRequest(@NotBlank @Size(max = 200) String title,
                         @Size(max = 5000) String description,
                         @NotNull Long campusId,
                         @Min(1) Integer requiredCount,
                         @NotNull @Future LocalDateTime deadline,
                         @Min(1) int version) {}

    record CancelRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }

    record ReturnRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }
}
