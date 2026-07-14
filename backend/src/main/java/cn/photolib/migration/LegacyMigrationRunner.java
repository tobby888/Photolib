package cn.photolib.migration;

import cn.photolib.admin.AdminAlertEntity;
import cn.photolib.admin.AdminAlertMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class LegacyMigrationRunner implements ApplicationRunner {
    private final LegacyMigrationProperties properties;
    private final LegacyMigrationService migrationService;
    private final AdminAlertMapper alertMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }

        try {
            LegacyMigrationService.Result result = migrationService.migrate(properties);
            log.info("旧 PhotoWarehouse 数据迁移完成：用户 {}，项目 {}，照片 {}",
                    result.users(), result.projects(), result.photos());
        } catch (Exception exception) {
            // ApplicationRunner抛出异常会终止Spring Boot；这里必须吞掉异常，让新系统继续服务。
            log.error("旧 PhotoWarehouse 数据迁移失败，新系统将继续启动", exception);
            try {
                AdminAlertEntity alert = new AdminAlertEntity();
                alert.setType("LEGACY_MIGRATION_FAILED");
                alert.setMessage(limit("旧 PhotoWarehouse 数据迁移失败：" + rootMessage(exception), 1000));
                alert.setResourceType("SYSTEM");
                alert.setResourceId("PhotoWarehouse");
                alert.setResolved(false);
                alertMapper.insert(alert);
            } catch (Exception alertException) {
                log.error("写入迁移失败管理员告警时再次失败", alertException);
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
