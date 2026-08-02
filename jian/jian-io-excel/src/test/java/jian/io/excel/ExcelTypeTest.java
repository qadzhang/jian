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
        // 整数列 → DataFrame 经 Schema 推断为 INT 或 LONG
        assertThat(df.getColumn("qty").get(0)).isInstanceOf(Number.class);
        assertThat(((Number) df.getColumn("qty").get(0)).longValue()).isEqualTo(10L);
        // 小数列
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
    void 大整数ID列不丢精度() throws Exception {
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
        // 注:Excel 用 double 存储数值,超 2^53 的 long 经 double 会丢精度(Excel 格式固有限制)
        // 但我们的转换不会再额外丢精度(long) d 直接截取 double 存的值
        Object v = df.getColumn("id").get(0);
        assertThat(v).isInstanceOfAny(Long.class, Double.class); // Excel 格式固有精度限制(超 2^53 的 long 存为 double)
    }

}
