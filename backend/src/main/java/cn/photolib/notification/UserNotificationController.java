package cn.photolib.notification;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    record UnreadCount(long count) {}
}
