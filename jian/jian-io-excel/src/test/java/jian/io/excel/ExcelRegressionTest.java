package jian.io.excel;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ExcelRegressionTest —— Excel 读写回归测试集
// │  Why  : 固化 Excel 读取/写出行为(因为列类型推断、空首行处理、公式注入防护、
// │         NUL/BOM 前缀绕过防护等边界行为一旦回归会破坏 dtype 精度与安全基线,
// │         所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-excel)
// │  When : 每次改动 Excel.java 的类型推断/空行处理/表头写出后
// │  Where: jian-io-excel/src/test/java/jian/io/excel/ExcelRegressionTest.java
// │  How  : 数据走向:手造 DataFrame / POI 手造 xlsx → Excel.write/read → 断言 dtype/列名/值。
class ExcelRegressionTest {

    @TempDir Path tmp;

    // ======================== 公式注入防护(含 NUL/BOM 前缀绕过) ========================

    @Test
    void NUL与BOM前缀公式被防护() throws Exception {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"\u0000=1+1"}, {"\uFEFF=2+2"}, {"\t=3+3"}});
        Path p = tmp.resolve("nul.xlsx");
        Excel.write(df, p.toString()).go();
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(p))) {
            for (int r = 1; r <= 3; r++) {
                String v = wb.getSheetAt(0).getRow(r).getCell(0).getStringCellValue();
                assertThat(v).as("第 %d 行应加 ' 前缀(6 字符跳过集)", r).startsWith("'");
            }
        }
    }

    @Test
    void 表头公式注入加单引号前缀() throws Exception {
        // 列名 "=cmd|calc" 开头 → 表头单元格写 "'=cmd|calc"(POI 层检查前缀 ');
        // 与数据格防护、CSV 表头防护同一口径。
        DataFrame df = DataFrame.of(
                Schema.of("=cmd|calc", DType.STRING, "plain", DType.STRING),
                new Object[][]{{"v1", "v2"}});
        Path p = tmp.resolve("inject.xlsx");
        Excel.write(df, p.toString()).go();
        // POI 直接打开检查表头 cell 值(读回 API 会把 ' 前缀原样带出,POI 层断言更直接)
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(p.toFile(), null, true)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .as("表头 = 开头须加 ' 前缀").isEqualTo("'=cmd|calc");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("plain");
        }
        // 数据格防护不回归
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(p.toFile(), null, true)) {
            assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("v1");
        }
    }

    @Test
    void 表头负号与at符号开头同样防护() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("-1+1", DType.STRING, "@SUM(A1)", DType.STRING),
                new Object[][]{{"a", "b"}});
        Path p = tmp.resolve("inject2.xlsx");
        Excel.write(df, p.toString()).go();
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(p.toFile(), null, true)) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("'-1+1");
            assertThat(wb.getSheetAt(0).getRow(0).getCell(1).getStringCellValue()).isEqualTo("'@SUM(A1)");
        }
    }

    // ======================== 数值列 dtype 精确性 ========================

    @Test
    void 整数列读回LONG_小数列读回DOUBLE_布尔列读回BOOL() throws Exception {
        // 因为非日期的数值单元格必须清除 allDate 标志(否则纯数值列被误判为日期列
        // 走 STRING 路径,整数经"字符串再解析"降级 Integer),所以 qty → LONG(Long 装箱)、
        // price → DOUBLE、vip → BOOL,dtype 逐列精确断言。
        DataFrame df = DataFrame.of(
                Schema.of("qty", DType.LONG, "price", DType.DOUBLE, "vip", DType.BOOL),
                new Object[][]{
                        {10L, 3.14, true},
                        {20L, 9.99, false}
                });
        Path p = tmp.resolve("types.xlsx");
        Excel.write(df, p.toString()).go();
        DataFrame r = Excel.read(p.toString()).go();
        assertThat(r.columnNames()).containsExactly("qty", "price", "vip");
        assertThat(r.dtypes().get(0)).as("纯整数列往返应为 LONG").isEqualTo(DType.LONG);
        assertThat(r.dtypes().get(1)).as("小数列往返应为 DOUBLE").isEqualTo(DType.DOUBLE);
        assertThat(r.dtypes().get(2)).as("布尔列往返应为 BOOL").isEqualTo(DType.BOOL);
        // 值也不经字符串中转:Long 装箱(不是 Integer/Double),NaN 语义不受影响
        assertThat(r.getColumn("qty").get(0)).isInstanceOf(Long.class).isEqualTo(10L);
        assertThat(r.getColumn("price").get(1)).isEqualTo(9.99);
        assertThat(r.getColumn("vip").get(0)).isEqualTo(true);
    }

    @Test
    void 数值单元格直写文件_整数列LONG小数列DOUBLE() throws Exception {
        // 外部 POI 手造文件(不经 jian 写出)同样验证:cellValuePrecise 的 NUMERIC 分支被命中,
        // 整数 → Long 装箱(不经字符串中转解析)。
        Path p = tmp.resolve("raw.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("n");
            h.createCell(1).setCellValue("f");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(85);
            r1.createCell(1).setCellValue(1.5);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.dtypes().get(0)).as("全整值 NUMERIC 列应为 LONG").isEqualTo(DType.LONG);
        assertThat(df.dtypes().get(1)).as("含小数 NUMERIC 列应为 DOUBLE").isEqualTo(DType.DOUBLE);
        assertThat(df.getColumn("n").get(0)).isInstanceOf(Long.class).isEqualTo(85L);
        assertThat(df.getColumn("f").get(0)).isEqualTo(1.5);
    }

    @Test
    void 日期格式列读回STRING的ISO表示() throws Exception {
        // 日期列的行为:allDate 保持 true → STRING(ISO)。
        // POI 手造日期单元格需要日期样式,DateUtil.isCellDateFormatted 才认。
        Path p = tmp.resolve("date.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("d");
            Row r1 = sheet.createRow(1);
            r1.createCell(0);
            r1.getCell(0).setCellStyle(dateStyle);
            r1.getCell(0).setCellValue(java.time.LocalDateTime.of(2026, 1, 15, 9, 30));
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.dtypes().get(0)).as("日期格式列应为 STRING(ISO)").isEqualTo(DType.STRING);
        // 默认转 UTC ISO:2026-01-15T09:30(本地)→ ISO 字符串含日期部分
        assertThat(String.valueOf(df.getStringColumn("d").get(0))).contains("2026-01-15");
    }

    @Test
    void 混合数值文本列读回STRING() throws Exception {
        // 混合列(NUMERIC + STRING):仍走 STRING 路径保留原样表达。
        Path p = tmp.resolve("mixed.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("m");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(123);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("hello");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.dtypes().get(0)).isEqualTo(DType.STRING);
        assertThat(df.getStringColumn("m").get(0)).isEqualTo("123");   // 整数不带 .0
        assertThat(df.getStringColumn("m").get(1)).isEqualTo("hello");
    }

    // ======================== 空首行处理 ========================

    @Test
    void 空首行向下找表头_不抛负数组异常() throws Exception {
        // 因为 row0 "已创建但无单元格"时 getLastCellNum() 返回 -1(直接以其建数组会抛
        // NegativeArraySizeException),所以向下找 row1 作表头。
        Path p = tmp.resolve("emptyfirst.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            sheet.createRow(0);   // 空首行(创建但无 cell)
            Row h = sheet.createRow(1);
            h.createCell(0).setCellValue("id");
            h.createCell(1).setCellValue("name");
            Row r1 = sheet.createRow(2);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("alice");
            Row r2 = sheet.createRow(3);
            r2.createCell(0).setCellValue(2);
            r2.createCell(1).setCellValue("bob");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.columnNames()).containsExactly("id", "name");
        assertThat(df.rowCount()).isEqualTo(2);
        assertThat(df.dtypes().get(0)).isEqualTo(DType.LONG);   // 整数列 LONG
        assertThat(df.getStringColumn("name").get(1)).isEqualTo("bob");
    }

    @Test
    void 全空sheet与只有空首行都按空表处理() throws Exception {
        // 整表无任何有内容的行 → 0 列空 DataFrame,不抛异常。
        Path p = tmp.resolve("allblank.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            sheet.createRow(0);   // 只有空首行
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isEqualTo(0);
        assertThat(df.columnCount()).isEqualTo(0);
    }
}
