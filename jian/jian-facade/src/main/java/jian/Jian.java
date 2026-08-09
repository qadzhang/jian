package jian;

import jian.core.DataFrame;
import jian.dsl.SqlDialect;
import jian.io.csv.Csv;
import jian.io.excel.Excel;
import jian.io.html.Html;
import jian.io.json.Json;
import jian.io.json.Json.Orient;
import jian.io.parquet.Parquet;
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
        if (lower.endsWith(".parquet")) return Parquet.read(path).go();
        if (lower.endsWith(".orc")) return jian.io.orc.Orc.read(path).go();
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
    public static DataFrame readParquet(String path) throws Exception { return jian.io.parquet.Parquet.read(path).go(); }
    /**
     * 对齐 pandas.read_orc。
     * @param path String ORC 文件路径,非 null
     * @return DataFrame
     * @throws Exception IO/解析异常
     */
    public static DataFrame readOrc(String path) throws Exception { return jian.io.orc.Orc.read(path).go(); }
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
    public static void toCsv(DataFrame df, String path) throws Exception { Csv.write(df, path).go(); }
    /**
     * 对齐 pandas.to_csv(sep='\t'):TSV 写。
     * @param df   DataFrame,非 null
     * @param path String 目标 TSV 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toTable(DataFrame df, String path) throws Exception { Csv.write(df, path).delimiter('\t').go(); }
    /**
     * 对齐 pandas.to_json。
     * @param df   DataFrame,非 null
     * @param path String 目标 JSON 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toJson(DataFrame df, String path) throws Exception { Json.write(df, path).go(); }
    /**
     * 对齐 pandas.to_excel。
     * @param df   DataFrame,非 null
     * @param path String 目标 .xlsx 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toExcel(DataFrame df, String path) throws Exception { Excel.write(df, path).go(); }
    /**
     * 对齐 pandas.to_parquet。
     * @param df   DataFrame,非 null
     * @param path String 目标 .parquet 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toParquet(DataFrame df, String path) throws Exception { jian.io.parquet.Parquet.write(df, path).go(); }
    /**
     * 对齐 pandas.to_orc。
     * @param df   DataFrame,非 null
     * @param path String 目标 .orc 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toOrc(DataFrame df, String path) throws Exception { jian.io.orc.Orc.write(df, path).go(); }
    /**
     * 对齐 pandas.to_pickle(jian 自定义 .jpk 格式)。
     * @param df   DataFrame,非 null
     * @param path String 目标 .jpk 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toPickle(DataFrame df, String path) throws Exception { jian.io.pickle.Pickle.write(df, path); }
    /**
     * 对齐 pandas.to_html(经 jian-export 的 HtmlRenderer)。
     * @param df   DataFrame,非 null
     * @param path String 目标 .html 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toHtml(DataFrame df, String path) throws Exception { jian.export.HtmlRenderer.of(df).renderTo(path); }
    /**
     * 对齐 pandas.to_xml。
     * @param df   DataFrame,非 null
     * @param path String 目标 .xml 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toXml(DataFrame df, String path) throws Exception { jian.io.xml.Xml.write(df, path).go(); }
    /**
     * 对齐 pandas.to_latex。
     * @param df   DataFrame,非 null
     * @param path String 目标 .tex 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toLatex(DataFrame df, String path) throws Exception { jian.io.latex.LatexIo.write(df, path).go(); }
    /**
     * 对齐 pandas.to_markdown。
     * @param df   DataFrame,非 null
     * @param path String 目标 .md 路径,非 null
     * @throws Exception IO 异常
     */
    public static void toMarkdown(DataFrame df, String path) throws Exception {
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
     * @param df   DataFrame 待写出的表,非 null
     * @param path String 目标文件路径,非 null;按扩展名分发
     * @throws Exception IO 异常
     * @throws IllegalArgumentException 不支持的扩展名
     */
    public static void write(DataFrame df, String path) throws Exception {
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
        else if (lower.endsWith(".parquet")) Parquet.write(df, path).go();
        else if (lower.endsWith(".orc")) jian.io.orc.Orc.write(df, path).go();
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

    /** 版本。 */
    public static String version() { return "1.0.0"; }
}
