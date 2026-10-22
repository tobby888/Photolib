package cn.photolib.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 操作名目录：覆盖面、反推老记录、对象名查询，都要对着真实的映射表和真实的表结构验。 */
@SpringBootTest
@Transactional
class AuditCatalogTests {
    private static final Set<RequestMethod> WRITES =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired private AuditCatalog catalog;
    @Autowired private AuditTargets targets;
    @Autowired private JdbcClient jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mapping;

    @Test
    void everyWriteEndpointIsNamedOrDeliberatelySkipped() {
        // 新接口忘了标 @AuditAction，日志里就只剩一个 POST 和一串路径——又回到"看不懂"。
        List<String> missing = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            if (methods.stream().noneMatch(WRITES::contains)) continue;
            HandlerMethod handler = entry.getValue();
            if (handler.hasMethodAnnotation(AuditAction.class) || handler.hasMethodAnnotation(AuditSkip.class)) continue;
            missing.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName() + " " + entry.getKey());
        }
        assertThat(missing).as("这些写接口既没有 @AuditAction 也没有 @AuditSkip").isEmpty();
    }

    @Test
    void everyModuleWithNamedOperationsHasAChineseLabel() {
        assertThat(catalog.resourceTypes())
                .allSatisfy(option -> assertThat(option.label()).as(option.value()).isNotEqualTo(option.value()));
    }

    @Test
    void oldRecordsGetTheirOperationNameFromThePath() {
        assertThat(catalog.describe("DELETE", "/api/v1/photos/12")).isEqualTo("删除图片");
        // 字面路径不能被 /photos/{id} 这类带变量的模式抢走。
        assertThat(catalog.describe("POST", "/api/v1/photos/batch-delete")).isEqualTo("批量删除图片");
        assertThat(catalog.describe("POST", "/api/v1/public/shares/01ARZ3NDEKTSV4RRFFQ69G5FAV/worklogs"))
                .isEqualTo("通过上传链接申报工时");
        assertThat(catalog.describe("POST", "/api/v1/no-such-thing")).isNull();
        assertThat(catalog.actions(List.of("老版本的操作")))
                .extracting(AuditCatalog.ActionOption::value).contains("删除图片", "认领需求", "老版本的操作");
    }

    @Test
    void everyTargetLookupRunsAgainstTheRealSchema() {
        // nameOf 吞掉所有异常（日志不能拖垮请求），所以列名写错只会让名字悄悄变成空——这里直接跑一遍 SQL。
        AuditTargets.LOOKUPS.forEach((segment, lookup) -> {
            if (lookup.byId() != null) {
                assertThat(jdbc.sql(lookup.byId()).param(0L).query(String.class).optional()).as(segment).isEmpty();
            }
            if (lookup.byPublicId() != null) {
                assertThat(jdbc.sql(lookup.byPublicId()).param("01ARZ3NDEKTSV4RRFFQ69G5FAV")
                        .query(String.class).optional()).as(segment).isEmpty();
            }
        });
    }

    @Test
    void namesAUserByDisplayNameAndAccount() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (91011, 'target-user', 'x', '被操作的人', 'MINISTER', TRUE, FALSE)
                """).update();

        assertThat(targets.nameOf("users", "91011")).isEqualTo("被操作的人 @target-user");
        assertThat(targets.nameOf("users", "99999999")).isNull();
        assertThat(targets.nameOf("no-such-segment", "1")).isNull();
    }
}
