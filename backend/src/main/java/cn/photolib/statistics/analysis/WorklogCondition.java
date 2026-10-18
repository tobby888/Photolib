package cn.photolib.statistics.analysis;

import java.util.List;

/**
 * 工时分析的一个筛选条件节点：要么是一组条件（{@code logic} + {@code children}），
 * 要么是一条比较（{@code field} + {@code operator} + {@code values}）。两种形状共用一个类型，
 * 是为了让前端把条件树原样序列化成 JSON 发过来。
 *
 * <p><b>这里的任何字符串都不会进 SQL 文本。</b>字段和运算符是枚举（JSON 里写错一个字母，
 * 反序列化就直接 400），每个枚举值对应一段写死的 SQL；{@code values} 只会作为绑定参数出现。
 * 编译见 {@link WorklogConditionCompiler}。</p>
 *
 * @param logic    组节点：子条件之间是「同时满足」还是「满足其一」
 * @param children 组节点的子条件
 * @param field    比较节点：比哪一列
 * @param operator 比较节点：怎么比
 * @param values   比较节点的取值，一律按字符串传，由字段类型决定怎么解析
 * @param not      对整个节点取反（「不满足以下条件」）。用包装类型：JSON 里不写就是不取反，而 Jackson 3
 *                 默认拒绝把缺省值读进基本类型
 */
public record WorklogCondition(Logic logic, List<WorklogCondition> children,
                               Field field, Operator operator, List<String> values, Boolean not) {

    public static WorklogCondition group(Logic logic, WorklogCondition... children) {
        return new WorklogCondition(logic, List.of(children), null, null, null, false);
    }

    public static WorklogCondition leaf(Field field, Operator operator, String... values) {
        return new WorklogCondition(null, null, field, operator, List.of(values), false);
    }

    public WorklogCondition negated() {
        return new WorklogCondition(logic, children, field, operator, values, !negates());
    }

    boolean negates() {
        return Boolean.TRUE.equals(not);
    }

    boolean isGroup() {
        return logic != null || children != null;
    }

    public enum Logic { AND, OR }

    public enum ValueType { ENUM, ID, TEXT, DATE, NUMBER }

    /**
     * 可以筛的列。{@code sql} 是写死的列表达式，{@code nullable} 决定否定类比较怎么对待空值：
     * 「校区不是北校区」应当把没有校区的链接申报工时也算进来，否则取反之后它们两边都不在。
     */
    public enum Field {
        STATUS("w.status", ValueType.ENUM, false, List.of("SUBMITTED", "CONFIRMED", "REJECTED")),
        /** 来源：站内填报（挂在需求下）或通过上传链接申报。 */
        SOURCE("CASE WHEN w.request_id IS NULL THEN 'SHARE_LINK' ELSE 'IN_APP' END",
                ValueType.ENUM, false, List.of("IN_APP", "SHARE_LINK")),
        CAMPUS("r.campus_id", ValueType.ID, true, List.of()),
        PROJECT("p.id", ValueType.ID, false, List.of()),
        PROJECT_STATUS("p.status", ValueType.ENUM, false, List.of("DRAFT", "ACTIVE", "COMPLETED", "CANCELLED")),
        REQUEST("w.request_id", ValueType.ID, true, List.of()),
        /** 填报账号；链接申报的工时记的是开链接的成员。 */
        SUBMITTER("w.user_id", ValueType.ID, false, List.of()),
        MEMBER_STUDENT_ID("w.member_student_id", ValueType.TEXT, false, List.of()),
        MEMBER_NAME("w.member_name", ValueType.TEXT, false, List.of()),
        REMARK("w.remark", ValueType.TEXT, true, List.of()),
        WORK_DATE("w.work_date", ValueType.DATE, false, List.of()),
        SHOOTING_MINUTES("w.shooting_minutes", ValueType.NUMBER, false, List.of()),
        RETOUCHING_MINUTES("w.retouching_minutes", ValueType.NUMBER, false, List.of()),
        TOTAL_MINUTES("(w.shooting_minutes + w.retouching_minutes)", ValueType.NUMBER, false, List.of());

        final String sql;
        final ValueType type;
        final boolean nullable;
        final List<String> allowedValues;

        Field(String sql, ValueType type, boolean nullable, List<String> allowedValues) {
            this.sql = sql;
            this.type = type;
            this.nullable = nullable;
            this.allowedValues = allowedValues;
        }
    }

    public enum Operator {
        EQ, NE, IN, NOT_IN, GT, GE, LT, LE, BETWEEN,
        CONTAINS, NOT_CONTAINS, STARTS_WITH, IS_EMPTY, IS_NOT_EMPTY
    }
}
