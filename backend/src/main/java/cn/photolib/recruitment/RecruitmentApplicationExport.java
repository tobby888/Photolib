package cn.photolib.recruitment;

import cn.photolib.common.util.SpreadsheetText;
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
 * 把一次招募收到的报名摊平成一张能直接打印、带到面试现场核对的表格。
 *
 * <p>列由题目 id 决定而不是题干文字：同一个招募里可能有两道题干重复的题，
 * 而每份报名冻结了自己提交那一刻的表单结构，任务后来改过题目时两者会不一致。
 * 因此列集合取“任务当前表单 + 各份报名自带表单”的并集，任何一份报名的答案
 * 都不会因为表单变动而被悄悄丢掉。
 */
final class RecruitmentApplicationExport {
    static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** 多选答案在一个单元格里的分隔符。 */
    static final String MULTI_VALUE_SEPARATOR = "，";
    private static final List<String> FIXED_HEADERS = List.of("学号", "提交时间", "附件数量");
    private static final DateTimeFormatter SUBMITTED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SHEET_NAME = "报名";
    /** Excel 文件名和 Windows 路径都吃不下这些字符，招募标题里出现就换成空格。 */
    private static final String UNSAFE_FILE_NAME_CHARS = "[\\\\/:*?\"<>|\\p{Cntrl}]";
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int COLUMN_WIDTH = 22 * 256;

    private RecruitmentApplicationExport() {
    }

    /** 一份报名在导出表里的一行；{@code schema} 是这份报名提交时冻结的表单。 */
    record Entry(String studentId, LocalDateTime submittedAt, int attachmentCount,
                 RecruitmentFormSchema schema, Map<String, Object> answers) {
        Entry {
            // Map.copyOf 不接受 null 值，而库里存的答案 JSON 里 null 是合法的“没填”。
            answers = answers == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }
    }

    static byte[] workbook(RecruitmentFormSchema taskSchema, List<Entry> entries) {
        Map<String, String> questionColumns = questionColumns(taskSchema, entries);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);
            CellStyle headerStyle = headerStyle(workbook);
            int column = 0;
            for (String label : FIXED_HEADERS) header(header, headerStyle, sheet, column++, label);
            for (String label : questionColumns.values()) header(header, headerStyle, sheet, column++, label);
            // 表头固定，几百行报名滚动核对时不用来回找列。
            sheet.createFreezePane(0, 1);

            int rowIndex = 1;
            for (Entry entry : entries) {
                Row row = sheet.createRow(rowIndex++);
                text(row, 0, entry.studentId());
                text(row, 1, entry.submittedAt() == null ? "" : SUBMITTED_AT.format(entry.submittedAt()));
                row.createCell(2).setCellValue(entry.attachmentCount());
                int answerColumn = FIXED_HEADERS.size();
                for (String fieldId : questionColumns.keySet()) {
                    text(row, answerColumn++, answerText(entry.answers().get(fieldId)));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("生成报名导出表失败", exception);
        }
    }

    static String fileName(String taskTitle, LocalDate exportedOn) {
        String safeTitle = taskTitle == null ? "" : taskTitle
                .replaceAll(UNSAFE_FILE_NAME_CHARS, " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (safeTitle.length() > MAX_TITLE_LENGTH) safeTitle = safeTitle.substring(0, MAX_TITLE_LENGTH).trim();
        if (safeTitle.isEmpty()) safeTitle = "未命名招募";
        return safeTitle + "-报名-" + exportedOn + ".xlsx";
    }

    /** 题目 id -> 列标题（题干），按任务当前表单、再按各份报名的顺序去重。 */
    private static Map<String, String> questionColumns(RecruitmentFormSchema taskSchema,
                                                       List<Entry> entries) {
        Map<String, String> columns = new LinkedHashMap<>();
        collect(columns, taskSchema);
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

    /** 空答案写成空单元格；多选答案在一个格子里用逗号连起来。 */
    private static String answerText(Object answer) {
        if (answer == null) return "";
        if (answer instanceof List<?> values) {
            return values.stream()
                    .filter(value -> value != null && !String.valueOf(value).isEmpty())
                    .map(String::valueOf)
                    .reduce((left, right) -> left + MULTI_VALUE_SEPARATOR + right)
                    .orElse("");
        }
        return String.valueOf(answer);
    }

    private static void header(Row row, CellStyle style, Sheet sheet, int column, String label) {
        // 表头一律建格：题干允许重复，但空列会让面试表少一列，读的人根本发现不了。
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
