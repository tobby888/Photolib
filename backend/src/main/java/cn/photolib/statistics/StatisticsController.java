package cn.photolib.statistics;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StatisticsController {
    private final StatisticsService statistics;
    private final ExportService exports;

    @GetMapping("/statistics/members")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<List<StatisticsService.MemberStatistics>> members(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long projectId, @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) Long userId) {
        return ApiResponse.ok(statistics.members(from, to, projectId, campusId, userId));
    }

    @GetMapping("/statistics/overview")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<Map<String, Long>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(statistics.overview(from, to, projectId));
    }

    @PostMapping("/statistics/members/export")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<ExportJobEntity> export(@Valid @RequestBody ExportRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(exports.createStatistics(request.from(), request.to(),
                request.projectId(), request.campusId(), user));
    }

    @PostMapping("/worklogs/export")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<ExportJobEntity> exportWorklogs(@Valid @RequestBody WorklogExportRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(exports.createWorklogs(request.from(), request.to(), user));
    }

    @PostMapping("/photos/batch-download")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<ExportJobEntity> photoZip(@Valid @RequestBody PhotoZipRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(exports.createPhotoZip(request.photoIds(), user));
    }

    @GetMapping("/export-jobs/{id}")
    ApiResponse<ExportService.JobView> job(@PathVariable String id,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(exports.get(id, user));
    }

    record ExportRequest(LocalDate from, LocalDate to, Long projectId, Long campusId,
                         @NotNull String format) {}
    record WorklogExportRequest(@NotNull LocalDate from, @NotNull LocalDate to,
                                @NotNull String format) {}
    record PhotoZipRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds,
                           String purpose) {}
}
