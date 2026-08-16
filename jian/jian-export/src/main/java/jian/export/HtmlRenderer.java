package jian.export;

import jian.core.DataFrame;

// ┌─ What : HtmlRenderer —— DataFrame → HTML 表格(对齐 pandas df.to_html)
// │  Why  : 规范 04 §3.1;HTML 是 jian 最基础的导出格式,纯 JDK 零依赖
// │  Who  : 用户经 df 调用 toHtml,或 Jian.toHtml(df)
// │  When : 报告、邮件、网页嵌入
// │  Where: jian-export/HtmlRenderer.java
// │  How  : 数据走向:DataFrame → 遍历行列拼 <table> HTML 字符串 → 返回/落盘。
// │         关键变量变化:
// │           - naRep:缺失值表示(默认 "<NA>");
// │           - 自动 HTML 转义(< > & 防注入);
// │           - 大表截断(对齐 pandas max_rows)。
// │         逻辑路线:
// │           路径 A(行数 ≤ maxRows)→ 全量输出;
// │           路径 B(行数 > maxRows)→ head/tail + 中间 "..." 行;
// │           路径 C(单元含 < > &)→ 自动转义。
/**
 * DataFrame → HTML 表格,对齐 pandas.to_html(纯 JDK 零依赖)。
 *
 * <p>用法:
 * <pre>{@code
 * String html = HtmlRenderer.of(df).border(1).index(true).render();
 * HtmlRenderer.of(df).renderTo(new File("report.html"));
 * }</pre>
 *
 * <p><b>Styler 子系统</b>(条件染色/渐变/bar)留 M4 实现(规范 04 §2)。
 */
public final class HtmlRenderer {

    private final DataFrame df;
    private boolean index = true;
    private int border = 1;
    private String naRep = "<NA>";
    private String classes = "jian-table";
    private int maxRows = 60;
    private String caption = null;

    private HtmlRenderer(DataFrame df) { this.df = df; }

    /**
     * 创建 HtmlRenderer。
     *
     * @param df DataFrame 待渲染的 DataFrame,非 null
     * @return HtmlRenderer 新建的 HtmlRenderer 实例(默认 index=true / border=1 / naRep="&lt;NA&gt;" / maxRows=60)
     */
    public static HtmlRenderer of(DataFrame df) { return new HtmlRenderer(df); }

    /**
     * 是否输出索引列。
     *
     * @param v boolean true 输出索引列(默认),false 隐藏
     * @return HtmlRenderer 当前实例(链式)
     */
    public HtmlRenderer index(boolean v) { this.index = v; return this; }

    /**
     * 设置 table border 属性。
     *
     * @param v int 边框像素,0 表示无边框
     * @return HtmlRenderer 当前实例(链式)
     */
    public HtmlRenderer border(int v) { this.border = v; return this; }

    /**
     * 设置缺失值显示文本。
     *
     * @param v String 缺失值占位文本,非 null
     * @return HtmlRenderer 当前实例(链式)
     */
    public HtmlRenderer naRep(String v) { this.naRep = v; return this; }
    // 说明:naRep 输出前经 HTML 转义(默认 "<NA>" 显示为文本 <NA>);
    // 若需输出 HTML(如 "<b>NA</b>"),请自行传入已转义形式 "&lt;b&gt;NA&lt;/b&gt;"。

    /**
     * 设置 table 的 CSS class。
     *
     * @param v String CSS class 名(可多个用空格分隔),非 null
     * @return HtmlRenderer 当前实例(链式)
     */
    public HtmlRenderer classes(String v) { this.classes = v; return this; }

    /**
     * 设置最大显示行数,超过则 head/tail 截断。
     *
     * @param v int 最大显示行数,正整数
     * @return HtmlRenderer 当前实例(链式)
     */
    public HtmlRenderer maxRows(int v) { this.maxRows = v; return this; }

    /**
     * 设置 table 标题。
     *
     * @param v String 标题文本,null 表示无标题
     * @return HtmlRenderer 当前实例(链式)
     */
    public HtmlRenderer caption(String v) { this.caption = v; return this; }

    /**
     * 渲染为 HTML 字符串。
     *
     * @return String HTML 表格字符串(含 thead / tbody 与截断占位)
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table border=\"").append(border).append("\" class=\"")
          .append(escape(classes)).append("\">\n");
        if (caption != null) {
            sb.append("  <caption>").append(escape(caption)).append("</caption>\n");
        }

        java.util.List<String> cols = df.columnNames();
        int n = df.rowCount();

        // 表头 thead
        sb.append("  <thead>\n    <tr>");
        if (index) sb.append("<th></th>");
        for (String c : cols) sb.append("<th>").append(escape(c)).append("</th>");
        sb.append("</tr>\n  </thead>\n");

        // 表体 tbody(带截断)
        sb.append("  <tbody>\n");
        boolean truncate = n > maxRows;
        // head/tail 均匀分配:maxRows=1 → head 0 + tail 1
        // (head=(maxRows+1)/2 在奇数值时 head 比 tail 多 1,显示行数超 maxRows)
        int headN = truncate ? maxRows / 2 : n;
        int tailN = truncate ? maxRows - headN : 0;
        for (int r = 0; r < headN; r++) appendRow(sb, r, cols);
        if (truncate) {
            sb.append("    <tr>");
            if (index) sb.append("<th>...</th>");
            for (int c = 0; c < cols.size(); c++) sb.append("<td>...</td>");
            sb.append("</tr>\n");
            for (int r = n - tailN; r < n; r++) appendRow(sb, r, cols);
        }
        sb.append("  </tbody>\n</table>\n");
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, int r, java.util.List<String> cols) {
        sb.append("    <tr>");
        if (index) sb.append("<th>").append(escape(String.valueOf(df.index().get(r)))).append("</th>");
        for (String c : cols) {
            // 用列级 isNull 判断缺失(DOUBLE 的 NaN / Object 的 null 都覆盖)
            int colIdx = df.columnIndex(c);
            boolean missing = colIdx >= 0 && df.getColumn(c).isNull(r);
            Object v = df.get(r, c);
            sb.append("<td>").append(missing ? escape(naRep) : escape(String.valueOf(v))).append("</td>");
        }
        sb.append("</tr>\n");
    }

    /**
     * 落盘。
     *
     * @param file java.io.File 目标 HTML 文件,非 null;父目录需可写
     * @throws java.io.IOException 写文件失败时抛出
     */
    public void renderTo(java.io.File file) throws java.io.IOException {
        java.nio.file.Files.writeString(file.toPath(), render());
    }

    /**
     * 落盘(路径)。
     *
     * @param path String 目标 HTML 文件路径,非 null
     * @throws java.io.IOException 写文件失败时抛出
     */
    public void renderTo(String path) throws java.io.IOException {
        java.nio.file.Files.writeString(java.nio.file.Path.of(path), render());
    }

    /** HTML 转义:< > & " '。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
