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
        private boolean warnExtraCols = true;  // 多字段截断警告开关(默认开)

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
         * 数据行字段数多于列数时是否输出 stderr 警告。
         * 多余字段始终截断保留(宽容语义不破 ETL);警告一次性汇总(首现行号+累计行数),默认 true。
         * @param v boolean true=输出警告(默认);false=静默(批量 ETL 降噪用)
         * @return CsvReader 当前配置器,便于链式调用
         */
        public CsvReader warnExtraCols(boolean v) { this.warnExtraCols = v; return this; }

        /**
         * 执行读取。线程安全。自动剥离 UTF-8 BOM(如有)。
         * <p>行为要点:
         * ① 空文件 —— 因为预读字符在 EOF(-1)时不回推,所以空文件读出 0 列 0 行的空 DataFrame
         * (而非带 U+FFFF 幽灵列名的帧);预读流整体纳入 try-with-resources,防 fd 泄漏。
         * ② 多字段 —— 因为宽容语义要求不破 ETL,所以数据行字段多于列数时保留截断,
         * 但 stderr 输出一次性汇总警告(首次行号 + 累计行数),可用 {@link #warnExtraCols(boolean)} 关闭。
         * @return DataFrame 解析出的数据帧(列名、行数据、列类型按配置/推断确定)
         * @throws IOException 文件不存在、不可读或解析过程发生 IO 错误时抛出
         */
        public DataFrame go() throws IOException {
            CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).build();
            try (java.io.PushbackReader pbr = new java.io.PushbackReader(
                    Files.newBufferedReader(path, encoding), 3)) {
                int ch = pbr.read();
                if (ch != '\uFEFF' && ch != -1) pbr.unread(ch);  // 不是 BOM 且非 EOF,退回(-1 不回推,防幽灵列)
                try (BufferedReader reader = new BufferedReader(pbr);
                     CSVParser parser = fmt.parse(reader)) {
                    List<String> names = new ArrayList<>();
                    List<CSVRecord> records = parser.getRecords();
                    if (records.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
                    int colCount = records.get(0).size();
                    int headerOffset = header ? 1 : 0;
                    if (header) {
                        for (int c = 0; c < colCount; c++) names.add(records.get(0).get(c));
                        // 因为外部导出文件常见重复列名,若直接取首记录作列名会触发
                        // Schema 校验抛"列名重复"、一个字段都拿不到,所以参照 Excel.dedupNames
                        // 的 _1/_2 后缀风格自动改名并 stderr 警告一次。
                        // 有意差异:pandas read_csv 自动改 "name.1",jian 统一用 "_1"(与 Excel 模块一致)。
                        List<String> deduped = dedupHeaderNames(names);
                        if (!deduped.equals(names)) {
                            System.err.println("[jian-csv] 警告:表头存在重复列名,已自动改名"
                                    + "(第 2 个重复名加 _1、第 3 个 _2,...;pandas 用 name.1,jian 统一 _1 与 Excel 模块一致):"
                                    + names + " -> " + deduped);
                        }
                        names = deduped;
                        records = records.subList(1, records.size());
                    } else {
                        for (int c = 0; c < colCount; c++) names.add("_" + c);
                    }
                    Object[][] rows = new Object[records.size()][colCount];
                    int extraRows = 0, firstExtraLine = -1;
                    for (int r = 0; r < records.size(); r++) {
                        CSVRecord rec = records.get(r);
                        if (rec.size() > colCount) {
                            extraRows++;
                            if (firstExtraLine < 0) firstExtraLine = (int) rec.getRecordNumber();
                        }
                        for (int c = 0; c < colCount; c++) {
                            String v = c < rec.size() ? rec.get(c) : null;
                            rows[r][c] = (v == null || v.isEmpty()) ? null : v;
                        }
                    }
                    // 多字段一次性汇总警告(不刷屏),可关
                    if (warnExtraCols && extraRows > 0) {
                        System.err.println("[jian-csv] 警告:" + extraRows + " 行字段数多于列数(" + colCount
                                + "),多余字段被截断;首见于文件第 " + firstExtraLine + " 行(含表头行号)。"
                                + "如需关闭本警告:Csv.read(path).warnExtraCols(false)");
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
            // 因为 Files.readAllLines 不过滤 BOM,带 BOM 的 FWF 首列名会变 "\uFEFFid"
            // (宽度计算也随之错位),所以与 CsvReader/JsonReader 同口径先剥 BOM。
            if (!lines.isEmpty() && !lines.get(0).isEmpty() && lines.get(0).charAt(0) == '\uFEFF') {
                lines.set(0, lines.get(0).substring(1));
            }
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
                if (header) {
                    // 因为列名为 "=cmd|calc"/"+x"/"@y" 时首行同样会被 Excel 当公式执行
                    // (仅防护数据格不够),所以表头列名也走公式注入防护:
                    // 与数据格同一 sanitizeFormulas 开关、同一跳过集(6 字符)。
                    java.util.List<String> hdr = new java.util.ArrayList<>(df.columnCount());
                    for (String name : df.columnNames()) {
                        hdr.add(sanitizeFormulas && startsWithFormulaAfterWhitespace(name) ? "'" + name : name);
                    }
                    printer.printRecord(hdr);
                }
                for (Object[] row : df.iterRows()) {
                    for (int c = 0; c < row.length; c++) {
                        Object v = row[c];
                        if (v == null) { printer.print(""); continue; }
                        String s = String.valueOf(v);
                        // 因为 OWASP 防的是字符串被 Excel 当公式执行,而数值/布尔的字符串形式
                        // ("-1.5"/"true")不可能构成公式载荷(若对 -0.0/-1.5 等合法负值加 ' 前缀,
                        // round-trip 后数值列整列降级 STRING 且值被污染),所以防护只对字符串值生效,
                        // Number/Boolean 豁免。
                        boolean sanitizable = !(v instanceof Number) && !(v instanceof Boolean);
                        if (sanitizeFormulas && sanitizable && startsWithFormulaAfterWhitespace(s)) {
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
            // 跳过前导空白类字符 + NUL/BOM(空格、Tab、CR、LF、\u0000、\uFEFF)——
            // Excel/LibreOffice 解析时会 trim;因为若不跳 NUL/BOM,"\uFEFF=1+1" 可
            // 绕过首字符检查,所以一并跳过(理论加固)。
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                    || s.charAt(i) == '\r' || s.charAt(i) == '\n'
                    || s.charAt(i) == '\u0000' || s.charAt(i) == '\uFEFF')) {
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

    // ┌─ What : dedupHeaderNames —— CSV 表头列名去重(重名自动加 _1/_2 后缀)
    // │  Why  : 因为重复表头会触发 Schema.validateUniqueNames 抛"列名重复",导致
    // │         整个读取失败、一个字段都拿不到,所以对重名列自动加 _1/_2 后缀去重;
    // │         同项目 Excel.dedupNames 同风格,pandas read_csv 自动改 name.1 ——
    // │         jian 统一 _1 风格(有意差异,与 Excel 模块一致)
    // │  Who  : CsvReader.go()(header=true 分支)
    // │  When : 首记录收集完列名之后、构造 Schema 之前
    // │  Where: jian-io-csv/Csv.java
    // │  How  : 伪代码:
    // │           1. seen 记录每个名字已出现次数(LinkedHashMap 保序)
    // │           2. 名字首次出现 → 原样保留;第 k 次重复(k≥1)→ 改为 "名_k"
    // │         关键变量变化:
    // │           - names:输入原始列名(可能重复)→ 输出全部唯一的列名;
    // │           - seen:每遇到一个名字,其计数 +1(重复者取改名前的计数作后缀)。
    // │         逻辑路线(两条路径):
    // │           路径 A(名字首次出现)→ 原样加入结果,seen 计 1;
    // │           路径 B(重复)→ 结果加 "名_计数",计数自增(第 2 个重复得 _1,第 3 个得 _2)。
    // │         数据走向:CSV 首记录字段 → names → dedupHeaderNames → DataFrame 列名。
    private static List<String> dedupHeaderNames(List<String> names) {
        List<String> result = new ArrayList<>(names.size());
        java.util.Map<String, Integer> seen = new java.util.LinkedHashMap<>();
        for (String name : names) {
            Integer count = seen.get(name);
            if (count == null) {
                result.add(name);
                seen.put(name, 1);
            } else {
                result.add(name + "_" + count);   // 第 2 个重复 → _1,第 3 个 → _2
                seen.put(name, count + 1);
            }
        }
        return result;
    }
}
