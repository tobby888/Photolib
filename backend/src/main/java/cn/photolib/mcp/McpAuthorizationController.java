package cn.photolib.mcp;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 客户端的配对接口。流程和各步为什么长这样，见 {@link McpAuthorizationService}。
 *
 * <p>匿名与需登录的分界在这里：发起（{@code /authorizations}）和换令牌
 * （{@code /token*}）由客户端调用，那时它手上还没有任何身份，凭据是自己持有的
 * {@code deviceCode} / 刷新令牌；查看与批准（{@code /authorizations/{id}...}）
 * 由浏览器里已登录的成员调用，身份就是那个会话。两组的 permitAll 划分见
 * {@code SecurityConfig}。
 */
@RestController
@RequestMapping("/auth/mcp")
@RequiredArgsConstructor
public class McpAuthorizationController {
    private final McpAuthorizationService service;
    private final AuthService authService;

    @PostMapping("/authorizations")
    ApiResponse<McpAuthorizationService.Pairing> open(@Valid @RequestBody OpenRequest request,
                                                      HttpServletRequest servletRequest) {
        return ApiResponse.ok(service.open(request.clientName(), request.deviceLabel(),
                servletRequest.getRemoteAddr()));
    }

    /**
     * 批准页要展示的内容。任何登录会话都能查——它只说"有这么一条待批准的配对"，
     * 而批准需要的配对码不在返回里，所以这不是一条可以被利用的信息。
     */
    @GetMapping("/authorizations/{requestId}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<McpAuthorizationService.PendingView> describe(@PathVariable String requestId) {
        return ApiResponse.ok(service.describe(requestId));
    }

    @PostMapping("/authorizations/{requestId}/approve")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Void> approve(@PathVariable String requestId,
                              @Valid @RequestBody ApproveRequest request,
                              HttpServletRequest servletRequest,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        // 这条是整个流程里唯一值得事后追查的写操作："谁，在什么时候，把一个令牌
        // 交给了哪个客户端"。URL 里只有配对号，客户端名字得由这里补进审计详情；
        // 配对码当然不能写进去。
        auditDetail(servletRequest, requestId);
        service.approve(requestId, request.userCode(), user);
        return ApiResponse.ok();
    }

    @PostMapping("/authorizations/{requestId}/deny")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Void> deny(@PathVariable String requestId,
                           HttpServletRequest servletRequest,
                           @AuthenticationPrincipal AuthenticatedUser user) {
        auditDetail(servletRequest, requestId);
        service.deny(requestId, user);
        return ApiResponse.ok();
    }

    private void auditDetail(HttpServletRequest servletRequest, String requestId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mcpRequestId", requestId);
        try {
            McpAuthorizationService.PendingView pending = service.describe(requestId);
            detail.put("clientName", pending.clientName());
            detail.put("deviceLabel", pending.deviceLabel());
        } catch (RuntimeException ignored) {
            // 配对号本身就不对时照样留一条记录，详情少两个字段总比整条丢掉好。
        }
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, detail);
    }

    /** 客户端轮询换令牌。批准之前一直回 {@code PENDING}，不是错误。 */
    @PostMapping("/token")
    ApiResponse<McpAuthorizationService.Grant> token(@Valid @RequestBody TokenRequest request) {
        return ApiResponse.ok(service.poll(request.requestId(), request.deviceCode()));
    }

    /**
     * 续期。和浏览器那条 {@code /auth/refresh} 是同一套会话与轮换规则，区别只在
     * 刷新令牌从请求体里来——MCP 客户端不是浏览器，没有 Cookie 可用。
     */
    @PostMapping("/token/refresh")
    ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthService.TokenPair pair = authService.refresh(request.refreshToken());
        return ApiResponse.ok(new TokenResponse(pair.accessToken(), pair.refreshToken(),
                pair.expiresIn(), pair.user()));
    }

    /**
     * 注销这台设备。匿名可调，因为刷新令牌本身就是凭据；拿不到令牌的人也注销不了别人。
     * 成员在别的地方改密或被停用时，这条会话同样会跟着失效（{@code AuthService}）。
     */
    @PostMapping("/token/revoke")
    ApiResponse<Void> revoke(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.ok();
    }

    record OpenRequest(@NotBlank @Size(max = 100) String clientName,
                       @Size(max = 100) String deviceLabel) {
    }

    record ApproveRequest(@NotBlank @Size(max = 32) String userCode) {
    }

    record TokenRequest(@NotBlank @Size(max = 64) String requestId,
                        @NotBlank @Size(max = 64) String deviceCode) {
    }

    record RefreshRequest(@NotBlank @Size(max = 64) String refreshToken) {
    }

    record TokenResponse(String accessToken, String refreshToken, long expiresIn,
                         AuthenticatedUser user) {
    }
}
