package cn.photolib.feedback;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网站问题反馈接口，挂在 {@code /api/v1/feedback} 下。
 *
 * <p>提交、读、回复不设 {@code @PreAuthorize}：任何已登录、能进系统的账号都可调用，
 * 权限在服务层按「ADMIN 或提交人」裁剪；只有改状态是 {@code hasRole('ADMIN')}。
 * 物品级写操作（reply / status）的资源 id 紧跟 {@code feedback} 段，
 * 保证审计拦截器能把资源 id 归档对。</p>
 */
@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {
    private final FeedbackService service;

    @PostMapping
    ApiResponse<FeedbackService.FeedbackView> submit(@Valid @RequestBody SubmitRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.submit(
                request.title(), request.category(), request.contentHtml(), user));
    }

    @GetMapping
    ApiResponse<List<FeedbackService.FeedbackSummary>> list(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(status, user));
    }

    @GetMapping("/{id}")
    ApiResponse<FeedbackService.FeedbackView> get(@PathVariable long id,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @PostMapping("/{id}/reply")
    ApiResponse<FeedbackService.FeedbackView> reply(@PathVariable long id,
                                                    @Valid @RequestBody ReplyRequest request,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.reply(id, request.contentHtml(), user));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<FeedbackService.FeedbackView> changeStatus(@PathVariable long id,
                                                           @Valid @RequestBody StatusRequest request,
                                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeStatus(id, request.status(), request.version(), user));
    }

    record SubmitRequest(@NotBlank @Size(max = 200) String title,
                         @NotBlank String category,
                         @NotBlank @Size(max = 20000) String contentHtml) {
    }

    record ReplyRequest(@NotBlank @Size(max = 20000) String contentHtml) {
    }

    record StatusRequest(@NotBlank String status, @Min(1) int version) {
    }
}
