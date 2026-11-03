package cn.photolib.salary;

import cn.photolib.common.util.SpreadsheetText;
import cn.photolib.salary.SalaryCalculationService.Basis;
import cn.photolib.salary.SalaryCalculationService.CalculationSummary;
import cn.photolib.salary.SalaryCalculationService.Detail;
import cn.photolib.salary.SalaryCalculationService.Line;
import cn.photolib.salary.SalaryCalculationService.MemberTotal;
import cn.photolib.salary.SalaryCalculationService.ProjectGroup;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工资计算导出表：第一张「汇总」按成员合计，后面每个选题一张 sheet，列出参与的摄影师的工资计算明细。
 * 金额和工时写数值（不写公式）：有的表格预览不重算公式，公式格会显示成 0。
 */
final class SalaryWorkbook {
    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String SUMMARY_SHEET = "汇总";
    /** Excel 的 sheet 名最长 31 个字符，且不能含这些字符。 */
    private static final int MAX_SHEET_NAME = 31;
    private static final String UNSAFE_SHEET_CHARS = "[\\\\/?*:\\[\\]\\p{Cntrl}]";
    private static final String UNSAFE_FILE_NAME_CHARS = "[\\\\/:*?\"<>|\\p{Cntrl}]";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final List<String> PROJECT_HEADERS = List.of("序号", "姓名", "学号", "校区", "拍摄（小时）", "修图（小时）",
            "已确认工时（小时）", "工时工资（元）", "被引张数", "被引工资（元）", "应发工资（元）", "计算依据", "待审核工时（条）");
    static final List<String> SUMMARY_HEADERS = List.of("序号", "姓名", "学号", "校区", "参与选题数", "已确认工时（小时）",
            "被引张数", "工时工资合计（元）", "被引工资合计（元）", "应发工资合计（元）", "待审核工时（条）");

    private SalaryWorkbook() {
    }

    static String fileName(CalculationSummary calculation) {
        return ("工资计算-" + calculation.from() + "至" + calculation.to() + ".xlsx").replaceAll(UNSAFE_FILE_NAME_CHARS, " ");
    }

    static String rule(CalculationSummary calculation) {
        return "每位成员在每个选题的工资 = max(已确认工时（小时）× " + plain(calculation.hourlyRate()) + " 元，被引张数 × "
                + plain(calculation.adoptionRate()) + " 元)。只算选题结束时间在 " + calculation.from() + " 至 "
                + calculation.to() + "（两端都含）的选题；待审核的工时不计入。";
    }

    static byte[] write(Detail detail) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            CalculationSummary calculation = detail.calculation();
            Set<String> usedNames = new HashSet<>();
            usedNames.add(SUMMARY_SHEET);
            summarySheet(workbook.createSheet(SUMMARY_SHEET), styles, calculation, detail);
            int index = 1;
            for (ProjectGroup project : detail.projects()) {
                String name = sheetName(index++, project.projectTitle(), usedNames);
                projectSheet(workbook.createSheet(name), styles, calculation, project);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("生成工资计算表失败", exception);
        }
    }

    /** 「序号 标题」，去掉 Excel 不认的字符、截到 31 个字符；撞名（截断后相同）时再加后缀。 */
    static String sheetName(int index, String title, Set<String> used) {
        String clean = (title == null ? "" : title).replaceAll(UNSAFE_SHEET_CHARS, " ").replaceAll("\\s+", " ").trim();
        // 开头结尾的单引号 Excel 不接受。
        clean = clean.replaceAll("^'+|'+$", "").trim();
        String prefix = String.format("%02d ", index);
        String base = prefix + (clean.isEmpty() ? "未命名选题" : clean);
        String name = truncate(base, MAX_SHEET_NAME);
        int suffix = 2;
        while (!used.add(name.toLowerCase())) {
            String tail = "(" + suffix++ + ")";
            name = truncate(base, MAX_SHEET_NAME - tail.length()) + tail;
        }
        return name;
    }

    /** 按 code point 截断，避免把代理对（生僻字、表情）劈成两半。 */
    private static String truncate(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max)).trim();
    }

    private static void summarySheet(Sheet sheet, Styles styles, CalculationSummary calculation, Detail detail) {
        int row = title(sheet, styles, "工资计算汇总（" + calculation.from() + " 至 " + calculation.to() + "）",
                calculation, SUMMARY_HEADERS.size());
        info(sheet, styles, row++, "选题数", calculation.projectCount() + " 个", SUMMARY_HEADERS.size());
        info(sheet, styles, row++, "成员数", calculation.memberCount() + " 人", SUMMARY_HEADERS.size());
        info(sheet, styles, row++, "应发合计", plain(calculation.totalAmount()) + " 元", SUMMARY_HEADERS.size());
        if (calculation.pendingWorklogCount() > 0) {
            info(sheet, styles, row++, "提醒", "计算时还有 " + calculation.pendingWorklogCount()
                    + " 条待审核工时没有计入，审核完后可以重新计算。", SUMMARY_HEADERS.size());
        }
        row++;
        int headerRow = row;
        header(sheet, styles, row++, SUMMARY_HEADERS);
        int index = 1;
        for (MemberTotal member : detail.members()) {
            Row r = sheet.createRow(row++);
            number(r, 0, index++, styles.integer);
            text(r, 1, member.memberName(), styles.text);
            text(r, 2, member.studentId(), styles.text);
            text(r, 3, member.campus(), styles.text);
            number(r, 4, member.projectCount(), styles.integer);
            number(r, 5, hours(member.confirmedMinutes()), styles.decimal);
            number(r, 6, member.adoptedPhotos(), styles.integer);
            money(r, 7, member.hourAmount(), styles.money);
            money(r, 8, member.adoptionAmount(), styles.money);
            money(r, 9, member.salaryAmount(), styles.moneyBold);
            number(r, 10, member.pendingWorklogCount(), styles.integer);
        }
        Row total = sheet.createRow(row);
        text(total, 1, "合计", styles.bold);
        number(total, 4, calculation.projectCount(), styles.integerBold);
        number(total, 5, hours(detail.members().stream().mapToInt(MemberTotal::confirmedMinutes).sum()), styles.decimalBold);
        number(total, 6, detail.members().stream().mapToInt(MemberTotal::adoptedPhotos).sum(), styles.integerBold);
        money(total, 7, detail.members().stream().map(MemberTotal::hourAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                styles.moneyBold);
        money(total, 8, detail.members().stream().map(MemberTotal::adoptionAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                styles.moneyBold);
        money(total, 9, calculation.totalAmount(), styles.moneyBold);
        number(total, 10, calculation.pendingWorklogCount(), styles.integerBold);
        finish(sheet, headerRow, SUMMARY_HEADERS.size());
    }

    private static void projectSheet(Sheet sheet, Styles styles, CalculationSummary calculation, ProjectGroup project) {
        int row = title(sheet, styles, project.projectTitle(), calculation, PROJECT_HEADERS.size());
        info(sheet, styles, row++, "选题结束时间",
                project.completedAt() == null ? "" : DATE_TIME.format(project.completedAt()), PROJECT_HEADERS.size());
        info(sheet, styles, row++, "参与人数", project.lines().size() + " 人", PROJECT_HEADERS.size());
        info(sheet, styles, row++, "本选题应发合计", plain(project.salaryAmount()) + " 元", PROJECT_HEADERS.size());
        row++;
        int headerRow = row;
        header(sheet, styles, row++, PROJECT_HEADERS);
        int index = 1;
        for (Line line : project.lines()) {
            Row r = sheet.createRow(row++);
            number(r, 0, index++, styles.integer);
            text(r, 1, line.memberName(), styles.text);
            text(r, 2, line.studentId(), styles.text);
            text(r, 3, line.campus(), styles.text);
            number(r, 4, hours(line.shootingMinutes()), styles.decimal);
            number(r, 5, hours(line.retouchingMinutes()), styles.decimal);
            number(r, 6, hours(line.confirmedMinutes()), styles.decimal);
            money(r, 7, line.hourAmount(), styles.money);
            number(r, 8, line.adoptedPhotos(), styles.integer);
            money(r, 9, line.adoptionAmount(), styles.money);
            money(r, 10, line.salaryAmount(), styles.moneyBold);
            text(r, 11, basisLabel(line.basis()), styles.text);
            number(r, 12, line.pendingWorklogCount(), styles.integer);
        }
        Row total = sheet.createRow(row);
        text(total, 1, "合计", styles.bold);
        number(total, 4, hours(project.lines().stream().mapToInt(Line::shootingMinutes).sum()), styles.decimalBold);
        number(total, 5, hours(project.lines().stream().mapToInt(Line::retouchingMinutes).sum()), styles.decimalBold);
        number(total, 6, hours(project.confirmedMinutes()), styles.decimalBold);
        money(total, 7, project.lines().stream().map(Line::hourAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                styles.moneyBold);
        number(total, 8, project.adoptedPhotos(), styles.integerBold);
        money(total, 9, project.lines().stream().map(Line::adoptionAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                styles.moneyBold);
        money(total, 10, project.salaryAmount(), styles.moneyBold);
        number(total, 12, project.pendingWorklogCount(), styles.integerBold);
        finish(sheet, headerRow, PROJECT_HEADERS.size());
    }

    static String basisLabel(Basis basis) {
        return switch (basis) {
            case HOURS -> "按工时";
            case ADOPTIONS -> "按被引";
            case EQUAL -> "两者相同";
            case NONE -> "无已确认记录";
        };
    }

    /** 标题、计算规则、计算人，返回下一行的行号。 */
    private static int title(Sheet sheet, Styles styles, String title, CalculationSummary calculation, int width) {
        Row titleRow = sheet.createRow(0);
        text(titleRow, 0, title, styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, width - 1));
        info(sheet, styles, 1, "计算规则", rule(calculation), width);
        info(sheet, styles, 2, "计算时间", DATE_TIME.format(calculation.createdAt()) + "（" + calculation.createdByName()
                + "）", width);
        return 3;
    }

    private static void info(Sheet sheet, Styles styles, int rowIndex, String label, String value, int width) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, label, styles.bold);
        text(row, 1, value, styles.text);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 1, width - 1));
    }

    private static void header(Sheet sheet, Styles styles, int rowIndex, List<String> headers) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < headers.size(); i++) text(row, i, headers.get(i), styles.header);
    }

    private static void finish(Sheet sheet, int headerRow, int width) {
        sheet.createFreezePane(0, headerRow + 1);
        sheet.setColumnWidth(0, 6 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 16 * 256);
        sheet.setColumnWidth(3, 18 * 256);
        for (int i = 4; i < width; i++) sheet.setColumnWidth(i, 16 * 256);
    }

    private static double hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        if (value != null && !value.isEmpty()) cell.setCellValue(SpreadsheetText.safe(value));
    }

    private static void number(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void money(Row row, int column, BigDecimal value, CellStyle style) {
        number(row, column, value == null ? 0 : value.doubleValue(), style);
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle text;
        final CellStyle bold;
        final CellStyle integer;
        final CellStyle integerBold;
        final CellStyle decimal;
        final CellStyle decimalBold;
        final CellStyle money;
        final CellStyle moneyBold;

        Styles(XSSFWorkbook workbook) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            short integerFormat = workbook.createDataFormat().getFormat("0");
            short decimalFormat = workbook.createDataFormat().getFormat("0.00");
            short moneyFormat = workbook.createDataFormat().getFormat("#,##0.00");

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            header = workbook.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setWrapText(true);
            text = workbook.createCellStyle();
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            integer = workbook.createCellStyle();
            integer.setDataFormat(integerFormat);
            integerBold = workbook.createCellStyle();
            integerBold.setDataFormat(integerFormat);
            integerBold.setFont(boldFont);
            decimal = workbook.createCellStyle();
            decimal.setDataFormat(decimalFormat);
            decimalBold = workbook.createCellStyle();
            decimalBold.setDataFormat(decimalFormat);
            decimalBold.setFont(boldFont);
            money = workbook.createCellStyle();
            money.setDataFormat(moneyFormat);
            moneyBold = workbook.createCellStyle();
            moneyBold.setDataFormat(moneyFormat);
            moneyBold.setFont(boldFont);
        }
    }
}
