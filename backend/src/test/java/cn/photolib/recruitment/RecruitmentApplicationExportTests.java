package cn.photolib.recruitment;

import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 报名导出表的排版规则：列怎么来、空答案怎么写、文件名怎么消毒。 */
class RecruitmentApplicationExportTests {

    @Test
    void columnsUnionTaskFormWithEachApplicationFrozenFormSoNoAnswerIsDropped() throws IOException {
        RecruitmentFormSchema taskSchema = new RecruitmentFormSchema(List.of(
                field("skills", RecruitmentFieldType.MULTIPLE_CHOICE, "擅长方向"),
                field("motto", RecruitmentFieldType.SHORT_TEXT, "一句话介绍")));
        // 这份报名是表单改动之前提交的，它自带的题目必须补成额外的列。
        RecruitmentFormSchema frozenSchema = new RecruitmentFormSchema(List.of(
                field("skills", RecruitmentFieldType.MULTIPLE_CHOICE, "擅长方向"),
                field("retired", RecruitmentFieldType.SHORT_TEXT, "已删掉的老题目")));

        List<List<String>> rows = sheetRows(RecruitmentApplicationExport.workbook(taskSchema, List.of(
                new RecruitmentApplicationExport.Entry("2023001", LocalDateTime.of(2026, 8, 25, 9, 30, 0),
                        2, taskSchema, Map.of("skills", List.of("人像", "风光"), "motto", "记录校园")),
                new RecruitmentApplicationExport.Entry("2023002", LocalDateTime.of(2026, 8, 24, 18, 5, 7),
                        0, frozenSchema, Map.of("retired", "老答案")))));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0))
                .containsExactly("学号", "提交时间", "附件数量", "擅长方向", "一句话介绍", "已删掉的老题目");
        assertThat(rows.get(1))
                .containsExactly("2023001", "2026-08-25 09:30:00", "2", "人像，风光", "记录校园", "");
        assertThat(rows.get(2))
                .containsExactly("2023002", "2026-08-24 18:05:07", "0", "", "", "老答案");
    }

    @Test
    void emptyAndNullAnswersStayEmptyCellsAndFormulaLookalikesAreNeutralized() throws IOException {
        RecruitmentFormSchema schema = new RecruitmentFormSchema(List.of(
                field("skills", RecruitmentFieldType.MULTIPLE_CHOICE, "擅长方向"),
                field("note", RecruitmentFieldType.LONG_TEXT, "备注"),
                field("blank", RecruitmentFieldType.SHORT_TEXT, "没人填的题")));
        Map<String, Object> answers = new HashMap<>();
        answers.put("skills", Arrays.asList("人像", null, ""));
        answers.put("note", "=1+1");
        answers.put("blank", null);

        List<List<String>> rows = sheetRows(RecruitmentApplicationExport.workbook(schema, List.of(
                new RecruitmentApplicationExport.Entry("=2023003", null, 0, schema, answers))));

        // 学号和答案里 = 开头的文本都加了前导单引号，Excel 打开时不会当公式执行。
        assertThat(rows.get(1)).containsExactly("'=2023003", "", "0", "人像", "'=1+1", "");
    }

    @Test
    void fileNameCarriesTitleAndExportDateAndDropsCharactersFileSystemsReject() {
        assertThat(RecruitmentApplicationExport.fileName("2026秋季摄影部招新", LocalDate.of(2026, 8, 25)))
                .isEqualTo("2026秋季摄影部招新-报名-2026-08-25.xlsx");
        assertThat(RecruitmentApplicationExport.fileName("春季/招新\t\"第一批\"", LocalDate.of(2026, 3, 1)))
                .isEqualTo("春季 招新 第一批-报名-2026-03-01.xlsx");
        assertThat(RecruitmentApplicationExport.fileName("   ", LocalDate.of(2026, 3, 1)))
                .isEqualTo("未命名招募-报名-2026-03-01.xlsx");
        assertThat(RecruitmentApplicationExport.fileName("招".repeat(200), LocalDate.of(2026, 3, 1)))
                .isEqualTo("招".repeat(80) + "-报名-2026-03-01.xlsx");
    }

    @Test
    void largeApplicationVolumeKeepsEveryRowAndItsOrder() throws IOException {
        RecruitmentFormSchema schema = new RecruitmentFormSchema(List.of(
                field("motto", RecruitmentFieldType.SHORT_TEXT, "一句话介绍")));
        List<RecruitmentApplicationExport.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            entries.add(new RecruitmentApplicationExport.Entry("2023" + index,
                    LocalDateTime.of(2026, 8, 25, 9, 0, 0), index % 3, schema,
                    Map.of("motto", "第 " + index + " 份报名")));
        }

        List<List<String>> rows = sheetRows(RecruitmentApplicationExport.workbook(schema, entries));

        assertThat(rows).hasSize(2_001);
        assertThat(rows.get(1)).containsExactly("20230", "2026-08-25 09:00:00", "0", "第 0 份报名");
        assertThat(rows.get(2_000))
                .containsExactly("20231999", "2026-08-25 09:00:00", "1", "第 1999 份报名");
    }

    private static RecruitmentFormSchema.Field field(String id, RecruitmentFieldType type, String label) {
        return new RecruitmentFormSchema.Field(id, type, label, null, null, false,
                type == RecruitmentFieldType.MULTIPLE_CHOICE ? List.of("人像", "风光", "后期") : List.of());
    }

    /** 每行读成定长的字符串列表（按表头宽度补空），空单元格就是空串。 */
    private static List<List<String>> sheetRows(byte[] content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            int width = sheet.getRow(0).getLastCellNum();
            List<List<String>> rows = new ArrayList<>();
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                List<String> values = new ArrayList<>(width);
                for (int column = 0; column < width; column++) {
                    values.add(row == null ? "" : cellText(row.getCell(column)));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BLANK -> "";
            default -> cell.getStringCellValue();
        };
    }
}
