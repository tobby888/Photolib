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
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoRequestEntity> create(@PathVariable Long projectId,
                                           @Valid @RequestBody CreateRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(projectId, new RequestService.CreateCommand(
                request.title(), request.description(), request.campusId(),
                request.requiredCount(), request.deadline()), user));
    }

    @GetMapping("/requests")
    ApiResponse<PageResponse<PhotoRequestEntity>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) Long campusId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, projectId, status, campusId, user));
    }

    @GetMapping("/requests/{id}")
    ApiResponse<PhotoRequestEntity> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/requests/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoRequestEntity> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, new RequestService.CreateCommand(
                request.title(), request.description(), request.campusId(),
                request.requiredCount(), request.deadline()), request.version(), user));
    }

    @PostMapping("/requests/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoRequestEntity> publish(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.publish(id, request.version(), user));
    }

    @PostMapping("/requests/{id}/accept")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPUS_MANAGER')")
    ApiResponse<PhotoRequestEntity> accept(@PathVariable Long id,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.accept(id, user));
    }

    @GetMapping("/requests/{id}/participants")
    ApiResponse<List<RequestParticipantEntity>> participants(@PathVariable Long id) {
        return ApiResponse.ok(service.participants(id));
    }

    @DeleteMapping("/requests/{id}/participants/me")
    @PreAuthorize("hasRole('CAMPUS_MANAGER')")
    ApiResponse<Void> leave(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.leave(id, user);
        return ApiResponse.ok();
    }

    @PostMapping("/requests/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPUS_MANAGER')")
    ApiResponse<PhotoRequestEntity> submit(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.submit(id, request.version(), user));
    }

    @PostMapping("/requests/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoRequestEntity> complete(@PathVariable Long id, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.ok(service.complete(id, request.version()));
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<PhotoRequestEntity> cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.cancel(id, request.reason(), request.version(), user));
    }

    record CreateRequest(@NotBlank @Size(max = 200) String title,
                         @Size(max = 5000) String description,
                         @NotNull Long campusId,
                         @Min(1) @Max(10000) int requiredCount,
                         @NotNull @Future LocalDateTime deadline) {
    }

    record VersionRequest(@Min(1) int version) {
    }

    record UpdateRequest(@NotBlank @Size(max = 200) String title,
                         @Size(max = 5000) String description,
                         @NotNull Long campusId,
                         @Min(1) @Max(10000) int requiredCount,
                         @NotNull @Future LocalDateTime deadline,
                         @Min(1) int version) {}

    record CancelRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }
}
