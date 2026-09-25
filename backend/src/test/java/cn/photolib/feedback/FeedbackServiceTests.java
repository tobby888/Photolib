package cn.photolib.feedback;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FeedbackServiceTests {
    private static final long SUBMITTER_ID = 821L;
    private static final long OTHER_ID = 823L;
    private static final long ADMIN_ID = 822L;

    @Autowired
    private FeedbackService service;
    @Autowired
    private JdbcClient jdbc;

    private final AuthenticatedUser submitter = new AuthenticatedUser(
            SUBMITTER_ID, "feedback-submitter", "提交人", UserRole.CAMPUS_MANAGER, null, false);
    private final AuthenticatedUser other = new AuthenticatedUser(
            OTHER_ID, "feedback-other", "其他人", UserRole.CAMPUS_MANAGER, null, false);
    private final AuthenticatedUser admin = new AuthenticatedUser(
            ADMIN_ID, "feedback-admin", "管理员", UserRole.ADMIN, null, false);

    @BeforeEach
    void setUp() {
        Long adminGroup = jdbc.sql("SELECT id FROM permission_group WHERE code='ADMIN'")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, permission_group_id,
                     enabled, must_change_password)
                VALUES
                    (:submitter, 'feedback-submitter', 'hash', '提交人', 'CAMPUS_MANAGER', NULL, true, false),
                    (:other, 'feedback-other', 'hash', '其他人', 'CAMPUS_MANAGER', NULL, true, false),
                    (:admin, 'feedback-admin', 'hash', '管理员', 'ADMIN', :adminGroup, true, false)
                """)
                .param("submitter", SUBMITTER_ID)
                .param("other", OTHER_ID)
                .param("admin", ADMIN_ID)
                .param("adminGroup", adminGroup)
                .update();
    }

    @Test
    void submit_shouldCreatePendingFeedbackAndNotifyAdmins() {
        var view = service.submit("网站打不开", "ISSUE", "<p>登录页一直转圈</p>", submitter);

        assertThat(view.title()).isEqualTo("网站打不开");
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.replies()).isEmpty();
        assertThat(service.list(null, 1, 20, admin).items()).extracting(FeedbackService.FeedbackSummary::id)
                .containsExactly(view.id());
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id = :admin AND event_type = 'FEEDBACK_CREATED'
                """).param("admin", ADMIN_ID).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void list_shouldIsolateMembersButShowAdminEverything() {
        long mine = service.submit("我的反馈", "SUGGESTION", "<p>建议</p>", submitter).id();
        long otherFeedback = service.submit("别人的反馈", "ISSUE", "<p>问题</p>", other).id();

        assertThat(service.list(null, 1, 20, submitter).items()).extracting(FeedbackService.FeedbackSummary::id)
                .containsExactly(mine);
        assertThat(service.list(null, 1, 20, admin).items()).extracting(FeedbackService.FeedbackSummary::id)
                .containsExactlyInAnyOrder(mine, otherFeedback);
    }

    @Test
    void get_shouldRejectNonParticipant() {
        long id = service.submit("隐私反馈", "ISSUE", "<p>内容</p>", submitter).id();

        assertThat(service.get(id, admin).id()).isEqualTo(id);
        assertThatThrownBy(() -> service.get(id, other))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能查看自己提交的反馈");
    }

    @Test
    void reply_shouldNotifyTheOtherSide() {
        long id = service.submit("需要回复", "ISSUE", "<p>正文</p>", submitter).id();

        service.reply(id, "<p>管理员已收到</p>", admin);
        assertThat(service.get(id, submitter).replies()).hasSize(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id = :submitter AND event_type = 'FEEDBACK_REPLIED'
                """).param("submitter", SUBMITTER_ID).query(Long.class).single()).isEqualTo(1);

        service.reply(id, "<p>提交人补充说明</p>", submitter);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id = :admin AND event_type = 'FEEDBACK_UPDATED'
                """).param("admin", ADMIN_ID).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void changeStatus_shouldFollowTheStateMachine() {
        long id = service.submit("状态流转", "ISSUE", "<p>正文</p>", submitter).id();

        assertThat(service.changeStatus(id, "IN_PROGRESS", 1, admin).status()).isEqualTo("IN_PROGRESS");
        assertThat(service.changeStatus(id, "RESOLVED", 2, admin).status()).isEqualTo("RESOLVED");
        assertThat(service.changeStatus(id, "IN_PROGRESS", 3, admin).status()).isEqualTo("IN_PROGRESS");
        assertThat(service.get(id, submitter).statusChanges()).hasSize(3);

        assertThatThrownBy(() -> service.changeStatus(id, "PENDING", 4, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许的状态流转");
    }

    @Test
    void changeStatus_shouldRejectStaleVersion() {
        long id = service.submit("并发冲突", "ISSUE", "<p>正文</p>", submitter).id();

        service.changeStatus(id, "IN_PROGRESS", 1, admin);
        assertThatThrownBy(() -> service.changeStatus(id, "RESOLVED", 1, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他操作修改");
    }

    @Test
    void list_shouldPage() {
        long first = service.submit("第一条", "ISSUE", "<p>一</p>", submitter).id();
        long second = service.submit("第二条", "ISSUE", "<p>二</p>", other).id();

        var page = service.list(null, 1, 1, admin);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(service.list(null, 2, 1, admin).items()).extracting(FeedbackService.FeedbackSummary::id)
                .doesNotContain(page.items().getFirst().id())
                .hasSize(1);
        assertThat(java.util.List.of(first, second)).contains(page.items().getFirst().id());
    }

    @Test
    void submit_shouldEnforcePerMinuteLimit() {
        service.submit("第一条", "ISSUE", "<p>一</p>", submitter);

        assertThatThrownBy(() -> service.submit("第二条", "ISSUE", "<p>二</p>", submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("提交太频繁");
    }

    @Test
    void submit_shouldEnforcePerDayLimit() {
        for (int i = 0; i < FeedbackService.RATE_LIMIT_PER_DAY; i++) {
            jdbc.sql("""
                    INSERT INTO feedback (id, submitter_id, title, content, content_html, category, status, created_at)
                    VALUES (:id, :submitter, '旧反馈', '正文', '<p>正文</p>', 'ISSUE', 'PENDING', :createdAt)
                    """)
                    .param("id", 9_400_000L + i)
                    .param("submitter", SUBMITTER_ID)
                    .param("createdAt", LocalDateTime.now().minusHours(2))
                    .update();
        }

        assertThatThrownBy(() -> service.submit("又一条", "ISSUE", "<p>正文</p>", submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("今天提交的反馈已达上限");
    }

    @Test
    void reply_shouldLimitSubmitterFollowUpsButNotAdmins() {
        long id = service.submit("追加限流", "ISSUE", "<p>正文</p>", submitter).id();
        for (int i = 0; i < FeedbackService.REPLY_LIMIT_PER_MINUTE; i++) {
            service.reply(id, "<p>补充 " + i + "</p>", submitter);
        }

        assertThatThrownBy(() -> service.reply(id, "<p>再补充</p>", submitter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回复太频繁");
        for (int i = 0; i <= FeedbackService.REPLY_LIMIT_PER_MINUTE; i++) {
            service.reply(id, "<p>管理员回复 " + i + "</p>", admin);
        }
        assertThat(service.get(id, admin).replies())
                .hasSize(FeedbackService.REPLY_LIMIT_PER_MINUTE * 2 + 1);
    }
}
