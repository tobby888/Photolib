package cn.photolib.salary;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.salary.SalaryCalculationService.CalculationSummary;
import cn.photolib.salary.SalaryCalculationService.Detail;
import cn.photolib.salary.SalaryCalculationService.Line;
import cn.photolib.salary.SalaryCalculationService.MemberTotal;
import cn.photolib.salary.SalaryCalculationService.ProjectGroup;
import cn.photolib.user.model.UserRole;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 工资计算：按选题结算、max(工时 × 20, 被引张数 × 10)、按学号归并成员、快照不随后续审核变化、导出表按选题分 sheet。
 */
@SpringBootTest
@Transactional
class SalaryCalculationServiceTests {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    SalaryCalculationService salaries;

    private long base;
    private long sequence = 100;
    private long ownerId;
    private long campusId;
    private AuthenticatedUser minister;

    @BeforeEach
    void setUp() {
        base = (System.nanoTime() & 0xFFFFFFFFFFL) * 1000;
        campusId = base + 1;
        ownerId = base + 2;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '工资校区')")
                .param("id", campusId).param("code", "SALARY-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '工资部长', 'MINISTER', TRUE, FALSE)
                """).param("id", ownerId).param("username", "salary-" + base).update();
        minister = new AuthenticatedUser(ownerId, "salary-" + base, "工资部长", UserRole.MINISTER, null, false,
                2L, "MINISTER", "部长", DataScope.GLOBAL, EnumSet.of(PermissionCode.SALARY_CALCULATE), Set.of());
    }

    @Test
    void eachProjectPaysTheHigherOfHoursAndAdoptions() {
        long sports = project("运动会", "COMPLETED", LocalDateTime.of(2026, 10, 10, 18, 0));
        long paper = project("校报", "COMPLETED", LocalDateTime.of(2026, 10, 20, 9, 0));
        long ongoing = project("进行中", "ACTIVE", null);
        long before = project("上月结束", "COMPLETED", LocalDateTime.of(2026, 9, 30, 23, 59));
        long sportsRequest = request(sports);
        long paperRequest = request(paper);

        // 甲：运动会 2 小时已确认（40 元）、被引 3 张（30 元）→ 40，按工时。
        worklog(sportsRequest, null, "甲", "S-JIA", 90, 0, "CONFIRMED");
        worklog(sportsRequest, null, "甲", "S-JIA", 0, 30, "CONFIRMED");
        adoptions(sports, "甲", "S-JIA", 3);
        // 乙：运动会通过上传链接申报 30 分钟（10 元）、被引 5 张（50 元）→ 50，按被引。被引里的姓名多了空格也归并。
        worklog(null, shareLink(sports), "乙", "S-YI", 30, 0, "CONFIRMED");
        adoptions(sports, " 乙 ", "S-YI", 5);
        // 丙：只有被引 2 张 → 20。
        adoptions(sports, "丙", "S-BING", 2);
        // 丁：只有待审核 → 0，列出来提醒。退回、草稿、删除的不算记录。
        worklog(sportsRequest, null, "丁", "S-DING", 120, 0, "SUBMITTED");
        worklog(sportsRequest, null, "戊", "S-WU", 60, 0, "REJECTED");
        worklog(sportsRequest, null, "戊", "S-WU", 60, 0, "DRAFT");
        // 校报：甲 20 分钟 → 6.67 元（四舍五入到分）；没有学号的老记录按姓名归并。
        worklog(paperRequest, null, "甲", "S-JIA", 20, 0, "CONFIRMED");
        worklog(paperRequest, null, "老成员", null, 30, 0, "CONFIRMED");
        worklog(paperRequest, null, "老成员 ", "  ", 30, 0, "CONFIRMED");
        // 甲在两个选题里被引的张数相同、工时工资也相同：两者相同。
        long both = project("两者相同", "COMPLETED", LocalDateTime.of(2026, 10, 31, 23, 0));
        worklog(request(both), null, "甲", "S-JIA", 30, 0, "CONFIRMED");
        adoptions(both, "甲", "S-JIA", 1);
        // 没结束的、期外结束的选题不算。
        worklog(request(ongoing), null, "甲", "S-JIA", 600, 0, "CONFIRMED");
        adoptions(before, "甲", "S-JIA", 9);

        List<Line> lines = mine(salaries.compute(FROM, TO, SalaryFormula.compile(null)));

        // 默认公式 max(工时 × 20, 被引张数 × 10)。上传张数：被引的图都传进了这个选题。
        assertThat(lines).extracting(Line::projectTitle, Line::memberName, Line::studentId,
                        Line::confirmedMinutes, Line::adoptedPhotos, Line::uploadedPhotos, Line::salaryAmount,
                        Line::pendingWorklogCount)
                .containsExactly(
                        tuple("运动会", "乙", "S-YI", 30, 5, 5, money("50.00"), 0),
                        tuple("运动会", "甲", "S-JIA", 120, 3, 3, money("40.00"), 0),
                        tuple("运动会", "丙", "S-BING", 0, 2, 2, money("20.00"), 0),
                        tuple("运动会", "丁", "S-DING", 0, 0, 0, money("0.00"), 1),
                        tuple("校报", "老成员", null, 60, 0, 0, money("20.00"), 0),
                        tuple("校报", "甲", "S-JIA", 20, 0, 0, money("6.67"), 0),
                        tuple("两者相同", "甲", "S-JIA", 30, 1, 1, money("10.00"), 0));
        // 校区取工时的需求校区和被引图片的校区；链接申报的工时没有校区。
        assertThat(lines.get(1).campus()).isEqualTo("工资校区");
        assertThat(lines.get(3).pendingMinutes()).isEqualTo(120);
    }

    @Test
    void aCustomFormulaUsesEveryField() {
        long sports = project("运动会", "COMPLETED", LocalDateTime.of(2026, 10, 10, 18, 0));
        long sportsRequest = request(sports);
        // 甲：拍摄 2 小时、修图 1 小时（两条），被引 2 张，另外又有 3 张没被引的图添加进了相册。
        worklog(sportsRequest, null, "甲", "S-JIA", 120, 0, "CONFIRMED");
        worklog(sportsRequest, null, "甲", "S-JIA", 0, 60, "CONFIRMED");
        worklog(sportsRequest, null, "甲", "S-JIA", 30, 0, "SUBMITTED");
        adoptions(sports, "甲", "S-JIA", 2);
        for (long photo : photos(null, "甲", "S-JIA", 3)) albumAdd(photo, sports);
        // 乙只有上传、没有工时也没有被引：不算「有记录」，不出现。
        photos(sports, "乙", "S-YI", 4);

        String formula = "拍摄时长 * 25 + 修图时长 * 15 + 被引张数 * 10 + 上传张数 + 工时条数 * 100"
                + " + if(待审核条数 > 0, 1000, 0) + 待审核时长 * 2";
        List<Line> lines = mine(salaries.compute(FROM, TO, SalaryFormula.compile(formula)));

        // 50 + 15 + 20 + 5 + 200 + 1000 + 1 = 1291。
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.memberName()).isEqualTo("甲");
            assertThat(line.uploadedPhotos()).isEqualTo(5);
            assertThat(line.confirmedWorklogCount()).isEqualTo(2);
            assertThat(line.salaryAmount()).isEqualByComparingTo("1291.00");
        });
        // 公式算不下去时报出是哪个选题、哪个人（先把库里别的测试留下的同期选题挪走）。
        isolateRange();
        assertThatThrownBy(() -> salaries.compute(FROM, TO, SalaryFormula.compile("工时 / (被引张数 - 2)")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("「运动会」的 甲").hasMessageContaining("除数为 0");
    }

    @Test
    void customProjectsAreSettledByWorkDateAndMergedByName() {
        long sports = project("运动会", "COMPLETED", LocalDateTime.of(2026, 10, 10, 18, 0));
        long ongoing = project("进行中", "ACTIVE", null);
        long sportsRequest = request(sports);
        String title = "迎新晚会" + base;
        // 填了自定义选题就算在它下面：站内的、链接申报的、需求所属选题还没结束的，同名合并成一组。
        customWorklog(sportsRequest, null, 60, title, LocalDate.of(2026, 10, 5));
        customWorklog(null, shareLink(sports), 30, title, LocalDate.of(2026, 10, 6));
        customWorklog(request(ongoing), null, 30, title, LocalDate.of(2026, 10, 31));
        // 工作日期在期外的不算。
        customWorklog(sportsRequest, null, 600, title, LocalDate.of(2026, 9, 30));
        // 运动会只剩没填自定义选题的 15 分钟和 1 张被引。
        worklog(sportsRequest, null, "甲", "S-JIA", 15, 0, "CONFIRMED");
        adoptions(sports, "甲", "S-JIA", 1);
        isolateRange();

        CalculationSummary summary = salaries.calculate(FROM, TO, null, minister);
        assertThat(summary.projectCount()).isEqualTo(2);
        assertThat(summary.memberCount()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualByComparingTo("50.00");

        Detail detail = salaries.detail(summary.id(), minister);
        // 自定义选题没有结束时间，排在真实选题后面；没有选题 id，被引和上传都是 0。
        assertThat(detail.projects()).extracting(ProjectGroup::projectId, ProjectGroup::projectTitle,
                        ProjectGroup::completedAt, ProjectGroup::confirmedMinutes, ProjectGroup::adoptedPhotos,
                        ProjectGroup::salaryAmount)
                .containsExactly(
                        tuple(sports, "运动会", LocalDateTime.of(2026, 10, 10, 18, 0), 15, 1, money("10.00")),
                        tuple(null, title, null, 120, 0, money("40.00")));
        assertThat(detail.members()).extracting(MemberTotal::memberName, MemberTotal::projectCount,
                        MemberTotal::salaryAmount)
                .containsExactly(tuple("甲", 2, money("50.00")));
        assertThat(SalaryWorkbook.write(detail)).isNotEmpty();
    }

    @Test
    void formulaCheckAndReferenceForTheEditor() {
        var reference = salaries.formulaReference(minister);
        assertThat(reference.defaultFormula()).isEqualTo(SalaryFormula.DEFAULT);
        assertThat(reference.variables()).extracting(SalaryCalculationService.VariableInfo::name)
                .contains("工时", "拍摄时长", "修图时长", "被引张数", "上传张数");
        assertThat(reference.functions()).extracting(SalaryCalculationService.FunctionInfo::name)
                .contains("max", "min", "round", "if");

        var ok = salaries.check(" max(拍摄时长 * 30, 被引张数 * 10) ", minister);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.formula()).isEqualTo("max(拍摄时长 * 30, 被引张数 * 10)");
        assertThat(ok.variables()).containsExactly("拍摄时长", "被引张数");
        assertThat(ok.sampleSalary()).isEqualByComparingTo("90.00");
        assertThat(ok.sampleProcess()).isEqualTo("max(3 × 30, 5 × 10) = 90.00");

        var bad = salaries.check("max(工时 * 20", minister);
        assertThat(bad.valid()).isFalse();
        assertThat(bad.error()).contains("少了右括号");
        assertThat(bad.position()).isEqualTo(11);

        // 写法没错、只是示例值下除以 0：可以用，但提示一下。
        var risky = salaries.check("工时 / (被引张数 - 5)", minister);
        assertThat(risky.valid()).isTrue();
        assertThat(risky.error()).contains("除数为 0");

        assertThatThrownBy(() -> salaries.calculate(FROM, TO, "工资 * 2", minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("工资公式有误").hasMessageContaining("第 1 个字符");
    }

    @Test
    void theSnapshotKeepsItsNumbersAndExportsOneSheetPerProject() throws Exception {
        long sports = project("运动会/开幕[一]", "COMPLETED", LocalDateTime.of(2026, 10, 10, 18, 0));
        long paper = project("=校报", "COMPLETED", LocalDateTime.of(2026, 10, 20, 9, 0));
        long sportsRequest = request(sports);
        long pending = worklog(sportsRequest, null, "甲", "S-JIA", 90, 0, "SUBMITTED");
        worklog(sportsRequest, null, "甲", "S-JIA", 60, 0, "CONFIRMED");
        adoptions(sports, "乙", "S-YI", 4);
        worklog(request(paper), null, "甲", "S-JIA", 30, 0, "CONFIRMED");
        isolateRange();

        CalculationSummary summary = salaries.calculate(FROM, TO, null, minister);
        assertThat(summary.projectCount()).isEqualTo(2);
        assertThat(summary.memberCount()).isEqualTo(2);
        assertThat(summary.lineCount()).isEqualTo(3);
        assertThat(summary.totalAmount()).isEqualByComparingTo("70.00");
        assertThat(summary.pendingWorklogCount()).isEqualTo(1);
        assertThat(summary.formula()).isEqualTo(SalaryFormula.DEFAULT);
        assertThat(salaries.formulaReference(minister).lastFormula()).isEqualTo(SalaryFormula.DEFAULT);
        assertThat(summary.createdByName()).isEqualTo("工资部长");
        assertThat(salaries.list(10, minister)).extracting(CalculationSummary::id).contains(summary.id());

        // 之后工时被确认，快照不变。
        jdbc.sql("UPDATE worklog SET status='CONFIRMED' WHERE id=:id").param("id", pending).update();
        Detail detail = salaries.detail(summary.id(), minister);
        assertThat(detail.projects()).extracting(ProjectGroup::projectTitle, ProjectGroup::salaryAmount)
                .containsExactly(tuple("运动会/开幕[一]", money("60.00")), tuple("=校报", money("10.00")));
        assertThat(detail.members()).extracting(MemberTotal::memberName, MemberTotal::projectCount,
                        MemberTotal::confirmedMinutes, MemberTotal::salaryAmount, MemberTotal::pendingWorklogCount)
                .containsExactly(tuple("乙", 1, 0, money("40.00"), 0), tuple("甲", 2, 90, money("30.00"), 1));
        // 计算过程用快照里的公式和数值现渲染。
        assertThat(detail.projects().get(0).lines()).extracting(Line::process)
                .containsExactly("max(0 × 20, 4 × 10) = 40.00", "max(1 × 20, 0 × 10) = 20.00");

        byte[] bytes = SalaryWorkbook.write(detail);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheetName(0)).isEqualTo("汇总");
            // sheet 名里 Excel 不认的 / [ ] 换成空格。
            assertThat(workbook.getSheetName(1)).isEqualTo("01 运动会 开幕 一");
            assertThat(workbook.getSheetName(2)).isEqualTo("02 =校报");

            Sheet sports1 = workbook.getSheet("01 运动会 开幕 一");
            int header = headerRow(sports1);
            assertThat(cells(sports1.getRow(header))).containsExactlyElementsOf(SalaryWorkbook.PROJECT_HEADERS);
            Row jia = sports1.getRow(header + 2);
            assertThat(jia.getCell(1).getStringCellValue()).isEqualTo("甲");
            assertThat(jia.getCell(6).getNumericCellValue()).isEqualTo(1.0);
            assertThat(jia.getCell(7).getNumericCellValue()).isEqualTo(0.0);
            assertThat(jia.getCell(9).getNumericCellValue()).isEqualTo(1.0);
            assertThat(jia.getCell(10).getStringCellValue()).isEqualTo("max(1 × 20, 0 × 10) = 20.00");
            assertThat(jia.getCell(11).getNumericCellValue()).isEqualTo(20.0);
            Row yi = sports1.getRow(header + 1);
            assertThat(yi.getCell(1).getStringCellValue()).isEqualTo("乙");
            assertThat(yi.getCell(8).getNumericCellValue()).isEqualTo(4.0);
            assertThat(yi.getCell(11).getNumericCellValue()).isEqualTo(40.0);
            Row total = sports1.getRow(header + 3);
            assertThat(total.getCell(1).getStringCellValue()).isEqualTo("合计");
            assertThat(total.getCell(11).getNumericCellValue()).isEqualTo(60.0);
            // 计算规则里写着这次用的公式。
            assertThat(sports1.getRow(1).getCell(1).getStringCellValue()).contains(SalaryFormula.DEFAULT);
            // 标题以 = 开头不能被当成公式。
            assertThat(workbook.getSheet("02 =校报").getRow(0).getCell(0).getStringCellValue()).isEqualTo("'=校报");

            Sheet summarySheet = workbook.getSheet("汇总");
            int summaryHeader = headerRow(summarySheet);
            assertThat(cells(summarySheet.getRow(summaryHeader)))
                    .containsExactlyElementsOf(SalaryWorkbook.SUMMARY_HEADERS);
            assertThat(summarySheet.getRow(summaryHeader + 3).getCell(11).getNumericCellValue()).isEqualTo(70.0);
        }
        assertThat(SalaryWorkbook.fileName(summary)).isEqualTo("工资计算-2026-10-01至2026-10-31.xlsx");

        salaries.delete(summary.id(), minister);
        assertThat(salaries.list(10, minister)).extracting(CalculationSummary::id).doesNotContain(summary.id());
        assertThatThrownBy(() -> salaries.detail(summary.id(), minister)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> salaries.delete(summary.id(), minister)).isInstanceOf(BusinessException.class);
    }

    @Test
    void campusScopedAccountsCannotCalculateAndRangesAreValidated() {
        AuthenticatedUser manager = new AuthenticatedUser(ownerId, "salary-m", "校区负责人", UserRole.CAMPUS_MANAGER,
                campusId, false, 3L, "CAMPUS_MANAGER", "负责人", DataScope.CAMPUS,
                EnumSet.of(PermissionCode.SALARY_CALCULATE), Set.of(campusId));
        assertThatThrownBy(() -> salaries.calculate(FROM, TO, null, manager)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("校区范围");
        assertThatThrownBy(() -> salaries.list(10, manager)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> salaries.calculate(TO, FROM, null, minister)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能早于");
        assertThatThrownBy(() -> salaries.calculate(FROM, FROM.plusYears(4), null, minister))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sheetNamesAreShortUniqueAndExcelSafe() {
        Set<String> used = new HashSet<>(Set.of("汇总"));
        String longTitle = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三";
        String first = SalaryWorkbook.sheetName(1, longTitle, used);
        assertThat(first).hasSize(31).startsWith("01 ");
        assertThat(SalaryWorkbook.sheetName(2, "'引号'", used)).isEqualTo("02 引号");
        assertThat(SalaryWorkbook.sheetName(3, "  ", used)).isEqualTo("03 未命名选题");
        assertThat(SalaryWorkbook.sheetName(4, "a:b*c?d\\e", used)).isEqualTo("04 a b c d e");
        // 同一个序号撞名（不会真的发生，但截断规则要保证唯一）。
        Set<String> clash = new HashSet<>();
        String a = SalaryWorkbook.sheetName(5, longTitle, clash);
        String b = SalaryWorkbook.sheetName(5, longTitle, clash);
        assertThat(a).isNotEqualTo(b);
        assertThat(b).hasSizeLessThanOrEqualTo(31).endsWith("(2)");
    }

    @Test
    void hoursAreExactBeforeTheFormulaRounds() {
        SalaryFormula formula = SalaryFormula.compile(SalaryFormula.DEFAULT);
        // 1 分钟 × 20 元/小时 = 0.333… → 0.33；2 分钟 → 0.67；90 分钟 → 30。
        assertThat(formula.salary(SalaryCalculationService.values(1, 0, 1, 0, 0, 0, 0))).isEqualByComparingTo("0.33");
        assertThat(formula.salary(SalaryCalculationService.values(1, 1, 1, 0, 0, 0, 0))).isEqualByComparingTo("0.67");
        assertThat(formula.salary(SalaryCalculationService.values(60, 30, 1, 0, 0, 0, 0))).isEqualByComparingTo("30.00");
        assertThat(formula.salary(SalaryCalculationService.values(0, 0, 0, 0, 0, 7, 0))).isEqualByComparingTo("70.00");
        assertThat(SalaryCalculationService.values(90, 30, 2, 1, 45, 3, 8))
                .containsEntry(SalaryFormula.Variable.TOTAL_HOURS, new BigDecimal("2"))
                .containsEntry(SalaryFormula.Variable.PENDING_HOURS, new BigDecimal("0.75"))
                .containsEntry(SalaryFormula.Variable.UPLOADED_PHOTOS, new BigDecimal("8"));
    }

    // ---- 造数 ----

    /** 只留本测试造的选题（别的测试类留在库里的已完成选题也会被算进去）。 */
    private List<Line> mine(List<Line> all) {
        return all.stream().filter(line -> line.projectId() != null
                && line.projectId() > base && line.projectId() < base + 1000).toList();
    }

    /** 计算会落库，按全库算：把本测试以外、在清算期内结束的选题临时挪走（事务回滚后复原）。 */
    private void isolateRange() {
        jdbc.sql("""
                UPDATE project SET completed_at=:far
                WHERE status='COMPLETED' AND completed_at >= :from AND completed_at < :to
                  AND (id <= :base OR id >= :limit)
                """).param("far", LocalDateTime.of(1999, 1, 1, 0, 0)).param("from", FROM)
                .param("to", TO.plusDays(1)).param("base", base).param("limit", base + 1000).update();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private long project(String title, String status, LocalDateTime completedAt) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, :title, :status, :userId, :completedAt)
                """).param("id", id).param("title", title).param("status", status).param("userId", ownerId)
                .param("completedAt", completedAt).update();
        return id;
    }

    private long request(long project) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '工资需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", id).param("projectId", project).param("campusId", campusId)
                .param("deadline", LocalDateTime.now().plusDays(3)).param("userId", ownerId).update();
        return id;
    }

    private long shareLink(long project) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO project_share_link(id, token, project_id, name, password_hash, created_by, purpose)
                VALUES (:id, :token, :projectId, '现场', 'hash', :userId, 'UPLOAD')
                """).param("id", id).param("token", String.format("%026d", id)).param("projectId", project)
                .param("userId", ownerId).update();
        return id;
    }

    private long worklog(Long request, Long shareLink, String name, String studentId, int shooting, int retouching,
                         String status) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, share_link_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status)
                VALUES (:id, :requestId, :shareLinkId, :userId, :workDate, :shooting, :retouching, :name, :studentId,
                        :status)
                """).param("id", id).param("requestId", request).param("shareLinkId", shareLink)
                .param("userId", ownerId).param("workDate", LocalDate.of(2026, 10, 5)).param("shooting", shooting)
                .param("retouching", retouching).param("name", name).param("studentId", studentId)
                .param("status", status).update();
        return id;
    }

    /** {@code count} 张拍摄者为 {@code name} 的图片传进 {@code project} 并被引用。 */
    private void customWorklog(Long request, Long shareLink, int minutes, String customProject, LocalDate workDate) {
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, share_link_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status, custom_project_title)
                VALUES (:id, :requestId, :shareLinkId, :userId, :workDate, :minutes, 0, '甲', 'S-JIA', 'CONFIRMED',
                        :customProject)
                """).param("id", base + sequence++).param("requestId", request).param("shareLinkId", shareLink)
                .param("userId", ownerId).param("workDate", workDate).param("minutes", minutes)
                .param("customProject", customProject).update();
    }

    private List<Long> adoptions(long project, String name, String studentId, int count) {
        List<Long> photos = photos(project, name, studentId, count);
        for (long photoId : photos) {
            jdbc.sql("""
                    INSERT INTO adoption(id, project_id, photo_id, photographer_student_id, photographer_name,
                                         adopted_by, adopted_at)
                    VALUES (:id, :projectId, :photoId, :studentId, :name, :userId, :adoptedAt)
                    """).param("id", base + sequence++).param("projectId", project).param("photoId", photoId)
                    .param("studentId", studentId).param("name", name).param("userId", ownerId)
                    .param("adoptedAt", LocalDateTime.of(2026, 10, 6, 10, 0)).update();
        }
        return photos;
    }

    /** 把图片添加进 {@code project} 的相册（从图库添加）。 */
    private void albumAdd(long photoId, long project) {
        jdbc.sql("INSERT INTO photo_project(photo_id, project_id, added_by) VALUES (:photoId, :projectId, :userId)")
                .param("photoId", photoId).param("projectId", project).param("userId", ownerId).update();
    }

    /** {@code count} 张拍摄者为 {@code name} 的图片，{@code project} 为空时是图库里直接上传的。 */
    private List<Long> photos(Long project, String name, String studentId, int count) {
        List<Long> photos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long photoId = base + sequence++;
            jdbc.sql("""
                    INSERT INTO photo(id, project_id, title, photographer_student_id, photographer_name, uploaded_by,
                                      campus_id, taken_at, size, content_type, object_key, stored_file_name, sha256,
                                      status)
                    VALUES (:id, :projectId, '工资图片', :studentId, :name, :userId, :campusId, :takenAt, 100,
                            'image/jpeg', :objectKey, 'salary.jpg', :sha256, 'AVAILABLE')
                    """).param("id", photoId).param("projectId", project).param("studentId", studentId)
                    .param("name", name).param("userId", ownerId).param("campusId", campusId)
                    .param("takenAt", LocalDateTime.of(2026, 10, 1, 9, 0))
                    .param("objectKey", "salary-" + photoId + ".jpg").param("sha256", String.format("%064x", photoId))
                    .update();
            photos.add(photoId);
        }
        return photos;
    }

    private static int headerRow(Sheet sheet) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && "序号".equals(row.getCell(0).getStringCellValue())) return row.getRowNum();
        }
        throw new AssertionError("没有表头");
    }

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        row.forEach(cell -> values.add(cell.getStringCellValue()));
        return values;
    }
}
