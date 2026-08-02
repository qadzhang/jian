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

    /** 对齐 pandas.read_csv。 */
    public static DataFrame readCsv(String path) throws Exception { return Csv.read(path).go(); }
    /** 对齐 pandas.read_csv(sep='\t'):TSV 读。 */
    public static DataFrame readTable(String path) throws Exception { return Csv.read(path).delimiter('\t').go(); }
    /** 对齐 pandas.read_fwf:定宽读。 */
    public static DataFrame readFwf(String path, int... widths) throws Exception {
        return Csv.readFwf(path).widths(widths).go();
    }
    /** 对齐 pandas.read_json。 */
    public static DataFrame readJson(String path) throws Exception { return Json.read(path).go(); }
    /** 对齐 pandas.read_excel。 */
    public static DataFrame readExcel(String path) throws Exception { return Excel.read(path).go(); }
    /** 对齐 pandas.read_html。 */
    public static java.util.List<DataFrame> readHtml(String path) throws Exception { return jian.io.html.Html.readAll(path); }
    /** 对齐 pandas.read_xml。 */
    public static DataFrame readXml(String path) throws Exception { return jian.io.xml.Xml.read(path).go(); }
    /** 对齐 pandas.read_parquet。 */
    public static DataFrame readParquet(String path) throws Exception { return jian.io.parquet.Parquet.read(path).go(); }
    /** 对齐 pandas.read_orc。 */
    public static DataFrame readOrc(String path) throws Exception { return jian.io.orc.Orc.read(path).go(); }
    /** 对齐 pandas.read_pickle(jian 自定义 .jpk 格式)。 */
    public static DataFrame readPickle(String path) throws Exception { return jian.io.pickle.Pickle.read(path); }
    /** 对齐 pandas.json_normalize:拍平嵌套 JSON(点号路径定位数组)。 */
    public static DataFrame jsonNormalize(String json, String recordPath) throws Exception {
        return jian.io.json.Json.normalize(json, recordPath);
    }

    /** 对齐 pandas.to_csv。 */
    public static void toCsv(DataFrame df, String path) throws Exception { Csv.write(df, path).go(); }
    /** 对齐 pandas.to_csv(sep='\t'):TSV 写。 */
    public static void toTable(DataFrame df, String path) throws Exception { Csv.write(df, path).delimiter('\t').go(); }
    /** 对齐 pandas.to_json。 */
    public static void toJson(DataFrame df, String path) throws Exception { Json.write(df, path).go(); }
    /** 对齐 pandas.to_excel。 */
    public static void toExcel(DataFrame df, String path) throws Exception { Excel.write(df, path).go(); }
    /** 对齐 pandas.to_parquet。 */
    public static void toParquet(DataFrame df, String path) throws Exception { jian.io.parquet.Parquet.write(df, path).go(); }
    /** 对齐 pandas.to_orc。 */
    public static void toOrc(DataFrame df, String path) throws Exception { jian.io.orc.Orc.write(df, path).go(); }
    /** 对齐 pandas.to_pickle(jian 自定义 .jpk 格式)。 */
    public static void toPickle(DataFrame df, String path) throws Exception { jian.io.pickle.Pickle.write(df, path); }
    /** 对齐 pandas.to_html(经 jian-export 的 HtmlRenderer)。 */
    public static void toHtml(DataFrame df, String path) throws Exception { jian.export.HtmlRenderer.of(df).renderTo(path); }
    /** 对齐 pandas.to_xml。 */
    public static void toXml(DataFrame df, String path) throws Exception { jian.io.xml.Xml.write(df, path).go(); }
    /** 对齐 pandas.to_latex。 */
    public static void toLatex(DataFrame df, String path) throws Exception { jian.io.latex.LatexIo.write(df, path).go(); }
    /** 对齐 pandas.to_markdown。 */
    public static void toMarkdown(DataFrame df, String path) throws Exception {
        java.nio.file.Files.writeString(Path.of(path), jian.export.MarkdownRenderer.of(df).render());
    }
    /** 对齐 pandas.to_clipboard(TSV 格式)。 */
    public static void toClipboard(DataFrame df) throws Exception { jian.io.clipboard.Clipboard.write(df); }

    public static DataFrame readSql(Connection conn, String sql, Object... params) throws java.sql.SQLException {
        return jian.sql.bridge.SqlBridge.toDataFrame(conn, sql, params);
    }

    /** 对齐 pandas.read_sql_query:显式 SQL 查询。 */
    public static DataFrame readSqlQuery(Connection conn, String sql, Object... params) throws java.sql.SQLException {
        return jian.io.sql.Sql.readQuery(conn, sql, params);
    }

    /** 读整张表(对齐 pandas.read_sql_table)。 */
    public static DataFrame readSqlTable(Connection conn, String table) throws java.sql.SQLException {
        return jian.io.sql.Sql.readTable(conn, table);
    }

    /** 对齐 pandas.to_sql:DataFrame 写库表(默认 CREATE_OR_REPLACE)。 */
    public static void toSql(DataFrame df, Connection conn, String table) throws java.sql.SQLException {
        jian.io.sql.Sql.toSql(df, conn, table);
    }

    /** 对齐 pandas.to_sql,可指定写模式。 */
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

    /** 在 DataFrame 上跑 SQL 子集(sql 在前,df 参数在后,统一风格)。 */
    public static DataFrame sql(String sql, DataFrame... dfs) {
        return jian.dsl.Dsl.sql(sql, dfs);
    }

    /** 指定方言。 */
    public static DataFrame sql(String sql, SqlDialect dialect, DataFrame... dfs) {
        return jian.dsl.Dsl.sql(sql, dialect, dfs);
    }

    /** 版本。 */
    public static String version() { return "1.0.0"; }
}
