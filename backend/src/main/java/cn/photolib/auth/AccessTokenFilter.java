package cn.photolib.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AccessTokenFilter extends OncePerRequestFilter {
    private final AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            AuthService.SessionAuthentication authenticated =
                    authService.authenticate(authorization.substring(7));
            if (authenticated != null) {
                AuthenticatedUser user = authenticated.user();
                // 未改初始密码的会话在"登出的人也能读"的接口上退回匿名继续，
                // 而不是拿到 403——否则它比一个登出的访客还差，浏览器里存着令牌
                // 反而打不开公开文档。但它也不算成员：初始密码还没换掉的账号
                // 不该看到仅限成员的内容，所以是"不设置 principal"，
                // 而不是"放行并认证"。
                if (user.mustChangePassword() && isAnonymousReadableDocs(request)) {
                    chain.doFilter(request, response);
                    return;
                }
                // 尚未分配权限组（或权限组被移除）的账号读文档时算“已登录的成员”：
                // 它确实登录成功了，"要求登录才能看"的文档正是给这类刚入部、还没被
                // 分配权限的同学准备的。放行的方式是设置一个不带任何 authority 的
                // principal——身份足以让 DocService 收到 authenticated=true，
                // 而空权限保证它碰不到任何要权限码的接口（这里只对文档读接口生效，
                // 其余路径照旧在下面被 403 挡住）。
                if (!user.hasSystemAccess() && isAnonymousReadableDocs(request)) {
                    authenticate(user, List.of());
                    authService.touch(authenticated.sessionId());
                    chain.doFilter(request, response);
                    return;
                }
                if (user.mustChangePassword() && !isAllowedBeforePasswordChange(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"code\":\"FORBIDDEN\",\"message\":\"首次登录必须先修改密码\",\"details\":[]}");
                    return;
                }
                if (!user.hasSystemAccess() && !isAllowedWithoutSystemAccess(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"code\":\"FORBIDDEN\",\"message\":\"账号尚未分配可用权限组\",\"details\":[]}");
                    return;
                }
                var authorities = new ArrayList<SimpleGrantedAuthority>();
                if ("ADMIN".equals(user.permissionGroupCode())
                        || "MINISTER".equals(user.permissionGroupCode())
                        || "CAMPUS_MANAGER".equals(user.permissionGroupCode())) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + user.permissionGroupCode()));
                }
                user.permissions().forEach(permission ->
                        authorities.add(new SimpleGrantedAuthority(permission.name())));
                authenticate(user, authorities);
                authService.touch(authenticated.sessionId());
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(AuthenticatedUser user,
                              Collection<? extends SimpleGrantedAuthority> authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    private boolean isAllowedBeforePasswordChange(String path) {
        return path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/initial-password")
                || path.equals("/api/v1/auth/logout")
                || isPublicBranding(path);
    }

    private boolean isAllowedWithoutSystemAccess(String path) {
        return path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/initial-password")
                || path.equals("/api/v1/auth/password")
                || path.equals("/api/v1/auth/logout")
                || isPublicBranding(path);
    }

    // Branding is anonymous-readable, so a restricted session must not be worse
    // off than a logged-out visitor: these screens still show the product identity.
    private boolean isPublicBranding(String path) {
        return path.equals("/api/v1/branding") || path.equals("/api/v1/branding/icon");
    }

    /**
     * 文档中心的阅读接口，未登录也能调。只认 GET：写接口在 {@code /docs/**} 下，
     * 要 {@code DOC_MANAGE}，绝不能因为这条降级而被受限会话摸到。
     */
    private boolean isAnonymousReadableDocs(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getServletPath().startsWith("/api/v1/public/docs");
    }
}
