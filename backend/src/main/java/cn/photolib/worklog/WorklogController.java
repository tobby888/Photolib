package cn.photolib.worklog;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

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
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) WorklogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, requestId, projectId, userId, status, from, to, user));
    }

    @PutMapping("/worklogs/{id}")
    @PreAuthorize("hasAuthority('WORKLOG_SUBMIT')")
    ApiResponse<WorklogEntity> update(@PathVariable Long id, @Valid @RequestBody WorklogRequest request,
                                      @RequestParam @Min(1) int version,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.command(), version, user));
    }

    /**
     * 管理员更正已报上来的工时（姓名、学号、日期、时长、说明），状态不变。
     * 审计只看得到路径，改了哪些字段、从什么改成什么记进详情，事后能查到原值。
     */
    @PutMapping("/worklogs/{id}/correction")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<WorklogEntity> correct(@PathVariable Long id, @Valid @RequestBody CorrectionRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser user,
                                       HttpServletRequest http) {
        WorklogService.Correction correction = service.correct(id, request.command(), request.version(), user);
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("changes", correction.changes()));
        return ApiResponse.ok(correction.worklog());
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

    record CorrectionRequest(@NotBlank @Size(max = 100) String memberName,
                             @NotBlank @Size(max = 64) String memberStudentId,
                             @NotNull @PastOrPresent LocalDate workDate,
                             @Min(0) @Max(1440) int shootingMinutes,
                             @Min(0) @Max(1440) int retouchingMinutes,
                             @Size(max = 1000) String remark,
                             @Min(1) int version) {
        WorklogService.CorrectionCommand command() {
            return new WorklogService.CorrectionCommand(memberName, memberStudentId, workDate,
                    shootingMinutes, retouchingMinutes, remark);
        }
    }

    record VersionRequest(@Min(1) int version) {
    }

    record RejectRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }
}
