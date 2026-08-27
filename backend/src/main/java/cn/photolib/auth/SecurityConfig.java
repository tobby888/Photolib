package cn.photolib.auth;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {
    private final AccessTokenFilter accessTokenFilter;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
                                "/login", "/initial-password", "/projects/**", "/requests/**",
                                "/photos", "/worklogs", "/notifications/**", "/statistics", "/admin",
                                "/recruitment", "/recruitments/**", "/recruitment-applications/**",
                                "/api", "/api/",
                                "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/actuator/health",
                                "/api/v1/local-storage/objects/**",
                                "/api/v1/branding/icon").permitAll()
                        // The login and public recruitment pages must render the
                        // administrator's branding before anyone is authenticated.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branding",
                                "/api/v1/branding/scheduled-icons/*/icon").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/public/recruitments",
                                "/api/v1/public/recruitments/*/drafts/*/batches/*").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/public/recruitments/*/drafts",
                                "/api/v1/public/recruitments/*/drafts/*/submit",
                                "/api/v1/public/recruitments/*/drafts/*/batches",
                                "/api/v1/public/recruitments/*/drafts/*/batches/*/complete").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                            "{\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或令牌失效\",\"details\":[]}");
                }).accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                            "{\"code\":\"FORBIDDEN\",\"message\":\"无权执行该操作\",\"details\":[]}");
                }))
                .addFilterBefore(accessTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
