package cn.photolib.doc.file;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.config.EndpointUploadLimitFilter;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件库接口走完整过滤器链：SecurityConfig 放行了哪些路径、{@code AccessTokenFilter} 把匿名 /
 * 尚未分配权限组的会话交给控制器时算不算"已登录"、multipart 的名单字段能不能绑上，
 * 以及按管理员限额早筛请求体的过滤器。
 */
@SpringBootTest
@Transactional
class DocFileHttpTests {
    private static final long UPLOADER_ID = 9_971L;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private DocFileService service;
    @Autowired private UploadLimitService limits;
    @Autowired private EndpointUploadLimitFilter uploadLimitFilter;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuthService authService;

    private AuthenticatedUser uploader;
    private String publicFile;
    private String membersFile;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .addFilters(uploadLimitFilter).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'doc-file-http', 'hash', '文件上传者', 'MINISTER', TRUE, FALSE)
                """).param("id", UPLOADER_ID).update();
        // 审计日志的操作人有外键，测试里用到的账号都得真的在库里。
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (9972, 'doc-file-newcomer', 'hash', '新同学', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).update();
        uploader = new AuthenticatedUser(UPLOADER_ID, "doc-file-http", "文件上传者", UserRole.MINISTER, null, false,
                2L, "MINISTER", "部长", DataScope.GLOBAL, Set.of(PermissionCode.FILE_UPLOAD), Set.of());
        publicFile = service.upload(file("公开手册.pdf"), null, null, DocVisibility.PUBLIC, null, null, uploader)
                .publicId();
        membersFile = service.upload(file("内部手册.pdf"), null, null, DocVisibility.MEMBERS, null, null, uploader)
                .publicId();
    }

    @Test
    void anonymousVisitorsListAndDownloadOnlyFilesOpenToEveryone() throws Exception {
        mvc.perform(path(get("/api/v1/public/doc-files"), "/api/v1/public/doc-files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("公开手册"));

        mvc.perform(download(publicFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl", startsWith("http://localhost:8080/api/v1/local-storage/")))
                .andExpect(jsonPath("$.data.fileName").value("公开手册.pdf"));
        mvc.perform(download(membersFile))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("登录")));
    }

    /** 尚未分配权限组的账号和读文档时一样，算"已登录"，"登录后"的文件下载得到。 */
    @Test
    void anAccountWaitingForItsPermissionGroupDownloadsLoggedInFiles() throws Exception {
        when(authService.authenticate(anyString())).thenReturn(new AuthService.SessionAuthentication(4_301L,
                new AuthenticatedUser(9_972L, "newcomer", "新同学", UserRole.CAMPUS_MANAGER, null, false, null,
                        "NO_ACCESS", "待分配权限", DataScope.NONE, Set.of(), Set.of())));

        mvc.perform(path(get("/api/v1/public/doc-files"), "/api/v1/public/doc-files")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(download(membersFile).header("Authorization", "Bearer token"))
                .andExpect(status().isOk());
        // 但上传接口照样被挡在"尚未分配可用权限组"外面。
        mvc.perform(path(multipart("/api/v1/doc-files").file(file("偷传.txt")), "/api/v1/doc-files")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadingNeedsALoginAndFileUpload() throws Exception {
        mvc.perform(path(multipart("/api/v1/doc-files").file(file("匿名.txt")), "/api/v1/doc-files"))
                .andExpect(status().isUnauthorized());

        when(authService.authenticate(anyString())).thenReturn(new AuthService.SessionAuthentication(4_302L,
                new AuthenticatedUser(UPLOADER_ID, "doc-file-http", "文件上传者", UserRole.MINISTER, null, false,
                        2L, "MINISTER", "部长", DataScope.GLOBAL, Set.of(PermissionCode.PHOTO_VIEW), Set.of())));
        mvc.perform(path(multipart("/api/v1/doc-files").file(file("没权限.txt")), "/api/v1/doc-files")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    /** 名单字段可以重复出现，绑成集合；不存在的人整批拒绝。 */
    @Test
    void aRealMultipartUploadBindsTheReaderList() throws Exception {
        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(4_303L, uploader));

        mvc.perform(path(multipart("/api/v1/doc-files").file(file("指定.txt")), "/api/v1/doc-files")
                        .param("title", "只给自己")
                        .param("visibility", "RESTRICTED")
                        .param("userIds", String.valueOf(UPLOADER_ID))
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("只给自己"))
                .andExpect(jsonPath("$.data.visibility").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.readerUserIds[0]").value(UPLOADER_ID))
                .andExpect(jsonPath("$.data.canManage").value(true));

        mvc.perform(path(multipart("/api/v1/doc-files").file(file("不存在.txt")), "/api/v1/doc-files")
                        .param("visibility", "RESTRICTED")
                        .param("userIds", "987654321")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest());
    }

    /** 请求体按管理员设的单文件上限早筛：Spring 还没把 multipart 落盘就回 413。 */
    @Test
    void oversizedUploadsAreRejectedBeforeTheBodyIsParsed() throws Exception {
        limits.update(Map.of(UploadLimit.FILE_MAX_BYTES.name(), 1024L * 1024), null);
        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(4_304L, uploader));

        mvc.perform(path(multipart("/api/v1/doc-files")
                        .file(new MockMultipartFile("file", "大文件.bin", "application/octet-stream",
                                new byte[1024 * 1024 + 128 * 1024])), "/api/v1/doc-files")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    private MockHttpServletRequestBuilder download(String publicId) {
        String target = "/api/v1/public/doc-files/" + publicId + "/download";
        return path(post(target), target);
    }

    /** 同 DocHttpTests：MockMvc 不会替我们填 servletPath，而 AccessTokenFilter 的分享早退要看它。 */
    private static <T extends AbstractMockHttpServletRequestBuilder<T>> T path(T builder, String servletPath) {
        builder.with(request -> {
            request.setServletPath(servletPath);
            return request;
        });
        return builder;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 手册".getBytes(StandardCharsets.UTF_8));
    }
}
