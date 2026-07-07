package cn.photolib.statistics;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.admin.AdminAlertEntity;
import cn.photolib.admin.AdminAlertMapper;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {
    private final ExportJobMapper mapper;
    private final StatisticsService statistics;
    private final PhotoMapper photoMapper;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;
    private final AdminAlertMapper alertMapper;

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
                try {
                    worklogs.autoSizeColumn(column);
                } catch (Exception ignored) {
                    // autoSizeColumn 依赖 AWT 字体度量，无头/字体缺失环境可能抛异常；
                    // 列宽仅影响美观，不能因此让工资相关的工时导出失败。
                }
            }
            workbook.write(output);
            save(event.jobId(), output.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
            alertUnmatchedAdoptions(event);
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
                // 仅打包在库/归档且未软删的图片；已删除或处理中的图片不得进入下载包。
                List<PhotoEntity> photos = photoMapper.selectList(Wrappers.<PhotoEntity>lambdaQuery()
                        .in(PhotoEntity::getId, event.photoIds())
                        .eq(PhotoEntity::getDeleted, false)
                        .in(PhotoEntity::getStatus, PhotoStatus.AVAILABLE, PhotoStatus.ARCHIVED));
                java.util.Set<String> usedNames = new java.util.HashSet<>();
                for (PhotoEntity photo : photos) {
                    zip.putNextEntry(new ZipEntry(uniqueEntryName(usedNames, photo)));
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

    /**
     * 工时导出后核对：若该期存在被引（采用）但学号匹配不到已确认工时成员的摄影师，
     * 其被引数会在导出里静默归零，属于潜在漏发工资。此处写入管理员告警提醒人工核对，
     * 但告警失败不得影响已成功的导出任务。
     */
    private void alertUnmatchedAdoptions(WorklogExportRequested event) {
        try {
            List<StatisticsService.UnmatchedAdoption> unmatched =
                    statistics.unmatchedAdoptions(event.from(), event.to());
            if (unmatched.isEmpty()) return;
            long photos = unmatched.stream().mapToLong(StatisticsService.UnmatchedAdoption::adoptedCount).sum();
            String detail = unmatched.stream().limit(20)
                    .map(u -> u.photographerName() + "(" + u.photographerStudentId() + ")：" + u.adoptedCount() + " 张")
                    .reduce((a, b) -> a + "，" + b).orElse("");
            String message = "工时导出（" + event.from() + " 至 " + event.to() + "）发现 "
                    + unmatched.size() + " 名摄影师共 " + photos
                    + " 张被引图片无法匹配到已确认工时成员，被引数可能被漏算：" + detail;
            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType("WORKLOG_EXPORT_UNMATCHED_ADOPTIONS");
            alert.setMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            alert.setResourceType("EXPORT_JOB");
            alert.setResourceId(event.jobId());
            alert.setResolved(false);
            alertMapper.insert(alert);
        } catch (Exception ex) {
            log.error("写入工时导出被引对账告警失败，导出本身已成功", ex);
        }
    }

    private void row(Sheet sheet, int index, Object... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
            else row.createCell(i).setCellValue(values[i] == null ? "" : values[i].toString());
        }
    }

    private String uniqueEntryName(java.util.Set<String> used, PhotoEntity photo) {
        // storedFileName 可能含路径分隔符或与其他图片重名，需消毒并去重，避免 ZIP 条目冲突。
        String stored = photo.getStoredFileName() == null ? "" : photo.getStoredFileName();
        String safe = stored.replaceAll("[/\\\\]", "_");
        if (safe.isBlank()) safe = photo.getId() + ".bin";
        String base = photo.getId() + "-" + safe;
        String candidate = base;
        int suffix = 1;
        while (!used.add(candidate)) {
            int dot = base.lastIndexOf('.');
            candidate = dot > 0
                    ? base.substring(0, dot) + "(" + suffix + ")" + base.substring(dot)
                    : base + "(" + suffix + ")";
            suffix++;
        }
        return candidate;
    }

    public record StatisticsExportRequested(String jobId, LocalDate from, LocalDate to,
                                            Long projectId, Long campusId) {}
    public record WorklogExportRequested(String jobId, LocalDate from, LocalDate to) {}
    public record PhotoZipRequested(String jobId, List<Long> photoIds) {}
    public record JobView(ExportJobEntity job, String downloadUrl, java.time.Instant expiresAt) {}
}
