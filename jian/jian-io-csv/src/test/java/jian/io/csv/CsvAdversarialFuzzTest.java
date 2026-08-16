package jian.io.csv;

import jian.io.csv.Csv;
import jian.core.DataFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** CsvAdversarialFuzzTest —— 自定义对抗性 fuzz 测试。
 *
 * 目标:用畸形 CSV 输入主动探测 jian.io.csv 的健壮性。
 * - 空文件/只有表头/无换行终止
 * - 含 BOM/CRLF/Unicode 转义
 * - 行列数不一/空字段/null 字节
 * - 列数不同的行混合
 */
public class CsvAdversarialFuzzTest {

    @TempDir
    Path tmp;

    private File writeCsv(String name, String content) throws IOException {
        File f = tmp.resolve(name).toFile();
        try (FileWriter w = new FileWriter(f, java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(content);
        }
        return f;
    }

    @Test void empty_file_should_not_crash() throws Exception {
        File f = writeCsv("empty.csv", "");
        assertDoesNotThrow(() -> Csv.read(f.toString()).go());
    }

    @Test void only_header_should_not_crash() throws Exception {
        File f = writeCsv("hdr.csv", "a,b\n");
        assertDoesNotThrow(() -> Csv.read(f.toString()).go());
    }

    @Test void no_trailing_newline_should_not_crash() throws Exception {
        File f = writeCsv("notrail.csv", "a,b\n1,2\n3,4");
        DataFrame df = Csv.read(f.toString()).go();
        assert df.rowCount() >= 1 : "至少应读到 1 行,实际 " + df.rowCount();
    }

    @Test void bom_should_not_crash() throws Exception {
        File f = writeCsv("bom.csv", "﻿a,b\n1,2\n");
        assertDoesNotThrow(() -> Csv.read(f.toString()).go());
    }

    @Test void unicode_should_not_crash() throws Exception {
        File f = writeCsv("uni.csv", "a,b\n中文,测试\n");
        DataFrame df = Csv.read(f.toString()).go();
        assert df.rowCount() >= 1;
    }

    @Test void quoted_quote_should_not_crash() throws Exception {
        File f = writeCsv("qq.csv", "a,b\n\"hello\"\"world\",2\n");
        assertDoesNotThrow(() -> Csv.read(f.toString()).go());
    }

    @Test void embedded_newline_should_not_crash() throws Exception {
        File f = writeCsv("nl.csv", "a,b\n\"line1\nline2\",2\n");
        assertDoesNotThrow(() -> Csv.read(f.toString()).go());
    }

    @Test void inconsistent_cols_should_not_crash() throws Exception {
        File f = writeCsv("inc.csv", "a,b\n1,2\n3,4,5\n6\n");
        assertDoesNotThrow(() -> Csv.read(f.toString()).go());
    }

    @Test void crlf_only_should_not_crash() throws Exception {
        File f = writeCsv("crlf.csv", "a,b\r\n1,2\r\n3,4\r\n");
        DataFrame df = Csv.read(f.toString()).go();
        assert df.rowCount() == 2 : "CRLF 应识别为行分隔,得 2 行,实际 " + df.rowCount();
    }

    @Test void header_only_no_rows() throws Exception {
        File f = writeCsv("hdr_only.csv", "a,b,c\n");
        DataFrame df = Csv.read(f.toString()).go();
        assert df.columnCount() == 3 : "表头 3 列,实际 " + df.columnCount();
    }
}