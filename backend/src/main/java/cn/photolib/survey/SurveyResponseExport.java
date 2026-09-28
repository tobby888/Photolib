package cn.photolib.survey;

import cn.photolib.common.util.SpreadsheetText;
import cn.photolib.form.FormAnswerText;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一份问卷的结果写成 Excel：
 * <ol>
 *   <li>「答卷」：一人一行，题目一题一列；</li>
 *   <li>「未提交」：发放名单里还没交的人，方便催交；</li>
 *   <li>「选择题统计」：每个选项被选的次数和占比。</li>
 * </ol>
 *
 * <p>列按题目 id 而不是题干取并集（问卷当前题目 + 各份答卷冻结的题目），理由和招募导出相同：
 * 题干可以重复，而任何一份答卷的答案都不能因为表单结构变过而被悄悄丢掉。
 */
final class SurveyResponseExport {
    static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String MULTI_VALUE_SEPARATOR = "，";
    private static final List<String> FIXED_HEADERS = List.of("姓名", "账号", "权限组", "提交时间");
    private static final DateTimeFormatter SUBMITTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String UNSAFE_FILE_NAME_CHARS = "[\\\\/:*?\"<>|\\p{Cntrl}]";
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int COLUMN_WIDTH = 22 * 256;

    private SurveyResponseExport() {
    }

    record Entry(String displayName, String username, String permissionGroupName, LocalDateTime submittedAt,
                 RecruitmentFormSchema schema, Map<String, Object> answers) {
        Entry {
            // Map.copyOf 不接受 null 值，而答卷 JSON 里 null 是合法的「没填」。
            answers = answers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }
    }

    record Pending(String displayName, String username, String permissionGroupName, String campuses) {
    }

    static byte[] workbook(RecruitmentFormSchema surveySchema, List<Entry> entries, List<Pending> pending,
                           SurveyResponseService.Summary summary) {
        Map<String, String> questionColumns = questionColumns(surveySchema, entries);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);

            Sheet responses = workbook.createSheet("答卷");
            Row header = responses.createRow(0);
            int column = 0;
            for (String label : FIXED_HEADERS) header(header, headerStyle, responses, column++, label);
            for (String label : questionColumns.values()) header(header, headerStyle, responses, column++, label);
            responses.createFreezePane(0, 1);
            int rowIndex = 1;
            for (Entry entry : entries) {
                Row row = responses.createRow(rowIndex++);
                text(row, 0, entry.displayName());
                text(row, 1, entry.username());
                text(row, 2, entry.permissionGroupName());
                text(row, 3, entry.submittedAt() == null ? "" : SUBMITTED_AT.format(entry.submittedAt()));
                int answerColumn = FIXED_HEADERS.size();
                for (String fieldId : questionColumns.keySet()) {
                    text(row, answerColumn++, FormAnswerText.of(entry.answers().get(fieldId), MULTI_VALUE_SEPARATOR));
                }
            }

            Sheet waiting = workbook.createSheet("未提交");
            Row waitingHeader = waiting.createRow(0);
            List<String> waitingHeaders = List.of("姓名", "账号", "权限组", "校区");
            for (int index = 0; index < waitingHeaders.size(); index++) {
                header(waitingHeader, headerStyle, waiting, index, waitingHeaders.get(index));
            }
            waiting.createFreezePane(0, 1);
            rowIndex = 1;
            for (Pending person : pending) {
                Row row = waiting.createRow(rowIndex++);
                text(row, 0, person.displayName());
                text(row, 1, person.username());
                text(row, 2, person.permissionGroupName());
                text(row, 3, person.campuses());
            }

            Sheet stats = workbook.createSheet("选择题统计");
            Row statsHeader = stats.createRow(0);
            List<String> statsHeaders = List.of("题目", "选项", "人次", "占答题人数");
            for (int index = 0; index < statsHeaders.size(); index++) {
                header(statsHeader, headerStyle, stats, index, statsHeaders.get(index));
            }
            stats.createFreezePane(0, 1);
            rowIndex = 1;
            for (SurveyResponseService.FieldSummary field : summary.fields()) {
                for (SurveyResponseService.OptionCount option : field.options()) {
                    Row row = stats.createRow(rowIndex++);
                    text(row, 0, field.label());
                    text(row, 1, option.option());
                    row.createCell(2).setCellValue(option.count());
                    text(row, 3, percent(option.count(), field.answeredCount()));
                }
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("生成问卷导出表失败", exception);
        }
    }

    static String fileName(String title, LocalDate exportedOn) {
        String safeTitle = title == null ? "" : title
                .replaceAll(UNSAFE_FILE_NAME_CHARS, " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (safeTitle.length() > MAX_TITLE_LENGTH) safeTitle = safeTitle.substring(0, MAX_TITLE_LENGTH).trim();
        if (safeTitle.isEmpty()) safeTitle = "未命名问卷";
        return safeTitle + "-问卷结果-" + exportedOn + ".xlsx";
    }

    static String percent(long count, long total) {
        if (total <= 0) return "0%";
        return String.format(java.util.Locale.ROOT, "%.1f%%", count * 100.0 / total);
    }

    private static Map<String, String> questionColumns(RecruitmentFormSchema schema, List<Entry> entries) {
        Map<String, String> columns = new LinkedHashMap<>();
        collect(columns, schema);
        for (Entry entry : entries) collect(columns, entry.schema());
        return columns;
    }

    private static void collect(Map<String, String> columns, RecruitmentFormSchema schema) {
        if (schema == null) return;
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            if (field == null || field.id() == null) continue;
            columns.putIfAbsent(field.id(), field.label() == null ? field.id() : field.label());
        }
    }

    private static void header(Row row, CellStyle style, Sheet sheet, int column, String label) {
        Cell cell = row.createCell(column);
        cell.setCellValue(SpreadsheetText.safe(label));
        cell.setCellStyle(style);
        sheet.setColumnWidth(column, COLUMN_WIDTH);
    }

    private static void text(Row row, int column, String value) {
        if (value == null || value.isEmpty()) return;
        row.createCell(column).setCellValue(SpreadsheetText.safe(value));
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
