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

    private void insertAdmin() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (91001, 'audit-admin', 'not-used', '审计管理员', 'ADMIN', true, false)
                """).update();
    }

    @Test
    void findsAndCountsLogsWithOperatorAndFilters() {
        insertAdmin();
        jdbc.sql("""
                INSERT INTO audit_log
                    (operator_id, action, resource_type, resource_id, request_id, detail_json, ip_address, created_at)
                VALUES
                    (91001, 'POST', 'PROJECTS', 'project-1', 'request-1',
                     '{"status":200}', '127.0.0.1', CURRENT_TIMESTAMP)
                """).update();

        var from = LocalDateTime.now().minusMinutes(1);
        var to = LocalDateTime.now().plusMinutes(1);
        AuditLogFilter filter = AuditLogFilter.builder().operatorId(91001L).action("POST").resourceType("PROJECTS")
                .from(from).to(to).build();
        var logs = mapper.findLogs(filter.withKeyword("audit-admin"), 20, 0);

        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getOperatorDisplayName()).isEqualTo("审计管理员");
        assertThat(logs.getFirst().getOperatorRole()).isEqualTo("ADMIN");
        assertThat(logs.getFirst().getResourceId()).isEqualTo("project-1");
        assertThat(mapper.countLogs(filter.withKeyword("127.0.0.1"))).isEqualTo(1);
        assertThat(mapper.countLogs(AuditLogFilter.builder().action("DELETE").build())).isZero();
    }

    @Test
    void keywordTreatsLikeWildcardsAsLiteralText() {
        jdbc.sql("""
                INSERT INTO audit_log
                    (action, resource_type, resource_id, request_id, detail_json, ip_address, created_at)
                VALUES
                    ('POST', 'PHOTOS', 'literal%value', 'one', '{}', '127.0.0.1', CURRENT_TIMESTAMP),
                    ('POST', 'PHOTOS', 'ordinary-value', 'two', '{}', '127.0.0.2', CURRENT_TIMESTAMP)
                """).update();

        assertThat(mapper.countLogs(AuditLogFilter.none().withKeyword("%"))).isEqualTo(1);
        assertThat(mapper.findLogs(AuditLogFilter.none().withKeyword("%"), 20, 0))
                .extracting(AuditLogView::getResourceId).containsExactly("literal%value");
    }

    @Test
    void filtersByOperationOutcomeAndSearchesNamesAndReasons() {
        insertAdmin();
        jdbc.sql("""
                INSERT INTO audit_log
                    (operator_id, action, action_name, resource_type, resource_id, resource_name, request_id,
                     http_status, success, error_code, error_message, detail_json, ip_address, user_agent,
                     duration_ms, created_at)
                VALUES
                    (91001, 'DELETE', '删除选题', 'PROJECTS', '12', '春季运动会', 'r1',
                     200, TRUE, NULL, NULL, '{}', '203.0.113.7', 'Chrome', 35, CURRENT_TIMESTAMP),
                    (91001, 'POST', '认领需求', 'REQUESTS', '3', '校庆合影', 'r2',
                     409, FALSE, 'CONFLICT', '需求已被认领满', '{}', '203.0.113.8', 'Safari', 12, CURRENT_TIMESTAMP)
                """).update();

        assertThat(mapper.findLogs(AuditLogFilter.builder().success(false).build(), 20, 0))
                .extracting(AuditLogView::getActionName).containsExactly("认领需求");
        assertThat(mapper.findLogs(AuditLogFilter.builder().actionName("删除选题").build(), 20, 0))
                .extracting(AuditLogView::getResourceName).containsExactly("春季运动会");
        assertThat(mapper.countLogs(AuditLogFilter.builder().ipAddress("203.0.113.8").build())).isEqualTo(1);
        assertThat(mapper.countLogs(AuditLogFilter.none().withKeyword("运动会"))).isEqualTo(1);
        assertThat(mapper.countLogs(AuditLogFilter.none().withKeyword("认领满"))).isEqualTo(1);
        AuditLogView failed = mapper.findLogs(AuditLogFilter.none().withKeyword("认领"), 20, 0).getFirst();
        assertThat(failed.getErrorCode()).isEqualTo("CONFLICT");
        assertThat(failed.getHttpStatus()).isEqualTo(409);
        assertThat(failed.getDurationMs()).isEqualTo(12);
        assertThat(failed.getUserAgent()).isEqualTo("Safari");
        assertThat(mapper.distinctActionNames()).contains("删除选题", "认领需求");
    }
}
