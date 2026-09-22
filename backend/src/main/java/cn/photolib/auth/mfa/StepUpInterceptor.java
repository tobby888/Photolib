package cn.photolib.auth.mfa;

import cn.photolib.auth.AccessTokenFilter;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 执行 {@link RequiresStepUp}。 */
@Component
@RequiredArgsConstructor
public class StepUpInterceptor implements HandlerInterceptor {
    private final MfaService mfa;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !requiresStepUp(method)) return true;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 未登录的请求交给 Spring Security 去拒绝，这里不抢着给出另一种答复。
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return true;
        }
        Long sessionId = request.getAttribute(AccessTokenFilter.SESSION_ID_ATTRIBUTE) instanceof Long id ? id : null;
        if (!mfa.stepUpSatisfied(user, sessionId)) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED, "该操作需要先完成两步验证");
        }
        return true;
    }

    static boolean requiresStepUp(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), RequiresStepUp.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequiresStepUp.class);
    }
}
