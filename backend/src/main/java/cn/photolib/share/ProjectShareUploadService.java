package cn.photolib.share;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.photo.PhotoProcessingService;
import cn.photolib.photo.PhotoService;
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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 上传链接的访客通道：拿到"链接 + 密码"的人把图片传进活动选题的相册。
 *
 * <p>四条不变量，改这个类之前先读一遍：</p>
 * <ol>
 *   <li><b>图片走的是站内那条完全一样的流水线。</b> 预签名 PUT 到 {@code temporary/photos/}，
 *       完成时置 PROCESSING 并发 {@link PhotoProcessingService.PhotoProcessRequested}，
 *       由同一个压缩/预览/校验管线接手（魔数、SHA-256、尺寸上限都在那里再验一遍）。
 *       访客传上来的字节一个都不会绕过它，所以"站外传的图"和"站内传的图"在库里
 *       没有第二套形态。</li>
 *   <li><b>拍摄者是会话上的身份快照，不是每次请求各报一次。</b> 站内的拍摄者强制来自
 *       通讯录（AGENTS §2.11），而上传链接的使用者按定义在通讯录之外，把通讯录摆给
 *       站外的人挑更是直接泄露姓名和学号。改为进门时自报一次，之后整场会话共用——
 *       逐次带身份的话，同一个人分两批传的图会落成两个拍摄者，而统计按学号归并。</li>
 *   <li><b>责任人记在 {@code uploaded_by}，口子记在 {@code share_link_id}。</b>
 *       {@code uploaded_by} 落的是链接创建者（站内唯一可追责的成员，与打包下载任务
 *       的 {@code created_by} 同一个道理），{@code share_link_id} 才是"这张图从哪条
 *       链接进来的"。完成上传的归属判定只认后者。</li>
 *   <li><b>每一次上传都重判选题还收不收图。</b> 链接没过期不等于选题还开着：活动结束
 *       把选题置为完成之后，已经拿着链接和会话的人必须立刻传不进来，而不是等会话过期。</li>
 *   <li><b>ZIP 批量走站内那条批次通道，限额一份不改。</b> 建批次、解包、落照片全部是
 *       {@link BatchUploadService} 和 {@code SafeImageZipExtractor}（{@link ImageUploadPolicy}：
 *       ZIP ≤ 1.5 GB、包内 ≤ 100 张、单张 ≤ 100 MiB、解压总量 ≤ 10 GiB），这里只是在前面
 *       加一层"这条链接能不能开批次、这个批次是不是它开的"。给站外单独开一套阈值的结果，
 *       是两套数字各改各的，而松的那一套就是被用来打进来的那一套。</li>
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
    private final PhotoUploadBatchMapper batchMapper;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final Clock clock;

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
        photoMapper.insert(photo);
        // 归属链接与站内一致：项目相册以 photo_project 为准。
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                .param("photoId", photo.getId())
                .param("projectId", project.getId())
                .update();

        ObjectStorageService.SignedUrl signed = storage.presignPut(
                originalKey, command.contentType(), storageProperties.uploadUrlTtl());
        return new UploadTicket(photo.getId(), signed.url().toString(), signed.method(),
                command.contentType(), signed.expiresAt());
    }

    /**
     * 访客把字节 PUT 上去之后调这里：确认对象真的在、置 PROCESSING、交给压缩管线。
     *
     * <p>标题和说明是选填的，标签一律为空——活动选题的标签由选片人按预设打
     * （§2.23），把预设标签摆给站外的人挑既没有意义也会把预设泄露出去。</p>
     */
    @Transactional
    public UploadedPhoto complete(ProjectShareService.GuestContext context, Long photoId,
                                  CompleteCommand command) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        requireOpenProject(link);
        PhotoEntity photo = requireOwnUpload(link, photoId);

        ObjectStorageService.ObjectInfo info = storage.stat(photo.getOriginalObjectKey());
        if (info.size() <= 0 || info.size() > storageProperties.imageMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图片为空或超过 100 MiB");
        }
        photo.setTitle(trimmedOrNull(command.title(), 200));
        photo.setDescription(trimmedOrNull(command.description(), 500));
        photo.setStatus(PhotoStatus.PROCESSING);
        photo.setFailureReason(null);

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

    // ------------------------------------------------------------------ ZIP 批量

    /**
     * 签一个 ZIP 批次的上传地址。
     *
     * <p>限额一律沿用站内那一套（{@link ImageUploadPolicy}：ZIP ≤ 1.5 GB、包内 ≤ 100 张、
     * 单张 ≤ 100 MiB、解压总量 ≤ 10 GiB），解包也走同一个 {@code SafeImageZipExtractor}
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
     * <p>站内这一步是人工填元数据（拍摄者、拍摄时间、标签），访客这边没有可填的：
     * 拍摄者来自会话身份，标题取包内的原文件名（访客和选片人之间唯一的共同语言），
     * 标签留空由选片人后续按预设打。所以前端轮到 WAITING_METADATA 就直接调这里。</p>
     */
    @Transactional
    public GuestBatch finishZip(ProjectShareService.GuestContext context, String batchId,
                                LocalDateTime takenAt) {
        ProjectShareLinkEntity link = shareService.requireUploadLink(context.link());
        requireOpenProject(link);
        requireOwnBatch(link, batchId);
        Uploader uploader = requireUploader(context);
        int waiting = batchService.waitingItems(batchId).size();
        requireCapacity(link, waiting);

        BatchUploadService.BatchView view = batchService.finishAllWithSnapshot(batchId,
                new BatchUploadService.PhotoSnapshot(uploader.studentId(), uploader.name(),
                        // 选题不属于任何校区，访客也报不出，与单张上传一致地留空。
                        null, link.getCreatedBy(), link.getId(),
                        takenAt == null ? LocalDateTime.now(clock) : takenAt, null, List.of()));
        // 与单张上传同一个口径：算进来的是"真的交给了压缩管线"的张数。
        jdbc.sql("UPDATE project_share_link SET upload_count = upload_count + :count WHERE id = :id")
                .param("count", waiting).param("id", link.getId()).update();
        return guestView(view.batch());
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

    public record CompleteCommand(String title, String description) {}

    /** ZIP 批量：{@code archiveSize} 的上限是 {@link ImageUploadPolicy#MAX_ARCHIVE_BYTES}。 */
    public record ZipCommand(String archiveFileName, Long archiveSize) {}

    public record GuestBatch(String batchId, BatchStatus status, int totalCount, int successCount,
                             int failureCount, String failureReason) {}

    public record UploadTicket(Long photoId, String uploadUrl, String method, String contentType,
                               java.time.Instant expiresAt) {}

    public record UploadedPhoto(Long photoId, String title, PhotoStatus status, String failureReason) {}
}
