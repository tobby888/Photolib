package cn.photolib.share;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import cn.photolib.photo.PhotoProcessingService;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.PhotoTags;
import cn.photolib.photo.batch.BatchUploadService;
import cn.photolib.photo.batch.PhotoUploadBatchEntity;
import cn.photolib.photo.batch.PhotoUploadBatchMapper;
import cn.photolib.photo.batch.BatchStatus;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.worklog.WorklogService;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 上传链接的访客通道：拿到"链接 + 密码"的人把图片传进选题的相册（创作选题和活动选题
 * 都可以，见 {@code ProjectShareService.requireUploadableProject}），传完之后在同一页申报工时。
 *
 * <p>几条不变量，改这个类之前先读一遍：</p>
 * <ol>
 *   <li><b>图片走的是站内那条完全一样的流水线。</b> 预签名 PUT 到 {@code temporary/photos/}，
 *       完成时置 PROCESSING 并发 {@link PhotoProcessingService.PhotoProcessRequested}，
 *       由同一个压缩/预览/校验管线接手（魔数、SHA-256、尺寸上限都在那里再验一遍）。
 *       访客传上来的字节一个都不会绕过它，所以"站外传的图"和"站内传的图"在库里
 *       没有第二套形态。</li>
 *   <li><b>拍摄者是会话上的身份快照，不是每次请求各报一次。</b> 站内的拍摄者强制来自
 *       通讯录（AGENTS §2.11），而上传链接的使用者按定义在通讯录之外，把通讯录摆给
 *       站外的人挑更是直接泄露姓名和学号。改为进门时自报一次，之后整场会话共用——
 *       逐次带身份的话，同一个人分两批传的图会落成两个拍摄者，而统计按学号归并。
 *       替两个人交图时可以中途切换（{@code ProjectShareService.switchUploader}），换的是
 *       整份快照，之后的上传和申报一律用新身份。</li>
 *   <li><b>责任人记在 {@code uploaded_by}，口子记在 {@code share_link_id}。</b>
 *       {@code uploaded_by} 落的是链接创建者（站内唯一可追责的成员，与打包下载任务
 *       的 {@code created_by} 同一个道理），{@code share_link_id} 才是"这张图从哪条
 *       链接进来的"。完成上传的归属判定只认后者。</li>
 *   <li><b>每一次上传都重判选题还收不收图。</b> 链接没过期不等于选题还开着：活动结束
 *       把选题置为完成之后，已经拿着链接和会话的人必须立刻传不进来，而不是等会话过期。</li>
 *   <li><b>ZIP 批量走站内那条批次通道，限额一份不改。</b> 建批次、解包、落照片全部是
 *       {@link BatchUploadService} 和 {@code SafeImageZipExtractor}（限额是管理员在
 *       {@link UploadLimitService} 里设的站内那一套：ZIP 大小、包内张数、单张大小），这里只是在前面
 *       加一层"这条链接能不能开批次、这个批次是不是它开的"。给站外单独开一套阈值的结果，
 *       是两套数字各改各的，而松的那一套就是被用来打进来的那一套。</li>
 *   <li><b>申报工时用的是同一份会话身份，工时规则用的是站内那一份。</b> 姓名学号取会话上的
 *       快照——和这场会话传的每一张照片上的拍摄者是同一个人，申报时不再让人填一遍，
 *       否则「传图的是张三、报工时的是李四」这种事就成了可能。日期、时长和「选题结束后
 *       不再收」的校验在 {@link WorklogService#createForShareLink}，与站内填报是同一段代码。
 *       申报落成「待确认」，由部长和管理员在工时面板里确认或退回。</li>
 *   <li><b>上传者只能打选题的预设标签，只能动预设范围内的那部分。</b> 上传时整批选好，
 *       或者传完之后逐张改（只限记在当前拍摄者名下、已经处理完的图）。选题没设预设就不能打，
 *       保留标签 {@code deprecated} 也不能打——那是选片人的结论。见 {@link #guestTags}。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ProjectShareUploadService {
    /** 一条上传链接最多收多少张。活动量级给得宽，但不能是无限——这是个匿名写入口。 */
    static final long MAX_UPLOADS_PER_LINK = 5_000L;

    private final ProjectShareService shareService;
    private final ProjectService projectService;
    private final PhotoService photoService;
    private final PhotoMapper photoMapper;
    private final BatchUploadService batchService;
    private final cn.photolib.photo.AbandonedUploadCleanupJob abandonedUploads;
    private final PhotoUploadBatchMapper batchMapper;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final UploadLimitService uploadLimits;
    private final WorklogService worklogService;

    /**
     * 签一张上传票据，并在库里占好这一行（状态 UPLOADING）。
     *
     * <p>顺序与站内 {@code PhotoService.createTicket} 保持一致：先文件校验、再业务上下文
     * 授权、最后才做全库哈希查重——查重的报错里带着既有图片的标题，不能让一个连选题都
     * 还没通过校验的请求把它读出去。</p>
     */
    @Transactional
    public UploadTicket createTicket(ProjectShareService.GuestContext context, TicketCommand command) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        photoService.validateUploadFile(command.fileName(), command.contentType(), command.size());
        ProjectEntity project = requireOpenProject(link);
        Uploader uploader = requireUploader(context);
        requireCapacity(link);

        String sha256Lower = command.sha256().toLowerCase();
        photoService.requireUniqueSha256(sha256Lower, null);

        String extension = command.contentType().equals("image/png") ? "png" : "jpg";
        String id = UUID.randomUUID().toString();
        String originalKey = "temporary/photos/" + id + "." + extension;
        String finalKey = "photos/" + LocalDateTime.now(clock).getYear() + "/" + id + "." + extension;

        PhotoEntity photo = new PhotoEntity();
        photo.setProjectId(project.getId());
        photo.setPhotographerStudentId(uploader.studentId());
        photo.setPhotographerName(uploader.name());
        // 站内唯一可追责的人是开这条链接的成员；访客没有账号，也不该凭一条链接获得一个。
        photo.setUploadedBy(link.getCreatedBy());
        photo.setShareLinkId(link.getId());
        // 选题不属于任何校区，访客也报不出校区，所以这里如实留空，而不是挑一个看着像的。
        photo.setCampusId(null);
        photo.setTakenAt(command.takenAt() == null ? LocalDateTime.now(clock) : command.takenAt());
        photo.setSize(command.size());
        photo.setContentType(command.contentType());
        photo.setObjectKey(finalKey);
        photo.setOriginalObjectKey(originalKey);
        photo.setSha256(sha256Lower);
        photo.setStatus(PhotoStatus.UPLOADING);
        // 与站内一致：记下直传地址的有效期，供清理任务判断何时能删（Flyway V50）。
        photo.setUploadUrlExpiresAt(LocalDateTime.now(clock).plus(storageProperties.uploadUrlTtl()));
        photoMapper.insert(photo);
        // 归属链接与站内一致：项目相册以 photo_project 为准。
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                .param("photoId", photo.getId())
                .param("projectId", project.getId())
                .update();

        ObjectStorageService.SignedUrl signed = storage.presignPut(
                originalKey, command.contentType(), storageProperties.uploadUrlTtl());
        // 这条路不经过 PhotoService.createTicket，清理任务得自己捅（它内部节流）。
        abandonedUploads.nudge();
        // 拍摄者回给页面：会话中途可以切换拍摄者，列表上每一张要写清楚它记在谁名下。
        return new UploadTicket(photo.getId(), signed.url().toString(), signed.method(),
                command.contentType(), signed.expiresAt(), uploader.name());
    }

    /**
     * 访客把字节 PUT 上去之后调这里：确认对象真的在、置 PROCESSING、交给压缩管线。
     *
     * <p>标题和说明是选填的。标签也是选填的，可以是预设、相册里已有的，也可以自己造
     * （{@link #guestTags}，保留标签除外）。</p>
     */
    @Transactional
    public UploadedPhoto complete(ProjectShareService.GuestContext context, Long photoId,
                                  CompleteCommand command) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        requireOpenProject(link);
        PhotoEntity photo = requireOwnUpload(link, photoId);
        List<String> tags = guestTags(command.tags());

        ObjectStorageService.ObjectInfo info = storage.stat(photo.getOriginalObjectKey());
        if (info.size() <= 0 || info.size() > uploadLimits.value(UploadLimit.PHOTO_IMAGE_MAX_BYTES)) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "图片为空或超过 " + uploadLimits.describe(UploadLimit.PHOTO_IMAGE_MAX_BYTES));
        }
        photo.setTitle(trimmedOrNull(command.title(), 200));
        photo.setDescription(trimmedOrNull(command.description(), 500));
        if (!tags.isEmpty()) photo.setTagsJson(PhotoTags.toJson(tags));
        photo.setStatus(PhotoStatus.PROCESSING);
        photo.setFailureReason(null);
        // 这是一次新的处理，恢复任务的重提交次数从头算（Flyway V53）。
        photo.setProcessingRecoveries(0);

        // 与站内 complete 同一条并发保护：只有仍看得见 UPLOADING 的那个请求算数。
        // version 由乐观锁拦截器自动处理，这里不要手动设置（见 PhotoService.complete 的注释）。
        int updated = photoMapper.update(photo, Wrappers.<PhotoEntity>lambdaUpdate()
                .eq(PhotoEntity::getId, photoId)
                .eq(PhotoEntity::getStatus, PhotoStatus.UPLOADING));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "这张图片已经提交过了");
        }
        jdbc.sql("UPDATE project_share_link SET upload_count = upload_count + 1 WHERE id = :id")
                .param("id", link.getId()).update();

        events.publishEvent(new PhotoProcessingService.PhotoProcessRequested(photo.getId()));
        return new UploadedPhoto(photo.getId(), photo.getTitle(), PhotoStatus.PROCESSING, null);
    }

    /**
     * 访客查自己刚传的那张处理完了没有。
     *
     * <p>压缩失败会把照片打回 UPLOADING 并写上原因（{@code PhotoProcessingService}），
     * 没有这个接口的话访客只会看到"传完了"，而那张图其实从来没进相册。</p>
     */
    public UploadedPhoto status(ProjectShareService.GuestContext context, Long photoId) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        PhotoEntity photo = requireOwnUpload(link, photoId, null);
        return new UploadedPhoto(photo.getId(), photo.getTitle(), photo.getStatus(),
                photo.getFailureReason());
    }

    /**
     * 上传者给自己传的一张图改标签，只能用选题的预设标签。
     *
     * <p>「自己的」= 通过这条链接传的 + 拍摄者学号就是会话当前的身份。会话中途切换过拍摄者的话，
     * 要切回那个人才能改他名下的图——和申报工时按学号认人是同一条线。学号是自报的，所以这道
     * 门挡的是「同一条链接上的别人顺手改了我的图」，不是冒名：链接和密码才是真正的凭据。</p>
     *
     * <p>只动预设范围内的那部分：图片上预设以外的标签（选片人打的 {@code deprecated}、
     * 站内补的自定义标签、预设改过之后留下的旧标签）原样保留。上传者能删的只有他自己能加的那些，
     * 否则一次「改标签」就能把选片人的结论抹掉。</p>
     *
     * <p>只在 {@code AVAILABLE} 时可改：压缩管线提交成品时按它读到的 version 做 CAS
     * （{@code completeProcessingWithProfileGuard}），处理中途改一次标签会让那一步失败、
     * 整张图被打成处理失败。处理失败的（{@code UPLOADING}）本来就没进相册，也不必打。
     * 选题结束后不再收图，同样不再改标签。</p>
     */
    @Transactional
    public TaggedPhoto updateTags(ProjectShareService.GuestContext context, Long photoId,
                                  List<String> requested) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        requireOpenProject(link);
        Uploader uploader = requireUploader(context);
        PhotoEntity photo = requireOwnUpload(link, photoId, null);
        if (!uploader.studentId().equals(photo.getPhotographerStudentId())) {
            // 409 而不是 404：访客页把 403/404 一律当成会话失效、退回密码页，
            // 而这里只是该先切换拍摄者，会话本身好好的。
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "只能给记在当前拍摄者名下的图片打标签，请先切换到这张图的拍摄者");
        }
        if (photo.getStatus() != PhotoStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    photo.getStatus() == PhotoStatus.PROCESSING
                            ? "这张图片还在处理中，处理完再打标签" : "这张图片没有进入图库，不能打标签");
        }
        List<String> chosen = guestTags(requested);
        List<String> existing = PhotoTags.parse(photo.getTagsJson());
        Set<String> merged = new LinkedHashSet<>();
        // 保留标签是选片人的结论，上传者改标签时原样留着；其余标签以他提交的为准。
        existing.stream().filter(PhotoTags::isReserved).forEach(merged::add);
        merged.addAll(chosen);
        if (merged.size() > PhotoTags.MAX_TAGS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "这张图片的标签将超过 " + PhotoTags.MAX_TAGS + " 个");
        }
        List<String> next = List.copyOf(merged);
        if (next.equals(existing)) {
            return new TaggedPhoto(photo.getId(), visibleTags(next));
        }
        // 与 PhotoService.batchTags 同一个写法：走 mapper（同一事务里 MyBatis 的一级缓存
        // 才不会读到旧标签），只写 tags_json，版本条件手写——实体参数为 null 时乐观锁拦截器不介入。
        int updated = photoMapper.update(null, Wrappers.<PhotoEntity>lambdaUpdate()
                .set(PhotoEntity::getTagsJson, PhotoTags.toJson(next))
                .set(PhotoEntity::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(PhotoEntity::getId, photo.getId())
                .eq(PhotoEntity::getVersion, photo.getVersion())
                .eq(PhotoEntity::getStatus, PhotoStatus.AVAILABLE));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片已被其他操作修改，请刷新后重试");
        }
        return new TaggedPhoto(photo.getId(), visibleTags(next));
    }

    /**
     * 访客交上来的标签：规范化之后除保留标签外都收，预设只是页面上的候选，上传者可以自己造新标签。
     *
     * <p>保留标签 {@code deprecated} 不收——「这张图用不上」是选片人的结论，不是上传者的。
     * 数量与长度仍由 {@link PhotoTags#normalize} 兜住。</p>
     */
    private List<String> guestTags(List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        List<String> tags = PhotoTags.normalize(requested);
        List<String> reserved = tags.stream().filter(PhotoTags::isReserved).toList();
        if (!reserved.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "不能使用保留标签：" + String.join("、", reserved));
        }
        return tags;
    }

    /** 回给访客的是除保留标签之外的全部：保留标签是选片人的结论，他既改不了，也没有理由看到。 */
    private static List<String> visibleTags(List<String> tags) {
        return tags.stream().filter(tag -> !PhotoTags.isReserved(tag)).toList();
    }

    // ------------------------------------------------------------------ ZIP 批量

    /**
     * 签一个 ZIP 批次的上传地址。
     *
     * <p>限额一律沿用站内那一套（{@link UploadLimitService} 里的 {@code PHOTO_*} 各项：
     * ZIP 大小、包内张数、单张大小），解包也走同一个 {@code SafeImageZipExtractor}
     * ——zip slip、zip bomb、包里混的非图片文件都在那里处理。不给站外单独开一套阈值：
     * 两套数字迟早各改各的，而松的那一套就是被用来打进来的那一套。</p>
     */
    @Transactional
    public BatchUploadService.BatchTicket createZipTicket(ProjectShareService.GuestContext context,
                                                          ZipCommand command) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        ProjectEntity project = requireOpenProject(link);
        requireUploader(context);
        requireCapacity(link);
        if (!StringUtils.hasText(command.archiveFileName())
                || !command.archiveFileName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "请选择 .zip 压缩包");
        }
        return batchService.createZipBatch(new BatchUploadService.ZipBatch(
                null, project.getId(), link.getCreatedBy(), link.getId(),
                command.archiveFileName(), command.archiveSize()));
    }

    /** ZIP 的字节已经传完：交给解包（异步），随后由访客轮询 {@link #batchStatus}。 */
    @Transactional
    public GuestBatch completeZip(ProjectShareService.GuestContext context, String batchId) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        requireOpenProject(link);
        requireOwnBatch(link, batchId);
        return guestView(batchService.completeZipBatch(batchId).batch());
    }

    /**
     * 批次当下的状态。解包失败（不是 ZIP、里面没有图片、超限额）会落成 FAILED 并带上
     * 原因，访客必须看得到——否则他只知道"传完了"，而那一包从来没进相册。
     */
    public GuestBatch batchStatus(ProjectShareService.GuestContext context, String batchId) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        return guestView(requireOwnBatch(link, batchId));
    }

    /**
     * 解包完成后把这一批落成照片。
     *
     * <p>站内这一步是人工填元数据（拍摄者、拍摄时间、标签），访客这边几乎没有可填的：
     * 拍摄者来自会话身份，标题取包内的原文件名（访客和选片人之间唯一的共同语言）。
     * 标签可以在上传前选好，整包共用，只能是选题的预设标签（{@link #guestTags}）；
     * 包里的图访客在页面上看不到，逐张打不了。前端轮到 WAITING_METADATA 就直接调这里。</p>
     */
    @Transactional
    public GuestBatch finishZip(ProjectShareService.GuestContext context, String batchId,
                                LocalDateTime takenAt, List<String> requestedTags) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        requireOpenProject(link);
        requireOwnBatch(link, batchId);
        Uploader uploader = requireUploader(context);
        List<String> tags = guestTags(requestedTags);
        int waiting = batchService.waitingItems(batchId).size();
        requireCapacity(link, waiting);

        BatchUploadService.BatchView view = batchService.finishAllWithSnapshot(batchId,
                new BatchUploadService.PhotoSnapshot(uploader.studentId(), uploader.name(),
                        // 选题不属于任何校区，访客也报不出，与单张上传一致地留空。
                        null, link.getCreatedBy(), link.getId(),
                        takenAt == null ? LocalDateTime.now(clock) : takenAt, null, tags));
        // 与单张上传同一个口径：算进来的是"真的交给了压缩管线"的张数。
        jdbc.sql("UPDATE project_share_link SET upload_count = upload_count + :count WHERE id = :id")
                .param("count", waiting).param("id", link.getId()).update();
        return guestView(view.batch());
    }

    // ------------------------------------------------------------------ 申报工时

    /**
     * 传完图之后申报这次的工时，直接落成「待确认」，进入部长和管理员的工时审核面板。
     *
     * <p>入口在上传页的最后一步，所以<b>先得真的通过这条链接交过图</b>：这是个匿名写入口，
     * 没有这一条，任何拿到链接和密码的人——哪怕一张没拍——都能给自己记上工时，审核的人
     * 面对的就是一串对不上任何照片的申报。判定按「这条链接 + 会话上的学号」现查，
     * 所以换了设备、会话过期后重新进门的人照样能申报，只要学号填的是同一个。</p>
     *
     * <p>工时记在哪：{@code share_link_id} 是这条链接，{@code user_id} 是开链接的成员
     * （站内唯一可追责的人，退回通知也发给他），校区如实没有。不挂需求——通过链接
     * 传进来的图本来就不挂需求，活动选题甚至可以一条需求都没有（Flyway V63）。</p>
     */
    @Transactional
    public GuestWorklog declareWorklog(ProjectShareService.GuestContext context,
                                       WorklogDeclaration declaration) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        Uploader uploader = requireUploader(context);
        requireHandedInPhotos(link, uploader);
        return guestView(worklogService.createForShareLink(new WorklogService.ShareLinkWorklog(
                link.getId(), link.getProjectId(), link.getCreatedBy(),
                uploader.name(), uploader.studentId(), declaration.workDate(),
                declaration.shootingMinutes(), declaration.retouchingMinutes(),
                declaration.remark())));
    }

    /**
     * 访客回看自己在这条链接上申报过的工时和审核结果。
     *
     * <p>退回通知发给的是开链接的成员，申报人没有账号、收不到；没有这个接口的话，
     * 一条被退回的申报对申报人来说就是石沉大海，而退回之后重新申报恰恰要由他来做。
     * 只按「这条链接 + 会话上的学号」取，看不到这条链接上其他人的申报。</p>
     */
    public List<GuestWorklog> worklogs(ProjectShareService.GuestContext context) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        Uploader uploader = requireUploader(context);
        return worklogService.listForShareLink(link.getId(), uploader.studentId())
                .stream().map(this::guestView).toList();
    }

    /**
     * 这个人是否通过这条链接交过图：至少有一张已经交给压缩管线（不再是 UPLOADING）。
     *
     * <p>只签了票据没传完的、处理失败被打回 UPLOADING 的都不算——那些图从没进过相册。
     * 不看 {@code deleted}：图片事后被清理掉（比如活动选题选片后的清理）不该让已经干完的
     * 活报不了工时。</p>
     */
    private void requireHandedInPhotos(ProjectShareLinkEntity link, Uploader uploader) {
        long handedIn = jdbc.sql("""
                        SELECT COUNT(*) FROM photo
                        WHERE share_link_id = :link AND photographer_student_id = :studentId
                          AND status <> 'UPLOADING'
                        """).param("link", link.getId()).param("studentId", uploader.studentId())
                .query(Long.class).single();
        if (handedIn == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "请先通过这条链接上传照片，传完之后再申报工时");
        }
    }

    /** 访客看到的工时比 {@code WorklogEntity} 少：没有填报账号、确认人和所属链接的 id。 */
    private GuestWorklog guestView(WorklogEntity worklog) {
        return new GuestWorklog(worklog.getId(), worklog.getWorkDate(),
                worklog.getShootingMinutes(), worklog.getRetouchingMinutes(), worklog.getRemark(),
                worklog.getStatus(), worklog.getRejectReason(), worklog.getCreatedAt());
    }

    /**
     * 批次必须是这条链接自己开的。批次的 {@code created_by} 记的是链接创建者，
     * 对访客不是凭据——只认 {@code share_link_id}，否则拿到一个批次 id 就能看别人的。
     */
    private PhotoUploadBatchEntity requireOwnBatch(ProjectShareLinkEntity link, String batchId) {
        PhotoUploadBatchEntity batch = batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null || !link.getId().equals(batch.getShareLinkId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "这个上传批次不属于这条链接");
        }
        return batch;
    }

    /** 访客看到的批次比 {@code BatchView} 少：没有条目明细、没有创建者、没有对象键。 */
    private GuestBatch guestView(PhotoUploadBatchEntity batch) {
        return new GuestBatch(batch.getId(), batch.getStatus(),
                batch.getTotalCount() == null ? 0 : batch.getTotalCount(),
                batch.getSuccessCount() == null ? 0 : batch.getSuccessCount(),
                batch.getFailureCount() == null ? 0 : batch.getFailureCount(),
                batch.getFailureReason());
    }

    private ProjectEntity requireOpenProject(ProjectShareLinkEntity link) {
        ProjectEntity project = projectService.get(link.getProjectId());
        if (!shareService.canAcceptUploads(project)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "这个选题已经不再接收上传了");
        }
        return project;
    }

    private Uploader requireUploader(ProjectShareService.GuestContext context) {
        ProjectShareSessionEntity session = context.session();
        String name = session == null ? null : session.getUploaderName();
        String studentId = session == null ? null : session.getUploaderStudentId();
        if (!StringUtils.hasText(name) || !StringUtils.hasText(studentId)) {
            // 只可能发生在 V48 之前发出、还没过期的会话上；让访客重新进一次门即可。
            throw new BusinessException(ErrorCode.FORBIDDEN, "请重新输入密码并填写上传者信息");
        }
        return new Uploader(name, studentId);
    }

    private void requireCapacity(ProjectShareLinkEntity link) {
        requireCapacity(link, 1);
    }

    private void requireCapacity(ProjectShareLinkEntity link, int incoming) {
        // 现查而不是用会话解析时读到的那一行：一个批次一次就能落几十张，
        // 同一条链接上的几个人也可能同时在传，按手里那份计数算会让上限形同虚设。
        Long current = jdbc.sql("SELECT upload_count FROM project_share_link WHERE id = :id")
                .param("id", link.getId()).query(Long.class).optional().orElse(0L);
        long uploaded = current == null ? 0 : current;
        if (uploaded + Math.max(incoming, 1) > MAX_UPLOADS_PER_LINK) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "这条上传链接最多收 " + MAX_UPLOADS_PER_LINK + " 张，剩下的额度装不下这一批，"
                            + "请找负责人要一条新链接");
        }
    }

    private PhotoEntity requireOwnUpload(ProjectShareLinkEntity link, Long photoId) {
        return requireOwnUpload(link, photoId, PhotoStatus.UPLOADING);
    }

    /**
     * 只有"这条链接自己建的那一行"才轮得到访客写。少了 {@code share_link_id} 这一比，
     * 拿到任意一个 photoId 就能改别人的图片。
     */
    private PhotoEntity requireOwnUpload(ProjectShareLinkEntity link, Long photoId,
                                         PhotoStatus requiredStatus) {
        PhotoEntity photo = photoId == null ? null : photoMapper.selectById(photoId);
        if (photo == null || !link.getId().equals(photo.getShareLinkId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "这张图片不是通过这条链接上传的");
        }
        if (requiredStatus != null && photo.getStatus() != requiredStatus) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "这张图片已经提交过了");
        }
        return photo;
    }

    private static String trimmedOrNull(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private record Uploader(String name, String studentId) {}

    public record TicketCommand(String fileName, String contentType, long size, String sha256,
                                LocalDateTime takenAt) {}

    /** {@code tags} 只能是选题的预设标签，可以为空，见 {@link #guestTags}。 */
    public record CompleteCommand(String title, String description, List<String> tags) {
        public CompleteCommand(String title, String description) {
            this(title, description, List.of());
        }
    }

    /** 改完之后这张图上预设范围内的标签。 */
    public record TaggedPhoto(Long photoId, List<String> tags) {}

    /** ZIP 批量：{@code archiveSize} 的上限是管理员设的 {@link UploadLimit#PHOTO_ZIP_MAX_BYTES}。 */
    public record ZipCommand(String archiveFileName, Long archiveSize) {}

    public record GuestBatch(String batchId, BatchStatus status, int totalCount, int successCount,
                             int failureCount, String failureReason) {}

    public record UploadTicket(Long photoId, String uploadUrl, String method, String contentType,
                               java.time.Instant expiresAt, String photographerName) {}

    public record UploadedPhoto(Long photoId, String title, PhotoStatus status, String failureReason) {}

    /** 访客申报的一条工时：没有姓名学号（取会话身份），没有草稿（交上来就是提交）。 */
    public record WorklogDeclaration(LocalDate workDate, int shootingMinutes, int retouchingMinutes,
                                     String remark) {}

    public record GuestWorklog(Long id, LocalDate workDate, int shootingMinutes, int retouchingMinutes,
                               String remark, WorklogStatus status, String rejectReason,
                               LocalDateTime createdAt) {}
}
