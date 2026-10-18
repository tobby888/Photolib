package cn.photolib.statistics.analysis;

import cn.photolib.common.error.BusinessException;
import cn.photolib.statistics.analysis.WorklogCondition.Field;
import cn.photolib.statistics.analysis.WorklogCondition.Logic;
import cn.photolib.statistics.analysis.WorklogCondition.Operator;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条件树编译器的纯单元测试：用户输入只能以绑定参数的形式出现，SQL 文本只由白名单拼成。
 * 真正跑在数据库上的效果见 {@link WorklogAnalysisServiceTests}。
 */
class WorklogConditionCompilerTests {
    private static final String INJECTION = "x' OR '1'='1'; DROP TABLE worklog; --";

    @Test
    void userInputNeverReachesTheSqlTextOnlyTheBoundParameters() {
        WorklogConditionCompiler.Compiled compiled = WorklogConditionCompiler.compile(WorklogCondition.group(Logic.OR,
                WorklogCondition.leaf(Field.MEMBER_NAME, Operator.CONTAINS, INJECTION),
                WorklogCondition.leaf(Field.MEMBER_STUDENT_ID, Operator.IN, INJECTION, "20230001"),
                WorklogCondition.leaf(Field.REMARK, Operator.EQ, INJECTION)));

        assertThat(compiled.sql()).doesNotContain("DROP").doesNotContain("'1'='1'").doesNotContain(INJECTION);
        // SQL 里只剩写死的列名、运算符和 :fN 占位符。
        assertThat(compiled.sql()).contains(":f0", ":f1", ":f2");
        assertThat(compiled.params()).containsValue(INJECTION);
        assertThat(compiled.params().get("f1")).isEqualTo(List.of(INJECTION, "20230001"));
    }

    @Test
    void likeWildcardsInTheValueAreEscapedNotInterpreted() {
        WorklogConditionCompiler.Compiled compiled = WorklogConditionCompiler.compile(
                WorklogCondition.leaf(Field.REMARK, Operator.CONTAINS, "100%_done!"));

        assertThat(compiled.params().get("f0")).isEqualTo("100!%!_done!!");
        assertThat(compiled.sql()).contains("ESCAPE '!'");
    }

    @Test
    void valuesAreParsedByFieldTypeAndRejectedWhenTheyDoNotFit() {
        WorklogConditionCompiler.Compiled compiled = WorklogConditionCompiler.compile(WorklogCondition.group(Logic.AND,
                WorklogCondition.leaf(Field.CAMPUS, Operator.IN, "3", "4", "3"),
                WorklogCondition.leaf(Field.WORK_DATE, Operator.BETWEEN, "2026-01-01", "2026-01-31"),
                WorklogCondition.leaf(Field.TOTAL_MINUTES, Operator.GE, "90")));
        assertThat(compiled.params().get("f0")).isEqualTo(List.of(3L, 4L));
        assertThat(compiled.params().get("f1")).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(compiled.params().get("f3")).isEqualTo(90);

        assertRejected(WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, "1 OR 1=1"), "编号格式不对");
        assertRejected(WorklogCondition.leaf(Field.PROJECT, Operator.EQ, "-5"), "编号格式不对");
        assertRejected(WorklogCondition.leaf(Field.STATUS, Operator.EQ, "CONFIRMED' OR 'a'='a"), "不认识的取值");
        // 草稿不进分析：它不在 STATUS 的白名单里，选不出来。
        assertRejected(WorklogCondition.leaf(Field.STATUS, Operator.EQ, "DRAFT"), "不认识的取值");
        assertRejected(WorklogCondition.leaf(Field.WORK_DATE, Operator.EQ, "2026-13-01"), "日期格式");
        assertRejected(WorklogCondition.leaf(Field.SHOOTING_MINUTES, Operator.GT, "1e9"), "整数分钟");
        assertRejected(WorklogCondition.leaf(Field.MEMBER_NAME, Operator.EQ, "长".repeat(101)), "不能超过");
    }

    @Test
    void operatorsMustSuitTheFieldType() {
        assertRejected(WorklogCondition.leaf(Field.MEMBER_NAME, Operator.GT, "张三"), "不支持这种比较方式");
        assertRejected(WorklogCondition.leaf(Field.WORK_DATE, Operator.CONTAINS, "2026"), "不支持这种比较方式");
        // 只有可空的列才有「为空」：成员姓名总是有值的。
        assertRejected(new WorklogCondition(null, null, Field.MEMBER_NAME, Operator.IS_EMPTY, List.of(), false),
                "不支持这种比较方式");
        assertRejected(WorklogCondition.leaf(Field.CAMPUS, Operator.IS_EMPTY, "1"), "不需要填值");
        assertRejected(WorklogCondition.leaf(Field.TOTAL_MINUTES, Operator.BETWEEN, "120", "60"), "起点不能大于终点");
        assertRejected(WorklogCondition.leaf(Field.TOTAL_MINUTES, Operator.EQ, "1", "2"), "正好一个值");
        assertRejected(WorklogCondition.leaf(Field.MEMBER_NAME, Operator.CONTAINS, "  "), "不能为空");
        assertRejected(new WorklogCondition(null, null, null, Operator.EQ, List.of("1"), false), "选好字段");
    }

    @Test
    void negativeComparisonsOnNullableColumnsCountMissingValuesAsMatching() {
        // 「校区不是 3」要包含没有校区的链接申报工时；「校区是 3」则必须先要求校区非空。
        assertThat(WorklogConditionCompiler.compile(WorklogCondition.leaf(Field.CAMPUS, Operator.NE, "3")).sql())
                .isEqualTo("(r.campus_id IS NULL OR r.campus_id <> :f0)");
        assertThat(WorklogConditionCompiler.compile(WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, "3")).sql())
                .isEqualTo("(r.campus_id IS NOT NULL AND r.campus_id = :f0)");
        assertThat(WorklogConditionCompiler.compile(
                WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, "3").negated()).sql())
                .startsWith("NOT (");
    }

    @Test
    void theTreeIsBoundedInDepthNodeCountAndValueCount() {
        WorklogCondition deep = WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, "1");
        for (int i = 0; i < WorklogConditionCompiler.MAX_DEPTH + 1; i++) deep = WorklogCondition.group(Logic.AND, deep);
        assertRejected(deep, "最多嵌套");

        List<WorklogCondition> many = new ArrayList<>();
        for (int i = 0; i < WorklogConditionCompiler.MAX_NODES; i++) {
            many.add(WorklogCondition.leaf(Field.CAMPUS, Operator.EQ, "1"));
        }
        assertRejected(new WorklogCondition(Logic.OR, many, null, null, null, false), "筛选条件太多");

        List<String> ids = Collections.nCopies(WorklogConditionCompiler.MAX_VALUES + 1, "7");
        assertRejected(new WorklogCondition(null, null, Field.PROJECT, Operator.IN, ids, false), "最多");
    }

    @Test
    void emptyGroupsAndAMissingFilterDoNotNarrowAnything() {
        assertThat(WorklogConditionCompiler.compile(null).sql()).isEqualTo("1=1");
        assertThat(WorklogConditionCompiler.compile(WorklogCondition.group(Logic.OR)).sql()).isEqualTo("1=1");
    }

    @Test
    void anUnknownFieldOrOperatorInTheJsonIsRejectedBeforeAnySqlIsBuilt() {
        // 与 Spring MVC 同一套 Jackson 3。
        JsonMapper json = JsonMapper.builder().build();
        // 不写 not 就是不取反：Jackson 3 默认拒绝把缺省值读进基本类型，这里钉住它能读。
        WorklogCondition parsed = json.readValue("""
                {"logic":"OR","children":[{"field":"STATUS","operator":"EQ","values":["CONFIRMED"]}]}
                """, WorklogCondition.class);
        assertThat(WorklogConditionCompiler.compile(parsed).sql()).isEqualTo("((w.status = :f0))");
        assertThatThrownBy(() -> json.readValue("""
                {"field":"w.status; DROP TABLE worklog","operator":"EQ","values":["1"]}
                """, WorklogCondition.class)).hasMessageContaining("not one of the values accepted");
        assertThatThrownBy(() -> json.readValue("""
                {"field":"STATUS","operator":"= 1 OR 1","values":["CONFIRMED"]}
                """, WorklogCondition.class)).hasMessageContaining("not one of the values accepted");
    }

    private static void assertRejected(WorklogCondition condition, String message) {
        assertThatThrownBy(() -> WorklogConditionCompiler.compile(condition))
                .isInstanceOf(BusinessException.class).hasMessageContaining(message);
    }
}
