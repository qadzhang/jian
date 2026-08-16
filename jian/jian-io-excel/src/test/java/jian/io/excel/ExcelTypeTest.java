package jian.io.excel;

import jian.core.DataFrame;
import org.apache.poi.ss.usermodel.Row;
import jian.core.DType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelTypeTest {

    @TempDir Path tmp;

    @Test
    void 手机号保留完整数字不丢精度() throws Exception {
        // 手动构造 xlsx:phone 列写入大整数(13800000000)
        Path p = tmp.resolve("phone.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("phone");
            h.createCell(1).setCellValue("name");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(13800000000L);  // Excel 存 NUMERIC
            r1.createCell(1).setCellValue("alice");
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(15912345678L);
            r2.createCell(1).setCellValue("bob");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.rowCount()).isEqualTo(2);
        // 整数列 → LONG,手机号完整保留
        assertThat(df.getColumn("phone").get(0)).isEqualTo(13800000000L);
        assertThat(df.getColumn("phone").get(1)).isEqualTo(15912345678L);
    }

    @Test
    void 混合类型列转STRING保留原样() throws Exception {
        // 一列里既有数字又有文本 → 该列转 STRING
        Path p = tmp.resolve("mixed.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("mixed");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(123);     // NUMERIC
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("hello"); // STRING
            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue(456);     // NUMERIC
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.dtypes().get(0)).isEqualTo(DType.STRING);  // 混合 → STRING
        // 123 → "123"(不是 123.0)
        assertThat(df.getStringColumn("mixed").get(0)).isEqualTo("123");
        assertThat(df.getStringColumn("mixed").get(1)).isEqualTo("hello");
        assertThat(df.getStringColumn("mixed").get(2)).isEqualTo("456");
    }

    @Test
    void 整数列转LONG小数列转DOUBLE() throws Exception {
        Path p = tmp.resolve("types.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("qty");     // 整数列
            h.createCell(1).setCellValue("price");   // 小数列
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(10);
            r1.createCell(1).setCellValue(3.14);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(20);
            r2.createCell(1).setCellValue(9.99);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        // 因为 isInstanceOf(Number.class) 断言过弱(Integer 也是 Number,无法区分
        // INT 与 LONG),所以改为精确 dtype 断言:整数列 LONG(Long 装箱)、小数列 DOUBLE。
        assertThat(df.dtypes().get(0)).as("纯整数列应为 LONG").isEqualTo(DType.LONG);
        assertThat(df.getColumn("qty").get(0)).isInstanceOf(Long.class);
        assertThat(((Number) df.getColumn("qty").get(0)).longValue()).isEqualTo(10L);
        // 小数列
        assertThat(df.dtypes().get(1)).as("小数列应为 DOUBLE").isEqualTo(DType.DOUBLE);
        assertThat(((Number) df.getColumn("price").get(0)).doubleValue()).isEqualTo(3.14);
    }

    @Test
    void 全文本列保留STRING() throws Exception {
        Path p = tmp.resolve("text.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("name");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("alice");
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("bob");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.dtypes().get(0)).isEqualTo(DType.STRING);
        assertThat(df.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void 布尔列转BOOL() throws Exception {
        Path p = tmp.resolve("bool.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("vip");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(true);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(false);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.dtypes().get(0)).isEqualTo(DType.BOOL);
    }

    @Test
    void 大整数超2_53同格收敛为Long() throws Exception {
        // 因为 POI 直写的超 2^53 long 经 Excel double 存储必然丢精度 —— 9e18 落在
        // [2^62, 2^63),double 的 ulp=1024,9_000_000_000_000_000_001 与 …002 都
        // 四舍五入到同一格 9.0e18(同格收敛),所以读回两个相邻 long 落到同一 long;
        // 又因为整值列走 NUMERIC 分支精确转 long,所以读回 Long 9000000000000000000
        // (无 E 记号、无二次字符串解析)。本测试锁定该行为。
        Path p = tmp.resolve("ids.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("id");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(9_000_000_000_000_000_001L);  // 超 2^53
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(9_000_000_000_000_000_002L);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p.toFile())) { wb.write(fos); }
        }
        DataFrame df = Excel.read(p.toString()).go();
        Object v0 = df.getColumn("id").get(0);
        Object v1 = df.getColumn("id").get(1);
        assertThat(v0).as("超 2^53 整值读回为 Long(整值走 NUMERIC 分支)").isInstanceOf(Long.class);
        assertThat(v1).isInstanceOf(Long.class);
        assertThat((Long) v0).as("ulp=1024 同格收敛:两个相邻 long 落到同一格,读回同一 long")
                .isEqualTo(9_000_000_000_000_000_000L);
        assertThat((Long) v1).isEqualTo(9_000_000_000_000_000_000L);
        assertThat(df.dtypes().get(0)).isEqualTo(DType.LONG);
    }

}
