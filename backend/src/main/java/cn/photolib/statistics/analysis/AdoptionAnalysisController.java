package cn.photolib.statistics.analysis;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 统计面板的被引分析。与成员统计同一条权限（{@code STATISTICS_DOWNLOAD}）。
 * 条件都是简单的单值，用 GET；审计拦截器只记写操作，这里不需要豁免。
 */
@RestController
@RequestMapping("/statistics/adoption-analysis")
@RequiredArgsConstructor
public class AdoptionAnalysisController {
    private final AdoptionAnalysisService service;

    @GetMapping
    @PreAuthorize("hasAuthority('STATISTICS_DOWNLOAD')")
    ApiResponse<AdoptionAnalysisService.AnalysisResult> analyze(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AdoptionAnalysisService.Basis basis,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) AdoptionAnalysisService.Granularity granularity,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.analyze(new AdoptionAnalysisService.Query(from, to, basis, projectId,
                campusId, studentId, granularity), user));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('STATISTICS_DOWNLOAD')")
    ApiResponse<AdoptionAnalysisService.AnalysisOptions> options(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AdoptionAnalysisService.Basis basis,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.options(from, to, basis, user));
    }
}
