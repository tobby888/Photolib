package cn.photolib.spa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端路径式深链走完整过滤器链的行为。这里验的是 Service 和控制器单测都看不到的
 * 那一段：{@link cn.photolib.SpaForwardController} 的清单和
 * {@link cn.photolib.auth.SecurityConfig} 的 permitAll 清单是不是都覆盖到了
 * {@code src/App.tsx} 里的每一条 {@code <Route path=...>}。
 *
 * <p>两边任缺一条，直接访问该地址就不是应用：漏在控制器是 404，漏在 permitAll
 * 是 401 的 JSON。所以断言同时要求 200 和 forward 到 /index.html。
 */
@SpringBootTest
class SpaForwardHttpTests {

    /** 手动装 MockMvc（而不是 @AutoConfigureMockMvc）：这个项目没有引入
     *  spring-boot-webmvc-test，而 springSecurity() 会把整条安全过滤器链装进来，
     *  匿名请求能不能过 permitAll 正是这些用例要验的。 */
    private MockMvc mvc;

    @Autowired private WebApplicationContext context;

    /** 和 src/App.tsx 的 <Route path=...> 一一对应，路径参数换成示例值。 */
    private static final List<String> SPA_PATHS = List.of(
            "/login",
            "/initial-password",
            "/recruitment",
            "/docs",
            "/docs/abc123",
            "/share/0123456789ABCDEFGHJKMNPQRS",
            "/projects",
            "/projects/42",
            "/requests",
            "/requests/42",
            "/photos",
            "/photos/batch-upload",
            "/photos/42",
            "/favorites",
            "/favorites/42",
            "/worklogs",
            "/directory",
            "/featured",
            "/featured/42",
            "/notifications",
            "/notifications/42",
            "/statistics",
            "/manager-campuses",
            "/recruitments",
            "/recruitments/42",
            "/recruitment-applications/42",
            "/documents",
            "/documents/abc123",
            "/admin");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @TestFactory
    Stream<DynamicTest> everyFrontendRouteIsForwardedToTheApplicationAnonymously() {
        return SPA_PATHS.stream().map(path -> DynamicTest.dynamicTest(path, () -> mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"))));
    }

    /**
     * permitAll 放开的只是"能拿到 index.html"这一层。同名前缀下的 API 仍然要鉴权，
     * 免得为了补深链顺手把接口也放开了。
     */
    @Test
    void apiEndpointsUnderTheSamePrefixesStillRequireAuthentication() throws Exception {
        for (String api : List.of("/api/v1/photos", "/api/v1/photos/42", "/api/v1/projects",
                "/api/v1/notifications", "/api/v1/statistics/summary", "/api/v1/manager-campuses",
                "/api/v1/directory/members", "/api/v1/featured/collections")) {
            mvc.perform(get(api)).andExpect(status().isUnauthorized());
        }
    }
}
