package cn.photolib.auth;

import cn.photolib.WebRoutingConfig;
import cn.photolib.common.error.GlobalExceptionHandler;
import cn.photolib.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(DeplytAuthorizationHttpTests.WebConfig.class)
@TestPropertySource(properties = {
        "DEPLYT_PUBLIC_URL=https://deploy.example.test",
        "DEPLYT_SSO_SECRET=example-deplyt-sso-key-for-test-only"
})
class DeplytAuthorizationHttpTests {
    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AccessTokenFilter.class, DeplytAuthorizationController.class,
            GlobalExceptionHandler.class, WebRoutingConfig.class})
    static class WebConfig {}

    private static final String ROOT = "/api/v1/auth/deplyt";
    private static final String SECRET = "example-deplyt-sso-key-for-test-only";
    private static final String STATE = "s".repeat(43);
    private static final String VERIFIER = "v".repeat(43);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private WebApplicationContext context;
    @MockitoBean private AuthService authService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockUser("admin", UserRole.ADMIN, false);
        mockUser("member", UserRole.MINISTER, false);
        mockUser("initial", UserRole.ADMIN, true);
    }

    @Test
    void onlyAdministratorsWithCompleteLoginsCanAuthorize() throws Exception {
        mvc.perform(post(ROOT + "/authorize").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        for (String token : new String[]{"member", "initial"}) {
            mvc.perform(post(ROOT + "/authorize").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(request()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void issuesOneCodeBoundToTheVerifierAndDeplytClient() throws Exception {
        String result = mvc.perform(post(ROOT + "/authorize").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        URI callback = URI.create(json.readTree(result).at("/data/callbackUrl").asText());
        assertThat(callback.toString()).matches("https://deploy\\.example\\.test/auth/callback\\?code=[A-Za-z0-9_-]{43}&state=" + STATE);
        String code = callback.getQuery().split("&")[0].substring("code=".length());

        mvc.perform(exchange(code, VERIFIER, "wrong-secret")).andExpect(status().isUnauthorized());
        mvc.perform(exchange(code, "x".repeat(43), SECRET)).andExpect(status().isUnauthorized());
        mvc.perform(exchange(code, VERIFIER, SECRET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("101"))
                .andExpect(jsonPath("$.data.username").value("admin"));
        mvc.perform(exchange(code, VERIFIER, SECRET)).andExpect(status().isUnauthorized());
    }

    private void mockUser(String token, UserRole role, boolean initial) {
        AuthenticatedUser user = new AuthenticatedUser(101L, token, token, role, null, initial);
        when(authService.authenticate(token)).thenReturn(new AuthService.SessionAuthentication(1L, user));
    }

    private String request() throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.UTF_8)));
        return json.createObjectNode().put("challenge", challenge).put("state", STATE)
                .put("redirectUri", "https://other.example.test").toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exchange(
            String code, String verifier, String secret) {
        return post(ROOT + "/token").header("X-Deplyt-Client-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("code", code).put("verifier", verifier).toString());
    }
}
