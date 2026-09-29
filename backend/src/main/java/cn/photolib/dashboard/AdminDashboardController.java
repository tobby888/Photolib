package cn.photolib.dashboard;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员面板里的数据面板：系统数据快照，以及每位管理员自己的面板布局。
 *
 * <p>与系统管理面板的其他接口一样只对系统管理员开放，并要求 15 分钟内做过两步验证（对他生效时）：
 * 流量、热门接口、在线人数都是运维信息，不该经一个偷来的会话读走。</p>
 */
@RestController
@RequestMapping("/admin-dashboard")
@PreAuthorize("hasRole('ADMIN')")
@RequiresStepUp
@RequiredArgsConstructor
public class AdminDashboardController {
    private final DashboardMetricsService metrics;
    private final DashboardLayoutService layouts;

    @GetMapping("/metrics")
    ApiResponse<DashboardMetricsService.DashboardMetrics> metrics() {
        return ApiResponse.ok(metrics.snapshot());
    }

    @GetMapping("/layout")
    ApiResponse<DashboardLayoutService.LayoutView> layout(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(layouts.get(user.id()));
    }

    @PutMapping("/layout")
    ApiResponse<DashboardLayoutService.LayoutView> saveLayout(@Valid @RequestBody SaveLayoutRequest request,
                                                              @AuthenticationPrincipal AuthenticatedUser user,
                                                              HttpServletRequest http) {
        DashboardLayoutService.LayoutView saved = layouts.save(user.id(), request.layout(), request.version());
        // 审计只看得到路径；记下是谁的布局、放了几个小面板，查「面板怎么变了」时有据可依。
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE,
                Map.of("owner", user.id(), "widgets", request.layout().widgets().size()));
        return ApiResponse.ok(saved);
    }

    @DeleteMapping("/layout")
    ApiResponse<Void> resetLayout(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest http) {
        layouts.reset(user.id());
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("owner", user.id(), "reset", true));
        return ApiResponse.ok();
    }

    record SaveLayoutRequest(@NotNull @Valid DashboardLayoutService.Layout layout, Integer version) {
    }
}
