package cn.photolib.share;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.PhotoTags;
import cn.photolib.photo.batch.BatchUploadService;
import cn.photolib.statistics.ExportJobEntity;
import cn.photolib.statistics.ExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分享链接的访客端，整条链路不需要登录。
 *
 * <p>"public" 只表示"不带令牌也能调用"，不表示"返回的都是公开内容"：除了第一个
 * 打招呼的接口，其余每个方法都先 {@link ProjectShareService#resolveGuest} 拿到
 * 当前这一刻的链接行，再由 Service 判下载/被引开关。会话头名字见
 * {@link #SESSION_HEADER}，前端 {@code src/pages/SharedProjectPage.tsx} 用同一个。</p>
 *
 * <p>限速对每个动作都开，理由见 {@link ShareAccessRateLimiter}：这条链路上的每次
 * 读图都是一次真实的对象存储读取，而密码是唯一可以被猜的东西。</p>
 */
@RestController
@RequestMapping("/public/shares/{token}")
@RequiredArgsConstructor
public class ProjectSharePublicController {
    public static final String SESSION_HEADER = "X-Share-Session";

    private final ProjectShareService service;
    private final ProjectShareUploadService uploadService;
    private final ShareAccessRateLimiter rateLimiter;

    // 会话头声明成可选：缺头是"这个人还没过密码"，该由 resolveGuest 回一句
    // "请重新输入密码"的 403，而不是让 Spring 抛一个 400 的缺参错误——前端在
    // 这两种情况下要做的事完全一样（退回密码页），错误码不一致只会让它多一条分支。

    /** 密码页用：只回答"这条链接还能不能用"，不泄露项目标题等任何项目信息。 */
    @GetMapping
    ApiResponse<ProjectShareService.LinkGreeting> greet(@PathVariable String token) {
        return ApiResponse.ok(service.greet(token));
    }

    /**
     * 进门。上传链接还要求自报姓名和学号——那是这次会话上传的每一张图片的拍摄者
     * 快照，理由见 {@link ProjectShareUploadService} 第 2 条不变量。浏览链接忽略这两项。
     */
    @PostMapping("/sessions")
    ApiResponse<ProjectShareService.GuestSession> openSession(
            @PathVariable String token, @Valid @RequestBody PasswordRequest request,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.PASSWORD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.openSession(token, request.password(), request.identity()));
    }

    /** 会话续用时重新取一次能力：链接的开关可能刚被创建者改过。 */
    @GetMapping("/access")
    ApiResponse<ProjectShareService.GuestAccess> access(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BROWSE, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.access(service.resolveGuestContext(token, session)));
    }

    @GetMapping("/photos")
    ApiResponse<PageResponse<ProjectShareService.SharePhotoView>> photos(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(required = false) @Size(max = PhotoTags.MAX_TAGS) List<String> tags,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenTo,
            @RequestParam(required = false) @Size(max = 200) List<String> photographers,
            @RequestParam(required = false) ProjectShareService.AdoptionFilter adoption,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BROWSE, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.photos(service.resolveGuest(token, session), page, pageSize,
                new ProjectShareService.PhotoFilter(keyword, tags, takenFrom, takenTo, photographers, adoption)));
    }

    /** 筛选下拉的候选：这条链接看得到的图片上的标签（含项目预设）和拍摄者。 */
    @GetMapping("/photo-filter-options")
    ApiResponse<ProjectShareService.FilterOptions> photoFilterOptions(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BROWSE, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.filterOptions(service.resolveGuest(token, session)));
    }

    @PostMapping("/photos/{photoId}/download-url")
    ApiResponse<PhotoService.DownloadUrl> download(
            @PathVariable String token, @PathVariable Long photoId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.DOWNLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.download(service.resolveGuest(token, session), photoId));
    }

    @PostMapping("/batch-downloads")
    ApiResponse<ExportJobEntity> batchDownload(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @Valid @RequestBody BatchDownloadRequest request, HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BATCH_DOWNLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.batchDownload(
                service.resolveGuest(token, session), request.photoIds()));
    }

    @GetMapping("/batch-downloads/{jobId}")
    ApiResponse<ExportService.JobView> batchDownloadStatus(
            @PathVariable String token, @PathVariable String jobId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BROWSE, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.batchDownloadStatus(
                service.resolveGuest(token, session), jobId));
    }

    @PostMapping("/photos/{photoId}/adoption")
    ApiResponse<ProjectShareService.SharePhotoAdoption> adopt(
            @PathVariable String token, @PathVariable Long photoId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.ADOPTION, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.adopt(service.resolveGuest(token, session), photoId));
    }

    @DeleteMapping("/photos/{photoId}/adoption")
    ApiResponse<ProjectShareService.SharePhotoAdoption> cancelAdoption(
            @PathVariable String token, @PathVariable Long photoId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.ADOPTION, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.cancelAdoption(service.resolveGuest(token, session), photoId));
    }

    // ------------------------------------------------------------------ 上传链接

    @PostMapping("/upload-tickets")
    ApiResponse<ProjectShareUploadService.UploadTicket> uploadTicket(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @Valid @RequestBody UploadTicketRequest request, HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.createTicket(
                service.resolveGuestContext(token, session), request.command()));
    }

    @PostMapping("/uploads/{photoId}/complete")
    ApiResponse<ProjectShareUploadService.UploadedPhoto> completeUpload(
            @PathVariable String token, @PathVariable Long photoId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @Valid @RequestBody UploadCompleteRequest request, HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.complete(service.resolveGuestContext(token, session),
                photoId, new ProjectShareUploadService.CompleteCommand(
                        request.title(), request.description())));
    }

    /** 轮询处理结果：压缩失败的图片会被打回 UPLOADING，访客必须能看到这件事。 */
    @GetMapping("/uploads/{photoId}")
    ApiResponse<ProjectShareUploadService.UploadedPhoto> uploadStatus(
            @PathVariable String token, @PathVariable Long photoId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD_STATUS, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.status(
                service.resolveGuestContext(token, session), photoId));
    }

    /**
     * ZIP 批量上传：建批次并签出压缩包的上传地址。限额与站内需求批量上传完全一致，
     * 见 {@link ProjectShareUploadService#createZipTicket}。
     */
    @PostMapping("/upload-batches")
    ApiResponse<BatchUploadService.BatchTicket> createUploadBatch(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @Valid @RequestBody UploadBatchRequest request, HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.createZipTicket(
                service.resolveGuestContext(token, session),
                new ProjectShareUploadService.ZipCommand(
                        request.archiveFileName(), request.archiveSize())));
    }

    /** 压缩包已经传完，交给解包（异步）。 */
    @PostMapping("/upload-batches/{batchId}/complete")
    ApiResponse<ProjectShareUploadService.GuestBatch> completeUploadBatch(
            @PathVariable String token, @PathVariable String batchId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.completeZip(
                service.resolveGuestContext(token, session), batchId));
    }

    /** 轮询解包与处理结果；解包失败的原因也从这里回。 */
    @GetMapping("/upload-batches/{batchId}")
    ApiResponse<ProjectShareUploadService.GuestBatch> uploadBatchStatus(
            @PathVariable String token, @PathVariable String batchId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD_STATUS, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.batchStatus(
                service.resolveGuestContext(token, session), batchId));
    }

    /** 解包完成后把这一批落成照片。访客没有元数据要填，拍摄者来自会话身份。 */
    @PostMapping("/upload-batches/{batchId}/finish")
    ApiResponse<ProjectShareUploadService.GuestBatch> finishUploadBatch(
            @PathVariable String token, @PathVariable String batchId,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @Valid @RequestBody UploadBatchFinishRequest request, HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.UPLOAD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(uploadService.finishZip(
                service.resolveGuestContext(token, session), batchId, request.takenAt()));
    }

    record UploadBatchRequest(@NotBlank @Size(max = 255) String archiveFileName,
                              @NotNull @Min(1) Long archiveSize) {}

    /** 拍摄时间留空按当前时间算，与单张上传同一条规则。 */
    record UploadBatchFinishRequest(LocalDateTime takenAt) {}

    record PasswordRequest(@NotNull @Size(max = ProjectShareService.MAX_PASSWORD_LENGTH) String password,
                           @Size(max = ProjectShareService.MAX_UPLOADER_NAME_LENGTH) String uploaderName,
                           @Size(max = 64) String uploaderStudentId) {
        ProjectShareService.GuestIdentity identity() {
            return new ProjectShareService.GuestIdentity(uploaderName, uploaderStudentId);
        }
    }

    record UploadTicketRequest(@NotBlank @Size(max = 255) String fileName,
                               @NotBlank @Size(max = 100) String contentType,
                               @Min(1) long size,
                               @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}",
                                       message = "SHA-256 必须是 64 位十六进制") String sha256,
                               LocalDateTime takenAt) {
        ProjectShareUploadService.TicketCommand command() {
            return new ProjectShareUploadService.TicketCommand(fileName, contentType, size,
                    sha256, takenAt);
        }
    }

    record UploadCompleteRequest(@Size(max = 200) String title, @Size(max = 500) String description) {}

    record BatchDownloadRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds) {}
}
