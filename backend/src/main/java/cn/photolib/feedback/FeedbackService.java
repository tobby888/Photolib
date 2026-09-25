package cn.photolib.feedback;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.feedback.mapper.FeedbackMapper;
import cn.photolib.feedback.mapper.FeedbackReplyMapper;
import cn.photolib.feedback.mapper.FeedbackStatusChangeMapper;
import cn.photolib.feedback.model.FeedbackCategory;
import cn.photolib.feedback.model.FeedbackEntity;
import cn.photolib.feedback.model.FeedbackReplyEntity;
import cn.photolib.feedback.model.FeedbackStatus;
import cn.photolib.feedback.model.FeedbackStatusChangeEntity;
import cn.photolib.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 网站问题反馈。把消息中心从「单向通知」扩成「有回复、有状态的轻量工单」。
 *
 * <ul>
 *   <li>提交、查看自己、回复自己的反馈：任何已登录、能进系统的账号。</li>
 *   <li>看全量、改状态：仅 {@code ADMIN}（{@code hasRole('ADMIN')}，即权限组 code 为 ADMIN）。</li>
 * </ul>
 *
 * <p>通知复用 {@link NotificationService}：新反馈推给所有 ADMIN，回复与状态变更推给提交人，
 * 外发沿用「绑了企业微信才外发，否则只站内」的既有规则。</p>
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_CONTENT_CHARS = 20000;
    static final int MAX_PAGE_SIZE = 100;
    static final int RATE_LIMIT_PER_MINUTE = 1;
    static final int RATE_LIMIT_PER_DAY = 20;
    /**
     * 提交人追加回复的频率上限。每条追加都会给所有 ADMIN 发站内信 + 企业微信，
     * 不设上限就能拿回复刷屏；ADMIN 回复只通知一个人，不受此限。
     */
    static final int REPLY_LIMIT_PER_MINUTE = 3;
    static final int REPLY_LIMIT_PER_DAY = 50;

    /** 「管理员」= 权限组 code 为 ADMIN，与 {@code AccessTokenFilter} 授予 ROLE_ADMIN 的口径一致。 */
    private static final String ADMIN_IDS_FROM = """
            SELECT DISTINCT u.id FROM app_user u
            JOIN permission_group pg ON pg.id = u.permission_group_id
            WHERE pg.code = 'ADMIN' AND u.enabled = TRUE AND u.deleted = FALSE
            """;

    private final FeedbackMapper feedbackMapper;
    private final FeedbackReplyMapper replyMapper;
    private final FeedbackStatusChangeMapper statusChangeMapper;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    // ------------------------------------------------------------------
    // 提交 / 读取 / 回复 / 状态
    // ------------------------------------------------------------------

    @Transactional
    public FeedbackView submit(String title, String category, String contentHtml,
                               AuthenticatedUser user) {
        String cleanTitle = normalizeTitle(title);
        FeedbackCategory cleanCategory = parseCategory(category);
        String safeHtml = NotificationService.sanitizeMessageHtml(contentHtml);
        String plain = Jsoup.parse(safeHtml).text();
        requireNonEmpty(plain, safeHtml);
        lockSubmitter(user.id());
        requireWithinRateLimit(user.id());

        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setSubmitterId(user.id());
        feedback.setTitle(cleanTitle);
        feedback.setCategory(cleanCategory);
        feedback.setStatus(FeedbackStatus.PENDING);
        feedback.setContent(plain);
        feedback.setContentHtml(safeHtml);
        feedbackMapper.insert(feedback);

        notifyAdmins("FEEDBACK_CREATED",
                "新的网站反馈：" + cleanTitle,
                "提交人 " + user.displayName() + " 反馈：" + cleanTitle + "（" + categoryLabel(cleanCategory) + "）",
                feedback.getId());
        return get(feedback.getId(), user);
    }

    public PageResponse<FeedbackSummary> list(String status, int page, int pageSize,
                                              AuthenticatedUser user) {
        String filter = normalizeStatusFilter(status);
        Long submitterId = user.isAdministrator() ? null : user.id();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        long total = feedbackMapper.count(submitterId, filter);
        List<FeedbackSummary> items = feedbackMapper
                .list(submitterId, filter, safeSize, (long) (safePage - 1) * safeSize).stream()
                .map(this::toSummary)
                .toList();
        return new PageResponse<>(items, safePage, safeSize, total,
                total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    }

    public FeedbackView get(long id, AuthenticatedUser user) {
        FeedbackEntity feedback = requireFeedback(id, user);
        List<FeedbackReplyView> replies = replyMapper.findByFeedbackId(id).stream()
                .map(this::toReply)
                .toList();
        List<FeedbackStatusChangeView> changes = statusChangeMapper.findByFeedbackId(id).stream()
                .map(this::toStatusChange)
                .toList();
        return toView(feedback, replies, changes);
    }

    @Transactional
    public FeedbackView reply(long id, String contentHtml, AuthenticatedUser user) {
        FeedbackEntity feedback = requireFeedback(id, user);
        String safeHtml = NotificationService.sanitizeMessageHtml(contentHtml);
        String plain = Jsoup.parse(safeHtml).text();
        requireNonEmpty(plain, safeHtml);
        if (!user.isAdministrator()) {
            lockSubmitter(user.id());
            requireWithinReplyLimit(user.id());
        }

        FeedbackReplyEntity reply = new FeedbackReplyEntity();
        reply.setFeedbackId(id);
        reply.setAuthorId(user.id());
        reply.setContent(plain);
        reply.setContentHtml(safeHtml);
        reply.setCreatedAt(LocalDateTime.now());
        replyMapper.insert(reply);

        String snippet = snippet(plain, 120);
        if (user.isAdministrator()) {
            notifySubmitter(feedback.getSubmitterId(), "FEEDBACK_REPLIED",
                    "你的反馈有了新回复：" + feedback.getTitle(),
                    "管理员回复：" + snippet, id);
        } else {
            notifyAdmins("FEEDBACK_UPDATED",
                    "反馈有新回复：" + feedback.getTitle(),
                    "提交人 " + user.displayName() + " 追加：" + snippet, id);
        }
        return get(id, user);
    }

    @Transactional
    public FeedbackView changeStatus(long id, String status, int version, AuthenticatedUser user) {
        if (!user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员能修改反馈状态");
        }
        FeedbackEntity feedback = requireFeedback(id, user);
        FeedbackStatus target = parseStatus(status);
        requireValidTransition(feedback.getStatus(), target);

        int updated = feedbackMapper.updateStatus(id, target.name(), version, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "反馈已被其他操作修改，请刷新后重试");
        }

        FeedbackStatusChangeEntity change = new FeedbackStatusChangeEntity();
        change.setFeedbackId(id);
        change.setFromStatus(feedback.getStatus());
        change.setToStatus(target);
        change.setOperatorId(user.id());
        change.setCreatedAt(LocalDateTime.now());
        statusChangeMapper.insert(change);

        notifySubmitter(feedback.getSubmitterId(), "FEEDBACK_STATUS_CHANGED",
                "反馈状态更新：" + feedback.getTitle(),
                "状态由「" + statusLabel(feedback.getStatus()) + "」变为「" + statusLabel(target) + "」",
                id);
        return get(id, user);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private FeedbackEntity requireFeedback(long id, AuthenticatedUser user) {
        FeedbackEntity feedback = feedbackMapper.findById(id);
        if (feedback == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "反馈不存在");
        }
        if (!user.isAdministrator() && !feedback.getSubmitterId().equals(user.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能查看自己提交的反馈");
        }
        return feedback;
    }

    /**
     * 限流是「先数再插」，两个并发请求会同时数到 0、一起插进去。锁住提交人自己那一行
     * {@code app_user}，把同一个人的提交 / 追加串行化；不同人之间互不影响，锁随事务释放。
     */
    private void lockSubmitter(long userId) {
        jdbc.sql("SELECT id FROM app_user WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(Long.class)
                .optional();
    }

    private void requireWithinReplyLimit(long userId) {
        LocalDateTime now = LocalDateTime.now();
        if (replyMapper.countByAuthorSince(userId, now.minusSeconds(60)) >= REPLY_LIMIT_PER_MINUTE) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "回复太频繁，请稍后再试");
        }
        if (replyMapper.countByAuthorSince(userId, now.minusDays(1)) >= REPLY_LIMIT_PER_DAY) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "今天的回复已达上限");
        }
    }

    private void requireWithinRateLimit(long userId) {
        LocalDateTime now = LocalDateTime.now();
        if (feedbackMapper.countSince(userId, now.minusSeconds(60)) >= RATE_LIMIT_PER_MINUTE) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "提交太频繁，请稍后再试");
        }
        if (feedbackMapper.countSince(userId, now.minusDays(1)) >= RATE_LIMIT_PER_DAY) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "今天提交的反馈已达上限");
        }
    }

    private void notifyAdmins(String event, String subject, String body, long feedbackId) {
        String actionUrl = "/notifications/feedback/" + feedbackId;
        adminIds().forEach(adminId -> notifications.notifyUser(
                adminId, event, subject, NotificationService.paragraphs(body), actionUrl));
    }

    private void notifySubmitter(long submitterId, String event, String subject,
                                 String body, long feedbackId) {
        notifications.notifyUser(submitterId, event, subject, NotificationService.paragraphs(body),
                "/notifications/feedback/" + feedbackId);
    }

    private List<Long> adminIds() {
        return jdbc.sql(ADMIN_IDS_FROM).query(Long.class).list();
    }

    private void requireNonEmpty(String plain, String safeHtml) {
        if ((plain == null || plain.isBlank()) && !safeHtml.contains("<img")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容不能为空");
        }
    }

    private String normalizeTitle(String title) {
        String cleaned = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标题不能为空");
        }
        if (cleaned.length() > MAX_TITLE_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "标题不能超过 " + MAX_TITLE_CHARS + " 个字符");
        }
        return cleaned;
    }

    private FeedbackCategory parseCategory(String category) {
        try {
            return FeedbackCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分类必须是「问题」或「建议」");
        }
    }

    private FeedbackStatus parseStatus(String status) {
        try {
            return FeedbackStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "状态不合法");
        }
    }

    private String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        return parseStatus(status).name();
    }

    private void requireValidTransition(FeedbackStatus from, FeedbackStatus to) {
        boolean allowed = switch (from) {
            case PENDING -> to == FeedbackStatus.IN_PROGRESS || to == FeedbackStatus.RESOLVED;
            case IN_PROGRESS -> to == FeedbackStatus.RESOLVED;
            case RESOLVED -> to == FeedbackStatus.IN_PROGRESS;
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "不允许的状态流转（" + statusLabel(from) + " → " + statusLabel(to) + "）");
        }
    }

    private static String categoryLabel(FeedbackCategory category) {
        return switch (category) {
            case ISSUE -> "问题";
            case SUGGESTION -> "建议";
        };
    }

    private static String statusLabel(FeedbackStatus status) {
        return switch (status) {
            case PENDING -> "待处理";
            case IN_PROGRESS -> "处理中";
            case RESOLVED -> "已解决";
        };
    }

    private static String snippet(String plain, int max) {
        if (plain == null || plain.isBlank()) return "";
        return plain.length() <= max ? plain : plain.substring(0, max) + "…";
    }

    private FeedbackSummary toSummary(FeedbackEntity feedback) {
        return new FeedbackSummary(
                feedback.getId(),
                feedback.getSubmitterId(),
                feedback.getSubmitterDisplayName(),
                feedback.getTitle(),
                feedback.getCategory().name(),
                feedback.getStatus().name(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt(),
                feedback.getVersion() == null ? 1 : feedback.getVersion());
    }

    private FeedbackReplyView toReply(FeedbackReplyEntity reply) {
        return new FeedbackReplyView(
                reply.getId(),
                reply.getAuthorId(),
                reply.getAuthorDisplayName(),
                reply.getContent(),
                reply.getContentHtml(),
                reply.getCreatedAt());
    }

    private FeedbackStatusChangeView toStatusChange(FeedbackStatusChangeEntity change) {
        return new FeedbackStatusChangeView(
                change.getId(),
                change.getFromStatus() == null ? null : change.getFromStatus().name(),
                change.getToStatus().name(),
                change.getOperatorId(),
                change.getOperatorDisplayName(),
                change.getCreatedAt());
    }

    private FeedbackView toView(FeedbackEntity feedback, List<FeedbackReplyView> replies,
                                List<FeedbackStatusChangeView> changes) {
        return new FeedbackView(
                feedback.getId(),
                feedback.getSubmitterId(),
                feedback.getSubmitterDisplayName(),
                feedback.getTitle(),
                feedback.getCategory().name(),
                feedback.getStatus().name(),
                feedback.getContent(),
                feedback.getContentHtml(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt(),
                feedback.getVersion() == null ? 1 : feedback.getVersion(),
                replies,
                changes);
    }

    public record FeedbackSummary(long id, long submitterId, String submitterName, String title,
                                  String category, String status, LocalDateTime createdAt,
                                  LocalDateTime updatedAt, int version) {
    }

    public record FeedbackReplyView(long id, long authorId, String authorName, String content,
                                    String contentHtml, LocalDateTime createdAt) {
    }

    public record FeedbackStatusChangeView(long id, String fromStatus, String toStatus,
                                           long operatorId, String operatorName,
                                           LocalDateTime createdAt) {
    }

    public record FeedbackView(long id, long submitterId, String submitterName, String title,
                               String category, String status, String content, String contentHtml,
                               LocalDateTime createdAt, LocalDateTime updatedAt, int version,
                               List<FeedbackReplyView> replies,
                               List<FeedbackStatusChangeView> statusChanges) {
    }
}
