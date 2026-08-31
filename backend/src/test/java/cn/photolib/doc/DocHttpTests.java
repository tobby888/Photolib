package cn.photolib.doc;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.doc.mapper.DocNodeMapper;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档接口走完整过滤器链的行为，用 MockMvc 而不是直接调 Service：
 * 这里验的恰恰是 Service 和控制器单测都看不到的那一段——SecurityConfig 的 permitAll
 * 覆盖了哪些路径、{@code AccessTokenFilter} 把一个还没分配权限组的会话交给控制器时
 * 它到底算不算"已登录"、以及 multipart 表单字段能不能绑上。
 */
@SpringBootTest
@Transactional
class DocHttpTests {
    private static final long MINISTER_ID = 9_951L;
    private static final byte[] PDF_BYTES = "%PDF-1.4 入部须知".getBytes(StandardCharsets.UTF_8);

    /** 手动装 MockMvc（而不是 @AutoConfigureMockMvc）：这个项目没有引入
     *  spring-boot-webmvc-test，而 springSecurity() 已经把整条安全过滤器链
     *  ——包括 AccessTokenFilter——装了进来，正是这些用例要验的那一段。 */
    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private DocService service;
    @Autowired private DocNodeMapper nodeMapper;
    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    /** 只为了给过滤器发一个"有效令牌"，不真的建会话。 */
    @MockitoBean private AuthService authService;

    private String publicId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'doc-http-minister', 'hash', '文档部长', 'MINISTER', TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        AuthenticatedUser minister = new AuthenticatedUser(MINISTER_ID, "doc-http-minister",
                "文档部长", UserRole.MINISTER, null, false);

        long id = service.createPdf(null, "入部须知",
                new MockMultipartFile("file", "入部须知.pdf", "application/pdf", PDF_BYTES),
                minister).focusId();
        service.setPublished(id, true, nodeMapper.selectById(id).getVersion(), minister);
        publicId = nodeMapper.selectById(id).getPublicId();
        assertThat(nodeMapper.selectById(id).getVisibility()).isEqualTo(DocVisibility.MEMBERS);

        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(4_242L, waitingForPermissions()));
    }

    @Test
    void anonymousVisitorsSeeNeitherTheMemberOnlyDocumentNorItsFile() throws Exception {
        mvc.perform(anonymous("/api/v1/public/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        // PDF 的直链就是它的正文，所以这里必须是 403 而不是文件。
        mvc.perform(anonymous("/api/v1/public/docs/" + publicId + "/file"))
                .andExpect(status().isForbidden());
    }

    /**
     * 本次改动的核心：一个已登录、但管理员还没给它分配权限组的账号，读文档时
     * 和普通成员一视同仁——目录里有仅成员文档，PDF 直链也给。
     */
    @Test
    void anAccountWaitingForItsPermissionGroupReadsMemberOnlyDocumentsAndTheirPdfs() throws Exception {
        mvc.perform(authorized("/api/v1/public/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("入部须知"))
                .andExpect(jsonPath("$.data[0].nodeType").value("PDF"))
                .andExpect(jsonPath("$.data[0].requiresLogin").value(true));

        mvc.perform(authorized("/api/v1/public/docs/" + publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileUrl")
                        .value("/api/v1/public/docs/" + publicId + "/file"));

        byte[] served = mvc.perform(authorized("/api/v1/public/docs/" + publicId + "/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PDF_BYTES);
    }

    /**
     * 放行只限于文档阅读。同一个令牌碰系统里的其他接口，仍然要拿到那句
     * "账号尚未分配可用权限组"——否则这条通道就成了绕过权限组的后门。
     */
    @Test
    void theSameTokenStillCannotReachTheRestOfTheSystem() throws Exception {
        mvc.perform(authorized("/api/v1/projects"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("尚未分配可用权限组")));
        // 文档的写接口在 /docs/** 下，要 DOC_MANAGE，同样挡住。
        mvc.perform(authorized("/api/v1/docs/tree"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder authorized(String path) {
        return anonymous(path).header("Authorization", "Bearer token");
    }

    /**
     * DispatcherServlet 映射在 `/` 上，所以线上 {@code getServletPath()} 就是完整路径；
     * MockMvc 不会替我们填这一项，而 {@code AccessTokenFilter} 正是按它判断
     * "这是不是一个登出的人也能读的文档接口"。不设的话这里会退化成一律 403，
     * 测出来的就不是线上的行为了。
     */
    private MockHttpServletRequestBuilder anonymous(String path) {
        return get(path).with(request -> {
            request.setServletPath(path);
            return request;
        });
    }

    /**
     * 上传接口走一遍真实的 multipart 请求。控制器单测只验授权，绑不绑得上
     * {@code title}/{@code parentId} 这两个表单字段要在这一层才看得出来。
     */
    @Test
    void aMinisterUploadsAPdfThroughARealMultipartRequest() throws Exception {
        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(4_243L, documentAuthor()));

        mvc.perform(multipart("/api/v1/docs/pdf")
                        .file(new MockMultipartFile("file", "流程规范.pdf", "application/pdf",
                                "%PDF-1.4 流程".getBytes(StandardCharsets.UTF_8)))
                        .param("title", "流程规范")
                        .header("Authorization", "Bearer token")
                        .with(request -> {
                            request.setServletPath("/api/v1/docs/pdf");
                            return request;
                        }))
                .andExpect(status().isOk())
                // 传上来就是草稿、就是仅成员可见，和 Markdown 文档同一条安全默认值。
                .andExpect(jsonPath("$.data.tree[?(@.title == '流程规范')].nodeType").value("PDF"))
                .andExpect(jsonPath("$.data.tree[?(@.title == '流程规范')].published").value(false))
                .andExpect(jsonPath("$.data.tree[?(@.title == '流程规范')].visibility").value("MEMBERS"));
    }

    private AuthenticatedUser documentAuthor() {
        return new AuthenticatedUser(MINISTER_ID, "doc-http-minister", "文档部长",
                UserRole.MINISTER, null, false, 2L, "MINISTER", "部长",
                DataScope.GLOBAL, Set.of(PermissionCode.DOC_MANAGE), Set.of());
    }

    private AuthenticatedUser waitingForPermissions() {
        return new AuthenticatedUser(9_952L, "newcomer", "新同学", UserRole.CAMPUS_MANAGER,
                null, false, null, "NO_ACCESS", "待分配权限", DataScope.NONE, Set.of(), Set.of());
    }
}
