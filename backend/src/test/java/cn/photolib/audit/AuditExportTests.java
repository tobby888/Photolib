package cn.photolib.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作日志导出按游标分批读、边读边写。分批的边界不能漏行、不能重行，同一时间戳的并列记录
 * 要靠 id 排开；输出格式与一次性导出时保持一致。
 */
@SpringBootTest
@Transactional
class AuditExportTests {
    private static final long OPERATOR = 91002L;

    @Autowired
    private AuditLogMapper mapper;
    @Autowired
    private AuditCatalog catalog;
    @Autowired
    private JdbcClient jdbc;

    private AuditController controller;

    @BeforeEach
    void setUp() {
        controller = new AuditController(mapper, catalog);
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (91002, 'export-admin', 'not-used', '导出管理员', 'ADMIN', true, false)
                """).update();
        // 三个时间点，前两个各有多条并列记录，专门压分批边界落在并列记录中间的情况。
        String[][] rows = {
                {"r1", "2020-01-01 10:00:00"}, {"r2", "2020-01-01 10:00:00"}, {"r3", "2020-01-01 10:00:00"},
                {"r4", "2020-01-02 10:00:00"}, {"r5", "2020-01-02 10:00:00"},
                {"r6", "2020-01-03 10:00:00"}, {"r7", "2020-01-03 10:00:00"},
        };
        for (String[] row : rows) {
            jdbc.sql("""
                    INSERT INTO audit_log
                        (operator_id, action, action_name, resource_type, resource_id, resource_name,
                         request_id, http_status, success, detail_json, ip_address, created_at)
                    VALUES (91002, 'POST', '新建选题', 'PROJECTS', :id, :name,
                            :id, 200, TRUE, '{}', '127.0.0.1', CAST(:at AS TIMESTAMP))
                    """).param("id", row[0]).param("name", "选题 " + row[0]).param("at", row[1]).update();
        }
    }

    private AuditLogFilter mine() {
        return AuditLogFilter.builder().operatorId(OPERATOR).build();
    }

    private String export(int batchSize, int limit) throws Exception {
        StringWriter out = new StringWriter();
        controller.writeCsv(mine(), out, batchSize, limit);
        return out.toString();
    }

    private static List<String> resourceIds(String csv) {
        return Arrays.stream(csv.split("\r\n"))
                .filter(line -> line.contains("\"POST\""))
                .map(line -> line.split("\",\"")[8])
                .toList();
    }

    @Test
    void everyBatchSizeProducesTheSameFileAsASinglePass() throws Exception {
        String singlePass = export(1_000, AuditController.EXPORT_LIMIT);

        assertThat(resourceIds(singlePass)).containsExactly("r7", "r6", "r5", "r4", "r3", "r2", "r1");
        for (int batchSize : new int[] {1, 2, 3, 7}) {
            assertThat(export(batchSize, AuditController.EXPORT_LIMIT)).as("batchSize=%d", batchSize)
                    .isEqualTo(singlePass);
        }
    }

    @Test
    void limitStopsInTheMiddleOfABatch() throws Exception {
        StringWriter out = new StringWriter();

        int written = controller.writeCsv(mine(), out, 2, 5);

        assertThat(written).isEqualTo(5);
        assertThat(resourceIds(out.toString())).containsExactly("r7", "r6", "r5", "r4", "r3");
    }

    @Test
    void respondsWithUtf8CsvStartingWithByteOrderMark() throws Exception {
        jdbc.sql("""
                INSERT INTO audit_log
                    (operator_id, action, resource_type, resource_id, resource_name, request_id,
                     error_message, detail_json, created_at)
                VALUES (91002, 'DELETE', 'PROJECTS', '=SUM(1)', '他说"你好"', 'x', NULL, '{}',
                        CAST('2020-01-04 10:00:00' AS TIMESTAMP))
                """).update();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.export(OPERATOR, null, null, null, null, null, null, null, null, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"audit-logs-");
        byte[] body = response.getContentAsByteArray();
        assertThat(Arrays.copyOf(body, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).startsWith(AuditController.CSV_HEADER);
        String newest = csv.substring(AuditController.CSV_HEADER.length(), csv.indexOf("\r\n"));
        // 公式开头加单引号防注入，字段内的双引号转义成两个，空值写成空串。
        assertThat(newest).contains("\"'=SUM(1)\"", "\"他说\"\"你好\"\"\"", "\"导出管理员\"", "\"\"");
        // 表头以 \n 结尾（沿用原格式），数据行以 \r\n 结尾：8 条记录按 \r\n 切成 8 段。
        assertThat(csv.split("\r\n")).hasSize(8);
    }
}
