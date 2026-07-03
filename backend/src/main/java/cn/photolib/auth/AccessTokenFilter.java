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
                if (user.mustChangePassword() && !isAllowedBeforePasswordChange(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"code\":\"FORBIDDEN\",\"message\":\"首次登录必须先修改密码\",\"details\":[]}");
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                authService.touch(authenticated.sessionId());
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowedBeforePasswordChange(String path) {
        return path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/initial-password")
                || path.equals("/api/v1/auth/logout");
    }
}
