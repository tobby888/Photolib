package cn.photolib.dashboard;

import cn.photolib.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在线人数的来源：带着有效会话走到接口的请求，把这个账号记成「刚刚活跃」。
 *
 * <p>放在 MVC 拦截器而不是 {@link RequestMetricsFilter} 里，是因为过滤器排在 Spring Security
 * 之前，那时还不知道请求是谁发的；等过滤器链返回，安全上下文又已经被清掉了。</p>
 */
@Component
@RequiredArgsConstructor
public class ActiveUserInterceptor implements HandlerInterceptor {
    private final RequestMetrics metrics;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            metrics.markActive(user.id());
        }
        return true;
    }
}
