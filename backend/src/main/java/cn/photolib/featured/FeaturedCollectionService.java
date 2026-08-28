package cn.photolib.featured;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.featured.mapper.FeaturedAssignmentMapper;
import cn.photolib.featured.mapper.FeaturedCollectionMapper;
import cn.photolib.featured.mapper.FeaturedEntryMapper;
import cn.photolib.featured.model.FeaturedAssignmentEntity;
import cn.photolib.featured.model.FeaturedCloseReason;
import cn.photolib.featured.model.FeaturedCollectionEntity;
import cn.photolib.featured.model.FeaturedCollectionStatus;
import cn.photolib.featured.model.FeaturedDocumentStatus;
import cn.photolib.featured.model.FeaturedEntryEntity;
import cn.photolib.notification.NotificationService;
import cn.photolib.permission.PermissionCode;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 好图精选。
 *
 * <p>权限分两层，不要混为一谈：</p>
 * <ul>
 *   <li><b>发布、编辑、删除、手动截止</b>需要 {@code FEATURED_MANAGE}，是部长的动作；</li>
 *   <li><b>查看与下载</b>对任何登录用户开放，控制器上只有 {@code isAuthenticated()}；</li>
 *   <li><b>填写条目</b>不看权限码，只看这份精选有没有指派到你——由
 *       {@link #isAssigned} 判定。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeaturedCollectionService {
    /** 单条精选最多可指派的校区数与个人数，防止一次误操作向全站广播通知。 */
    private static final int MAX_ASSIGNMENTS = 100;
    private static final int MAX_ENTRY_LIMIT = 50;

    private final FeaturedCollectionMapper mapper;
    private final FeaturedAssignmentMapper assignmentMapper;
    private final FeaturedEntryMapper entryMapper;
    private final PhotoService photoService;
    private final PhotoMapper photoMapper;
    private final NotificationService notifications;
    private final UserService userService;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PageResponse<CollectionView> list(int page, int pageSize, String keyword,
                                             FeaturedCollectionStatus status, AuthenticatedUser user) {
        long total = mapper.countPage(status, keyword);
        List<FeaturedCollectionEntity> rows =
                mapper.findPage(status, keyword, pageSize, (long) (page - 1) * pageSize);
        long totalPages = pageSize == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResponse<>(toViews(rows, user), page, pageSize, total, totalPages);
    }

    public CollectionView get(long id, AuthenticatedUser user) {
        return toView(requireView(id), user);
    }

    /** 一份精选的全部条目。查看不设限，任何登录用户都能看到所有校区的内容。 */
    public List<EntryView> entries(long id, AuthenticatedUser user) {
        requireView(id);
        List<FeaturedEntryEntity> rows = entryMapper.findByCollection(id);
        if (rows.isEmpty()) return List.of();
        Map<Long, PhotoEntity> photos = loadPhotos(rows);
        return rows.stream().map(entry -> toEntryView(entry, photos.get(entry.getPhotoId()), user)).toList();
    }

    /**
     * 指派候选：全部校区范围的启用账号。
     *
     * <p>刻意不让前端去调 {@code GET /users/campus-assignable}——那个接口要
     * {@code MANAGER_CAMPUS_ASSIGN}，把它和精选绑在一起，就等于要求"能发精选的人"
     * 同时得有改负责人校区的权限。这里只认 {@code FEATURED_MANAGE}。</p>
     */
    public List<UserService.CampusAssignmentView> assignableManagers(AuthenticatedUser user) {
        requireManage(user);
        return userService.campusAssignableUsers();
    }

    // ------------------------------------------------------------------
    // 部长动作
    // ------------------------------------------------------------------

    @Transactional
    public CollectionView create(CollectionCommand command, AuthenticatedUser user) {
        requireManage(user);
        validate(command, FeaturedCollectionStatus.DRAFT);
        String safeHtml = FeaturedRequirementHtml.sanitize(command.requirementHtml());
        FeaturedCollectionEntity collection = new FeaturedCollectionEntity();
        collection.setTitle(command.title().trim());
        collection.setRequirementHtml(safeHtml);
        collection.setRequirementText(FeaturedRequirementHtml.toPlainText(safeHtml));
        collection.setStartsAt(command.startsAt());
        collection.setEndsAt(command.endsAt());
        collection.setStatus(FeaturedCollectionStatus.DRAFT);
        collection.setAssignAll(command.assignAll());
        collection.setEntryLimit(command.entryLimit());
        collection.setDocumentStatus(FeaturedDocumentStatus.PENDING);
        collection.setCreatedBy(user.id());
        mapper.insert(collection);
        replaceAssignments(collection.getId(), command);
        return toView(requireView(collection.getId()), user);
    }

    @Transactional
    public CollectionView update(long id, CollectionCommand command, int version, AuthenticatedUser user) {
        requireManage(user);
        FeaturedCollectionEntity collection = requireEditable(id);
        validate(command, collection.getStatus());
        // 已发布的精选不允许改开始时间：负责人可能已经按原时间开始填报了。
        if (collection.getStatus() == FeaturedCollectionStatus.PUBLISHED
                && !collection.getStartsAt().equals(command.startsAt())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "精选已发布，开始时间不能再修改");
        }
        Set<Long> before = collection.getStatus() == FeaturedCollectionStatus.PUBLISHED
                ? resolveAssignees(collection) : Set.of();
        String safeHtml = FeaturedRequirementHtml.sanitize(command.requirementHtml());
        FeaturedCollectionEntity update = new FeaturedCollectionEntity();
        update.setId(id);
        update.setVersion(version);
        update.setTitle(command.title().trim());
        update.setRequirementHtml(safeHtml);
        update.setRequirementText(FeaturedRequirementHtml.toPlainText(safeHtml));
        update.setStartsAt(command.startsAt());
        update.setEndsAt(command.endsAt());
        update.setAssignAll(command.assignAll());
        update.setEntryLimit(command.entryLimit());
        updateChecked(update);
        replaceAssignments(id, command);
        FeaturedCollectionEntity saved = requireView(id);
        if (saved.getStatus() == FeaturedCollectionStatus.PUBLISHED) {
            // 改了指派范围就通知新加入的人；已经收到过通知的不重复打扰。
            Set<Long> added = new LinkedHashSet<>(resolveAssignees(saved));
            added.removeAll(before);
            notifyAssignees(saved, added);
        }
        return toView(saved, user);
    }

    @Transactional
    public CollectionView publish(long id, int version, AuthenticatedUser user) {
        requireManage(user);
        FeaturedCollectionEntity collection = requireExisting(id);
        if (collection.getStatus() != FeaturedCollectionStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有草稿状态的精选可以发布");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!collection.getEndsAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间必须晚于当前时间");
        }
        FeaturedCollectionEntity update = new FeaturedCollectionEntity();
        update.setId(id);
        update.setVersion(version);
        update.setStatus(FeaturedCollectionStatus.PUBLISHED);
        update.setPublishedBy(user.id());
        update.setPublishedAt(now);
        updateChecked(update);
        FeaturedCollectionEntity saved = requireView(id);
        notifyAssignees(saved, resolveAssignees(saved));
        return toView(saved, user);
    }

    /**
     * 手动截止。与定时任务共用 {@link FeaturedCollectionMapper#closeIfPublished} 的
     * 条件更新，因此"部长点截止"和"刚好到点"同时发生时只有一方会真正关闭，
     * 文档生成事件也只会发一次。
     */
    @Transactional
    public CollectionView close(long id, AuthenticatedUser user) {
        requireManage(user);
        FeaturedCollectionEntity collection = requireExisting(id);
        if (collection.getStatus() != FeaturedCollectionStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有已发布的精选可以截止");
        }
        if (!closeInternal(id, user.id(), FeaturedCloseReason.MANUAL)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选已被其他操作截止");
        }
        return toView(requireView(id), user);
    }

    /**
     * 关闭并排队生成文档。返回 false 表示这一行已经不是 PUBLISHED——
     * 另一个调用者（定时任务或部长）先关闭了它。
     */
    @Transactional
    public boolean closeInternal(long id, Long closedBy, FeaturedCloseReason reason) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.closeIfPublished(id, closedBy, now, reason.name()) != 1) return false;
        // 事务提交后才生成文档：生成要读对象存储、写对象存储，不能占着数据库事务，
        // 提交前发事件还会让生成任务读到尚未可见的条目。
        events.publishEvent(new FeaturedDocumentRequested(id));
        return true;
    }

    @Transactional
    public void delete(long id, int version, AuthenticatedUser user) {
        requireManage(user);
        requireExisting(id);
        if (mapper.softDelete(id, version, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选已被其他操作修改");
        }
        // 条目、指派与已生成的文档对象都保留：逻辑删除可以撤销，
        // 真删掉这些就没有退路了（与需求删除的取舍一致）。
    }

    /** 文档生成失败或需要按最新内容重出时，由部长手动重试。 */
    @Transactional
    public CollectionView regenerateDocument(long id, AuthenticatedUser user) {
        requireManage(user);
        FeaturedCollectionEntity collection = requireExisting(id);
        if (collection.getStatus() != FeaturedCollectionStatus.CLOSED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选截止后才能生成文档");
        }
        if (mapper.markRegenerating(id, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "文档正在生成中");
        }
        events.publishEvent(new FeaturedDocumentRequested(id));
        return toView(requireView(id), user);
    }

    // ------------------------------------------------------------------
    // 负责人填报
    // ------------------------------------------------------------------

    @Transactional
    public EntryView addEntry(long collectionId, EntryCommand command, AuthenticatedUser user) {
        FeaturedCollectionEntity collection = requireExisting(collectionId);
        requireSubmissionOpen(collection, user);
        validateEntry(command);
        if (entryMapper.countBySubmitter(collectionId, user.id()) >= collection.getEntryLimit()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "本次精选每人最多提交 " + collection.getEntryLimit() + " 张图片");
        }
        // 选图沿用图库可见范围：校区范围账号只能选自己上传、且在授权校区内的图片。
        PhotoEntity photo = photoService.requireGallerySelectable(command.photoId(), user);
        if (photo.getStatus() != PhotoStatus.AVAILABLE && photo.getStatus() != PhotoStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只能选用已上传完成的图片");
        }
        if (entryMapper.selectCount(Wrappers.<FeaturedEntryEntity>lambdaQuery()
                .eq(FeaturedEntryEntity::getCollectionId, collectionId)
                .eq(FeaturedEntryEntity::getPhotoId, photo.getId())) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "这张图片已经在本次精选中");
        }
        FeaturedEntryEntity entry = new FeaturedEntryEntity();
        entry.setCollectionId(collectionId);
        entry.setPhotoId(photo.getId());
        // 章节按图片所属校区分，不是填报人的校区：一位负责人可能被授权多个校区。
        entry.setCampusId(photo.getCampusId());
        entry.setSubmittedBy(user.id());
        entry.setIdea(command.idea().trim());
        entry.setLocation(command.location().trim());
        // 拍摄人与拍摄时间由图库信息直接落快照，不接受填报人输入。
        entry.setPhotographerName(photo.getPhotographerName());
        entry.setPhotographerStudentId(photo.getPhotographerStudentId());
        entry.setTakenAt(photo.getTakenAt());
        entry.setPhotoTitle(photo.getTitle());
        entry.setSortOrder(entryMapper.maxSortOrder(collectionId, user.id()) + 1);
        entry.setVersion(1);
        entryMapper.insert(entry);
        return toEntryView(entryMapper.selectById(entry.getId()), photo, user);
    }

    @Transactional
    public EntryView updateEntry(long collectionId, long entryId, EntryCommand command,
                                 int version, AuthenticatedUser user) {
        FeaturedCollectionEntity collection = requireExisting(collectionId);
        requireSubmissionOpen(collection, user);
        FeaturedEntryEntity entry = requireOwnEntry(collectionId, entryId, user);
        validateEntry(command);
        if (!entry.getPhotoId().equals(command.photoId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "不能替换条目图片，请删除后重新选图");
        }
        FeaturedEntryEntity update = new FeaturedEntryEntity();
        update.setId(entryId);
        update.setVersion(version);
        update.setIdea(command.idea().trim());
        update.setLocation(command.location().trim());
        if (entryMapper.updateById(update) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "条目已被其他操作修改");
        }
        FeaturedEntryEntity saved = entryMapper.selectById(entryId);
        return toEntryView(saved, photoOrNull(saved.getPhotoId()), user);
    }

    @Transactional
    public void deleteEntry(long collectionId, long entryId, AuthenticatedUser user) {
        FeaturedCollectionEntity collection = requireExisting(collectionId);
        requireSubmissionOpen(collection, user);
        requireOwnEntry(collectionId, entryId, user);
        // 物理删除：这张表没有 deleted 列，删掉才能让同一张图片重新加入（唯一键）。
        entryMapper.deleteById(entryId);
    }

    // ------------------------------------------------------------------
    // 文档下载
    // ------------------------------------------------------------------

    /** 下载不设限，任何登录用户都能拿到签名地址。 */
    public DocumentDownload document(long id) {
        FeaturedCollectionEntity collection = requireView(id);
        if (collection.getDocumentStatus() != FeaturedDocumentStatus.READY
                || !StringUtils.hasText(collection.getDocumentObjectKey())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选文档尚未生成");
        }
        String fileName = FeaturedDocumentService.fileName(collection);
        ObjectStorageService.SignedUrl signed = storage.presignGet(
                collection.getDocumentObjectKey(), fileName, storageProperties.downloadUrlTtl());
        return new DocumentDownload(signed.url().toString(), signed.expiresAt(), fileName);
    }

    // ------------------------------------------------------------------
    // 指派解析
    // ------------------------------------------------------------------

    /**
     * 这份精选要求哪些负责人提交。
     *
     * <p>"负责人"的判定与需求发布通知一致：权限组数据范围为 CAMPUS 的启用账号。
     * 不按 {@code role} 列判断，否则自定义权限组里的校区账号会被漏掉。</p>
     */
    public Set<Long> resolveAssignees(FeaturedCollectionEntity collection) {
        List<Long> ids = jdbc.sql("""
                SELECT DISTINCT u.id
                FROM app_user u
                JOIN permission_group pg
                  ON pg.id=COALESCE(u.permission_group_id,
                      (SELECT legacy_pg.id FROM permission_group legacy_pg WHERE legacy_pg.code=u.role))
                 AND pg.data_scope='CAMPUS'
                WHERE u.enabled=TRUE AND u.deleted=FALSE AND pg.deleted=FALSE
                  AND (:assignAll=TRUE
                       OR EXISTS (SELECT 1 FROM featured_collection_assignment a
                                  JOIN user_campus_permission ucp
                                    ON ucp.user_id=u.id AND ucp.campus_id=a.campus_id
                                  WHERE a.collection_id=:collectionId)
                       OR EXISTS (SELECT 1 FROM featured_collection_assignment a
                                  WHERE a.collection_id=:collectionId AND a.user_id=u.id))
                """)
                .param("assignAll", Boolean.TRUE.equals(collection.getAssignAll()))
                .param("collectionId", collection.getId())
                .query(Long.class).list();
        return new LinkedHashSet<>(ids);
    }

    /**
     * 反向的指派判定：给定若干精选，返回其中要求 {@code userId} 提交的那些。
     *
     * <p>与 {@link #resolveAssignees} 是同一条规则的两个方向——那个用于发布时的通知
     * 扇出（一份精选 → 全部负责人），这个用于页面判断（一批精选 → 我是否在内）。
     * 两条 SQL 的判定必须始终一致，{@code FeaturedCollectionServiceTests} 里有一条
     * 用例专门交叉验证它们不会各自漂移。</p>
     *
     * <p>做成批量是因为列表页每页最多 100 条：逐行判定会退化成 N+1 查询，
     * 这与图片收藏列表"必须批量查询当前页收藏"的既有约定是同一条要求。</p>
     */
    private Set<Long> assignedCollectionIds(List<Long> collectionIds, Long userId) {
        if (collectionIds.isEmpty()) return Set.of();
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT c.id
                FROM featured_collection c
                WHERE c.id IN (:collectionIds)
                  AND EXISTS (
                      SELECT 1 FROM app_user u
                      JOIN permission_group pg
                        ON pg.id=COALESCE(u.permission_group_id,
                            (SELECT legacy_pg.id FROM permission_group legacy_pg
                             WHERE legacy_pg.code=u.role))
                       AND pg.data_scope='CAMPUS'
                      WHERE u.id=:userId AND u.enabled=TRUE AND u.deleted=FALSE AND pg.deleted=FALSE)
                  AND (c.assign_all=TRUE
                       OR EXISTS (SELECT 1 FROM featured_collection_assignment a
                                  JOIN user_campus_permission ucp
                                    ON ucp.user_id=:userId AND ucp.campus_id=a.campus_id
                                  WHERE a.collection_id=c.id)
                       OR EXISTS (SELECT 1 FROM featured_collection_assignment a
                                  WHERE a.collection_id=c.id AND a.user_id=:userId))
                """).param("collectionIds", collectionIds).param("userId", userId)
                .query(Long.class).list());
    }

    private boolean isAssigned(FeaturedCollectionEntity collection, AuthenticatedUser user) {
        return assignedCollectionIds(List.of(collection.getId()), user.id())
                .contains(collection.getId());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void requireManage(AuthenticatedUser user) {
        if (!user.hasPermission(PermissionCode.FEATURED_MANAGE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理好图精选");
        }
    }

    private FeaturedCollectionEntity requireExisting(long id) {
        FeaturedCollectionEntity collection = mapper.selectById(id);
        if (collection == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "好图精选不存在");
        }
        return collection;
    }

    /** 查看用的读取，带创建人姓名和条目数。 */
    private FeaturedCollectionEntity requireView(long id) {
        FeaturedCollectionEntity collection = mapper.findViewById(id);
        if (collection == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "好图精选不存在");
        }
        return collection;
    }

    private FeaturedCollectionEntity requireEditable(long id) {
        FeaturedCollectionEntity collection = requireExisting(id);
        if (collection.getStatus() == FeaturedCollectionStatus.CLOSED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已截止的精选不能再修改");
        }
        return collection;
    }

    private void updateChecked(FeaturedCollectionEntity update) {
        if (mapper.updateById(update) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选已被其他操作修改");
        }
    }

    private void validate(CollectionCommand command, FeaturedCollectionStatus status) {
        if (!StringUtils.hasText(command.title())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "精选标题不能为空");
        }
        if (command.startsAt() == null || command.endsAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开始时间和截止时间必填");
        }
        if (!command.endsAt().isAfter(command.startsAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间必须晚于开始时间");
        }
        if (status == FeaturedCollectionStatus.PUBLISHED
                && !command.endsAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间必须晚于当前时间");
        }
        if (command.entryLimit() < 1 || command.entryLimit() > MAX_ENTRY_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "每人提交上限需在 1 至 " + MAX_ENTRY_LIMIT + " 之间");
        }
        int assignments = command.campusIds().size() + command.userIds().size();
        if (!command.assignAll() && assignments == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "请选择需要提交的校区或负责人，或改为面向全部校区负责人");
        }
        if (assignments > MAX_ASSIGNMENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "指派对象最多 " + MAX_ASSIGNMENTS + " 个");
        }
    }

    private void validateEntry(EntryCommand command) {
        if (command.photoId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请先从图库选择图片");
        }
        if (!StringUtils.hasText(command.idea())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄思路不能为空");
        }
        if (!StringUtils.hasText(command.location())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄地点不能为空");
        }
        if (command.idea().trim().length() > 2_000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄思路最多 2000 字");
        }
        if (command.location().trim().length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄地点最多 200 字");
        }
    }

    /** 指派整体替换：全量删除再写入，避免"改了一半"的中间状态。 */
    private void replaceAssignments(long collectionId, CollectionCommand command) {
        assignmentMapper.delete(Wrappers.<FeaturedAssignmentEntity>lambdaQuery()
                .eq(FeaturedAssignmentEntity::getCollectionId, collectionId));
        if (Boolean.TRUE.equals(command.assignAll())) return;
        LocalDateTime now = LocalDateTime.now();
        for (Long campusId : new LinkedHashSet<>(command.campusIds())) {
            FeaturedAssignmentEntity row = new FeaturedAssignmentEntity();
            row.setCollectionId(collectionId);
            row.setCampusId(campusId);
            row.setCreatedAt(now);
            assignmentMapper.insert(row);
        }
        for (Long userId : new LinkedHashSet<>(command.userIds())) {
            FeaturedAssignmentEntity row = new FeaturedAssignmentEntity();
            row.setCollectionId(collectionId);
            row.setUserId(userId);
            row.setCreatedAt(now);
            assignmentMapper.insert(row);
        }
    }

    /**
     * 站内通知走 {@link NotificationService}，不要在这里直接写通知表或邮件表
     * （与需求发布同一条约定）。单个接收人失败不影响其余接收人。
     */
    private void notifyAssignees(FeaturedCollectionEntity collection, Set<Long> assignees) {
        for (Long userId : assignees) {
            try {
                notifications.notifyUser(userId, "FEATURED_PUBLISHED", "新的好图精选征集",
                        NotificationService.paragraphs(collection.getTitle(),
                                "截止时间：" + collection.getEndsAt()));
            } catch (RuntimeException failure) {
                log.warn("好图精选通知发送失败 collectionId={} userId={}",
                        collection.getId(), userId, failure);
            }
        }
    }

    private void requireSubmissionOpen(FeaturedCollectionEntity collection, AuthenticatedUser user) {
        if (collection.getStatus() != FeaturedCollectionStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    collection.getStatus() == FeaturedCollectionStatus.CLOSED
                            ? "精选已截止，不能再修改内容" : "精选尚未发布");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(collection.getStartsAt())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选填报尚未开始");
        }
        // 到点但定时任务还没跑到时同样必须拒绝，否则截止时间形同虚设。
        if (!now.isBefore(collection.getEndsAt())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "精选已过截止时间");
        }
        if (!isAssigned(collection, user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "本次精选未指派给您填写");
        }
    }

    private FeaturedEntryEntity requireOwnEntry(long collectionId, long entryId, AuthenticatedUser user) {
        FeaturedEntryEntity entry = entryMapper.selectById(entryId);
        if (entry == null || !entry.getCollectionId().equals(collectionId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "精选条目不存在");
        }
        // 部长负责发布与截止，不代填也不代改：条目始终由提交人自己维护。
        if (!entry.getSubmittedBy().equals(user.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能修改自己提交的精选条目");
        }
        return entry;
    }

    private Map<Long, PhotoEntity> loadPhotos(List<FeaturedEntryEntity> rows) {
        List<Long> ids = rows.stream().map(FeaturedEntryEntity::getPhotoId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return photoMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PhotoEntity::getId, Function.identity(), (left, right) -> left));
    }

    private PhotoEntity photoOrNull(Long photoId) {
        return photoMapper.selectById(photoId);
    }

    private CollectionView toView(FeaturedCollectionEntity collection, AuthenticatedUser user) {
        return toViews(List.of(collection), user).get(0);
    }

    /** 列表与单条共用一条路径，避免两处各自计算"我是否被指派"而得出不同结论。 */
    private List<CollectionView> toViews(List<FeaturedCollectionEntity> rows, AuthenticatedUser user) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(FeaturedCollectionEntity::getId).toList();
        Map<Long, List<FeaturedAssignmentEntity>> assignments =
                assignmentMapper.findByCollections(ids).stream()
                        .collect(Collectors.groupingBy(FeaturedAssignmentEntity::getCollectionId));
        Set<Long> assignedToMe = assignedCollectionIds(ids, user.id());
        Map<Long, Long> myCounts = entryMapper.countBySubmitterForCollections(ids, user.id()).stream()
                .collect(Collectors.toMap(FeaturedEntryMapper.SubmitterCount::collectionId,
                        FeaturedEntryMapper.SubmitterCount::total));
        boolean canManage = user.hasPermission(PermissionCode.FEATURED_MANAGE);
        LocalDateTime now = LocalDateTime.now();
        return rows.stream().map(collection -> {
            List<FeaturedAssignmentEntity> rowAssignments =
                    assignments.getOrDefault(collection.getId(), List.of());
            // 草稿还没通知任何人，不该让负责人在列表里看到"需要我提交"。
            boolean assigned = collection.getStatus() != FeaturedCollectionStatus.DRAFT
                    && assignedToMe.contains(collection.getId());
            boolean windowOpen = collection.getStatus() == FeaturedCollectionStatus.PUBLISHED
                    && !now.isBefore(collection.getStartsAt())
                    && now.isBefore(collection.getEndsAt());
            return new CollectionView(
                    collection.getId(), collection.getTitle(), collection.getRequirementHtml(),
                    collection.getRequirementText(), collection.getStartsAt(), collection.getEndsAt(),
                    collection.getStatus(), Boolean.TRUE.equals(collection.getAssignAll()),
                    collection.getEntryLimit(),
                    rowAssignments.stream().map(FeaturedAssignmentEntity::getCampusId)
                            .filter(Objects::nonNull).toList(),
                    rowAssignments.stream().map(FeaturedAssignmentEntity::getUserId)
                            .filter(Objects::nonNull).toList(),
                    collection.getDocumentStatus(), collection.getDocumentGeneratedAt(),
                    collection.getDocumentSize(), collection.getDocumentError(),
                    collection.getCreatedBy(), collection.getCreatorDisplayName(),
                    collection.getPublishedAt(), collection.getClosedAt(), collection.getClosedReason(),
                    collection.getEntryCount() == null ? 0 : collection.getEntryCount(),
                    assigned, windowOpen && assigned,
                    myCounts.getOrDefault(collection.getId(), 0L), canManage,
                    collection.getCreatedAt(), collection.getVersion());
        }).toList();
    }

    private EntryView toEntryView(FeaturedEntryEntity entry, PhotoEntity photo, AuthenticatedUser user) {
        // 图片被删除或还没处理完时预览为空，条目本身仍然保留：
        // 拍摄人、拍摄时间与标题都是提交时的快照，内容不会因此丢失。
        String previewUrl = photo == null ? null : photoService.previewUrl(photo);
        return new EntryView(entry.getId(), entry.getCollectionId(), entry.getPhotoId(),
                entry.getCampusId(), entry.getCampusName(), entry.getPhotoTitle(), previewUrl,
                photo != null, entry.getIdea(), entry.getLocation(), entry.getPhotographerName(),
                entry.getPhotographerStudentId(), entry.getTakenAt(), entry.getSubmittedBy(),
                entry.getSubmitterDisplayName(), entry.getSortOrder() == null ? 0 : entry.getSortOrder(),
                entry.getSubmittedBy().equals(user.id()), entry.getVersion());
    }

    // ------------------------------------------------------------------
    // 命令与视图
    // ------------------------------------------------------------------

    public record CollectionCommand(String title, String requirementHtml, LocalDateTime startsAt,
                                    LocalDateTime endsAt, Boolean assignAll, int entryLimit,
                                    List<Long> campusIds, List<Long> userIds) {
        public CollectionCommand {
            campusIds = campusIds == null ? List.of() : List.copyOf(campusIds);
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
            assignAll = assignAll != null && assignAll;
        }
    }

    public record EntryCommand(Long photoId, String idea, String location) {
    }

    public record CollectionView(Long id, String title, String requirementHtml, String requirementText,
                                 LocalDateTime startsAt, LocalDateTime endsAt,
                                 FeaturedCollectionStatus status, boolean assignAll, Integer entryLimit,
                                 List<Long> campusIds, List<Long> userIds,
                                 FeaturedDocumentStatus documentStatus, LocalDateTime documentGeneratedAt,
                                 Long documentSize, String documentError, Long createdBy,
                                 String creatorDisplayName, LocalDateTime publishedAt,
                                 LocalDateTime closedAt, FeaturedCloseReason closedReason,
                                 long entryCount, boolean assignedToMe, boolean submissionOpen,
                                 long myEntryCount, boolean canManage, LocalDateTime createdAt,
                                 Integer version) {
    }

    public record EntryView(Long id, Long collectionId, Long photoId, Long campusId, String campusName,
                            String photoTitle, String previewUrl, boolean photoAvailable, String idea,
                            String location, String photographerName, String photographerStudentId,
                            LocalDateTime takenAt, Long submittedBy, String submitterDisplayName,
                            int sortOrder, boolean mine, Integer version) {
    }

    public record DocumentDownload(String downloadUrl, java.time.Instant expiresAt, String fileName) {
    }

    /** 关闭事务提交后触发文档生成。 */
    public record FeaturedDocumentRequested(long collectionId) {
    }

    /** 供文档生成器读取内容，避免它依赖 Service 的鉴权入口。 */
    List<FeaturedEntryEntity> entriesForDocument(long collectionId) {
        return entryMapper.findByCollection(collectionId);
    }

    FeaturedCollectionEntity documentSource(long collectionId) {
        return mapper.findViewById(collectionId);
    }

    /** 生成结果写回；返回 false 表示这份精选已经不在 GENERATING 状态。 */
    boolean finishDocument(long collectionId, FeaturedDocumentStatus status, String objectKey,
                           Long size, String error) {
        return mapper.finishDocument(collectionId, status.name(), objectKey, size,
                LocalDateTime.now(), error) == 1;
    }

    List<PhotoEntity> photosForDocument(List<Long> photoIds) {
        return photoIds.isEmpty() ? List.of() : photoMapper.selectBatchIds(photoIds);
    }
}
