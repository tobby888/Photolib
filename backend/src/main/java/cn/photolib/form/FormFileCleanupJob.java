package cn.photolib.form;

import cn.photolib.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 删掉传了却一直没被提交引用的「上传文件」题文件。
 *
 * <p>只动签名已经过期（再加 {@link #GRACE}）的行：签名还有效时，删了对象也可能被一次重放的 PUT 写回来，
 * 而行一旦删掉，那个对象就再也没人知道了。先删对象、再删行；对象删失败就保留行，
 * 下一轮重试。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FormFileCleanupJob {
    private static final int BATCH_SIZE = 200;
    /**
     * 签名过期后再等一会儿才删：页面是先传文件、紧接着提交，留出余量，
     * 免得恰好卡在过期那一刻的提交因为文件被清掉而失败。
     */
    static final Duration GRACE = Duration.ofHours(1);

    private final FormFileUploadMapper mapper;
    private final ObjectStorageService storage;
    private final Clock recruitmentClock;

    @Value("${photolib.form-files.cleanup-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${photolib.form-files.cleanup-delay-ms:600000}",
            initialDelayString = "${photolib.form-files.cleanup-initial-delay-ms:120000}")
    public void scheduledCleanup() {
        if (enabled) cleanup();
    }

    public int cleanup() {
        int removed = 0;
        LocalDateTime before = LocalDateTime.now(recruitmentClock).minus(GRACE);
        for (FormFileUploadEntity upload : mapper.findAbandoned(before, BATCH_SIZE)) {
            try {
                storage.delete(upload.getObjectKey());
            } catch (RuntimeException exception) {
                log.warn("清理未提交的表单文件失败，下一轮重试: id={}, objectKey={}",
                        upload.getId(), upload.getObjectKey(), exception);
                continue;
            }
            removed += mapper.deletePending(upload.getId());
        }
        if (removed > 0) log.info("已清理 {} 个未提交的表单文件", removed);
        return removed;
    }
}
