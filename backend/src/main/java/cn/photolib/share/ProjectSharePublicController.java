package cn.photolib.share;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.photo.PhotoService;
import cn.photolib.statistics.ExportJobEntity;
import cn.photolib.statistics.ExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
    private final ShareAccessRateLimiter rateLimiter;

    // 会话头声明成可选：缺头是"这个人还没过密码"，该由 resolveGuest 回一句
    // "请重新输入密码"的 403，而不是让 Spring 抛一个 400 的缺参错误——前端在
    // 这两种情况下要做的事完全一样（退回密码页），错误码不一致只会让它多一条分支。

    /** 密码页用：只回答"这条链接还能不能用"，不泄露项目标题等任何项目信息。 */
    @GetMapping
    ApiResponse<ProjectShareService.LinkGreeting> greet(@PathVariable String token) {
        return ApiResponse.ok(service.greet(token));
    }

    @PostMapping("/sessions")
    ApiResponse<ProjectShareService.GuestSession> openSession(
            @PathVariable String token, @Valid @RequestBody PasswordRequest request,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.PASSWORD, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.openSession(token, request.password()));
    }

    /** 会话续用时重新取一次能力：链接的开关可能刚被创建者改过。 */
    @GetMapping("/access")
    ApiResponse<ProjectShareService.GuestAccess> access(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BROWSE, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.access(service.resolveGuest(token, session)));
    }

    @GetMapping("/photos")
    ApiResponse<PageResponse<ProjectShareService.SharePhotoView>> photos(
            @PathVariable String token,
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            HttpServletRequest servletRequest) {
        rateLimiter.requireAllowed(ShareAccessRateLimiter.Action.BROWSE, token,
                servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.photos(service.resolveGuest(token, session),
                page, pageSize, keyword));
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

    record PasswordRequest(@NotNull @Size(max = ProjectShareService.MAX_PASSWORD_LENGTH) String password) {}

    record BatchDownloadRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds) {}
}
