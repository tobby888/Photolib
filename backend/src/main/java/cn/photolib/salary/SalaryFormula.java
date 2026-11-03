package cn.photolib.salary;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自定义工资公式：一个只认四则运算、比较和白名单函数的小表达式语言，不经过任何脚本引擎。
 *
 * <pre>
 * 表达式   := 比较
 * 比较     := 加减 ( ("&gt;" | "&lt;" | "&gt;=" | "&lt;=" | "==" | "!=") 加减 )?
 * 加减     := 乘除 ( ("+" | "-") 乘除 )*
 * 乘除     := 一元 ( ("*" | "/") 一元 )*
 * 一元     := ("-" | "+") 一元 | 基本
 * 基本     := 数字 | 字段 | 函数 "(" 参数 ("," 参数)* ")" | "(" 表达式 ")"
 * </pre>
 *
 * <ul>
 *   <li>字段见 {@link Variable}，中文名和英文别名都认（英文不分大小写）。</li>
 *   <li>函数见 {@link Function}：max、min（任意个参数）、round、floor、ceil、abs、if（只算选中的那一支，
 *       所以 {@code if(被引张数 > 0, 100 / 被引张数, 0)} 不会除以 0）。</li>
 *   <li>比较的结果是 1（成立）或 0（不成立），可以直接拿来乘。</li>
 *   <li>全角的括号、逗号和 ×、÷ 按半角处理——中文输入法下顺手就打出来了。</li>
 * </ul>
 *
 * <p>用 {@link MathContext#DECIMAL128} 计算；整条公式的结果小于 0 按 0 计，再四舍五入到分（{@link #salary}）。
 * 公式长度、嵌套深度有上限，防止一条恶意公式把栈打爆。</p>
 */
public final class SalaryFormula {
    public static final int MAX_LENGTH = 500;
    private static final int MAX_DEPTH = 40;
    /** 第一版的固定规则：已确认工时每小时 20 元、被引每张 10 元，两者取高。 */
    public static final String DEFAULT = "max(工时 * 20, 被引张数 * 10)";
    private static final MathContext MATH = MathContext.DECIMAL128;

    /** 公式里能用的字段。顺序就是界面上「插入字段」按钮的顺序。 */
    public enum Variable {
        TOTAL_HOURS("工时", "totalHours", "已确认工时（拍摄 + 修图），单位小时", "4.5"),
        SHOOTING_HOURS("拍摄时长", "shootingHours", "已确认工时里的拍摄时长，单位小时", "3"),
        RETOUCHING_HOURS("修图时长", "retouchingHours", "已确认工时里的修图时长，单位小时", "1.5"),
        ADOPTED_PHOTOS("被引张数", "adoptedPhotos", "这个选题里被引用的、他拍的图片张数", "5"),
        UPLOADED_PHOTOS("上传张数", "uploadedPhotos", "进入这个选题的、他拍的图片张数（上传或添加进来的）", "20"),
        WORKLOG_COUNT("工时条数", "worklogCount", "已确认的工时记录条数", "2"),
        PENDING_HOURS("待审核时长", "pendingHours", "还在待审核的工时，单位小时（默认不计入）", "1"),
        PENDING_COUNT("待审核条数", "pendingCount", "还在待审核的工时条数", "1");

        private final String label;
        private final String alias;
        private final String description;
        private final BigDecimal sample;

        Variable(String label, String alias, String description, String sample) {
            this.label = label;
            this.alias = alias;
            this.description = description;
            this.sample = new BigDecimal(sample);
        }

        public String label() {
            return label;
        }

        public String alias() {
            return alias;
        }

        public String description() {
            return description;
        }

        /** 「试算」用的示例值。 */
        public BigDecimal sample() {
            return sample;
        }
    }

    /** 能用的函数。中文名也认。 */
    public enum Function {
        MAX("max", "最大", 1, Integer.MAX_VALUE, "max(a, b, …)", "取最大的一个"),
        MIN("min", "最小", 1, Integer.MAX_VALUE, "min(a, b, …)", "取最小的一个"),
        ROUND("round", "四舍五入", 1, 2, "round(x, 位数)", "四舍五入，位数省略时取整，最多 6 位"),
        FLOOR("floor", "向下取整", 1, 1, "floor(x)", "向下取整"),
        CEIL("ceil", "向上取整", 1, 1, "ceil(x)", "向上取整"),
        ABS("abs", "绝对值", 1, 1, "abs(x)", "绝对值"),
        IF("if", "如果", 3, 3, "if(条件, 成立时, 不成立时)", "条件成立（不为 0）取第二个，否则取第三个；只算选中的那一个");

        private final String functionName;
        private final String chineseName;
        private final int minArgs;
        private final int maxArgs;
        private final String signature;
        private final String description;

        Function(String name, String chineseName, int minArgs, int maxArgs, String signature, String description) {
            this.functionName = name;
            this.chineseName = chineseName;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.signature = signature;
            this.description = description;
        }

        public String functionName() {
            return functionName;
        }

        public String chineseName() {
            return chineseName;
        }

        public String signature() {
            return signature;
        }

        public String description() {
            return description;
        }
    }

    private static final Map<String, Variable> VARIABLES = new LinkedHashMap<>();
    private static final Map<String, Function> FUNCTIONS = new LinkedHashMap<>();

    static {
        for (Variable variable : Variable.values()) {
            VARIABLES.put(variable.label, variable);
            VARIABLES.put(variable.alias.toLowerCase(Locale.ROOT), variable);
        }
        for (Function function : Function.values()) {
            FUNCTIONS.put(function.functionName, function);
            FUNCTIONS.put(function.chineseName, function);
        }
    }

    private final String source;
    private final Node root;
    private final Set<Variable> variables;

    private SalaryFormula(String source, Node root, Set<Variable> variables) {
        this.source = source;
        this.root = root;
        this.variables = variables;
    }

    /** 解析公式；空白时用 {@link #DEFAULT}。写错时抛 {@link FormulaException}，带出错的位置。 */
    public static SalaryFormula compile(String text) {
        // 按原文解析（空白本来就跳过），出错位置和编辑框里的字符一一对应；存下来的是去掉首尾空白的。
        String raw = text == null || text.isBlank() ? DEFAULT : text;
        String source = raw.strip();
        if (source.length() > MAX_LENGTH) throw new FormulaException("公式最长 " + MAX_LENGTH + " 个字符", MAX_LENGTH);
        Parser parser = new Parser(tokenize(raw));
        Node root = parser.expression(0);
        if (!parser.atEnd()) {
            Token token = parser.peek();
            throw new FormulaException("这里多了「" + token.text + "」", token.position);
        }
        Set<Variable> used = EnumSet.noneOf(Variable.class);
        root.collect(used);
        return new SalaryFormula(source, root, Collections.unmodifiableSet(used));
    }

    public String source() {
        return source;
    }

    /** 公式里用到的字段。 */
    public Set<Variable> variables() {
        return variables;
    }

    /** 原始结果，不截负数、不取整。没给的字段按 0 算。 */
    public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
        return root.evaluate(values);
    }

    /** 工资：结果小于 0 按 0 计，四舍五入到分。 */
    public BigDecimal salary(Map<Variable, BigDecimal> values) {
        return evaluate(values).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 「计算过程」：把字段换成这一行的数值，例如 {@code max(4.5 × 20, 5 × 10) = 90.00}。
     * 字段值最多显示两位小数（工时可能是 1.1666… 小时），计算用的仍是精确值。
     */
    public String explain(Map<Variable, BigDecimal> values) {
        BigDecimal raw = evaluate(values);
        String result = raw.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return root.render(values) + " = " + result + (raw.signum() < 0 ? "（小于 0 按 0 计）" : "");
    }

    /** 每个字段都用示例值，给编辑器「试算」用。 */
    public static Map<Variable, BigDecimal> sampleValues() {
        Map<Variable, BigDecimal> values = new EnumMap<>(Variable.class);
        for (Variable variable : Variable.values()) values.put(variable, variable.sample());
        return values;
    }

    static String plain(BigDecimal value) {
        BigDecimal scaled = value.setScale(Math.min(Math.max(value.scale(), 0), 2), RoundingMode.HALF_UP);
        return scaled.stripTrailingZeros().toPlainString();
    }

    // ---- 词法 ----

    private enum Kind { NUMBER, NAME, OPERATOR, LEFT, RIGHT, COMMA }

    private record Token(Kind kind, String text, int position) {}

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = normalize(source.charAt(i));
            if (Character.isWhitespace(c) || c == '　') {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                boolean dot = false;
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || (source.charAt(i) == '.' && !dot))) {
                    if (source.charAt(i) == '.') dot = true;
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, source.substring(start, i), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
                tokens.add(new Token(Kind.NAME, source.substring(start, i), start));
            } else if (c == '(') {
                tokens.add(new Token(Kind.LEFT, "(", i++));
            } else if (c == ')') {
                tokens.add(new Token(Kind.RIGHT, ")", i++));
            } else if (c == ',') {
                tokens.add(new Token(Kind.COMMA, ",", i++));
            } else if ("+-*/".indexOf(c) >= 0) {
                tokens.add(new Token(Kind.OPERATOR, String.valueOf(c), i++));
            } else if ("<>=!".indexOf(c) >= 0) {
                boolean twoChar = i + 1 < source.length() && source.charAt(i + 1) == '=';
                String op = twoChar ? c + "=" : String.valueOf(c);
                if ("=".equals(op) || "!".equals(op)) {
                    throw new FormulaException("「" + op + "」不是运算符，相等写 ==，不等写 !=", i);
                }
                tokens.add(new Token(Kind.OPERATOR, op, i));
                i += op.length();
            } else {
                throw new FormulaException("不认识的字符「" + source.charAt(i) + "」", i);
            }
        }
        return tokens;
    }

    /** 全角括号、逗号和 ×、÷ 按半角处理。 */
    private static char normalize(char c) {
        return switch (c) {
            case '（' -> '(';
            case '）' -> ')';
            case '，' -> ',';
            case '×' -> '*';
            case '÷' -> '/';
            default -> c;
        };
    }

    // ---- 语法 ----

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return index >= tokens.size();
        }

        Token peek() {
            return tokens.get(index);
        }

        private int endPosition() {
            return tokens.isEmpty() ? 0 : tokens.get(tokens.size() - 1).position + tokens.get(tokens.size() - 1).text.length();
        }

        private boolean nextIs(Kind kind, String... texts) {
            if (atEnd() || peek().kind != kind) return false;
            if (texts.length == 0) return true;
            for (String text : texts) if (text.equals(peek().text)) return true;
            return false;
        }

        private void depth(int depth) {
            if (depth > MAX_DEPTH) {
                throw new FormulaException("公式嵌套太深", atEnd() ? endPosition() : peek().position);
            }
        }

        Node expression(int depth) {
            depth(depth);
            Node left = additive(depth);
            if (nextIs(Kind.OPERATOR, ">", "<", ">=", "<=", "==", "!=")) {
                Token op = tokens.get(index++);
                Node right = additive(depth);
                if (nextIs(Kind.OPERATOR, ">", "<", ">=", "<=", "==", "!=")) {
                    throw new FormulaException("比较不能连着写，用 if 分开", peek().position);
                }
                return new Binary(op.text, left, right);
            }
            return left;
        }

        private Node additive(int depth) {
            Node left = term(depth);
            while (nextIs(Kind.OPERATOR, "+", "-")) {
                String op = tokens.get(index++).text;
                left = new Binary(op, left, term(depth));
            }
            return left;
        }

        private Node term(int depth) {
            Node left = unary(depth);
            while (nextIs(Kind.OPERATOR, "*", "/")) {
                String op = tokens.get(index++).text;
                left = new Binary(op, left, unary(depth));
            }
            return left;
        }

        private Node unary(int depth) {
            if (nextIs(Kind.OPERATOR, "-", "+")) {
                String op = tokens.get(index++).text;
                depth(depth + 1);
                Node operand = unary(depth + 1);
                return "-".equals(op) ? new Negate(operand) : operand;
            }
            return primary(depth);
        }

        private Node primary(int depth) {
            if (atEnd()) throw new FormulaException(tokens.isEmpty() ? "公式不能为空" : "公式没写完", endPosition());
            Token token = tokens.get(index++);
            switch (token.kind) {
                case NUMBER -> {
                    return new Constant(new BigDecimal(token.text));
                }
                case LEFT -> {
                    Node inner = expression(depth + 1);
                    expect(Kind.RIGHT, "少了右括号「)」");
                    return new Group(inner);
                }
                case NAME -> {
                    if (nextIs(Kind.LEFT)) return call(token, depth);
                    Variable variable = VARIABLES.get(token.text);
                    if (variable == null) variable = VARIABLES.get(token.text.toLowerCase(Locale.ROOT));
                    if (variable == null) {
                        Function function = function(token.text);
                        if (function != null) {
                            throw new FormulaException("函数 " + token.text + " 后面要跟括号，例如 " + function.signature,
                                    token.position);
                        }
                        throw new FormulaException("没有「" + token.text + "」这个字段", token.position);
                    }
                    return new Field(variable);
                }
                default -> throw new FormulaException("这里不能是「" + token.text + "」", token.position);
            }
        }

        private Node call(Token name, int depth) {
            Function function = function(name.text);
            if (function == null) throw new FormulaException("没有「" + name.text + "」这个函数", name.position);
            index++; // (
            List<Node> args = new ArrayList<>();
            if (!nextIs(Kind.RIGHT)) {
                args.add(expression(depth + 1));
                while (nextIs(Kind.COMMA)) {
                    index++;
                    args.add(expression(depth + 1));
                }
            }
            expect(Kind.RIGHT, "函数 " + name.text + " 少了右括号「)」");
            if (args.size() < function.minArgs || args.size() > function.maxArgs) {
                throw new FormulaException("函数 " + name.text + " 的参数个数不对，写法是 " + function.signature,
                        name.position);
            }
            if (function == Function.ROUND && args.size() == 2 && !(args.get(1) instanceof Constant)) {
                throw new FormulaException("round 的位数要直接写数字", name.position);
            }
            if (function == Function.ROUND && args.size() == 2) {
                BigDecimal digits = ((Constant) args.get(1)).value;
                if (digits.scale() > 0 && digits.stripTrailingZeros().scale() > 0
                        || digits.compareTo(BigDecimal.ZERO) < 0 || digits.compareTo(BigDecimal.valueOf(6)) > 0) {
                    throw new FormulaException("round 的位数要是 0 到 6 的整数", name.position);
                }
            }
            return new Call(function, name.text, args);
        }

        private void expect(Kind kind, String message) {
            if (!nextIs(kind)) throw new FormulaException(message, atEnd() ? endPosition() : peek().position);
            index++;
        }

        private static Function function(String name) {
            Function function = FUNCTIONS.get(name);
            return function != null ? function : FUNCTIONS.get(name.toLowerCase(Locale.ROOT));
        }
    }

    // ---- 语法树 ----

    private interface Node {
        BigDecimal evaluate(Map<Variable, BigDecimal> values);

        String render(Map<Variable, BigDecimal> values);

        default void collect(Set<Variable> used) {
        }
    }

    private record Constant(BigDecimal value) implements Node {
        public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
            return value;
        }

        public String render(Map<Variable, BigDecimal> values) {
            return value.stripTrailingZeros().toPlainString();
        }
    }

    private record Field(Variable variable) implements Node {
        public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
            BigDecimal value = values.get(variable);
            return value == null ? BigDecimal.ZERO : value;
        }

        public String render(Map<Variable, BigDecimal> values) {
            return plain(evaluate(values));
        }

        public void collect(Set<Variable> used) {
            used.add(variable);
        }
    }

    private record Group(Node inner) implements Node {
        public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
            return inner.evaluate(values);
        }

        public String render(Map<Variable, BigDecimal> values) {
            return "(" + inner.render(values) + ")";
        }

        public void collect(Set<Variable> used) {
            inner.collect(used);
        }
    }

    private record Negate(Node operand) implements Node {
        public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
            return operand.evaluate(values).negate();
        }

        public String render(Map<Variable, BigDecimal> values) {
            return "-" + operand.render(values);
        }

        public void collect(Set<Variable> used) {
            operand.collect(used);
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
            BigDecimal a = left.evaluate(values);
            BigDecimal b = right.evaluate(values);
            return switch (op) {
                case "+" -> a.add(b, MATH);
                case "-" -> a.subtract(b, MATH);
                case "*" -> a.multiply(b, MATH);
                case "/" -> {
                    if (b.signum() == 0) throw new FormulaException("除数为 0（可以用 if 先判断，例如 if(被引张数 > 0, …, 0)）", -1);
                    yield a.divide(b, MATH);
                }
                case ">" -> bool(a.compareTo(b) > 0);
                case "<" -> bool(a.compareTo(b) < 0);
                case ">=" -> bool(a.compareTo(b) >= 0);
                case "<=" -> bool(a.compareTo(b) <= 0);
                case "==" -> bool(a.compareTo(b) == 0);
                case "!=" -> bool(a.compareTo(b) != 0);
                default -> throw new IllegalStateException(op);
            };
        }

        public String render(Map<Variable, BigDecimal> values) {
            String symbol = switch (op) {
                case "*" -> "×";
                case "/" -> "÷";
                default -> op;
            };
            return left.render(values) + " " + symbol + " " + right.render(values);
        }

        public void collect(Set<Variable> used) {
            left.collect(used);
            right.collect(used);
        }
    }

    private record Call(Function function, String name, List<Node> args) implements Node {
        public BigDecimal evaluate(Map<Variable, BigDecimal> values) {
            return switch (function) {
                case MAX -> args.stream().map(arg -> arg.evaluate(values)).reduce(BigDecimal::max).orElseThrow();
                case MIN -> args.stream().map(arg -> arg.evaluate(values)).reduce(BigDecimal::min).orElseThrow();
                case ROUND -> args.get(0).evaluate(values).setScale(
                        args.size() == 2 ? args.get(1).evaluate(values).intValue() : 0, RoundingMode.HALF_UP);
                case FLOOR -> args.get(0).evaluate(values).setScale(0, RoundingMode.FLOOR);
                case CEIL -> args.get(0).evaluate(values).setScale(0, RoundingMode.CEILING);
                case ABS -> args.get(0).evaluate(values).abs();
                case IF -> args.get(0).evaluate(values).signum() != 0
                        ? args.get(1).evaluate(values) : args.get(2).evaluate(values);
            };
        }

        public String render(Map<Variable, BigDecimal> values) {
            List<String> parts = new ArrayList<>();
            args.forEach(arg -> parts.add(arg.render(values)));
            return name + "(" + String.join(", ", parts) + ")";
        }

        public void collect(Set<Variable> used) {
            args.forEach(arg -> arg.collect(used));
        }
    }

    private static BigDecimal bool(boolean value) {
        return value ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    /** 公式写错或算不下去。{@code position} 是出错位置（从 0 数的字符下标），算的时候出错为 -1。 */
    public static final class FormulaException extends RuntimeException {
        private final int position;

        public FormulaException(String message, int position) {
            super(message);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }
}
