package cn.photolib.statistics;

import cn.photolib.storage.ObjectStorageService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

        long photoOne = insertPhoto(base + 20, insideProjectId, requestId, campusId, userId,
                "20260001", "成员甲", "inside-1-" + base + ".jpg");
        long photoTwo = insertPhoto(base + 21, insideProjectId, requestId, campusId, userId,
                "20260001", "成员甲", "inside-2-" + base + ".jpg");
        long outsidePhoto = insertPhoto(base + 22, outsideProjectId, requestId, campusId, userId,
                "20260001", "成员甲", "outside-" + base + ".jpg");
        insertAdoption(base + 30, insideProjectId, photoOne, userId, "20260001", "成员甲");
        insertAdoption(base + 31, insideProjectId, photoTwo, userId, "20260001", "成员甲");
        insertAdoption(base + 32, outsideProjectId, outsidePhoto, userId, "20260001", "成员甲");

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
            assertExportRow(rows.get("20260001"), "成员甲", "测试校区", 75, 75, 150, 2);
            assertExportRow(rows.get("20260002"), "成员乙", "测试校区", 20, 10, 30, 0);
        }
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
        jdbc.sql("""
                INSERT INTO worklog
                    (id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                     member_name, member_student_id, status)
                VALUES
                    (:id, :requestId, :userId, :workDate, :shooting, :retouching,
                     :memberName, :studentId, 'CONFIRMED')
                """)
                .param("id", id).param("requestId", requestId).param("userId", userId)
                .param("workDate", date).param("shooting", shooting).param("retouching", retouching)
                .param("memberName", name).param("studentId", studentId).update();
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
        assertThat(row.getCell(3).getStringCellValue()).isEqualTo("拍摄分钟");
        assertThat(row.getCell(4).getStringCellValue()).isEqualTo("修图分钟");
        assertThat(row.getCell(5).getStringCellValue()).isEqualTo("总分钟");
        assertThat(row.getCell(6).getStringCellValue()).isEqualTo("被引张数");
    }

    private void assertExportRow(Row row, String name, String campus, int shooting,
                                 int retouching, int total, int adopted) {
        assertThat(row).isNotNull();
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo(name);
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo(campus);
        assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(shooting);
        assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(retouching);
        assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(total);
        assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(adopted);
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
}
