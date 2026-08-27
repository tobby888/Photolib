package cn.photolib.backup;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库备份与回滚接口，仅系统管理员可用。
 *
 * <p>这里刻意用 {@code hasRole('ADMIN')} 而不是某个 {@link cn.photolib.permission.PermissionCode}：
 * 该能力不进权限面板，也不能通过自定义权限组授予他人。Service 层会再校验一次，
 * 前端隐藏入口不构成访问控制。
 */
@RestController
@RequiredArgsConstructor
public class DatabaseBackupController {
    private final DatabaseBackupService service;

    @PostMapping("/database-backups")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<DatabaseBackupService.BackupView> create(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.startManualBackup(user));
    }

    @GetMapping("/database-backups")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PageResponse<DatabaseBackupService.BackupView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.listBackups(page, pageSize, user));
    }

    @GetMapping("/database-backups/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<DatabaseBackupService.BackupView> get(@PathVariable String id,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.getBackup(id, user));
    }

    @GetMapping("/database-backups/{id}/download")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<DatabaseBackupService.DownloadLink> download(@PathVariable String id,
                                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.downloadLink(id, user));
    }

    @PostMapping("/database-backups/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<DatabaseBackupService.RestoreView> restore(@PathVariable String id,
                                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.startRestore(id, user));
    }

    @GetMapping("/database-restores")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PageResponse<DatabaseBackupService.RestoreView>> restores(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.listRestores(page, pageSize, user));
    }

    @GetMapping("/database-restores/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<DatabaseBackupService.RestoreView> getRestore(@PathVariable String id,
                                                              @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.getRestore(id, user));
    }
}
