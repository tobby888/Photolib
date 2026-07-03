package cn.photolib.notification;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class UserNotificationController {
    private final NotificationService service;

    @GetMapping
    ApiResponse<List<UserNotificationEntity>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.listForUser(user.id(), unreadOnly));
    }

    @GetMapping("/{id}")
    ApiResponse<UserNotificationEntity> get(@PathVariable Long id,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.getForUser(id, user.id()));
    }

    @GetMapping("/unread-count")
    ApiResponse<UnreadCount> unreadCount(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(new UnreadCount(service.unreadCount(user.id())));
    }

    @PostMapping("/{id}/read")
    ApiResponse<Void> markRead(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.markRead(id, user.id());
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    ApiResponse<Void> markAllRead(@AuthenticationPrincipal AuthenticatedUser user) {
        service.markAllRead(user.id());
        return ApiResponse.ok();
    }

    @PostMapping("/messages")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<SendResult> send(@Valid @RequestBody SendMessageRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        int recipients = service.sendMessage(user.id(), request.targetUserId(), request.broadcast(),
                request.title(), request.contentHtml());
        return ApiResponse.ok(new SendResult(recipients));
    }

    record SendMessageRequest(
            boolean broadcast,
            Long targetUserId,
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 20000) String contentHtml) {
        @AssertTrue(message = "单独发送时必须选择接收成员")
        boolean isTargetValid() {
            return broadcast || targetUserId != null;
        }
    }

    record SendResult(int recipientCount) {}

    record UnreadCount(long count) {}
}
