package cn.photolib.share;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.mail.MailSender;
import cn.photolib.mail.OutgoingMail;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.WorklogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:worklog-mail;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Transactional
class ProjectShareWorklogMailTests {
    private static final String TITLE = "校园摄影 & 展览";
    private static final String AUTHOR = "邮件通知作者";
    private static final String EMAIL = "worklog-author@example.test";
    private static final long MINISTER_ID = 79_801L;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private ProjectService projects;
    @Autowired private ProjectShareService links;
    @Autowired private WorklogService worklogs;
    @MockitoBean private MailSender sender;

    private MockMvc mvc;
    private AuthenticatedUser minister;
    private ProjectShareService.ShareLinkView link;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(sender.configured()).thenReturn(true);
        addUser(MINISTER_ID, "邮件测试部长", "minister@example.test", "MINISTER");
        minister = new AuthenticatedUser(MINISTER_ID, "worklog-mail-" + MINISTER_ID, "邮件测试部长", UserRole.MINISTER, null, false);
        var project = projects.create(TITLE, "邮件通知测试", ProjectStatus.ACTIVE, minister);
        link = links.create(project.getId(), new ProjectShareService.CreateCommand(ShareLinkPurpose.UPLOAD,
                "外援摄影", "mail-test-pass", false, false, null), minister).link();
    }

    @Test
    void sendsAfterCommitToTheUniqueAuthorWithRequestMetadataAndSendsApprovalSeparately() throws Exception {
        addUser(79_802L, AUTHOR, EMAIL, "CAMPUS_MANAGER");
        addUser(79_803L, AUTHOR, "disabled@example.test", "CAMPUS_MANAGER");
        jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE id=79803").update();
        long worklogId = declare(AUTHOR, "20239801", 79_801L);
        addUser(79_804L, "两个同名号", "first@example.test", "CAMPUS_MANAGER");
        addUser(79_805L, "两个同名号", null, "CAMPUS_MANAGER");
        declare("两个同名号", "20239802", 79_802L);
        declare("没有系统账号", "20239803", 79_803L);
        addUser(79_806L, "没有绑定邮箱", null, "CAMPUS_MANAGER");
        declare("没有绑定邮箱", "20239804", 79_804L);
        verifyNoInteractions(sender);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        var submission = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(sender, timeout(5000)).send(submission.capture());
        assertThat(submission.getValue().to()).isEqualTo(EMAIL);
        assertThat(submission.getValue().subject()).contains("工时申报成功", TITLE);
        assertThat(submission.getValue().plainText()).contains(AUTHOR, "2 小时", "193.0.2.10", "请及时联系部长");
        assertThat(submission.getValue().html()).contains("校园摄影 &amp; 展览", "&lt;返工&gt;", "Browser &lt;UA&gt;",
                "cid:" + submission.getValue().inlineImages().getFirst().contentId())
                .doesNotContain("198.51.100.30", "<返工>", "<UA>");

        worklogs.confirm(worklogId, 1, minister);
        var sent = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(sender, after(1000).times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(mail -> assertThat(mail.to()).isEqualTo(EMAIL));
        var approval = sent.getValue();
        assertThat(approval.subject()).contains("工时审核通过", TITLE);
        assertThat(approval.plainText()).contains("邮件测试部长", "2 小时", "现场跟拍 <返工>").doesNotContain("193.0.2.10");

    }

    @Test
    void neverQueuesMailForARolledBackSubmission() throws Exception {
        addUser(79_802L, AUTHOR, EMAIL, "CAMPUS_MANAGER");
        declare(AUTHOR, "20239801", 79_801L);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        verifyNoInteractions(sender);
    }

    private long declare(String name, String student, long photoId) throws Exception {
        String base = "/api/v1/public/shares/" + link.token();
        var session = links.openSession(link.token(), "mail-test-pass", new ProjectShareService.GuestIdentity(name, student));
        jdbc.sql("""
                INSERT INTO photo (id, project_id, photographer_student_id, photographer_name, uploaded_by,
                    share_link_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES (:id, :project, :student, :name, :user, :link, CURRENT_TIMESTAMP, 2048,
                    'image/jpeg', :key, :sha, 'PROCESSING')
                """).param("id", photoId).param("project", link.projectId()).param("student", student).param("name", name)
                .param("user", MINISTER_ID).param("link", link.id()).param("key", "photos/mail-test-" + photoId)
                .param("sha", String.format("%064x", photoId)).update();
        String body = mvc.perform(post(base + "/worklogs")
                        .with(request -> { request.setRemoteAddr("193.0.2.10"); return request; })
                        .header("X-Real-IP", "198.51.100.30").header("User-Agent", "Browser <UA>")
                        .header(ProjectSharePublicController.SESSION_HEADER, session.sessionToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"workDate":"%s","shootingMinutes":90,"retouchingMinutes":30,"remark":"现场跟拍 <返工>"}
                                """.formatted(LocalDate.now())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("id").asLong();
    }

    private void addUser(long id, String name, String email, String role) {
        jdbc.sql("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled, must_change_password, email)
                VALUES (:id, :username, 'hash', :name, :role, TRUE, FALSE, :email)
                """).param("id", id).param("username", "worklog-mail-" + id)
                .param("name", name).param("email", email).param("role", role).update();
    }
}
