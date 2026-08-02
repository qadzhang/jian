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

    public static HtmlRenderer of(DataFrame df) { return new HtmlRenderer(df); }

    public HtmlRenderer index(boolean v) { this.index = v; return this; }
    public HtmlRenderer border(int v) { this.border = v; return this; }
    public HtmlRenderer naRep(String v) { this.naRep = v; return this; }
    public HtmlRenderer classes(String v) { this.classes = v; return this; }
    public HtmlRenderer maxRows(int v) { this.maxRows = v; return this; }
    public HtmlRenderer caption(String v) { this.caption = v; return this; }

    /** 渲染为 HTML 字符串。 */
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
        int headN = truncate ? (maxRows + 1) / 2 : n;
        int tailN = truncate ? maxRows / 2 : 0;
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

    /** 落盘。 */
    public void renderTo(java.io.File file) throws java.io.IOException {
        java.nio.file.Files.writeString(file.toPath(), render());
    }

    /** 落盘(路径)。 */
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
