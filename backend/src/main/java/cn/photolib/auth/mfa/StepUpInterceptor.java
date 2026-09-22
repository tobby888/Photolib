package cn.photolib.auth.mfa;

import cn.photolib.auth.AccessTokenFilter;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 执行 {@link RequiresStepUp}。
 *
 * <p>做成方法拦截器、排在 {@code @PreAuthorize} 之后（见 {@link MfaConfig}），而不是 MVC 的
 * HandlerInterceptor：后者跑在权限检查之前，没有权限的人会先被要求输验证码、输完才看到
 * "无权执行该操作"。现在权限不够的请求直接得到 403，只有真能执行的人才会被要求验证。
 */
public class StepUpInterceptor implements MethodInterceptor {
    /** 延迟取：这个拦截器是基础设施 bean，早于业务 bean 创建。 */
    private final ObjectProvider<MfaService> mfa;

    public StepUpInterceptor(ObjectProvider<MfaService> mfa) {
        this.mfa = mfa;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 未登录的请求交给 Spring Security 去拒绝，这里不抢着给出另一种答复。
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && !mfa.getObject().stepUpSatisfied(user, currentSessionId())) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED, "该操作需要先完成两步验证");
        }
        return invocation.proceed();
    }

    private static Long currentSessionId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        return attributes.getAttribute(AccessTokenFilter.SESSION_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                instanceof Long id ? id : null;
    }
}
