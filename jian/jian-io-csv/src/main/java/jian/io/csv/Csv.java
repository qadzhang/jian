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

    /**
     * 读 CSV 的 builder(需要配置参数时用)。链式配置后 .go()。
     * @param path String CSV 文件路径,需为合法可读文件路径,不允许为 null
     * @return CsvReader 配置器,链式调用 .delimiter/.header/... 后 .go() 执行读取
     */
    public static CsvReader read(String path) {
        return new CsvReader(path);
    }

    /**
     * 读 FWF(定宽)的 builder。需 .widths(...) 后 .go()。
     * @param path String FWF(定宽)文件路径,需为合法可读文件路径,不允许为 null
     * @return FwfReader 配置器,链式调用 .widths(...).encoding(...) 后 .go() 执行读取
     */
    public static FwfReader readFwf(String path) {
        return new FwfReader(path);
    }

    // ======================== 写 ========================

    /**
     * 写 CSV 的 builder(需要配置时用)。链式配置后 .go()。
     * @param df DataFrame 要写出的数据帧,不允许为 null
     * @param path String 输出 CSV 文件路径,需为合法可写路径,不允许为 null
     * @return CsvWriter 配置器,链式调用 .delimiter/.header/... 后 .go() 执行写出
     */
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

        /**
         * 设置列分隔符。
         * @param d char 列分隔符字符,常用取值:','(CSV 默认)、'\t'(TSV)、'|'、';'
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader delimiter(char d) { this.delimiter = d; return this; }

        /**
         * 设置首行是否为表头。
         * @param h boolean true=首行作列名(默认);false=首行作数据,列名自动取 _0,_1,...
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader header(boolean h) { this.header = h; return this; }

        /**
         * 按字符集名设置文件编码。
         * @param enc String 字符集名称(如 "UTF-8"、"GBK"、"ISO-8859-1"),需为 Java 支持的合法 Charset 名,不允许 null
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader encoding(String enc) { this.encoding = Charset.forName(enc); return this; }

        /**
         * 直接用 Charset 对象设置文件编码。
         * @param enc Charset 字符集对象(如 StandardCharsets.UTF_8),不允许 null
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader encoding(Charset enc) { this.encoding = enc; return this; }

        /**
         * 指定列类型 Schema(跳过自动推断,强制按指定类型解析)。
         * @param s Schema 列名与类型的定义;null 表示不指定,改走自动推断或全字符串模式
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader schema(Schema s) { this.schema = s; return this; }

        /**
         * 是否把所有列当字符串读取(不推断数值/布尔类型)。
         * @param v boolean true=全部按 STRING 类型读取(防手机号/身份证被转数字丢精度);false=按值自动推断(默认)
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader allString(boolean v) { this.allString = v; return this; }

        /**
         * 执行读取。线程安全。自动剥离 UTF-8 BOM(如有)。
         * @return DataFrame 解析出的数据帧(列名、行数据、列类型按配置/推断确定)
         * @throws IOException 文件不存在、不可读或解析过程发生 IO 错误时抛出
         */
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

        /**
         * 设置每列的字符宽度(必填,缺省 go() 会抛异常)。
         * @param w int... 变长参数,每个值代表一列的字符宽度(必须 &gt; 0),如 widths(5, 10, 3)
         * @return FwfReader 当前配置器,便于链式调用
         */
        public FwfReader widths(int... w) { this.widths = w; return this; }

        /**
         * 设置首行是否为表头。
         * @param h boolean true=首行作列名(默认);false=首行作数据,列名取 _0,_1,...
         * @return FwfReader 当前配置器,便于链式调用
         */
        public FwfReader header(boolean h) { this.header = h; return this; }

        /**
         * 按字符集名设置文件编码。
         * @param enc String 字符集名称(如 "UTF-8"、"GBK"),需为 Java 支持的合法 Charset 名,不允许 null
         * @return FwfReader 当前配置器,便于链式调用
         */
        public FwfReader encoding(String enc) { this.encoding = Charset.forName(enc); return this; }

        /**
         * 执行读取。
         * @return DataFrame 解析出的定宽数据帧(列名按 header 配置决定,类型自动推断)
         * @throws IOException 文件不存在、不可读或解析 IO 错误时抛出;@throws IllegalStateException 未调用 widths() 时抛出
         */
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

        /**
         * 设置列分隔符。
         * @param d char 列分隔符字符,常用:','(CSV 默认)、'\t'(TSV)、'|'、';'
         * @return CsvWriter 当前配置器,便于链式调用
         */
        public CsvWriter delimiter(char d) { this.delimiter = d; return this; }

        /**
         * 设置是否写表头行。
         * @param h boolean true=输出表头行(默认);false=不输出表头
         * @return CsvWriter 当前配置器,便于链式调用
         */
        public CsvWriter header(boolean h) { this.header = h; return this; }

        /**
         * 按字符集名设置输出文件编码。
         * @param enc String 字符集名称(如 "UTF-8"、"GBK"),需为 Java 支持的合法 Charset 名,不允许 null
         * @return CsvWriter 当前配置器,便于链式调用
         */
        public CsvWriter encoding(String enc) { this.encoding = Charset.forName(enc); return this; }

        /**
         * CSV 公式注入防护(OWASP):值为 {@code = + - @} 开头(Excel/WPS 会当公式执行)时,
         * 前缀 {@code '} 使单元格按文本处理。默认 true;需要保留原始值时可关闭。
         * @param v boolean true=启用公式注入防护(默认);false=关闭,保留原始值(有 CSV 注入风险)
         * @return CsvWriter 当前配置器,便于链式调用
         */
        public CsvWriter sanitizeFormulas(boolean v) { this.sanitizeFormulas = v; return this; }

        /**
         * 执行写出。线程安全。
         * @throws IOException 目标路径不可写或写出过程发生 IO 错误时抛出
         */
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
                        if (sanitizeFormulas && startsWithFormulaAfterWhitespace(s)) {
                            // OWASP CSV Injection:在前导空白/制表符后接 = + - @ 时,
                            // 整串加 ' 前缀(Escel/LibreOffice 会把首字符 ' 视为转义)。
                            printer.print("'" + s);
                        } else {
                            printer.print(s);
                        }
                    }
                    printer.println();
                }
            }
        }

        /**
         * 公式注入检测(OWASP 严格版)。
         * <p>不只看首字符——很多注入 payload 会用前导空白/Tab/CR/LF 绕过首字符检查
         * (如 {@code "\t=cmd|..."}、{@code " =cmd|..."}),Excel 会先 trim 再判定。
         * 本方法跳过前导空白类字符后,再看第一个有效字符是否是公式起始符。
         */
        private static boolean startsWithFormulaAfterWhitespace(String s) {
            if (s.isEmpty()) return false;
            int i = 0;
            // 跳过前导空白类字符(空格、Tab、CR、LF)——Excel/LibreOffice 解析时会 trim
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                    || s.charAt(i) == '\r' || s.charAt(i) == '\n')) {
                i++;
            }
            if (i >= s.length()) return false;
            char ch = s.charAt(i);
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
