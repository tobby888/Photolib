package cn.photolib.photo;

import cn.photolib.admin.AdminAlertEntity;
import cn.photolib.admin.AdminAlertMapper;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.storage.ObjectStorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class OriginalCleanupJob {
    private final PhotoMapper photoMapper;
    private final ObjectStorageService storage;
    private final AdminAlertMapper alertMapper;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void clean() {
        photoMapper.selectList(Wrappers.<PhotoEntity>lambdaQuery()
                .isNotNull(PhotoEntity::getOriginalObjectKey)
                .le(PhotoEntity::getOriginalDeleteAfter, LocalDateTime.now())
                .last("LIMIT 1000")).forEach(photo -> {
            try {
                storage.delete(photo.getOriginalObjectKey());
                photo.setOriginalObjectKey(null);
                photo.setOriginalDeleteAfter(null);
                photoMapper.updateById(photo);
            } catch (Exception ex) {
                AdminAlertEntity alert = new AdminAlertEntity();
                alert.setType("ORIGINAL_CLEANUP_FAILED");
                alert.setMessage("原图清理失败：" + ex.getMessage());
                alert.setResourceType("PHOTO");
                alert.setResourceId(photo.getId().toString());
                alert.setResolved(false);
                alert.setCreatedAt(LocalDateTime.now());
                alertMapper.insert(alert);
            }
        });
    }
}
