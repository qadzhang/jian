package jian.export;

import jian.core.DataFrame;
import jian.core.DType;

import java.util.List;

// ┌─ What : MarkdownRenderer —— DataFrame → GFM Markdown 表格(对齐 pandas df.to_markdown)
// │  Why  : 规范 04 §3.4;Markdown 是文档/README 嵌入常用格式
// │  Who  : 用户经 df 调用 toMarkdown
// │  When : README、文档、Issue 嵌入
// │  Where: jian-export/MarkdownRenderer.java
// │  How  : 数据走向:DataFrame → 列宽自适应 → GFM 表格语法(| col | col | + 分隔行)。
// │         关键变量变化:
// │           - widths:每列最大宽度(列名 + 数据最长);
// │           - 数值列默认右对齐,字符串左对齐(对齐 GFM 习惯)。
/**
 * DataFrame → GFM Markdown 表格,对齐 pandas.to_markdown。
 *
 * <p>用法:
 * <pre>{@code
 * String md = MarkdownRenderer.of(df).render();
 * }</pre>
 */
public final class MarkdownRenderer {

    private final DataFrame df;
    private boolean index = false;
    private int maxRows = 60;

    private MarkdownRenderer(DataFrame df) { this.df = df; }

    public static MarkdownRenderer of(DataFrame df) { return new MarkdownRenderer(df); }

    public MarkdownRenderer index(boolean v) { this.index = v; return this; }
    public MarkdownRenderer maxRows(int v) { this.maxRows = v; return this; }

    public String render() {
        List<String> cols = df.columnNames();
        List<DType> dtypes = df.dtypes();
        int n = df.rowCount();

        // 列宽:列名长 + 数据最长,封顶 30
        int[] widths = new int[cols.size() + (index ? 1 : 0)];
        int offset = index ? 1 : 0;
        if (index) widths[0] = 4;
        for (int c = 0; c < cols.size(); c++) widths[c + offset] = Math.min(Math.max(cols.get(c).length(), 4), 30);

        boolean truncate = n > maxRows;
        int headN = truncate ? (maxRows + 1) / 2 : n;
        int tailN = truncate ? maxRows / 2 : 0;

        // 扫描数据更新列宽
        for (int r = 0; r < headN; r++) updateWidths(widths, r, cols, offset);
        if (truncate) for (int r = n - tailN; r < n; r++) updateWidths(widths, r, cols, offset);

        StringBuilder sb = new StringBuilder();
        // 表头行
        sb.append('|');
        if (index) sb.append(pad("", widths[0], false)).append('|');
        for (int c = 0; c < cols.size(); c++) sb.append(pad(cols.get(c), widths[c + offset], false)).append('|');
        sb.append('\n');
        // 分隔行(数值右对齐,字符串左对齐)
        sb.append('|');
        if (index) sb.append(repeat('-', widths[0])).append('|');
        for (int c = 0; c < cols.size(); c++) {
            boolean numeric = dtypes.get(c).isNumeric();
            String fill = numeric ? repeat('-', widths[c + offset] - 1) : repeat('-', widths[c + offset] - 1);
            sb.append(numeric ? fill + ":|" : ":" + fill + "|");
        }
        sb.append('\n');
        // 数据行
        for (int r = 0; r < headN; r++) appendMdRow(sb, r, widths, cols, offset, dtypes);
        if (truncate) {
            sb.append('|');
            if (index) sb.append(pad("...", widths[0], false)).append('|');
            for (int c = 0; c < cols.size(); c++) sb.append(pad("...", widths[c + offset], false)).append('|');
            sb.append('\n');
            for (int r = n - tailN; r < n; r++) appendMdRow(sb, r, widths, cols, offset, dtypes);
        }
        return sb.toString();
    }

    private void updateWidths(int[] widths, int r, List<String> cols, int offset) {
        if (index) widths[0] = Math.max(widths[0], Math.min(String.valueOf(df.index().get(r)).length(), 30));
        for (int c = 0; c < cols.size(); c++) {
            Object v = df.get(r, c);
            int w = v == null ? 4 : Math.min(String.valueOf(v).length(), 30);
            widths[c + offset] = Math.max(widths[c + offset], w);
        }
    }

    private void appendMdRow(StringBuilder sb, int r, int[] widths, List<String> cols, int offset, List<DType> dtypes) {
        sb.append('|');
        if (index) sb.append(pad(String.valueOf(df.index().get(r)), widths[0], false)).append('|');
        for (int c = 0; c < cols.size(); c++) {
            Object v = df.get(r, c);
            String s = v == null ? "<NA>" : String.valueOf(v);
            // 管道符转义(对齐规范 04 §5)
            s = s.replace("|", "\\|");
            sb.append(pad(s, widths[c + offset], dtypes.get(c).isNumeric())).append('|');
        }
        sb.append('\n');
    }

    private static String pad(String s, int w, boolean rightAlign) {
        if (s.length() >= w) return s;
        String pad = repeat(' ', w - s.length());
        return rightAlign ? pad + s : s + pad;
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
