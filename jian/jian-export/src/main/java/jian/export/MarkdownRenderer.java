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

    /** 缺失值表示(默认空串,对齐 pandas to_markdown 默认)。 */
    private String naRep = "";

    /**
     * 设置缺失值表示(对齐 pandas to_markdown(na_rep="N/A"))。
     *
     * @param v String 缺失值展示文本,如 "N/A";null 视为空串
     * @return MarkdownRenderer 当前实例(链式)
     */
    public MarkdownRenderer naRep(String v) { this.naRep = v == null ? "" : v; return this; }

    /**
     * 创建 MarkdownRenderer。
     *
     * @param df DataFrame 待渲染的 DataFrame,非 null
     * @return MarkdownRenderer 新建的 MarkdownRenderer 实例(默认 index=false / maxRows=60)
     */
    public static MarkdownRenderer of(DataFrame df) { return new MarkdownRenderer(df); }

    /**
     * 是否输出索引列。
     *
     * @param v boolean true 输出索引列,false 隐藏(默认)
     * @return MarkdownRenderer 当前实例(链式)
     */
    public MarkdownRenderer index(boolean v) { this.index = v; return this; }

    /**
     * 设置最大显示行数,超过则 head/tail 截断。
     *
     * @param v int 最大显示行数,正整数
     * @return MarkdownRenderer 当前实例(链式)
     */
    public MarkdownRenderer maxRows(int v) { this.maxRows = v; return this; }

    /**
     * 渲染为 GFM Markdown 表格字符串。
     *
     * @return String Markdown 表格文本(含表头/分隔行/数据行,管道符已转义)
     */
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
            boolean missing = df.getColumn(cols.get(c)).isNull(r);
            Object v = df.get(r, c);
            int w = missing ? 0 : Math.min(String.valueOf(v).length(), 30);
            widths[c + offset] = Math.max(widths[c + offset], w);
        }
    }

    private void appendMdRow(StringBuilder sb, int r, int[] widths, List<String> cols, int offset, List<DType> dtypes) {
        sb.append('|');
        if (index) sb.append(pad(String.valueOf(df.index().get(r)), widths[0], false)).append('|');
        for (int c = 0; c < cols.size(); c++) {
            boolean missing = df.getColumn(cols.get(c)).isNull(r);
            Object v = df.get(r, c);
            // 缺失行显示 naRep(可配,默认空串)
            String s = missing ? naRep : String.valueOf(v);
            // 先转义反斜杠再转义管道符(只转义 | 的话,值里的 \| 会渲染成分隔符)
            s = s.replace("\\", "\\\\").replace("|", "\\|");
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
