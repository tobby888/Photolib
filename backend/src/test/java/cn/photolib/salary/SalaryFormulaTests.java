package cn.photolib.salary;

import cn.photolib.salary.SalaryFormula.FormulaException;
import cn.photolib.salary.SalaryFormula.Variable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工资公式：字段、运算、函数、出错位置和计算过程。纯单元测试，不起 Spring。 */
class SalaryFormulaTests {

    private static Map<Variable, BigDecimal> values(String totalHours, String shooting, String retouching, int adopted,
                                                    int uploaded) {
        Map<Variable, BigDecimal> values = new EnumMap<>(Variable.class);
        values.put(Variable.TOTAL_HOURS, new BigDecimal(totalHours));
        values.put(Variable.SHOOTING_HOURS, new BigDecimal(shooting));
        values.put(Variable.RETOUCHING_HOURS, new BigDecimal(retouching));
        values.put(Variable.ADOPTED_PHOTOS, BigDecimal.valueOf(adopted));
        values.put(Variable.UPLOADED_PHOTOS, BigDecimal.valueOf(uploaded));
        return values;
    }

    private static BigDecimal salary(String formula, Map<Variable, BigDecimal> values) {
        return SalaryFormula.compile(formula).salary(values);
    }

    @Test
    void theDefaultFormulaIsTheOriginalFixedRule() {
        Map<Variable, BigDecimal> hours = values("4", "3", "1", 3, 0);
        assertThat(SalaryFormula.compile(null).source()).isEqualTo(SalaryFormula.DEFAULT);
        assertThat(SalaryFormula.compile("  ").salary(hours)).isEqualByComparingTo("80.00");
        assertThat(salary(SalaryFormula.DEFAULT, values("0.5", "0.5", "0", 5, 0))).isEqualByComparingTo("50.00");
        // 20 分钟 = 1/3 小时，× 20 = 6.666… → 6.67。
        BigDecimal third = BigDecimal.valueOf(20).divide(BigDecimal.valueOf(60), java.math.MathContext.DECIMAL128);
        Map<Variable, BigDecimal> twenty = new EnumMap<>(Variable.class);
        twenty.put(Variable.TOTAL_HOURS, third);
        assertThat(salary(SalaryFormula.DEFAULT, twenty)).isEqualByComparingTo("6.67");
    }

    @Test
    void separateRatesForShootingAndRetouchingWithFunctions() {
        Map<Variable, BigDecimal> v = values("4.5", "3", "1.5", 5, 20);
        assertThat(salary("拍摄时长 * 25 + 修图时长 * 15 + 被引张数 * 10", v)).isEqualByComparingTo("147.50");
        assertThat(salary("min(max(工时 * 20, 被引张数 * 10), 80)", v)).isEqualByComparingTo("80.00");
        assertThat(salary("max(工时 * 20, 被引张数 * 10, 上传张数 * 2)", v)).isEqualByComparingTo("90.00");
        assertThat(salary("round(工时 * 7.3)", v)).isEqualByComparingTo("33.00");
        assertThat(salary("round(工时 * 7.333, 1)", v)).isEqualByComparingTo("33.00");
        assertThat(salary("floor(工时) * 20 + ceil(修图时长)", v)).isEqualByComparingTo("82.00");
        assertThat(salary("abs(被引张数 - 上传张数)", v)).isEqualByComparingTo("15.00");
        assertThat(salary("(拍摄时长 + 修图时长) * 2 - 1", v)).isEqualByComparingTo("8.00");
        assertThat(salary("-工时 + 10", v)).isEqualByComparingTo("5.50");
        // 英文别名、中文函数名、全角括号和逗号、× ÷ 都认。
        assertThat(salary("MAX(totalHours * 20, adoptedPhotos * 10)", v)).isEqualByComparingTo("90.00");
        assertThat(salary("最大（工时 × 20，被引张数 × 10）", v)).isEqualByComparingTo("90.00");
        assertThat(salary("上传张数 ÷ 4", v)).isEqualByComparingTo("5.00");
    }

    @Test
    void comparisonsAndIfSelectOnlyOneBranch() {
        Map<Variable, BigDecimal> none = values("2", "2", "0", 0, 0);
        Map<Variable, BigDecimal> some = values("2", "2", "0", 4, 0);
        String formula = "if(被引张数 > 0, 100 / 被引张数, 工时 * 20)";
        // 被引 0 张时不会去算 100 / 0。
        assertThat(salary(formula, none)).isEqualByComparingTo("40.00");
        assertThat(salary(formula, some)).isEqualByComparingTo("25.00");
        assertThat(salary("(被引张数 >= 4) * 50 + 工时", some)).isEqualByComparingTo("52.00");
        assertThat(salary("(被引张数 == 4) + (被引张数 != 4) * 100 + (工时 <= 2) + (工时 < 2)", some))
                .isEqualByComparingTo("2.00");
        assertThat(salary("如果(工时 > 1, 10, 0)", some)).isEqualByComparingTo("10.00");
    }

    @Test
    void negativeResultsCountAsZero() {
        SalaryFormula formula = SalaryFormula.compile("工时 * 20 - 100");
        Map<Variable, BigDecimal> v = values("2", "2", "0", 0, 0);
        assertThat(formula.evaluate(v)).isEqualByComparingTo("-60");
        assertThat(formula.salary(v)).isEqualByComparingTo("0.00");
        assertThat(formula.explain(v)).isEqualTo("2 × 20 - 100 = 0.00（小于 0 按 0 计）");
    }

    @Test
    void explainSubstitutesValues() {
        Map<Variable, BigDecimal> v = values("1.1666666666", "1", "0.1666666666", 3, 0);
        assertThat(SalaryFormula.compile(SalaryFormula.DEFAULT).explain(v))
                .isEqualTo("max(1.17 × 20, 3 × 10) = 30.00");
        assertThat(SalaryFormula.compile("(拍摄时长 + 修图时长) * 2").explain(v))
                .isEqualTo("(1 + 0.17) × 2 = 2.33");
        assertThat(SalaryFormula.compile("if(被引张数 > 2, 1, 0)").explain(v)).isEqualTo("if(3 > 2, 1, 0) = 1.00");
    }

    @Test
    void reportsWhatIsWrongAndWhere() {
        // 首尾空白先去掉，出错位置落在最后一个字符之后。
        assertError("工时 * ", "公式没写完", 4);
        assertError("工资 * 20", "没有「工资」这个字段", 0);
        assertError("max(工时, 20", "少了右括号", 10);
        assertError("sum(工时)", "没有「sum」这个函数", 0);
        assertError("工时 * 20)", "多了「)」", 7);
        assertError("工时 # 2", "不认识的字符「#」", 3);
        assertError("工时 = 2", "相等写 ==", 3);
        assertError("if(工时, 1)", "参数个数不对", 0);
        assertError("abs(1, 2)", "参数个数不对", 0);
        assertError("max", "后面要跟括号", 0);
        assertError("round(工时, 被引张数)", "位数要直接写数字", 0);
        assertError("round(工时, 1.5)", "0 到 6 的整数", 0);
        assertError("1 < 2 < 3", "比较不能连着写", 6);
        assertError("", null, -2);
        assertThatThrownBy(() -> SalaryFormula.compile("1+".repeat(300))).isInstanceOf(FormulaException.class)
                .hasMessageContaining("最长");
        assertThatThrownBy(() -> SalaryFormula.compile("(".repeat(60) + "1" + ")".repeat(60)))
                .isInstanceOf(FormulaException.class).hasMessageContaining("嵌套太深");
    }

    @Test
    void divisionByZeroIsAnErrorAtEvaluation() {
        SalaryFormula formula = SalaryFormula.compile("工时 / 被引张数");
        assertThatThrownBy(() -> formula.salary(values("1", "1", "0", 0, 0))).isInstanceOf(FormulaException.class)
                .hasMessageContaining("除数为 0");
    }

    @Test
    void knowsWhichFieldsItUses() {
        assertThat(SalaryFormula.compile("拍摄时长 * 25 + if(待审核条数 > 0, 0, 被引张数)").variables())
                .containsExactlyInAnyOrder(Variable.SHOOTING_HOURS, Variable.PENDING_COUNT, Variable.ADOPTED_PHOTOS);
        assertThat(SalaryFormula.compile("100").variables()).isEmpty();
        // 示例值：工时 4.5，被引 5 → 默认公式 max(90, 50) = 90。
        assertThat(SalaryFormula.compile(SalaryFormula.DEFAULT).salary(SalaryFormula.sampleValues()))
                .isEqualByComparingTo("90.00");
    }

    /** {@code position} 为 -2 表示空公式：空白按默认公式处理，不报错。 */
    private static void assertError(String formula, String message, int position) {
        if (position == -2) {
            assertThat(SalaryFormula.compile(formula).source()).isEqualTo(SalaryFormula.DEFAULT);
            return;
        }
        assertThatThrownBy(() -> SalaryFormula.compile(formula)).isInstanceOf(FormulaException.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((FormulaException) e).position()).as(formula).isEqualTo(position));
    }
}
