package cn.photolib.statistics.analysis;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 统计面板的工时分析。与成员统计同一条权限（{@code STATISTICS_DOWNLOAD}）。
 *
 * <p>分析本身是只读的，但条件树是一棵嵌套的 JSON，塞进查询串既难读又容易超长，所以用 POST 带请求体。
 * 它不改任何数据，审计拦截器把它和 MCP 轮询一样排除在外（见 {@code AuditInterceptor}）——
 * 统计面板每改一次条件就要重算一次，记下来只会把真正的写操作冲掉。</p>
 */
@RestController
@RequestMapping("/statistics/worklog-analysis")
@RequiredArgsConstructor
public class WorklogAnalysisController {
    private final WorklogAnalysisService service;

    @PostMapping
    @PreAuthorize("hasAuthority('STATISTICS_DOWNLOAD')")
    ApiResponse<WorklogAnalysisService.AnalysisResult> analyze(
            @RequestBody WorklogAnalysisService.AnalysisRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.analyze(request, user));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('STATISTICS_DOWNLOAD')")
    ApiResponse<WorklogAnalysisService.AnalysisOptions> options(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.options(from, to, user));
    }
}
