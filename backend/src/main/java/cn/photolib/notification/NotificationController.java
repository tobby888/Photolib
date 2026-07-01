package cn.photolib.notification;

import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notification-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class NotificationController {
    private final NotificationService service;

    @GetMapping
    ApiResponse<List<NotificationLogEntity>> list(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Long userId) {
        return ApiResponse.ok(service.list(status, userId));
    }

    @PostMapping("/{id}/retry")
    ApiResponse<Void> retry(@PathVariable Long id) {
        service.retry(id);
        return ApiResponse.ok();
    }
}
