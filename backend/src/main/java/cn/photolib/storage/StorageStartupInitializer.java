package cn.photolib.storage;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class StorageStartupInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StorageStartupInitializer.class);
    private final ObjectStorageService storage;
    private final PhotoStorageReconciliationService reconciliation;

    @Override
    public void run(ApplicationArguments args) {
        try {
            storage.initialize();
        } catch (Exception e) {
            log.error("存储服务初始化失败，部分功能可能不可用", e);
        }
        try {
            reconciliation.reconcile();
        } catch (Exception e) {
            log.error("启动时对账任务执行失败，将由定时任务后续重试", e);
        }
    }
}
