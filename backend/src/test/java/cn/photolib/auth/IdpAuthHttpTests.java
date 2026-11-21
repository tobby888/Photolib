package cn.photolib.auth;

import cn.photolib.WebRoutingConfig;
import cn.photolib.common.error.GlobalExceptionHandler;
import cn.photolib.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(IdpAuthHttpTests.WebConfig.class)
@TestPropertySource(properties = {
        "IDP_PUBLIC_URL='https://auth.example.test/'",
        "IDP_CLIENT_ID=photolib",
        "IDP_CLIENT_SECRET='example-photolib-idp-secret-for-test-only'"
})
class IdpAuthHttpTests {
    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AccessTokenFilter.class, IdpAuthController.class,
            GlobalExceptionHandler.class, WebRoutingConfig.class})
    static class WebConfig {}

    @Autowired private WebApplicationContext context;
    @MockitoBean private AuthService authService;
    @MockitoBean private UserService userService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void exposesIdpConfigurationAndStartsPkceAuthorizationFlow() throws Exception {
        mvc.perform(get("/api/v1/auth/idp/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.publicUrl").value("https://auth.example.test"))
                .andExpect(jsonPath("$.data.registerUrl").value("https://auth.example.test/#register"))
                .andExpect(jsonPath("$.data.clientId").value("photolib"));

        mvc.perform(post("/api/v1/auth/idp/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirectUri\":\"https://photolib.example.test/auth/callback\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("photolib_idp_state"))
                .andExpect(jsonPath("$.data.authorizeUrl").value(org.hamcrest.Matchers.startsWith(
                        "https://auth.example.test/oauth/authorize?client_id=photolib")));
    }

    @Test
    void stripsAccidentalSurroundingQuotesFromSecrets() {
        assertThat(DeplytAuthorizationController.stripQuotes("'secret-value'")).isEqualTo("secret-value");
        assertThat(DeplytAuthorizationController.stripQuotes("\"secret-value\"")).isEqualTo("secret-value");
        assertThat(DeplytAuthorizationController.stripQuotes("  bare-value  ")).isEqualTo("bare-value");
    }
}
