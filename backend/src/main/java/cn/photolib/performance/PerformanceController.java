package cn.photolib.performance;

import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员面板「性能分析」：实时窗口、一周分位数和性能快照。
 *
 * <p>只对系统管理员开放，并要求两步验证（对他生效时）。和数据备份一样刻意不设权限码：
 * 快照里有全部 SQL 形状和接口耗时，是运维信息，不能被下放给自定义权限组。</p>
 */
@RestController
@RequestMapping("/performance")
@PreAuthorize("hasRole('ADMIN')")
@RequiresStepUp
@RequiredArgsConstructor
public class PerformanceController {
    private final PerformanceAnalysisService analysis;
    private final PerformanceSnapshotService snapshots;

    @GetMapping("/live")
    ApiResponse<PerformanceAnalysisService.LiveView> live(@RequestParam(defaultValue = "60") int windowSeconds,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(analysis.live(windowSeconds, user));
    }

    @GetMapping("/weekly")
    ApiResponse<PerformanceAnalysisService.WeeklyView> weekly(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(analysis.weekly(user));
    }

    @GetMapping("/snapshots/current")
    ApiResponse<PerformanceSnapshotService.Overview> current(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(snapshots.overview(user));
    }

    @GetMapping("/snapshots")
    ApiResponse<PageResponse<PerformanceSnapshotService.SessionView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(snapshots.list(page, pageSize, user));
    }

    @GetMapping("/snapshots/{id}")
    ApiResponse<PerformanceSnapshotService.SessionView> get(@PathVariable String id,
                                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(snapshots.get(id, user));
    }

    @AuditAction("开始性能快照记录")
    @PostMapping("/snapshots")
    ApiResponse<PerformanceSnapshotService.SessionView> start(@Valid @RequestBody StartRequest request,
                                                              @AuthenticationPrincipal AuthenticatedUser user,
                                                              HttpServletRequest http) {
        PerformanceSnapshotService.SessionView started =
                snapshots.start(user, request.durationMinutes(), request.note());
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE,
                Map.of("durationMinutes", request.durationMinutes(), "plannedEndAt", started.plannedEndAt()));
        return ApiResponse.ok(started);
    }

    @AuditAction("停止性能快照记录")
    @PostMapping("/snapshots/{id}/stop")
    ApiResponse<PerformanceSnapshotService.SessionView> stop(@PathVariable String id,
                                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(snapshots.stop(id, user));
    }

    @AuditAction("重新导出性能快照")
    @PostMapping("/snapshots/{id}/retry-export")
    ApiResponse<PerformanceSnapshotService.SessionView> retryExport(@PathVariable String id,
                                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(snapshots.retryExport(id, user));
    }

    @AuditAction("下载性能快照")
    @GetMapping("/snapshots/{id}/download")
    ApiResponse<PerformanceSnapshotService.DownloadLink> download(@PathVariable String id,
                                                                  @RequestParam(defaultValue = "events") String file,
                                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(snapshots.downloadLink(id, file, user));
    }

    /** 时长按分钟给，最长 72 小时（4320 分钟）；服务层还会按配置再卡一次。 */
    record StartRequest(@Min(1) @Max(4320) long durationMinutes, @Size(max = 200) String note) {
    }
}
