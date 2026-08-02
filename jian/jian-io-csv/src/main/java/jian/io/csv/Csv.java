package jian.io.csv;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Csv —— CSV/TSV/FWF 读写(对齐 pandas read_csv / to_csv)
// │  How  : 无配置直接执行;有配置 builder 链式 + go()。线程安全(纯执行无共享状态)。
/**
 * CSV/TSV/FWF 读写。
 *
 * <p>读:
 * <pre>{@code
 * DataFrame df = Csv.read("data.csv");                              // 无配置直接用
 * DataFrame df = Csv.read("data.tsv").delimiter('\t').go();  // 有配置 builder+go
 * DataFrame df = Csv.read("data.csv").allString(true).go();  // 全字符串
 * DataFrame df = Csv.read("data.csv").schema(s).go();        // 指定类型
 * }</pre>
 *
 * <p>写:
 * <pre>{@code
 * Csv.write(df, "out.csv");                                        // 无配置直接用
 * Csv.write(df, "out.tsv").delimiter('\t').go();                  // 有配置 builder+go
 * }</pre>
 */
public final class Csv {

    private Csv() {}

    // ======================== 读 ========================

    /** 读 CSV,默认配置(逗号、首行表头、UTF-8、自动推断类型)。 */

    /** 读 CSV 的 builder(需要配置参数时用)。链式配置后 .go()。 */
    public static CsvReader read(String path) {
        return new CsvReader(path);
    }

    /** 读 FWF(定宽)的 builder。需 .widths(...) 后 .go()。 */
    public static FwfReader readFwf(String path) {
        return new FwfReader(path);
    }

    // ======================== 写 ========================

    /** 写 CSV,默认配置(逗号、含表头、UTF-8)。 */
    /** 写 CSV 的 builder(需要配置时用)。链式配置后 .go()。 */
    public static CsvWriter write(DataFrame df, String path) {
        return new CsvWriter(df, Path.of(path));
    }

    // ======================== CsvReader ========================

    public static final class CsvReader {
        private final Path path;
        private char delimiter = ',';
        private boolean header = true;
        private Charset encoding = StandardCharsets.UTF_8;
        private Schema schema = null;
        private boolean allString = false;

        CsvReader(String p) { this.path = Path.of(p); }
        CsvReader(Path p) { this.path = p; }

        public CsvReader delimiter(char d) { this.delimiter = d; return this; }
        public CsvReader header(boolean h) { this.header = h; return this; }
        public CsvReader encoding(String enc) { this.encoding = Charset.forName(enc); return this; }
        public CsvReader encoding(Charset enc) { this.encoding = enc; return this; }
        public CsvReader schema(Schema s) { this.schema = s; return this; }
        public CsvReader allString(boolean v) { this.allString = v; return this; }

        /** 执行读取。线程安全。自动剥离 UTF-8 BOM(如有)。 */
        public DataFrame go() throws IOException {
            CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).build();
            // 读第一个字符判断 BOM,有则跳过
            java.io.PushbackReader pbr = new java.io.PushbackReader(
                Files.newBufferedReader(path, encoding), 3);
            int ch = pbr.read();
            if (ch != '\uFEFF') pbr.unread(ch);  // 不是 BOM,退回
            try (BufferedReader reader = new BufferedReader(pbr);
                 CSVParser parser = fmt.parse(reader)) {
                List<String> names = new ArrayList<>();
                List<CSVRecord> records = parser.getRecords();
                if (records.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
                int colCount = records.get(0).size();
                if (header) {
                    for (int c = 0; c < colCount; c++) names.add(records.get(0).get(c));
                    records = records.subList(1, records.size());
                } else {
                    for (int c = 0; c < colCount; c++) names.add("_" + c);
                }
                Object[][] rows = new Object[records.size()][colCount];
                for (int r = 0; r < records.size(); r++) {
                    CSVRecord rec = records.get(r);
                    for (int c = 0; c < colCount; c++) {
                        String v = c < rec.size() ? rec.get(c) : null;
                        rows[r][c] = (v == null || v.isEmpty()) ? null : v;
                    }
                }
                if (allString) {
                    List<DType> allStr = new ArrayList<>();
                    for (int c = 0; c < colCount; c++) allStr.add(DType.STRING);
                    return DataFrame.of(new Schema(names, allStr), rows);
                } else if (schema != null) {
                    return DataFrame.of(schema, rows);
                } else {
                    return DataFrame.of(Schema.infer(names, rows), rows);
                }
            }
        }
    }

    // ======================== FwfReader ========================

    public static final class FwfReader {
        private final Path path;
        private int[] widths;
        private Charset encoding = StandardCharsets.UTF_8;
        private boolean header = true;

        FwfReader(String p) { this.path = Path.of(p); }
        public FwfReader widths(int... w) { this.widths = w; return this; }
        public FwfReader header(boolean h) { this.header = h; return this; }
        public FwfReader encoding(String enc) { this.encoding = Charset.forName(enc); return this; }

        public DataFrame go() throws IOException {
            if (widths == null) throw new IllegalStateException("FWF 必须指定 widths");
            List<String> lines = Files.readAllLines(path, encoding);
            if (lines.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
            int colCount = widths.length;
            List<String> names = new ArrayList<>();
            int startRow = 0;
            if (header) {
                String[] hv = splitFwf(lines.get(0), widths);
                for (String v : hv) names.add(v == null || v.trim().isEmpty() ? "_" : v.trim());
                startRow = 1;
            } else { for (int c = 0; c < colCount; c++) names.add("_" + c); }
            Object[][] rows = new Object[lines.size() - startRow][colCount];
            for (int r = startRow; r < lines.size(); r++) {
                String[] vals = splitFwf(lines.get(r), widths);
                for (int c = 0; c < colCount; c++) {
                    String v = vals[c] == null ? null : vals[c].trim();
                    rows[r - startRow][c] = (v == null || v.isEmpty()) ? null : v;
                }
            }
            return DataFrame.of(Schema.infer(names, rows), rows);
        }
    }

    // ======================== CsvWriter ========================

    public static final class CsvWriter {
        private final DataFrame df;
        private final Path path;
        private char delimiter = ',';
        private boolean header = true;
        private Charset encoding = StandardCharsets.UTF_8;
        private boolean sanitizeFormulas = true;  // 默认开启:防 CSV 公式注入(OWASP)

        CsvWriter(DataFrame df, Path p) { this.df = df; this.path = p; }
        public CsvWriter delimiter(char d) { this.delimiter = d; return this; }
        public CsvWriter header(boolean h) { this.header = h; return this; }
        public CsvWriter encoding(String enc) { this.encoding = Charset.forName(enc); return this; }

        /**
         * CSV 公式注入防护(OWASP):值为 {@code = + - @} 开头(Excel/WPS 会当公式执行)时,
         * 前缀 {@code '} 使单元格按文本处理。默认 true;需要保留原始值时可关闭。
         */
        public CsvWriter sanitizeFormulas(boolean v) { this.sanitizeFormulas = v; return this; }

        /** 执行写出。线程安全。 */
        public void go() throws IOException {
            CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).build();
            try (java.io.BufferedWriter w = Files.newBufferedWriter(path, encoding);
                 CSVPrinter printer = new CSVPrinter(w, fmt)) {
                if (header) printer.printRecord(df.columnNames());
                for (Object[] row : df.iterRows()) {
                    for (int c = 0; c < row.length; c++) {
                        Object v = row[c];
                        if (v == null) { printer.print(""); continue; }
                        String s = String.valueOf(v);
                        if (sanitizeFormulas && !s.isEmpty() && isFormulaStart(s.charAt(0))) {
                            printer.print("'" + s);
                        } else {
                            printer.print(s);
                        }
                    }
                    printer.println();
                }
            }
        }

        /** 公式注入危险起始字符(OWASP CSV Injection)。 */
        private static boolean isFormulaStart(char ch) {
            return ch == '=' || ch == '+' || ch == '-' || ch == '@';
        }
    }

    private static String[] splitFwf(String line, int[] widths) {
        String[] r = new String[widths.length];
        int pos = 0;
        for (int c = 0; c < widths.length; c++) {
            int end = Math.min(pos + widths[c], line.length());
            r[c] = pos < line.length() ? line.substring(pos, end) : null;
            pos += widths[c];
        }
        return r;
    }
}
