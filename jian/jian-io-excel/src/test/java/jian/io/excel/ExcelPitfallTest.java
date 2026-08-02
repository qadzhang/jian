package jian.io.excel;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : POI skill 陷阱测试 —— #5 表头去重 / #8/#9 空行跳过
class ExcelPitfallTest {

    @TempDir Path tmp;

    @Test
    void 陷阱5_表头列名去重() throws Exception {
        Path p = tmp.resolve("dup.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new java.io.FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            // 表头有两个 "name" 列
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("id");
            h.createCell(1).setCellValue("name");
            h.createCell(2).setCellValue("name");  // 重名
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("alice");
            r1.createCell(2).setCellValue("alice2");
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        // 重名列应自动去重: name + name_1
        assertThat(df.columnNames()).containsExactly("id", "name", "name_1");
        assertThat(df.rowCount()).isEqualTo(1);
        assertThat(df.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(df.getStringColumn("name_1").get(0)).isEqualTo("alice2");
    }

    @Test
    void 陷阱8_末尾空行跳过() throws Exception {
        Path p = tmp.resolve("empty.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new java.io.FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("id");
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(2);
            // 末尾空行(getLastRowNum 会算到这些行)
            sheet.createRow(3);  // 完全空行
            sheet.createRow(4);  // 完全空行
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        // 应该只有 2 行数据(跳过末尾 2 个空行)
        assertThat(df.rowCount()).isEqualTo(2);
    }

    @Test
    void 陷阱9_中间空行跳过() throws Exception {
        Path p = tmp.resolve("mid.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new java.io.FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("id");
            sheet.createRow(1).createCell(0).setCellValue(1);
            sheet.createRow(2);  // 中间空行
            sheet.createRow(3).createCell(0).setCellValue(3);
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        // 跳过中间空行,2 行数据
        assertThat(df.rowCount()).isEqualTo(2);
        assertThat(((Number) df.getColumn("id").get(0)).intValue()).isEqualTo(1);
        assertThat(((Number) df.getColumn("id").get(1)).intValue()).isEqualTo(3);
    }

    @Test
    void 陷阱5_三个同名表头() throws Exception {
        Path p = tmp.resolve("tripledup.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new java.io.FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("v");
            h.createCell(1).setCellValue("v");
            h.createCell(2).setCellValue("v");
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue(2);
            r1.createCell(2).setCellValue(3);
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        assertThat(df.columnNames()).containsExactly("v", "v_1", "v_2");
    }

    @Test
    void 陷阱5_空表头补下划线() throws Exception {
        Path p = tmp.resolve("emptyhdr.xlsx");
        try (var wb = new XSSFWorkbook(); var fos = new java.io.FileOutputStream(p.toFile())) {
            var sheet = wb.createSheet("s");
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("id");
            // 第二列表头为空
            h.createCell(1);
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("x");
            wb.write(fos);
        }
        DataFrame df = Excel.read(p.toString()).go();
        // 空表头应该变成 "_"
        assertThat(df.columnNames()).containsExactly("id", "_");
    }
}
