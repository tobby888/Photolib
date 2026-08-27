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
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                authService.touch(authenticated.sessionId());
            }
        }
        chain.doFilter(request, response);
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
}
