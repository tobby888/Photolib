package cn.photolib.statistics;

import cn.photolib.storage.ObjectStorageService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
class WorklogExportIntegrationTests {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectStorageService storage;
    @Autowired
    ExportService exports;

    @Test
    void exportsWorklogsAndCountsAdoptedPhotosByProjectCompletionRange() throws Exception {
        long base = System.nanoTime() & Long.MAX_VALUE;
        long userId = base;
        long campusId = base + 1;
        long insideProjectId = base + 2;
        long outsideProjectId = base + 3;
        long requestId = base + 4;
        long secondInsideProjectId = base + 5;

        jdbc.sql("""
                INSERT INTO campus (id, code, name)
                VALUES (:id, :code, '测试校区')
                """).param("id", campusId).param("code", "export-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '导出管理员', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "worklog-export-" + base).update();
        insertProject(insideProjectId, userId, "范围内结束项目",
                LocalDateTime.of(2026, 6, 20, 12, 0));
        insertProject(outsideProjectId, userId, "范围外结束项目",
                LocalDateTime.of(2026, 7, 1, 0, 0));
        insertProject(secondInsideProjectId, userId, "范围内结束项目二",
                LocalDateTime.of(2026, 6, 22, 12, 0));
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES (:id, :projectId, '测试拍摄需求', :campusId, 3, :deadline, 'IN_PROGRESS', :userId)
                """)
                .param("id", requestId)
                .param("projectId", insideProjectId)
                .param("campusId", campusId)
                .param("deadline", LocalDateTime.of(2026, 6, 30, 23, 59))
                .param("userId", userId)
                .update();

        insertWorklog(base + 10, requestId, userId, LocalDate.of(2026, 6, 1),
                "成员甲", "20260001", 60, 30);
        insertWorklog(base + 11, requestId, userId, LocalDate.of(2026, 6, 30),
                "成员甲", "20260001", 15, 45);
        insertWorklog(base + 12, requestId, userId, LocalDate.of(2026, 6, 15),
                "成员乙", "20260002", 20, 10);
        insertWorklog(base + 13, requestId, userId, LocalDate.of(2026, 5, 31),
                "成员甲", "20260001", 999, 999);
        // 未确认（SUBMITTED）工时必须被排除，否则会把未审核工时发进工资。
        insertWorklog(base + 14, requestId, userId, LocalDate.of(2026, 6, 10),
                "成员乙", "20260002", 500, 500, "SUBMITTED");

        long photoOne = insertPhoto(base + 20, insideProjectId, requestId, campusId, userId,
                "20260001", "成员甲", "inside-1-" + base + ".jpg");
        long photoTwo = insertPhoto(base + 21, insideProjectId, requestId, campusId, userId,
                "20260001", "成员甲", "inside-2-" + base + ".jpg");
        long outsidePhoto = insertPhoto(base + 22, outsideProjectId, requestId, campusId, userId,
                "20269999", "范围外摄影师", "outside-" + base + ".jpg");
        insertAdoption(base + 30, insideProjectId, photoOne, userId, "20260001", "成员甲");
        insertAdoption(base + 31, insideProjectId, photoTwo, userId, "20260001", "成员甲");
        insertAdoption(base + 32, outsideProjectId, outsidePhoto, userId, "20269999", "范围外摄影师");
        // 同一张照片被第二个（同样在范围内完成的）项目再次采用；被引张数应按 DISTINCT 照片计，
        // 成员甲仍应是 2 张而非 3，避免一图多项目导致工资重复计发。
        insertAdoption(base + 33, secondInsideProjectId, photoOne, userId, "20260001", "成员甲");

        String jobId = "WEXP" + base;
        jdbc.sql("""
                INSERT INTO export_job (id, type, status, progress, created_by)
                VALUES (:id, 'WORKLOGS', 'PENDING', 0, :userId)
                """).param("id", jobId).param("userId", userId).update();

        exports.exportWorklogs(new ExportService.WorklogExportRequested(
                jobId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)));

        ExportJobEntity job = waitForCompletion(jobId);
        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");

        try (XSSFWorkbook workbook = new XSSFWorkbook(storage.open(job.getObjectKey()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheet("工时统计");
            assertThat(sheet).isNotNull();
            assertHeader(sheet.getRow(0));
            assertThat(sheet.getLastRowNum()).isEqualTo(2);

            Map<String, Row> rows = new HashMap<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                rows.put(sheet.getRow(index).getCell(1).getStringCellValue(), sheet.getRow(index));
            }
            // 成员甲：被引按 DISTINCT 照片计为 2（photoOne 虽被两个项目采用也只算一次）。
            assertExportRow(rows.get("20260001"), "成员甲", "测试校区", 1.25, 1.25, 2.5, 2, "已确认");
            // 成员乙：仅 CONFIRMED 工时计入（20/10），SUBMITTED 的 500/500 被排除。
            assertExportRow(rows.get("20260002"), "成员乙", "测试校区",
                    20.0 / 60.0, 10.0 / 60.0, 0.5, 0, "已确认");
            assertThat(rows).doesNotContainKey("20269999");
        }

        // 本例所有被引摄影师均能匹配到已确认工时，不应产生对账告警。
        Long falseAlerts = jdbc.sql("""
                SELECT COUNT(*) FROM admin_alert
                WHERE type='WORKLOG_EXPORT_UNMATCHED_ADOPTIONS' AND resource_id=:jobId
                """).param("jobId", jobId).query(Long.class).single();
        assertThat(falseAlerts).isZero();
    }

    @Test
    void exportsAndAlertsWhenAdoptionHasNoMatchingConfirmedWorklog() throws Exception {
        long base = System.nanoTime() & Long.MAX_VALUE;
        long userId = base;
        long campusId = base + 1;
        long projectId = base + 2;
        long requestId = base + 3;

        jdbc.sql("""
                INSERT INTO campus (id, code, name)
                VALUES (:id, :code, '对账校区')
                """).param("id", campusId).param("code", "recon-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '对账管理员', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "recon-export-" + base).update();
        insertProject(projectId, userId, "对账范围内项目", LocalDateTime.of(2025, 3, 20, 12, 0));
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES (:id, :projectId, '对账拍摄需求', :campusId, 1, :deadline, 'IN_PROGRESS', :userId)
                """)
                .param("id", requestId).param("projectId", projectId).param("campusId", campusId)
                .param("deadline", LocalDateTime.of(2025, 3, 30, 23, 59)).param("userId", userId).update();

        // 使用与其他用例零重叠的 2025-03 区间隔离数据（worklogs() 是全库查询）。
        // 工时成员学号 20250001；被采用照片的摄影师学号 20259999（对不上），应触发漏发告警。
        insertWorklog(base + 10, requestId, userId, LocalDate.of(2025, 3, 10),
                "有工时成员", "20250001", 60, 0);
        long photo = insertPhoto(base + 20, projectId, requestId, campusId, userId,
                "20259999", "无工时摄影师", "recon-" + base + ".jpg");
        insertAdoption(base + 30, projectId, photo, userId, "20259999", "无工时摄影师");

        String jobId = "RECON" + base;
        jdbc.sql("""
                INSERT INTO export_job (id, type, status, progress, created_by)
                VALUES (:id, 'WORKLOGS', 'PENDING', 0, :userId)
                """).param("id", jobId).param("userId", userId).update();

        exports.exportWorklogs(new ExportService.WorklogExportRequested(
                jobId, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31)));
        ExportJobEntity job = waitForCompletion(jobId);
        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");

        try (XSSFWorkbook workbook = new XSSFWorkbook(storage.open(job.getObjectKey()))) {
            Sheet sheet = workbook.getSheet("工时统计");
            assertHeader(sheet.getRow(0));
            assertThat(sheet.getLastRowNum()).isEqualTo(2);

            Map<String, Row> rows = new HashMap<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                rows.put(sheet.getRow(index).getCell(1).getStringCellValue(), sheet.getRow(index));
            }
            assertExportRow(rows.get("20250001"), "有工时成员", "对账校区",
                    1, 0, 1, 0, "已确认");
            assertExportRow(rows.get("20259999"), "无工时摄影师", "对账校区",
                    0, 0, 0, 1, "未申报");
        }

        String message = waitForUnmatchedAdoptionAlert(jobId);
        assertThat(message).contains("20259999").contains("1 张").contains("核对工时申报和审核状态");
    }

    private void insertProject(long id, long userId, String title, LocalDateTime completedAt) {
        jdbc.sql("""
                INSERT INTO project (id, title, status, created_by, completed_at)
                VALUES (:id, :title, 'COMPLETED', :userId, :completedAt)
                """)
                .param("id", id).param("title", title).param("userId", userId)
                .param("completedAt", completedAt).update();
    }

    private void insertWorklog(long id, long requestId, long userId, LocalDate date,
                               String name, String studentId, int shooting, int retouching) {
        insertWorklog(id, requestId, userId, date, name, studentId, shooting, retouching, "CONFIRMED");
    }

    private void insertWorklog(long id, long requestId, long userId, LocalDate date,
                               String name, String studentId, int shooting, int retouching,
                               String status) {
        jdbc.sql("""
                INSERT INTO worklog
                    (id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                     member_name, member_student_id, status)
                VALUES
                    (:id, :requestId, :userId, :workDate, :shooting, :retouching,
                     :memberName, :studentId, :status)
                """)
                .param("id", id).param("requestId", requestId).param("userId", userId)
                .param("workDate", date).param("shooting", shooting).param("retouching", retouching)
                .param("memberName", name).param("studentId", studentId)
                .param("status", status).update();
    }

    private long insertPhoto(long id, long projectId, long requestId, long campusId, long userId,
                             String studentId, String name, String objectKey) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key,
                     stored_file_name, sha256, status)
                VALUES
                    (:id, :requestId, :projectId, '测试图片', :studentId, :name,
                     :userId, :campusId, :takenAt, 100, 'image/jpeg', :objectKey,
                     'test.jpg', :sha256, 'AVAILABLE')
                """)
                .param("id", id).param("requestId", requestId).param("projectId", projectId)
                .param("studentId", studentId).param("name", name).param("userId", userId)
                .param("campusId", campusId).param("takenAt", LocalDateTime.of(2026, 6, 10, 10, 0))
                .param("objectKey", objectKey).param("sha256", Long.toHexString(id) + "0".repeat(48))
                .update();
        return id;
    }

    private void insertAdoption(long id, long projectId, long photoId, long userId,
                                String studentId, String name) {
        jdbc.sql("""
                INSERT INTO adoption
                    (id, project_id, photo_id, photographer_student_id, photographer_name,
                     adopted_by, adopted_at)
                VALUES (:id, :projectId, :photoId, :studentId, :name, :userId, :adoptedAt)
                """)
                .param("id", id).param("projectId", projectId).param("photoId", photoId)
                .param("studentId", studentId).param("name", name).param("userId", userId)
                .param("adoptedAt", LocalDateTime.of(2026, 6, 25, 10, 0)).update();
    }

    private void assertHeader(Row row) {
        assertThat(row).isNotNull();
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo("姓名");
        assertThat(row.getCell(1).getStringCellValue()).isEqualTo("学号");
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo("校区");
        assertThat(row.getCell(3).getStringCellValue()).isEqualTo("拍摄时长（小时）");
        assertThat(row.getCell(4).getStringCellValue()).isEqualTo("修图时长（小时）");
        assertThat(row.getCell(5).getStringCellValue()).isEqualTo("总时长（小时）");
        assertThat(row.getCell(6).getStringCellValue()).isEqualTo("被引张数");
        assertThat(row.getCell(7).getStringCellValue()).isEqualTo("工时状态");
    }

    private void assertExportRow(Row row, String name, String campus, double shooting,
                                 double retouching, double total, int adopted, String worklogStatus) {
        assertThat(row).isNotNull();
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo(name);
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo(campus);
        assertHourCell(row.getCell(3), shooting);
        assertHourCell(row.getCell(4), retouching);
        assertHourCell(row.getCell(5), total);
        assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(adopted);
        assertThat(row.getCell(7).getStringCellValue()).isEqualTo(worklogStatus);
    }

    private void assertHourCell(Cell cell, double expected) {
        assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(cell.getNumericCellValue()).isCloseTo(expected, within(0.000000001));
        assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("0.00");
    }

    private ExportJobEntity waitForCompletion(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            ExportJobEntity job = jdbc.sql("SELECT * FROM export_job WHERE id=:id")
                    .param("id", jobId).query(ExportJobEntity.class).single();
            if (!"PENDING".equals(job.getStatus())) return job;
            Thread.sleep(100);
        }
        return jdbc.sql("SELECT * FROM export_job WHERE id=:id")
                .param("id", jobId).query(ExportJobEntity.class).single();
    }

    private String waitForUnmatchedAdoptionAlert(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<String> messages = jdbc.sql("""
                    SELECT message FROM admin_alert
                    WHERE type='WORKLOG_EXPORT_UNMATCHED_ADOPTIONS' AND resource_id=:jobId
                    """).param("jobId", jobId).query(String.class).list();
            if (!messages.isEmpty()) return messages.getFirst();
            Thread.sleep(100);
        }
        throw new AssertionError("工时导出完成后未生成被引对账告警: " + jobId);
    }
}
