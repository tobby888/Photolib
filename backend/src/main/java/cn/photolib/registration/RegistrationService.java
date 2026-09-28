package cn.photolib.registration;

import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.LikeFilter;
import cn.photolib.notification.NotificationService;
import cn.photolib.permission.PermissionGroupEntity;
import cn.photolib.permission.PermissionGroupService;
import cn.photolib.permission.mapper.PermissionGroupMapper;
import cn.photolib.registration.mapper.RegistrationApplicationMapper;
import cn.photolib.registration.mapper.RegistrationCodeMapper;
import cn.photolib.registration.model.RegistrationApplicationEntity;
import cn.photolib.registration.model.RegistrationCodeEntity;
import cn.photolib.registration.model.RegistrationStatus;
import cn.photolib.user.UserService;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 注册码与注册申请。
 *
 * <p>规则：
 * <ul>
 *   <li>注册码的数量、有效期（左闭右开）和注册后的权限组由管理员生成时定下；系统管理员组不能
 *       经注册码授予——注册码是要发到群里的东西，一旦外流，审核就成了唯一一道门。</li>
 *   <li>提交申请时用条件 UPDATE 占一个名额，并发提交不会超发；驳回归还名额，通过不归还。</li>
 *   <li>审核通过前不建 {@code app_user}：待审核的人不能登录、不出现在账号列表里。</li>
 *   <li>审核通过时按注册码<em>当时</em>的权限组和校区建号；权限组被删了就拒绝通过，
 *       而不是悄悄落到别的组。</li>
 *   <li>批量审核逐条各开一个事务：一条冲突（比如账号在这期间被管理员手工建走了）
 *       不应该让整批回滚，结果里逐条说明。</li>
 * </ul>
 */
@Service
public class RegistrationService {
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 16;
    static final String PENDING_EVENT = "REGISTRATION_PENDING";
    private static final String ADMIN_GROUP = "ADMIN";
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RegistrationCodeMapper codes;
    private final RegistrationApplicationMapper applications;
    private final UserMapper users;
    private final UserService userService;
    private final PermissionGroupService permissionGroups;
    private final PermissionGroupMapper permissionGroupMapper;
    private final NotificationService notifications;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    /** 全应用唯一的 Asia/Shanghai 时钟；有效期的比较都按它算，测试可以固定时间。 */
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RegistrationService(RegistrationCodeMapper codes, RegistrationApplicationMapper applications,
                               UserMapper users, UserService userService,
                               PermissionGroupService permissionGroups, PermissionGroupMapper permissionGroupMapper,
                               NotificationService notifications, PasswordEncoder passwordEncoder,
                               JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.codes = codes;
        this.applications = applications;
        this.users = users;
        this.userService = userService;
        this.permissionGroups = permissionGroups;
        this.permissionGroupMapper = permissionGroupMapper;
        this.notifications = notifications;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 注册码

    @Transactional
    public CodeView createCode(CreateCode command, Long operatorId) {
        PermissionGroupEntity group = permissionGroups.requireForAuthorization(command.permissionGroupId());
        if (ADMIN_GROUP.equals(group.getCode())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "注册码不能授予系统管理员权限组");
        }
        Set<Long> campusIds = command.campusIds() == null ? Set.of() : new LinkedHashSet<>(command.campusIds());
        permissionGroups.replaceUserCampusesValidation(group.getDataScope(), campusIds);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime validFrom = toSeconds(command.validFrom() == null ? now : command.validFrom());
        LocalDateTime validUntil = toSeconds(command.validUntil());
        validateValidity(validFrom, validUntil, now);
        RegistrationCodeEntity code = new RegistrationCodeEntity();
        code.setName(command.name().trim());
        code.setPermissionGroupId(group.getId());
        code.setMaxUses(command.maxUses());
        code.setUsedCount(0);
        code.setValidFrom(validFrom);
        code.setValidUntil(validUntil);
        code.setEnabled(true);
        code.setCreatedBy(operatorId);
        // 16 位、32 个字符的字母表是 80 比特，撞上已有的码几乎不可能；真撞上了换一个再试。
        for (int attempt = 0; ; attempt++) {
            code.setCode(randomCode());
            try {
                codes.insert(code);
                break;
            } catch (DuplicateKeyException collision) {
                if (attempt >= 4) throw collision;
                code.setId(null);
            }
        }
        for (Long campusId : campusIds) {
            jdbc.sql("INSERT INTO registration_code_campus(code_id, campus_id) VALUES (:codeId, :campusId)")
                    .param("codeId", code.getId()).param("campusId", campusId).update();
        }
        return toCodeView(requireCode(code.getId()), Map.of());
    }

    @Transactional
    public CodeView updateCode(Long id, UpdateCode command) {
        RegistrationCodeEntity code = requireCode(id);
        if (command.maxUses() < code.getUsedCount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "可注册数量不能少于已占用的 " + code.getUsedCount() + " 个名额（待审核和已通过的申请都占名额）");
        }
        LocalDateTime validFrom = toSeconds(command.validFrom());
        LocalDateTime validUntil = toSeconds(command.validUntil());
        if (!validUntil.isAfter(validFrom)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间必须晚于开始时间");
        }
        code.setName(command.name().trim());
        code.setMaxUses(command.maxUses());
        code.setValidFrom(validFrom);
        code.setValidUntil(validUntil);
        code.setEnabled(command.enabled());
        // 版本号同时挡住两种并发：别的管理员的修改，以及这期间有人用码提交了申请
        // （占名额的 UPDATE 也会把版本号加一）——后者会让上面「不少于已占用」的判断过期。
        code.setVersion(command.version());
        if (codes.updateById(code) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "注册码已被其他操作修改，请刷新后重试");
        }
        return toCodeView(requireCode(id), pendingCounts(List.of(id)));
    }

    /** 只有从没被用过的码能删；用过的码背后挂着申请记录，停用即可。 */
    @Transactional
    public void deleteCode(Long id) {
        RegistrationCodeEntity code = requireCode(id);
        long used = applications.selectCount(Wrappers.<RegistrationApplicationEntity>lambdaQuery()
                .eq(RegistrationApplicationEntity::getCodeId, code.getId()));
        if (used > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "已经有同学用这个注册码提交过申请，不能删除；不想再让人使用的话请停用它");
        }
        jdbc.sql("DELETE FROM registration_code_campus WHERE code_id = :id").param("id", id).update();
        codes.deleteById(id);
    }

    public PageResponse<CodeView> listCodes(int page, int pageSize, String keyword) {
        String likeKeyword = LikeFilter.escape(keyword == null ? null : keyword.trim());
        String normalizedCode = normalizeCode(keyword);
        var query = Wrappers.<RegistrationCodeEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q
                        .apply(LikeFilter.contains("name"), likeKeyword)
                        .or(StringUtils.hasText(normalizedCode),
                                inner -> inner.apply(LikeFilter.contains("code"), LikeFilter.escape(normalizedCode))))
                .orderByDesc(RegistrationCodeEntity::getCreatedAt)
                .orderByDesc(RegistrationCodeEntity::getId);
        Page<RegistrationCodeEntity> result = codes.selectPage(Page.of(page, pageSize), query);
        Map<Long, Long> pending = pendingCounts(result.getRecords().stream().map(RegistrationCodeEntity::getId).toList());
        return new PageResponse<>(result.getRecords().stream().map(code -> toCodeView(code, pending)).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    // ---------------------------------------------------------------- 提交申请（匿名）

    @Transactional
    public SubmittedApplication register(Register command) {
        String normalizedCode = normalizeCode(command.code());
        RegistrationCodeEntity code = normalizedCode.length() == CODE_LENGTH
                ? codes.selectOne(Wrappers.<RegistrationCodeEntity>lambdaQuery()
                .eq(RegistrationCodeEntity::getCode, normalizedCode))
                : null;
        if (code == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "注册码无效，请核对后重新输入");
        }
        String username = command.username().trim();
        if (username.toLowerCase(Locale.ROOT).startsWith("del.")) {
            // 删除账号时会把用户名改成 del.{id}.原名 来释放它，这个前缀留给那一步用。
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "登录账号不能以 del. 开头");
        }
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        String displayName = command.displayName().trim();
        if (permissionGroupMapper.selectById(code.getPermissionGroupId()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该注册码已失效，请联系管理员");
        }
        if (usernameTaken(username)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该登录账号已被使用，请换一个");
        }
        if (emailTaken(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该邮箱已被使用，请换一个");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int occupied = jdbc.sql("""
                UPDATE registration_code
                SET used_count = used_count + 1, version = version + 1, updated_at = :now
                WHERE id = :id AND deleted = FALSE AND enabled = TRUE
                  AND used_count < max_uses AND valid_from <= :now AND valid_until > :now
                """).param("id", code.getId()).param("now", now).update();
        if (occupied != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, unavailableReason(requireCode(code.getId()), now));
        }
        RegistrationApplicationEntity application = new RegistrationApplicationEntity();
        application.setCodeId(code.getId());
        application.setUsername(username);
        application.setDisplayName(displayName);
        application.setEmail(email);
        application.setPasswordHash(passwordEncoder.encode(command.password()));
        application.setStatus(RegistrationStatus.PENDING);
        application.setPendingUsername(username);
        application.setPendingEmail(email);
        try {
            applications.insert(application);
        } catch (DuplicateKeyException exception) {
            // 两个人同时用同一个账号或邮箱提交：唯一索引挡下后到的那个，占的名额随事务回滚。
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该登录账号或邮箱已有待审核的申请");
        }
        notifyAdmins();
        return new SubmittedApplication(application.getId(), username, displayName);
    }

    // ---------------------------------------------------------------- 审核

    public PageResponse<ApplicationView> listApplications(int page, int pageSize, String keyword,
                                                          RegistrationStatus status, Long codeId) {
        String likeKeyword = LikeFilter.escape(keyword == null ? null : keyword.trim());
        var query = Wrappers.<RegistrationApplicationEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q
                        .apply(LikeFilter.contains("username"), likeKeyword)
                        .or().apply(LikeFilter.contains("display_name"), likeKeyword)
                        .or().apply(LikeFilter.contains("email"), likeKeyword))
                .eq(status != null, RegistrationApplicationEntity::getStatus, status)
                .eq(codeId != null, RegistrationApplicationEntity::getCodeId, codeId)
                .orderByDesc(RegistrationApplicationEntity::getCreatedAt)
                .orderByDesc(RegistrationApplicationEntity::getId);
        Page<RegistrationApplicationEntity> result = applications.selectPage(Page.of(page, pageSize), query);
        Map<Long, RegistrationCodeEntity> codeById = new HashMap<>();
        Map<Long, String> groupNames = new HashMap<>();
        Map<Long, String> reviewerNames = new HashMap<>();
        List<ApplicationView> items = result.getRecords().stream().map(application -> {
            RegistrationCodeEntity code = codeById.computeIfAbsent(application.getCodeId(), codes::selectById);
            String groupName = code == null ? null : groupNames.computeIfAbsent(code.getPermissionGroupId(), id -> {
                PermissionGroupEntity group = permissionGroupMapper.selectById(id);
                return group == null ? null : group.getName();
            });
            String reviewerName = application.getReviewerId() == null ? null
                    : reviewerNames.computeIfAbsent(application.getReviewerId(), id -> {
                UserEntity reviewer = users.selectById(id);
                return reviewer == null ? null : reviewer.getDisplayName();
            });
            return new ApplicationView(application.getId(), application.getUsername(),
                    application.getDisplayName(), application.getEmail(), application.getStatus(),
                    application.getCodeId(), code == null ? null : code.getName(), groupName,
                    application.getCreatedAt(), application.getReviewedAt(), reviewerName,
                    application.getRejectReason(), application.getUserId());
        }).toList();
        return new PageResponse<>(items, result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public ReviewResult approve(Collection<Long> ids, Long reviewerId) {
        return review(ids, id -> approveOne(id, reviewerId));
    }

    public ReviewResult reject(Collection<Long> ids, String reason, Long reviewerId) {
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : null;
        return review(ids, id -> rejectOne(id, normalizedReason, reviewerId));
    }

    private ReviewResult review(Collection<Long> ids, java.util.function.Consumer<Long> action) {
        List<Long> succeeded = new ArrayList<>();
        List<ReviewFailure> failed = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            try {
                transactions.executeWithoutResult(status -> action.accept(id));
                succeeded.add(id);
            } catch (BusinessException failure) {
                RegistrationApplicationEntity application = applications.selectById(id);
                failed.add(new ReviewFailure(id, application == null ? null : application.getUsername(),
                        failure.getMessage()));
            }
        }
        return new ReviewResult(succeeded, failed);
    }

    private void approveOne(Long id, Long reviewerId) {
        RegistrationApplicationEntity application = requirePending(id);
        RegistrationCodeEntity code = codes.selectById(application.getCodeId());
        if (code == null || permissionGroupMapper.selectById(code.getPermissionGroupId()) == null) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "注册码对应的权限组已被删除，无法通过，请驳回");
        }
        markReviewed(application, RegistrationStatus.APPROVED, null, reviewerId);
        UserService.UserView user = userService.createRegistered(new UserService.RegisteredUser(
                application.getUsername(), application.getDisplayName(), application.getEmail(),
                application.getPasswordHash(), code.getPermissionGroupId(), codeCampusIds(code.getId())));
        application.setUserId(user.id());
        if (applications.updateById(application) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "申请已被其他操作处理，请刷新后重试");
        }
        notifications.notifyUser(user.id(), "REGISTRATION_APPROVED", "注册申请已通过",
                NotificationService.paragraphs("欢迎加入！你的注册申请已通过审核，账号已可以正常使用。"));
    }

    private void rejectOne(Long id, String reason, Long reviewerId) {
        RegistrationApplicationEntity application = requirePending(id);
        markReviewed(application, RegistrationStatus.REJECTED, reason, reviewerId);
        // 驳回归还名额：发出去 30 个名额的码，不该因为几份乱填的申请就让真正的同学注册不了。
        jdbc.sql("""
                UPDATE registration_code
                SET used_count = used_count - 1, version = version + 1, updated_at = :now
                WHERE id = :id AND used_count > 0
                """).param("id", application.getCodeId()).param("now", LocalDateTime.now(clock)).update();
    }

    /**
     * 先把申请改成已审核并释放待审核占位，版本号保证同一份申请只会被处理一次
     * （两个管理员同时点通过时，后到的那个在这里失败，整条事务回滚，不会建出第二个账号）。
     */
    private void markReviewed(RegistrationApplicationEntity application, RegistrationStatus status,
                              String reason, Long reviewerId) {
        application.setStatus(status);
        application.setPendingUsername(null);
        application.setPendingEmail(null);
        application.setRejectReason(reason);
        application.setReviewerId(reviewerId);
        application.setReviewedAt(LocalDateTime.now(clock));
        if (applications.updateById(application) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "申请已被其他操作处理，请刷新后重试");
        }
    }

    private RegistrationApplicationEntity requirePending(Long id) {
        RegistrationApplicationEntity application = applications.selectById(id);
        if (application == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "注册申请不存在");
        }
        if (application.getStatus() != RegistrationStatus.PENDING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "这份申请已经审核过了");
        }
        return application;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 给还没读过「待审核」提醒的管理员各发一条站内信。招新时一晚上几十份申请，
     * 每份都提醒一次会把消息中心淹掉；已经有一条未读的就不再叠加。
     */
    private void notifyAdmins() {
        List<Long> adminIds = jdbc.sql("""
                SELECT u.id FROM app_user u
                JOIN permission_group g ON g.id = u.permission_group_id
                WHERE g.code = :adminGroup AND u.enabled = TRUE AND u.deleted = FALSE
                  AND NOT EXISTS (SELECT 1 FROM user_notification n
                                  WHERE n.user_id = u.id AND n.event_type = :event AND n.read_at IS NULL)
                """).param("adminGroup", ADMIN_GROUP).param("event", PENDING_EVENT)
                .query(Long.class).list();
        if (adminIds.isEmpty()) return;
        notifications.notifyInApp(adminIds, PENDING_EVENT, "有新的注册申请待审核",
                NotificationService.paragraphs("有同学使用注册码提交了注册申请，请到「系统管理 → 注册审核」处理。"));
    }

    private boolean usernameTaken(String username) {
        return users.selectCount(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username)) > 0
                || applications.selectCount(Wrappers.<RegistrationApplicationEntity>lambdaQuery()
                .eq(RegistrationApplicationEntity::getPendingUsername, username)) > 0;
    }

    private boolean emailTaken(String email) {
        return users.selectCount(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getEmail, email)) > 0
                || applications.selectCount(Wrappers.<RegistrationApplicationEntity>lambdaQuery()
                .eq(RegistrationApplicationEntity::getPendingEmail, email)) > 0;
    }

    private String unavailableReason(RegistrationCodeEntity code, LocalDateTime now) {
        if (!Boolean.TRUE.equals(code.getEnabled())) return "该注册码已停用";
        if (now.isBefore(code.getValidFrom())) {
            return "该注册码尚未生效，将于 " + DISPLAY_TIME.format(code.getValidFrom()) + " 开放注册";
        }
        if (!now.isBefore(code.getValidUntil())) return "该注册码已过期";
        return "该注册码的注册名额已满";
    }

    private void validateValidity(LocalDateTime validFrom, LocalDateTime validUntil, LocalDateTime now) {
        if (!validUntil.isAfter(validFrom)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间必须晚于开始时间");
        }
        if (!validUntil.isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间必须晚于当前时间");
        }
    }

    /**
     * 有效期只精确到秒。「现在开始生效」取的是时钟当下的纳秒值，而 DATETIME(6) 入库时会把
     * 微秒以下的部分四舍五入——进位之后存下的开始时间比「现在」还晚，刚生成的码读回来就成了
     * 「未开始」，紧接着的提交也会被拒。Windows 上系统时间隔一小段才走一格，同一格里连读两次
     * 拿到的是同一个带 100 纳秒位的值，测试里就这样复现过。
     */
    private static LocalDateTime toSeconds(LocalDateTime value) {
        return value.truncatedTo(ChronoUnit.SECONDS);
    }

    private RegistrationCodeEntity requireCode(Long id) {
        RegistrationCodeEntity code = codes.selectById(id);
        if (code == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "注册码不存在");
        }
        return code;
    }

    private Set<Long> codeCampusIds(Long codeId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT campus_id FROM registration_code_campus WHERE code_id = :codeId ORDER BY campus_id
                """).param("codeId", codeId).query(Long.class).list());
    }

    private Map<Long, Long> pendingCounts(List<Long> codeIds) {
        if (codeIds.isEmpty()) return Map.of();
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("""
                SELECT code_id, COUNT(*) AS pending FROM registration_application
                WHERE status = 'PENDING' AND deleted = FALSE AND code_id IN (:ids)
                GROUP BY code_id
                """).param("ids", codeIds)
                .query((rs, row) -> Map.entry(rs.getLong("code_id"), rs.getLong("pending")))
                .list().forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    private CodeView toCodeView(RegistrationCodeEntity code, Map<Long, Long> pendingCounts) {
        PermissionGroupEntity group = permissionGroupMapper.selectById(code.getPermissionGroupId());
        return new CodeView(code.getId(), code.getCode(), code.getName(), code.getPermissionGroupId(),
                group == null ? null : group.getName(), codeCampusIds(code.getId()), code.getMaxUses(),
                code.getUsedCount(), pendingCounts.getOrDefault(code.getId(), 0L), code.getValidFrom(),
                code.getValidUntil(), code.getEnabled(), status(code, group != null), code.getCreatedAt(),
                code.getVersion());
    }

    private CodeStatus status(RegistrationCodeEntity code, boolean groupExists) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (!Boolean.TRUE.equals(code.getEnabled())) return CodeStatus.DISABLED;
        if (!groupExists) return CodeStatus.INVALID;
        if (now.isBefore(code.getValidFrom())) return CodeStatus.NOT_STARTED;
        if (!now.isBefore(code.getValidUntil())) return CodeStatus.EXPIRED;
        if (code.getUsedCount() >= code.getMaxUses()) return CodeStatus.EXHAUSTED;
        return CodeStatus.ACTIVE;
    }

    private String randomCode() {
        StringBuilder value = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            value.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return value.toString();
    }

    /** 同学输入时常带空格、连字符或小写：一律去掉分隔符再转大写。 */
    static String normalizeCode(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public enum CodeStatus { ACTIVE, NOT_STARTED, EXPIRED, EXHAUSTED, DISABLED, INVALID }

    public record CreateCode(String name, Long permissionGroupId, Set<Long> campusIds, int maxUses,
                             LocalDateTime validFrom, LocalDateTime validUntil) {
    }

    public record UpdateCode(String name, int maxUses, LocalDateTime validFrom, LocalDateTime validUntil,
                             boolean enabled, int version) {
    }

    public record Register(String code, String username, String displayName, String email, String password) {
    }

    public record CodeView(Long id, String code, String name, Long permissionGroupId, String permissionGroupName,
                           Set<Long> campusIds, int maxUses, int usedCount, long pendingCount,
                           LocalDateTime validFrom, LocalDateTime validUntil, Boolean enabled, CodeStatus status,
                           LocalDateTime createdAt, Integer version) {
    }

    public record SubmittedApplication(Long id, String username, String displayName) {
    }

    public record ApplicationView(Long id, String username, String displayName, String email,
                                  RegistrationStatus status, Long codeId, String codeName,
                                  String permissionGroupName, LocalDateTime createdAt, LocalDateTime reviewedAt,
                                  String reviewerName, String rejectReason, Long userId) {
    }

    public record ReviewFailure(Long id, String username, String message) {
    }

    public record ReviewResult(List<Long> succeeded, List<ReviewFailure> failed) {
    }
}
