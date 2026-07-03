package cn.photolib.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class StorageStartupInitializer implements ApplicationRunner {
    private final ObjectStorageService storage;
    private final PhotoStorageReconciliationService reconciliation;

    @Override
    public void run(ApplicationArguments args) {
        storage.initialize();
        reconciliation.reconcile();
    }
}
