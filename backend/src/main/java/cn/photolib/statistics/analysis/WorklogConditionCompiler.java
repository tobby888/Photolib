package cn.photolib.statistics.analysis;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.LikeFilter;
import cn.photolib.statistics.analysis.WorklogCondition.Field;
import cn.photolib.statistics.analysis.WorklogCondition.Operator;
import cn.photolib.statistics.analysis.WorklogCondition.ValueType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把用户拼出来的条件树编译成一段 WHERE 子句和它的绑定参数。
 *
 * <p><b>防注入的做法是让用户输入根本没有机会成为 SQL 文本：</b></p>
 * <ul>
 *   <li>列：只能是 {@link Field} 的枚举值，每个值对应一段写死的列表达式；</li>
 *   <li>比较方式：只能是 {@link Operator} 的枚举值，每个值对应一段写死的模板，且按字段类型
 *       收窄（文本不能比大小，日期不能做包含匹配）；</li>
 *   <li>取值：先按字段类型解析成 {@code Long} / {@code Integer} / {@code LocalDate} /
 *       白名单里的枚举字符串，解析失败就拒绝，然后一律作为命名参数绑定。参数名由这里按序号生成
 *       （{@code f0}、{@code f1}……），不取自请求；</li>
 *   <li>模糊匹配的取值还要过 {@link LikeFilter#escape}：绑定参数挡得住 SQL 注入，挡不住
 *       {@code %} / {@code _} 在 LIKE 里的通配含义。</li>
 * </ul>
 *
 * <p>另外限制树的深度、节点数和每条比较的取值个数，免得一个请求拼出几万个 OR 拖垮数据库。</p>
 *
 * <p>编译结果里的每一条比较都是二值的（不会算出 NULL）：可空列上的肯定比较先要求非空，否定比较
 * 把空值算作满足。否则「不满足以下条件」对 NULL 行取反之后仍是 NULL，那些行两边都不在。</p>
 */
public final class WorklogConditionCompiler {
    public static final int MAX_DEPTH = 5;
    public static final int MAX_NODES = 50;
    public static final int MAX_VALUES = 200;
    public static final int MAX_TEXT_LENGTH = 100;
    public static final int MAX_MINUTES = 100_000;

    private static final Set<Operator> EQUALITY = EnumSet.of(Operator.EQ, Operator.NE, Operator.IN, Operator.NOT_IN);
    private static final Set<Operator> ORDERED = EnumSet.of(Operator.EQ, Operator.NE,
            Operator.GT, Operator.GE, Operator.LT, Operator.LE, Operator.BETWEEN);
    private static final Set<Operator> TEXTUAL = EnumSet.of(Operator.EQ, Operator.NE, Operator.IN, Operator.NOT_IN,
            Operator.CONTAINS, Operator.NOT_CONTAINS, Operator.STARTS_WITH);
    private static final Set<Operator> EMPTINESS = EnumSet.of(Operator.IS_EMPTY, Operator.IS_NOT_EMPTY);
    /** 否定类比较：可空列上把空值算作满足。 */
    private static final Set<Operator> NEGATIVE = EnumSet.of(Operator.NE, Operator.NOT_IN, Operator.NOT_CONTAINS);

    private final Map<String, Object> params = new LinkedHashMap<>();
    private int nodes;

    private WorklogConditionCompiler() {
    }

    /** {@code condition} 为空时返回恒真条件。 */
    public static Compiled compile(WorklogCondition condition) {
        WorklogConditionCompiler compiler = new WorklogConditionCompiler();
        String sql = condition == null ? "1=1" : compiler.node(condition, 1);
        return new Compiled(sql, Map.copyOf(compiler.params));
    }

    public static Set<Operator> operatorsFor(Field field) {
        Set<Operator> allowed = EnumSet.noneOf(Operator.class);
        allowed.addAll(switch (field.type) {
            case ENUM, ID -> EQUALITY;
            case TEXT -> TEXTUAL;
            case DATE, NUMBER -> ORDERED;
        });
        if (field.nullable) allowed.addAll(EMPTINESS);
        return allowed;
    }

    private String node(WorklogCondition condition, int depth) {
        if (++nodes > MAX_NODES) {
            throw invalid("筛选条件太多了，最多 " + MAX_NODES + " 条");
        }
        String sql = condition.isGroup() ? group(condition, depth) : leaf(condition);
        return condition.negates() ? "NOT (" + sql + ")" : sql;
    }

    private String group(WorklogCondition condition, int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("条件分组最多嵌套 " + MAX_DEPTH + " 层");
        }
        List<WorklogCondition> children = condition.children() == null ? List.of() : condition.children();
        // 空的分组不起筛选作用：前端新建一个分组还没往里加条件时就是这样。
        if (children.isEmpty()) return "1=1";
        String joiner = condition.logic() == WorklogCondition.Logic.OR ? " OR " : " AND ";
        List<String> parts = new ArrayList<>(children.size());
        for (WorklogCondition child : children) {
            if (child == null) throw invalid("筛选条件不完整");
            parts.add(node(child, depth + 1));
        }
        return "(" + String.join(joiner, parts) + ")";
    }

    private String leaf(WorklogCondition condition) {
        Field field = condition.field();
        Operator operator = condition.operator();
        if (field == null || operator == null) {
            throw invalid("每条筛选条件都要选好字段和比较方式");
        }
        if (!operatorsFor(field).contains(operator)) {
            throw invalid("这个字段不支持这种比较方式");
        }
        List<String> values = condition.values() == null ? List.of() : condition.values();
        String expr = field.sql;
        String predicate = switch (operator) {
            case EQ -> expr + " = " + bind(single(field, values));
            case NE -> expr + " <> " + bind(single(field, values));
            case GT -> expr + " > " + bind(single(field, values));
            case GE -> expr + " >= " + bind(single(field, values));
            case LT -> expr + " < " + bind(single(field, values));
            case LE -> expr + " <= " + bind(single(field, values));
            case IN -> expr + " IN (" + bind(many(field, values)) + ")";
            case NOT_IN -> expr + " NOT IN (" + bind(many(field, values)) + ")";
            case BETWEEN -> between(field, expr, values);
            case CONTAINS -> "LOWER(" + expr + ") LIKE CONCAT('%', LOWER(" + bind(likeValue(field, values))
                    + "), '%') ESCAPE '!'";
            case NOT_CONTAINS -> "LOWER(" + expr + ") NOT LIKE CONCAT('%', LOWER(" + bind(likeValue(field, values))
                    + "), '%') ESCAPE '!'";
            case STARTS_WITH -> "LOWER(" + expr + ") LIKE CONCAT(LOWER(" + bind(likeValue(field, values))
                    + "), '%') ESCAPE '!'";
            case IS_EMPTY -> emptiness(field, expr, values);
            case IS_NOT_EMPTY -> "NOT " + emptiness(field, expr, values);
        };
        if (!field.nullable || EMPTINESS.contains(operator)) return "(" + predicate + ")";
        return NEGATIVE.contains(operator)
                ? "(" + expr + " IS NULL OR " + predicate + ")"
                : "(" + expr + " IS NOT NULL AND " + predicate + ")";
    }

    private String between(Field field, String expr, List<String> values) {
        if (values.size() != 2) throw invalid("「介于」需要填两个值");
        Object low = parse(field, values.get(0));
        Object high = parse(field, values.get(1));
        @SuppressWarnings({"unchecked", "rawtypes"})
        int order = ((Comparable) low).compareTo(high);
        if (order > 0) throw invalid("「介于」的起点不能大于终点");
        return expr + " BETWEEN " + bind(low) + " AND " + bind(high);
    }

    private String emptiness(Field field, String expr, List<String> values) {
        if (!values.isEmpty()) throw invalid("「为空 / 不为空」不需要填值");
        return field.type == ValueType.TEXT
                ? "(" + expr + " IS NULL OR " + expr + " = '')"
                : "(" + expr + " IS NULL)";
    }

    private Object single(Field field, List<String> values) {
        if (values.size() != 1) throw invalid("这条筛选条件需要正好一个值");
        return parse(field, values.get(0));
    }

    private List<Object> many(Field field, List<String> values) {
        if (values.isEmpty()) throw invalid("「属于 / 不属于」至少要选一个值");
        if (values.size() > MAX_VALUES) throw invalid("一条筛选条件最多 " + MAX_VALUES + " 个值");
        // 去重，顺序无所谓：IN 列表里的重复值不改变结果，只会让 SQL 变长。
        return values.stream().map(value -> parse(field, value)).distinct().toList();
    }

    private String likeValue(Field field, List<String> values) {
        String value = (String) single(field, values);
        if (value.isBlank()) throw invalid("包含匹配的关键词不能为空");
        return LikeFilter.escape(value);
    }

    private Object parse(Field field, String raw) {
        if (raw == null) throw invalid("筛选条件的值不能为空");
        return switch (field.type) {
            case ENUM -> {
                if (!field.allowedValues.contains(raw)) throw invalid("不认识的取值：" + abbreviate(raw));
                yield raw;
            }
            case ID -> {
                try {
                    long id = Long.parseLong(raw.trim());
                    if (id <= 0) throw new NumberFormatException();
                    yield id;
                } catch (NumberFormatException e) {
                    throw invalid("编号格式不对：" + abbreviate(raw));
                }
            }
            case TEXT -> {
                if (raw.codePointCount(0, raw.length()) > MAX_TEXT_LENGTH) {
                    throw invalid("文本条件不能超过 " + MAX_TEXT_LENGTH + " 个字");
                }
                yield raw;
            }
            case DATE -> {
                try {
                    yield LocalDate.parse(raw.trim());
                } catch (DateTimeParseException e) {
                    throw invalid("日期格式应为 YYYY-MM-DD：" + abbreviate(raw));
                }
            }
            case NUMBER -> {
                try {
                    int minutes = Integer.parseInt(raw.trim());
                    if (minutes < 0 || minutes > MAX_MINUTES) throw new NumberFormatException();
                    yield minutes;
                } catch (NumberFormatException e) {
                    throw invalid("时长应为 0 ~ " + MAX_MINUTES + " 之间的整数分钟：" + abbreviate(raw));
                }
            }
        };
    }

    private String bind(Object value) {
        String name = "f" + params.size();
        params.put(name, value);
        return ":" + name;
    }

    private static String abbreviate(String raw) {
        return raw.length() > 20 ? raw.substring(0, 20) + "…" : raw;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    /** 编译好的 WHERE 片段：{@code sql} 里只有写死的文本和 {@code :fN} 占位符。 */
    public record Compiled(String sql, Map<String, Object> params) {
    }
}
