package cn.photolib.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AuditLogMapperTests {
    @Autowired
    private AuditLogMapper mapper;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void findsAndCountsLogsWithOperatorAndFilters() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (91001, 'audit-admin', 'not-used', '审计管理员', 'ADMIN', true, false)
                """).update();
        jdbc.sql("""
                INSERT INTO audit_log
                    (operator_id, action, resource_type, resource_id, request_id, detail_json, ip_address, created_at)
                VALUES
                    (91001, 'POST', 'PROJECTS', 'project-1', 'request-1',
                     '{"status":200}', '127.0.0.1', CURRENT_TIMESTAMP)
                """).update();

        var from = LocalDateTime.now().minusMinutes(1);
        var to = LocalDateTime.now().plusMinutes(1);
        var logs = mapper.findLogs(91001L, "POST", "PROJECTS", from, to, "audit-admin", 20, 0);

        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getOperatorDisplayName()).isEqualTo("审计管理员");
        assertThat(logs.getFirst().getResourceId()).isEqualTo("project-1");
        assertThat(mapper.countLogs(91001L, "POST", "PROJECTS", from, to, "127.0.0.1")).isEqualTo(1);
        assertThat(mapper.countLogs(null, "DELETE", null, null, null, null)).isZero();
    }
}
