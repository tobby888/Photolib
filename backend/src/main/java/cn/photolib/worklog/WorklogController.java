package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class WorklogController {
    private final WorklogService service;

    @PostMapping("/requests/{requestId}/worklogs")
    @PreAuthorize("hasAuthority('WORKLOG_SUBMIT')")
    ApiResponse<WorklogEntity> create(@PathVariable Long requestId, @Valid @RequestBody WorklogRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(requestId, request.command(), user));
    }

    @GetMapping("/worklogs")
    @PreAuthorize("hasAnyAuthority('WORKLOG_SUBMIT','WORKLOG_CONFIRM','WORKLOG_EXPORT')")
    ApiResponse<PageResponse<WorklogEntity>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long requestId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) WorklogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, requestId, userId, status, from, to, user));
    }

    @PutMapping("/worklogs/{id}")
    @PreAuthorize("hasAuthority('WORKLOG_SUBMIT')")
    ApiResponse<WorklogEntity> update(@PathVariable Long id, @Valid @RequestBody WorklogRequest request,
                                      @RequestParam @Min(1) int version,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.command(), version, user));
    }

    @PostMapping("/worklogs/{id}/submit")
    @PreAuthorize("hasAuthority('WORKLOG_SUBMIT')")
    ApiResponse<WorklogEntity> submit(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.submit(id, request.version(), user));
    }

    @PostMapping("/worklogs/{id}/confirm")
    @PreAuthorize("hasAuthority('WORKLOG_CONFIRM')")
    ApiResponse<WorklogEntity> confirm(@PathVariable Long id, @Valid @RequestBody VersionRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.confirm(id, request.version(), user));
    }

    @PostMapping("/worklogs/{id}/reject")
    @PreAuthorize("hasAuthority('WORKLOG_CONFIRM')")
    ApiResponse<WorklogEntity> reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.reject(id, request.reason(), request.version(), user));
    }

    @DeleteMapping("/worklogs/{id}")
    @PreAuthorize("hasAnyAuthority('WORKLOG_SUBMIT','WORKLOG_CONFIRM')")
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    record WorklogRequest(@NotNull @PastOrPresent LocalDate workDate,
                          @NotNull Long memberContactId,
                          @Min(0) @Max(1440) int shootingMinutes,
                          @Min(0) @Max(1440) int retouchingMinutes,
                          @Size(max = 1000) String remark,
                          @NotNull WorklogStatus status) {
        WorklogService.WorklogCommand command() {
            return new WorklogService.WorklogCommand(
                    workDate, memberContactId,
                    shootingMinutes, retouchingMinutes, remark, status);
        }
    }

    record VersionRequest(@Min(1) int version) {
    }

    record RejectRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }
}
