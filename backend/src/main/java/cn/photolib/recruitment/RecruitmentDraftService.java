package cn.photolib.recruitment;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.recruitment.mapper.RecruitmentApplicationMapper;
import cn.photolib.recruitment.mapper.RecruitmentDraftMapper;
import cn.photolib.recruitment.model.RecruitmentDraftEntity;
import cn.photolib.recruitment.model.RecruitmentDraftStatus;
import cn.photolib.recruitment.model.RecruitmentTaskEntity;
import cn.photolib.recruitment.model.RecruitmentTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class RecruitmentDraftService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RecruitmentDraftMapper mapper;
    private final RecruitmentApplicationMapper applicationMapper;
    private final RecruitmentTaskService taskService;
    private final Clock recruitmentClock;

    @Transactional
    public DraftTicket create(String publicId, String studentId) {
        RecruitmentTaskEntity task = taskService.requireActiveForDraftCreation(publicId);
        String normalizedStudentId = RecruitmentStudentId.normalize(studentId).value();

        String token = newToken();
        LocalDateTime now = now();
        RecruitmentDraftEntity draft = new RecruitmentDraftEntity();
        draft.setId(PublicId.next());
        draft.setTaskId(task.getId());
        draft.setNormalizedStudentId(normalizedStudentId);
        draft.setTokenHash(hashToken(token));
        draft.setStatus(RecruitmentDraftStatus.DRAFT);
        draft.setExpiresAt(task.getEndsAt());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        mapper.insert(draft);
        return new DraftTicket(draft.getId(), token, draft.getExpiresAt());
    }

    /**
     * Authenticates an anonymous draft and verifies that both its draft lifetime
     * and the owning task's half-open recruitment window are still active.
     */
    @Transactional
    public RecruitmentDraftEntity requireWritable(String publicId, String draftId, String rawToken) {
        RecruitmentTaskEntity task = taskService.requireByPublicId(publicId);
        RecruitmentDraftEntity draft = draftId == null ? null : mapper.selectById(draftId);
        return validateWritable(task, draft, rawToken);
    }

    /**
     * Serializes every anonymous mutation on the task row and then the draft
     * row. Holding both locks until the caller's transaction commits makes
     * submission, upload creation/completion, task close and deadline changes
     * observe one unambiguous lifecycle order.
     */
    @Transactional
    public RecruitmentDraftEntity requireWritableForMutation(
            String publicId, String draftId, String rawToken) {
        RecruitmentTaskEntity task = taskService.requireByPublicIdForUpdate(publicId);
        RecruitmentDraftEntity draft = draftId == null ? null : mapper.findByIdForUpdate(draftId);
        return validateWritable(task, draft, rawToken);
    }

    private RecruitmentDraftEntity validateWritable(RecruitmentTaskEntity task,
                                                      RecruitmentDraftEntity draft,
                                                      String rawToken) {
        if (draft == null || !task.getId().equals(draft.getTaskId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "招募申请草稿不存在");
        }
        if (!tokenMatches(draft.getTokenHash(), rawToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "招募申请草稿凭证无效");
        }

        LocalDateTime now = now();
        if (draft.getStatus() != RecruitmentDraftStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "招募申请草稿已提交或已失效");
        }
        if (!draft.getExpiresAt().isAfter(now)) {
            mapper.transition(draft.getId(), RecruitmentDraftStatus.DRAFT,
                    RecruitmentDraftStatus.EXPIRED, now);
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "招募申请草稿已过期");
        }
        if (task.getStatus() != RecruitmentTaskStatus.PUBLISHED
                || task.getStartsAt().isAfter(now)
                || !task.getEndsAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前招募任务不在开放时间内");
        }
        return draft;
    }

    public void requireNotSubmitted(long taskId, String normalizedStudentId) {
        if (applicationMapper.countByStudent(taskId, normalizedStudentId) > 0) {
            throw duplicateStudent();
        }
    }

    int markSubmitted(String draftId) {
        return mapper.transition(draftId, RecruitmentDraftStatus.DRAFT,
                RecruitmentDraftStatus.SUBMITTED, now());
    }

    static BusinessException duplicateStudent() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                "该学号已提交过本次招募，请勿重复提交");
    }

    private boolean tokenMatches(String expectedHash, String rawToken) {
        if (expectedHash == null || rawToken == null || rawToken.isBlank()) return false;
        byte[] expected;
        byte[] actual;
        try {
            expected = HexFormat.of().parseHex(expectedHash);
            actual = HexFormat.of().parseHex(hashToken(rawToken));
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(recruitmentClock);
    }

    public record DraftTicket(String draftId, String draftToken, LocalDateTime expiresAt) {
    }
}
