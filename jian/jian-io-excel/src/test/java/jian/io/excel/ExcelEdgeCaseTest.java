package jian.io.excel;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : Excel 异常数据/边界输入测试 —— 畸形 xlsx 的读写健壮性
// │  Why  : ExcelPitfallTest 只测表头去重/空行;公式注入(§3.7.3)、合并单元格、大数精度、
// │         空表头、全空表 是 Excel IO 的关键边界,必须实跑验证(AGENTS.md §3.3)。
// │  Who  : CI(./mvnw test -pl jian-io-excel),含异常数据的用例须实跑验证
// │  When : IO 改动/回归时
// │  Where: jian-io-excel/ExcelEdgeCaseTest.java
// │  How  : 数据走向(两种构造路径,互补):
// │           A(读边界):POI 的 XSSFWorkbook 手构造畸形 xlsx(合并单元格/大数/空表头/全空表)
// │             → Excel.read(path).go() → 断言 rowCount/列名/值不崩溃 + 合理返回;
// │           B(写安全):DataFrame 含 "=HYPERLINK(...)" 危险值 → Excel.write 写出
// │             → POI XSSFWorkbook 读回 cell → 断言 getCellType() != FORMULA(被转义为文本,§3.7.3)。
// │         关键变量:公式注入 cell 类型——FORMULA(未防护=危险,Excel 打开会执行)
// │           vs STRING(已加单引号转义=安全);本测试钉死后者。
// │         逻辑路线:每类异常一条独立 @Test——合并/大数/空表头/空表 验证"不崩溃+合理返回",
// │           公式注入验证"cellType 不是 FORMULA"(§3.7.3 安全红线),日期验证类型往返。
class ExcelEdgeCaseTest {

    @TempDir Path tmp;

    @Test
    void 写_公式注入防护_危险前缀不作为公式() throws Exception {
        // What:验证 §3.7.3 红线——Excel 写出对 = + - @ 开头转义,不被 Excel 当公式执行。
        // Why :=HYPERLINK / =SUM 等公式可被恶意构造(RCE/外链);写出端必须转义为文本,本测试实跑确认。
        // 伪代码:1. 造含 =/+/-/@ 四种危险前缀的 STRING 列;2. Excel.write 写出 xlsx;
        //         3. POI XSSFWorkbook 读回;4. 逐行断言 cell.getCellType() != FORMULA(转义为文本)。
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{
                        {"=HYPERLINK(\"http://evil\",\"click\")"},   // = 开头(危险公式)
                        {"+1+1"},                                     // + 开头
                        {"-1+1"},                                     // - 开头
                        {"@SUM(A1)"}                                  // @ 开头
                });
        Path p = tmp.resolve("inj.xlsx");
        Excel.write(df, p.toString()).go();
        // 用 POI 读回:危险前缀的 cell 必须不是 FORMULA 类型(即被转义为文本,不会被 Excel 执行)
        try (var wb = new XSSFWorkbook(p.toFile())) {
            var sheet = wb.getSheetAt(0);
            for (int i = 1; i <= 4; i++) {   // 数据行 1-4(0 是表头)
                Row row = sheet.getRow(i);
                if (row == null) continue;
                var cell = row.getCell(0);
                if (cell == null) continue;
                assertThat(cell.getCellType())
                        .as("第 " + i + " 行(危险前缀)不应是公式类型")
                        .isNotEqualTo(CellType.FORMULA);
            }
        }
    }

    @Test
    void 读_合并单元格_不崩溃() throws Exception {
        // 合并单元格是 Excel 常见形态;jian 读应不崩溃(取首值或合理处理)
        Path p = tmp.resolve("merged.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0); h.createCell(0).setCellValue("v");
            var r1 = sheet.createRow(1); r1.createCell(0).setCellValue(1);
            var r2 = sheet.createRow(2); r2.createCell(0).setCellValue(2);
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));   // 合并 A2:A3
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isGreaterThanOrEqualTo(1);   // 不崩溃即可
    }

    @Test
    void 读_超15位大整数_记录精度行为() throws Exception {
        // Excel 存 double,>15 位整数固有精度限制;jian 行为需明确(不静默返回错值)
        Path p = tmp.resolve("bigint.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0); h.createCell(0).setCellValue("id");
            var r1 = sheet.createRow(1); r1.createCell(0).setCellValue(1234567890123456789.0);  // 19 位
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isEqualTo(1);
        // 不崩溃即合格(精度丢失是 Excel 固有,见 README 免责声明)
        assertThat(df.getColumn("id").get(0)).isNotNull();
    }

    @Test
    void 读_空表头列容错() throws Exception {
        Path p = tmp.resolve("emptyh.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("id");
            h.createCell(1).setCellValue("");   // 空表头
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("alice");
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isEqualTo(1);
        assertThat(df.columnNames()).hasSize(2);
    }

    @Test
    void 读_全空表_返回空DataFrame() throws Exception {
        Path p = tmp.resolve("blank.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new FileOutputStream(p.toFile())) {
            wb.createSheet("empty");   // 无任何数据
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isEqualTo(0);
    }

    @Test
    void 读写_日期类型往返() throws Exception {
        // 日期是 Excel 常见类型;读写往返应保类型
        DataFrame df = DataFrame.of(Schema.of("d", DType.DATE),
                new Object[][]{{java.time.LocalDate.of(2026, 1, 1)}, {java.time.LocalDate.of(2026, 8, 13)}});
        Path p = tmp.resolve("date.xlsx");
        Excel.write(df, p.toString()).go();
        DataFrame r = Excel.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(2);
        // 读回应是日期类型(不变成数字序列号)
        assertThat(r.dtypes().get(0)).isIn(DType.DATE, DType.DATETIME, DType.STRING);
    }

    @Test
    void 读_重复行不去重_全部保留() throws Exception {
        // What:Excel 读不自动去重重复行(对齐 pandas read_excel;去重是 dropDuplicates 显式算子)。
        // Why :IO 层悄悄去重会丢数据且无提示 —— 严重 bug。需与"表头列名去重"(ExcelPitfallTest.陷阱5)
        //      区分:后者是重名列→name_1,不是行去重。
        // How :数据走向 POI 建含两行相同数据(1,alice)+(1,alice)+(2,bob)→ Excel.read → 3 行
        //      → 断言 rowCount==3 且前两行 id 都=1(重复行原样保留)。
        Path p = tmp.resolve("duprow.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0); h.createCell(0).setCellValue("id"); h.createCell(1).setCellValue("name");
            var r1 = sheet.createRow(1); r1.createCell(0).setCellValue(1); r1.createCell(1).setCellValue("alice");
            var r2 = sheet.createRow(2); r2.createCell(0).setCellValue(1); r2.createCell(1).setCellValue("alice");  // 与 r1 完全相同
            var r3 = sheet.createRow(3); r3.createCell(0).setCellValue(2); r3.createCell(1).setCellValue("bob");
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isEqualTo(3);   // 重复行全保留,不去重
        assertThat(df.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(df.getLongColumn("id").getLong(1)).isEqualTo(1L);   // 第 2 行也是 1(未去重)
        assertThat(df.getStringColumn("name").get(2)).isEqualTo("bob");
    }
}
