package jian;

import jian.core.DataFrame;
import jian.dsl.SqlDialect;
import jian.io.csv.Csv;
import jian.io.excel.Excel;
import jian.io.html.Html;
import jian.io.json.Json;
import jian.io.json.Json.Orient;
import jian.io.pickle.Pickle;
import jian.io.xml.Xml;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

// ┌─ What : Jian —— 顶层门面,聚合全部 io 入口(对齐 pandas 顶层 pd.read_*/to_* 风格)
// │  Why  : 规范 02 §2.1;用户 import jian.Jian 后,Jian.readCsv/readJson/... 单点访问,无需记各 io 类名
// │  Who  : 用户主入口
// │  When : 任何场景
// │  Where: jian-facade/Jian.java
// │  How  : 数据走向:Jian.readXxx → 委托对应 io 模块的 builder → DataFrame;
// │         Jian.writeDf(df, "out.csv") → 委托写出。
// │         关键变量变化:每个 read* 返回 builder(链式配置),write* 同理。
// │         逻辑路线:
// │           路径 A(读 CSV)→ Jian.readCsv(path) → Csv.read(path);
// │           路径 B(写 Excel)→ Jian.writeDf(df, "out.xlsx") → 按扩展名分发;
// │           路径 C(未知扩展名)→ 抛 IllegalArgumentException 带支持的格式列表。
/**
 * jian 顶层门面。聚合全部 io 入口,用户引此模块即可 {@code Jian.readCsv("x.csv")} 单点访问。
 *
 * <p>用法:
 * <pre>{@code
 * import jian.Jian;
 * import jian.core.DataFrame;
 *
 * DataFrame df = Jian.read("users.csv");        // 自动按扩展名分发
 * DataFrame json = Jian.read("data.json");
 * DataFrame xls = Jian.read("report.xlsx").sheet("Sheet1").go();  // Excel builder
 *
 * Jian.write(df, "out.csv");                    // 按扩展名写出
 * Jian.write(df, "out.json");                  // 默认 RECORDS orient
 * Jian.write(df, "out.html");
 * }</pre>
 *
 * <p>注:此模块依赖全部 io 子模块(带入全部传递依赖)。
 * 若要按需加载(规范 §4.2),用户可直接引具体 io 模块用其类(如 {@code Csv.read})。
 */
public final class Jian {

    private Jian() {}

    // ======================== 通用读(按扩展名分发)========================

    /**
     * 通用读:按文件扩展名自动分发到对应 io 模块。
     * <ul>
     *   <li>.csv → CSV(逗号分隔);</li>
     *   <li>.tsv → TSV(制表符);</li>
     *   <li>.json → JSON RECORDS orient;</li>
     *   <li>.xlsx/.xls → Excel(首 sheet);</li>
     *   <li>.html/.htm → HTML(首个表);</li>
     *   <li>.xml → XML;</li>
     *   <li>.parquet → Parquet;</li>
     *   <li>.jpk → jian 自定义 pickle。</li>
     * </ul>
     * @param path String 文件路径,非 null;按扩展名分发
     * @return DataFrame 读入的数据
     * @throws Exception IO/解析异常
     * @throws IllegalArgumentException 不支持的扩展名
     */
    /**
     * 说明:各 IO 格式(CSV/JSON/Parquet/ORC)读回的 dtype
     * 由各自 reader 推断,与 Schema.infer 口径可能略有差异(写回再读可能变 dtype);
     * DataFrame.attrs 元数据不随格式持久化(序列化器只写列数据)——需要持久化元数据
     * 请自行并入数据列或使用 .jpk(jian 自定义格式)。
     * @param path String 文件路径,非 null
     * @return DataFrame 读入的数据
     * @throws Exception IO/解析异常
     * @throws IllegalArgumentException 不支持的扩展名
     */
    public static DataFrame read(String path) throws Exception {
        String lower = path.toLowerCase();
        if (lower.endsWith(".csv")) return Csv.read(path).go();
        if (lower.endsWith(".tsv")) return Csv.read(path).delimiter('\t').go();
        if (lower.endsWith(".json")) return Json.read(path).go();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return Excel.read(path).go();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            List<DataFrame> tables = Html.readAll(path);
            if (tables.isEmpty()) throw new IllegalArgumentException("HTML 无表格:" + path);
            return tables.get(0);
        }
        if (lower.endsWith(".xml")) return Xml.read(path).go();
        if (lower.endsWith(".parquet")) return columnarRead("jian.io.parquet.Parquet", path, "parquet");
        if (lower.endsWith(".orc")) return columnarRead("jian.io.orc.Orc", path, "orc");
        if (lower.endsWith(".jpk")) return Pickle.read(path);
        throw new IllegalArgumentException("不支持的文件类型:" + path
                + "(支持:csv/tsv/json/xlsx/xls/html/xml/parquet/orc/jpk)");
    }

    // ======================== 显式 read*(对应 io 模块 builder)========================

    // ======================== pandas 风格 read_*/to_* (直接执行)========================

    /**
     * 对齐 pandas.read_csv。
     * @param path String CSV 文件路径,非 null
     * @return DataFrame 读入的数据
     * @throws Exception IO/解析异常
     */
    public static DataFrame readCsv(String path) throws Exception { return Csv.read(path).go(); }
    /**
     * 对齐 pandas.read_csv(sep='\t'):TSV 读。
     * @param path String TSV 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readTable(String path) throws Exception { return Csv.read(path).delimiter('\t').go(); }
    /**
     * 对齐 pandas.read_fwf:定宽读。
     * @param path   String 文件路径,非 null
     * @param widths int... 每列宽度(字符数),非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readFwf(String path, int... widths) throws Exception {
        return Csv.readFwf(path).widths(widths).go();
    }
    /**
     * 对齐 pandas.read_json。
     * @param path String JSON 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readJson(String path) throws Exception { return Json.read(path).go(); }
    /**
     * 对齐 pandas.read_excel。
     * @param path String Excel 文件路径(.xlsx/.xls),非 null
     * @return DataFrame(首 sheet)
     * @throws Exception IO/解析异常
     */
    public static DataFrame readExcel(String path) throws Exception { return Excel.read(path).go(); }
    /**
     * 对齐 pandas.read_html。
     * @param path String HTML 文件路径,非 null
     * @return List&lt;DataFrame&gt; 每个 &lt;table&gt; 一个 DataFrame(可能多个)
     * @throws Exception IO/解析异常
     */
    public static java.util.List<DataFrame> readHtml(String path) throws Exception { return jian.io.html.Html.readAll(path); }
    /**
     * 对齐 pandas.read_xml。
     * @param path String XML 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readXml(String path) throws Exception { return jian.io.xml.Xml.read(path).go(); }
    /**
     * 对齐 pandas.read_parquet。
     * @param path String Parquet 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readParquet(String path) throws Exception { return columnarRead("jian.io.parquet.Parquet", path, "parquet"); }
    /**
     * 对齐 pandas.read_orc。
     * @param path String ORC 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readOrc(String path) throws Exception { return columnarRead("jian.io.orc.Orc", path, "orc"); }
    /**
     * 对齐 pandas.read_pickle(jian 自定义 .jpk 格式)。
     * @param path String .jpk 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readPickle(String path) throws Exception { return jian.io.pickle.Pickle.read(path); }
    /**
     * 对齐 pandas.json_normalize:拍平嵌套 JSON(点号路径定位数组)。
     * @param json       String JSON 文本,非 null
     * @param recordPath String 点号路径(如 "data.items"),定位要拍平的数组;非 null
     * @return DataFrame 拍平后的表
     * @throws Exception 解析异常
     */
    public static DataFrame jsonNormalize(String json, String recordPath) throws Exception {
        return jian.io.json.Json.normalize(json, recordPath);
    }

    /**
     * 对齐 pandas.to_csv。
     * @param df   DataFrame 待写出的表,非 null
     * @param path String 目标 CSV 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toCsv(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        Csv.write(df, path).go();
    }
    /**
     * 对齐 pandas.to_csv(sep='\t'):TSV 写。
     * @param df   DataFrame,非 null
     * @param path String 目标 TSV 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toTable(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        Csv.write(df, path).delimiter('\t').go();
    }
    /**
     * 对齐 pandas.to_json。
     * @param df   DataFrame,非 null
     * @param path String 目标 JSON 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toJson(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        Json.write(df, path).go();
    }
    /**
     * 对齐 pandas.to_excel。
     * @param df   DataFrame,非 null
     * @param path String 目标 .xlsx 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toExcel(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        Excel.write(df, path).go();
    }
    /**
     * 对齐 pandas.to_parquet。
     * @param df   DataFrame,非 null
     * @param path String 目标 .parquet 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toParquet(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        columnarWrite("jian.io.parquet.Parquet", df, path, "parquet");
    }
    /**
     * 对齐 pandas.to_orc。
     * @param df   DataFrame,非 null
     * @param path String 目标 .orc 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toOrc(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        columnarWrite("jian.io.orc.Orc", df, path, "orc");
    }
    /**
     * 对齐 pandas.to_pickle(jian 自定义 .jpk 格式)。
     * @param df   DataFrame,非 null
     * @param path String 目标 .jpk 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toPickle(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        jian.io.pickle.Pickle.write(df, path);
    }
    /**
     * 对齐 pandas.to_html(经 jian-export 的 HtmlRenderer)。
     * @param df   DataFrame,非 null
     * @param path String 目标 .html 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toHtml(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        jian.export.HtmlRenderer.of(df).renderTo(path);
    }
    /**
     * 对齐 pandas.to_xml。
     * @param df   DataFrame,非 null
     * @param path String 目标 .xml 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toXml(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        jian.io.xml.Xml.write(df, path).go();
    }
    /**
     * 对齐 pandas.to_latex。
     * @param df   DataFrame,非 null
     * @param path String 目标 .tex 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toLatex(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        jian.io.latex.LatexIo.write(df, path).go();
    }
    /**
     * 对齐 pandas.to_markdown。
     * @param df   DataFrame,非 null
     * @param path String 目标 .md 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toMarkdown(DataFrame df, String path) throws Exception {
        requireDf(df); ensureParent(path);
        java.nio.file.Files.writeString(Path.of(path), jian.export.MarkdownRenderer.of(df).render());
    }
    /**
     * 对齐 pandas.to_clipboard(TSV 格式)。
     * @param df DataFrame 待写入剪贴板的表,非 null
     * @throws Exception IO/平台异常
     */
    public static void toClipboard(DataFrame df) throws Exception { jian.io.clipboard.Clipboard.write(df); }

    /**
     * 通过 SQL 查询读取数据库(经 jian-sql-bridge)。
     * @param conn   java.sql.Connection 数据库连接,非 null
     * @param sql    String SQL 查询语句(支持 ? 占位符),非 null
     * @param params Object... 占位符参数,按顺序对应 ?
     * @return DataFrame 查询结果
     * @throws java.sql.SQLException SQL 执行异常
     */
    public static DataFrame readSql(Connection conn, String sql, Object... params) throws java.sql.SQLException {
        return jian.sql.bridge.SqlBridge.toDataFrame(conn, sql, params);
    }

    /**
     * 对齐 pandas.read_sql_query:显式 SQL 查询。
     * @param conn   java.sql.Connection,非 null
     * @param sql    String SQL 查询,非 null
     * @param params Object... 占位符参数
     * @return DataFrame
     * @throws java.sql.SQLException SQL 异常
     */
    public static DataFrame readSqlQuery(Connection conn, String sql, Object... params) throws java.sql.SQLException {
        return jian.io.sql.Sql.readQuery(conn, sql, params);
    }

    /**
     * 读整张表(对齐 pandas.read_sql_table)。
     * @param conn  java.sql.Connection,非 null
     * @param table String 表名,非 null
     * @return DataFrame 整表数据
     * @throws java.sql.SQLException SQL 异常
     */
    public static DataFrame readSqlTable(Connection conn, String table) throws java.sql.SQLException {
        return jian.io.sql.Sql.readTable(conn, table);
    }

    /**
     * 对齐 pandas.to_sql:DataFrame 写库表(默认 CREATE_OR_REPLACE)。
     * @param df    DataFrame 待写出的表,非 null
     * @param conn  java.sql.Connection,非 null
     * @param table String 目标表名,非 null
     * @throws java.sql.SQLException SQL 异常
     */
    public static void toSql(DataFrame df, Connection conn, String table) throws java.sql.SQLException {
        jian.io.sql.Sql.toSql(df, conn, table);
    }

    /**
     * 对齐 pandas.to_sql,可指定写模式。
     * @param df    DataFrame,非 null
     * @param conn  java.sql.Connection,非 null
     * @param table String 目标表名,非 null
     * @param mode  jian.io.sql.Sql.Mode 写入模式(OVERWRITE/APPEND/CREATE_OR_REPLACE/FAIL_IF_EXISTS)
     * @throws java.sql.SQLException SQL 异常
     */
    public static void toSql(DataFrame df, Connection conn, String table, jian.io.sql.Sql.Mode mode)
            throws java.sql.SQLException {
        jian.io.sql.Sql.toSql(df, conn, table, mode);
    }

    /** 读剪贴板。 */
    public static DataFrame readClipboard() throws java.io.IOException {
        return jian.io.clipboard.Clipboard.read();
    }

    // ======================== 通用写(按扩展名分发)========================

    /**
     * 通用写:按扩展名自动分发。
     * <ul>
     *   <li>.csv/.tsv → CSV(逗号/制表符);</li>
     *   <li>.json → JSON RECORDS orient;</li>
     *   <li>.xlsx → Excel(单 sheet);</li>
     *   <li>.html → HTML 表格;</li>
     *   <li>.xml → XML;</li>
     *   <li>.tex → LaTeX;</li>
     *   <li>.md → Markdown;</li>
     *   <li>.parquet → Parquet;</li>
     *   <li>.jpk → jian 自定义 pickle。</li>
     * </ul>
     * 与显式 to* 行为对齐:入口统一 requireDf(df)
     * (df 为 null 抛 IAE 而非 NPE)与 ensureParent(path)(父目录缺失自动创建,
     * 避免 write 失败、toCsv 却自动建目录这类同一能力两条入口行为分裂)。
     *
     * @param df   DataFrame 待写出的表,非 null
     * @param path String 目标文件路径,非 null;按扩展名分发
     * @throws Exception IO 异常
     * @throws IllegalArgumentException df 为 null,或不支持的扩展名
     */
    public static void write(DataFrame df, String path) throws Exception {
        requireDf(df);      // 通用写也判空(与显式 to* 同口径,防 write(null) 深层 NPE)
        ensureParent(path); // 自动建父目录(与显式 to* 同口径)
        String lower = path.toLowerCase();
        if (lower.endsWith(".csv")) Csv.write(df, path).go();
        else if (lower.endsWith(".tsv")) Csv.write(df, path).delimiter('\t').go();
        else if (lower.endsWith(".json")) Json.write(df, path).go();
        else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) Excel.write(df, path).go();
        else if (lower.endsWith(".html") || lower.endsWith(".htm")) jian.export.HtmlRenderer.of(df).renderTo(path);
        else if (lower.endsWith(".xml")) Xml.write(df, path).go();
        else if (lower.endsWith(".tex")) jian.io.latex.LatexIo.write(df, path).go();
        else if (lower.endsWith(".md")) {
            String md = jian.export.MarkdownRenderer.of(df).render();
            java.nio.file.Files.writeString(Path.of(path), md);
        }
        else if (lower.endsWith(".parquet")) columnarWrite("jian.io.parquet.Parquet", df, path, "parquet");
        else if (lower.endsWith(".orc")) columnarWrite("jian.io.orc.Orc", df, path, "orc");
        else if (lower.endsWith(".jpk")) Pickle.write(df, path);
        else throw new IllegalArgumentException("不支持的写出类型:" + path
                + "(支持:csv/tsv/json/xlsx/html/xml/tex/md/parquet/orc/jpk)");
    }

    // ======================== 显式 to*(对应 builder)========================

    // ======================== DSL 入口(L3 SQL)========================

    /**
     * 在 DataFrame 上跑 SQL 子集(sql 在前,df 参数在后,统一风格)。
     * @param sql String SQL 语句(支持 ${name} 占位 + SELECT/WHERE/GROUP BY/JOIN),非 null
     * @param dfs DataFrame... 绑定的 DataFrame(${name} 按出现顺序对应)
     * @return DataFrame SQL 执行结果
     */
    public static DataFrame sql(String sql, DataFrame... dfs) {
        return jian.dsl.Dsl.sql(sql, dfs);
    }

    /**
     * 指定方言。
     * @param sql     String SQL 语句,非 null
     * @param dialect SqlDialect 方言(PG/MySQL/H2/SQLite/Oracle),非 null
     * @param dfs     DataFrame... 绑定的 DataFrame
     * @return DataFrame SQL 执行结果
     */
    public static DataFrame sql(String sql, SqlDialect dialect, DataFrame... dfs) {
        return jian.dsl.Dsl.sql(sql, dialect, dfs);
    }

    /** df 为 null 抛带提示的 IAE(而非深层 NPE)。 */
    private static void requireDf(DataFrame df) {
        if (df == null) throw new IllegalArgumentException("df 不能为 null");
    }

    // ┌─ What : columnarRead/columnarWrite —— 列存格式(Parquet/ORC)的反射桥(按需加载,§4.2)
    // │  Why  : 列存两模块自带 ~45MB Hadoop 生态依赖(hadoop-common/mapreduce/orc 系,
    // │         见 doc/02 §2.8),默认构建不含(-Pcolumnar 激活)。
    // │         facade 对其编译期解耦:改直接调用为反射 + ModuleNotLoadedException(§2.2 模式,
    // │         与 jian-io-sql 探测 JDBC 驱动同构)——未引 jar 时给安装指引而非 NoClassDefFoundError
    // │  Who  : read()/write() 扩展名分发 + readParquet/readOrc/toParquet/toOrc 四个显式方法
    // │  When : 用户调用列存 IO 而 classpath 无对应模块 jar 时 / 正常调用时反射透传
    // │  Where: jian-facade/Jian.java
    // │  How  : 数据走向:
    // │           columnarRead:Class.forName(引擎类) → 反射 read(path) 得 builder
    // │             → 反射 builder.go() → DataFrame
    // │           columnarWrite:反射 write(df, path) 得 builder → 反射 go()
    // │         关键变量变化:
    // │           - fqn 引擎类全限定名("jian.io.parquet.Parquet" / "jian.io.orc.Orc");
    // │           - kind 仅用于报错文案(parquet/orc)。
    // │         逻辑路线:
    // │           路径 A(Class.forName 失败 = jar 未引)→ ModuleNotLoadedException,
    // │             带指引「引 jian-io-<kind> jar;该模块默认不编译(自带 ~45MB Hadoop 依赖),
    // │             构建加 -Pcolumnar」;
    // │           路径 B(反射调用/终结)→ 透传引擎自身异常(如 IO 错)。
    /**
     * 列存引擎类探测(缺 jar 抛带指引的 ModuleNotLoadedException)。
     * @param fqn String 引擎类全限定名,非 null
     * @param kind String 格式名(parquet/orc,报错文案用),非 null
     * @return Class&lt;?&gt; 已加载的引擎类
     */
    private static Class<?> columnarClass(String fqn, String kind) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            throw new jian.core.ModuleNotLoadedException(
                "jian.io." + kind + " 模块未加载:列存(Parquet/ORC)自带 ~45MB Hadoop 依赖,"
                    + "已独立为附加制品 —— fat 用户请在 classpath 叠加 jian-columnar-all-1.0.0-all.jar,"
                    + "thin 用户引 jian:jian-io-" + kind + ":1.0.0(源码构建加 -Pcolumnar;见 doc/02 §2.8)");
        }
    }

    /**
     * 列存读(反射引擎 read(path).go())。
     * @param fqn String 引擎类全限定名,非 null
     * @param path String 文件路径,非 null
     * @param kind String 格式名(报错文案用),非 null
     * @return DataFrame 读入结果
     * @throws Exception 引擎 IO 异常透传
     */
    private static DataFrame columnarRead(String fqn, String path, String kind) throws Exception {
        Object builder = columnarClass(fqn, kind).getMethod("read", String.class).invoke(null, path);
        return (DataFrame) builder.getClass().getMethod("go").invoke(builder);
    }

    /**
     * 列存写(反射引擎 write(df, path).go())。
     * @param fqn String 引擎类全限定名,非 null
     * @param df DataFrame 数据,非 null
     * @param path String 目标路径,非 null
     * @param kind String 格式名(报错文案用),非 null
     * @throws Exception 引擎 IO 异常透传
     */
    private static void columnarWrite(String fqn, DataFrame df, String path, String kind) throws Exception {
        Object builder = columnarClass(fqn, kind)
                .getMethod("write", DataFrame.class, String.class).invoke(null, df, path);
        builder.getClass().getMethod("go").invoke(builder);
    }

    /** 写文件前确保父目录存在(对齐 pandas to_csv 自动建目录)。 */
    private static void ensureParent(String path) throws java.io.IOException {
        Path p = Path.of(path).toAbsolutePath().getParent();
        if (p != null) java.nio.file.Files.createDirectories(p);
    }

    /**
     * 版本。
     * 从 jar Manifest 的 Implementation-Version 读取,
     * 不与 pom.xml 硬编码漂移;未打包(开发态 classpath)时回退 "1.0.0"。
     * @return String 版本号
     */
    public static String version() {
        Package p = Jian.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v != null ? v : "1.0.0";
    }

    /**
     * L1 参数化过滤(用户输入安全入口):值经 {@link jian.dsl.Params} 以 {@code ${name}} 占位注入,
     * 引擎展开时自动字面量化(字符串加引号、'' 翻倍),**不走字符串拼接** —— 用户可控值请一律用本方法,
     * 不要拼进表达式(注入风险)。等价于 {@code Dsl.query(df, expr, params)}。
     * <pre>{@code
     * DataFrame r = Jian.query(df, "类别 == ${c} && 金额 > ${m}", jian.dsl.Params.of("c", "食品").with("m", 5));
     * }</pre>
     * @param df DataFrame 数据源,非 null
     * @param expr String 布尔表达式(可含 ${name} 占位),非 null
     * @param params jian.dsl.Params 命名参数绑定,非 null
     * @return DataFrame 满足 expr 的行组成的新 DataFrame
     * @throws jian.core.ModuleNotLoadedException 未引 jian-dsl 时抛(core 兜底引擎无参数化)
     */
    public static DataFrame query(DataFrame df, String expr, jian.dsl.Params params) {
        return jian.dsl.Dsl.query(df, expr, params);
    }

    /**
     * record 列表 → DataFrame(组件名→列名,组件声明类型精确映射 DType;委托 DataFrame.fromRecords)。
     * <pre>{@code
     * record Order(String 类别, long 金额) {}
     * DataFrame df = Jian.fromRecords(List.of(new Order("食品", 10L)));
     * }</pre>
     * @param records List&lt;?&gt; record 实例列表,非 null 且非空;元素须为同一 record 类型
     * @return DataFrame 列 = record 组件,行数 = records.size()
     * @throws IllegalArgumentException 空列表 / 元素非 record / 元素类型不齐
     */
    public static DataFrame fromRecords(java.util.List<?> records) {
        return DataFrame.fromRecords(records);
    }

    // ======================== 开发辅助(借鉴 Kotlin DataFrame 的 schema 常量化)========================

    /**
     * 从 DataFrame 列名生成 Java 常量类源码(消灭列名拼写错:业务代码引用常量而非字符串字面量,
     * 重命名时改一处)。列名不是合法 Java 标识符(如含空格/数字开头)的列以注释形式列出;
     * 列名是 Java 关键字/保留字(class/int/new/true/false/null/_ 等)的列加 {@code _}
     * 后缀常量化 —— 因为 {@code public static final String class = ...} 不可编译,
     * 而生成的源码必须可直接编译,所以关键字列一律加后缀。
     * <pre>{@code
     * // 示例输出(df 列 = [类别, 金额])
     * public final class OrderCols {
     *     private OrderCols() {}
     *     public static final String 类别 = "类别";
     *     public static final String 金额 = "金额";
     * }
     * }</pre>
     * @param df DataFrame 数据源(取其列名),非 null
     * @param className String 生成的类名(须为合法 Java 标识符),非 null
     * @return String Java 源码文本(仅返回,不写文件;调用方 Files.writeString 落盘)
     * @throws IllegalArgumentException className 非法标识符
     */
    public static String generateColumnsSource(DataFrame df, String className) {
        if (className == null || className.isEmpty()
                || !isLegalIdentifier(className)) {
            throw new IllegalArgumentException("className 须为合法 Java 标识符:" + className);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 jian.Jian.generateColumnsSource 生成 —— 列名常量类,业务代码引用常量防拼写错\n");
        sb.append("public final class ").append(className).append(" {\n");
        sb.append("    private ").append(className).append("() {}\n");
        for (String col : df.columnNames()) {
            if (isIdentifierChars(col)) {
                // 关键字/保留字列名加 _ 后缀:
                // isLegalIdentifier 已把关键字判为"不合法",此处按字符类合法单独分支
                String constName = isLegalIdentifier(col) ? col : col + "_";
                sb.append("    public static final String ").append(constName)
                  .append(" = \"").append(col).append("\";\n");
            } else {
                sb.append("    // 列名「").append(col).append("」不是合法 Java 标识符,无法常量化,请 df.rename 后重生成\n");
            }
        }
        return sb.append("}\n").toString();
    }

    /**
     * 列名是否为合法 Java 标识符:字符类检查(首字符 + 后续字符)+ <b>非 Java
     * 关键字/保留字</b>(只查字符类会让列名 class/int 生成
     * {@code public static final String class = ...} 不可编译)。
     *
     * @param s String 待检查文本,可 null
     * @return boolean true 表示可作 Java 标识符(非关键字且字符类合法)
     */
    private static boolean isLegalIdentifier(String s) {
        return isIdentifierChars(s) && !JAVA_RESERVED_WORDS.contains(s);
    }

    /** 纯字符类检查(首字符 isJavaIdentifierStart + 后续 isJavaIdentifierPart)。 */
    private static boolean isIdentifierChars(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Java 17 关键字 + 保留字 + 字面量集合(JLS §3.9;const/goto 为保留不可用,
     * _ 自 Java 9 起不可作单字符标识符;true/false/null 是字面量同样不可作标识符)。
     * var/record/yield/sealed 等上下文关键字可作合法标识符,不在此列。
     */
    private static final java.util.Set<String> JAVA_RESERVED_WORDS = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "_", "true", "false", "null");
}
