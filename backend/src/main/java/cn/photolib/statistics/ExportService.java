package cn.photolib.statistics;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ExportService {
    private final ExportJobMapper mapper;
    private final StatisticsService statistics;
    private final PhotoMapper photoMapper;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;

    @Transactional
    public ExportJobEntity createStatistics(LocalDate from, LocalDate to, Long projectId,
                                            Long campusId, AuthenticatedUser user) {
        ExportJobEntity job = newJob("MEMBER_STATISTICS", user.id());
        mapper.insert(job);
        events.publishEvent(new StatisticsExportRequested(job.getId(), from, to, projectId, campusId));
        return job;
    }

    @Transactional
    public ExportJobEntity createPhotoZip(List<Long> photoIds, AuthenticatedUser user) {
        if (photoIds == null || photoIds.isEmpty() || photoIds.size() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "批量下载需选择 1 至 200 张图片");
        }
        ExportJobEntity job = newJob("PHOTO_BATCH", user.id());
        mapper.insert(job);
        events.publishEvent(new PhotoZipRequested(job.getId(), photoIds.stream().distinct().toList()));
        return job;
    }

    @Transactional
    public ExportJobEntity createWorklogs(LocalDate from, LocalDate to, AuthenticatedUser user) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工时日期范围无效");
        }
        ExportJobEntity job = newJob("WORKLOGS", user.id());
        mapper.insert(job);
        events.publishEvent(new WorklogExportRequested(job.getId(), from, to));
        return job;
    }

    public JobView get(String id, AuthenticatedUser user) {
        ExportJobEntity job = mapper.selectById(id);
        if (job == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导出任务不存在");
        if (!job.getCreatedBy().equals(user.id()) && user.role() != cn.photolib.user.model.UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        String url = null;
        java.time.Instant expires = null;
        if ("SUCCEEDED".equals(job.getStatus()) && job.getExpiresAt().isAfter(LocalDateTime.now())) {
            ObjectStorageService.SignedUrl signed = storage.presignGet(
                    job.getObjectKey(), job.getType() + (job.getType().equals("PHOTO_BATCH") ? ".zip" : ".xlsx"),
                    storageProperties.downloadUrlTtl());
            url = signed.url().toString();
            expires = signed.expiresAt();
        }
        return new JobView(job, url, expires);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void exportStatistics(StatisticsExportRequested event) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet worklogs = workbook.createSheet("工时统计");
            row(worklogs, 0, "姓名", "学号", "校区", "拍摄分钟", "修图分钟", "总分钟", "被引张数");
            int index = 1;
            for (var value : statistics.members(event.from(), event.to(), event.projectId(), event.campusId(), null)) {
                row(worklogs, index++, value.displayName(), value.studentId(), value.campus(),
                        value.shootingMinutes(), value.retouchingMinutes(), value.totalMinutes(),
                        value.adoptedCount());
            }
            workbook.write(output);
            save(event.jobId(), output.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
        } catch (Exception ex) {
            fail(event.jobId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void exportWorklogs(WorklogExportRequested event) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet worklogs = workbook.createSheet("工时统计");
            row(worklogs, 0, "姓名", "学号", "校区", "拍摄分钟", "修图分钟", "总分钟", "被引张数");
            int index = 1;
            for (var value : statistics.worklogs(event.from(), event.to())) {
                row(worklogs, index++, value.memberName(), value.studentId(), value.campus(),
                        value.shootingMinutes(), value.retouchingMinutes(), value.totalMinutes(),
                        value.adoptedCount());
            }
            for (int column = 0; column < 7; column++) {
                worklogs.autoSizeColumn(column);
            }
            workbook.write(output);
            save(event.jobId(), output.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
        } catch (Exception ex) {
            fail(event.jobId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void exportPhotos(PhotoZipRequested event) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("photolib-export-", ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                List<PhotoEntity> photos = photoMapper.selectList(Wrappers.<PhotoEntity>lambdaQuery()
                        .in(PhotoEntity::getId, event.photoIds()));
                for (PhotoEntity photo : photos) {
                    zip.putNextEntry(new ZipEntry(photo.getId() + "-" + photo.getStoredFileName()));
                    try (InputStream input = storage.open(photo.getObjectKey())) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            String key = "exports/" + event.jobId() + ".zip";
            try (InputStream input = Files.newInputStream(temporary)) {
                storage.put(key, input, Files.size(temporary), "application/zip");
            }
            succeed(event.jobId(), key);
        } catch (Exception ex) {
            fail(event.jobId(), ex);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
            }
        }
    }

    private ExportJobEntity newJob(String type, Long userId) {
        ExportJobEntity job = new ExportJobEntity();
        job.setId(PublicId.next());
        job.setType(type);
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setCreatedBy(userId);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    private void save(String id, byte[] bytes, String contentType, String extension) {
        String key = "exports/" + id + extension;
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        succeed(id, key);
    }

    private void succeed(String id, String key) {
        ExportJobEntity job = mapper.selectById(id);
        job.setObjectKey(key);
        job.setProgress(100);
        job.setStatus("SUCCEEDED");
        job.setExpiresAt(LocalDateTime.now().plusDays(1));
        mapper.updateById(job);
    }

    private void fail(String id, Exception ex) {
        ExportJobEntity job = mapper.selectById(id);
        job.setStatus("FAILED");
        job.setErrorMessage(ex.getMessage());
        mapper.updateById(job);
    }

    private void row(Sheet sheet, int index, Object... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
            else row.createCell(i).setCellValue(values[i] == null ? "" : values[i].toString());
        }
    }

    public record StatisticsExportRequested(String jobId, LocalDate from, LocalDate to,
                                            Long projectId, Long campusId) {}
    public record WorklogExportRequested(String jobId, LocalDate from, LocalDate to) {}
    public record PhotoZipRequested(String jobId, List<Long> photoIds) {}
    public record JobView(ExportJobEntity job, String downloadUrl, java.time.Instant expiresAt) {}
}
