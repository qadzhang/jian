package jian.core;

import java.util.List;

// ┌─ What : DataFrameDisplay —— toString 表格格式化(从 DataFrame.java 拆出,落实 §3.1 ≤600 行红线)
// │  Why  : toString 簇(toString/valWidth/appendRow/formatVal/trunc)~75 行,完全内聚(只被 toString 调);
// │         所有 String.format 强制 Locale.ROOT,防德语/法语等 locale 下数字格式错乱。
// │  Who  : 由 DataFrame.toString()/toString(int,int) 委托调用
// │  When : 任何 DataFrame 显示/日志场景
// │  Where: jian-core/DataFrameDisplay.java
// │  How  : 数据走向:df → 列宽扫描(headN/tailN 显示行的 formatVal 长度,封顶 maxColWidth)
// │           → 表头行 + head 数据行 + "..." 截断行 + tail 数据行 + "[N rows × M columns]" 摘要。
// │         关键变量:widths[](列宽数组)、truncate(行数超 maxRows 时 head/tail 截断)、
// │           Locale.ROOT(所有 String.format 强制 ROOT,防 locale 敏感)。
final class DataFrameDisplay {
    private DataFrameDisplay() {}

    /**
     * 格式化表格输出(对齐 pandas __repr__:截断 + head/tail + 维度摘要)。
     *
     * @param df          DataFrame 目标表;非 null
     * @param maxRows     int 最大显示行数;超出按 head/tail 截断并显示 "..."
     * @param maxColWidth int 每列最大字符宽;超出截断
     * @return String 多行表格字符串;空表返回 "Empty DataFrame"
     */
    static String toString(DataFrame df, int maxRows, int maxColWidth) {
        int nRows = df.rowCount();
        List<Column> columns = df.columnsInternal();
        if (nRows == 0) {
            return "Empty DataFrame\ncolumns: " + df.columnNames();
        }
        StringBuilder sb = new StringBuilder();
        List<String> names = df.columnNames();

        boolean truncate = nRows > maxRows;
        int headN = truncate ? maxRows / 2 + 1 : nRows;
        int tailN = truncate ? maxRows / 2 : 0;

        int[] widths = new int[names.size() + 1];
        widths[0] = 4;  // 行索引列宽
        for (int c = 0; c < names.size(); c++) {
            int w = Math.max(names.get(c).length(), 4);
            for (int r = 0; r < headN; r++) w = Math.max(w, valWidth(columns, r, c));
            if (truncate) for (int r = nRows - tailN; r < nRows; r++) w = Math.max(w, valWidth(columns, r, c));
            widths[c + 1] = Math.min(w, maxColWidth);
        }

        // Locale.ROOT(防德语/法语 locale 下数字/宽度格式错乱)
        sb.append(String.format(java.util.Locale.ROOT, "%-" + widths[0] + "s", "")).append(' ');
        for (int c = 0; c < names.size(); c++)
            sb.append(String.format(java.util.Locale.ROOT, "%-" + widths[c + 1] + "s",
                trunc(names.get(c), widths[c + 1]))).append(' ');
        sb.append('\n');

        for (int r = 0; r < headN; r++) appendRow(df, sb, r, widths);
        if (truncate) {
            sb.append(String.format(java.util.Locale.ROOT, "%-" + widths[0] + "s", "...")).append(' ');
            for (int c = 0; c < names.size(); c++)
                sb.append(String.format(java.util.Locale.ROOT, "%-" + widths[c + 1] + "s", "...")).append(' ');
            sb.append('\n');
            for (int r = nRows - tailN; r < nRows; r++) appendRow(df, sb, r, widths);
        }
        sb.append("\n[").append(nRows).append(" rows × ").append(columns.size()).append(" columns]");
        return sb.toString();
    }

    /** 计算 (r, c) 单元格值的显示宽度。 */
    private static int valWidth(List<Column> columns, int r, int c) {
        Object v = columns.get(c).get(r);
        if (v == null) return 4;  // <NA>
        return formatVal(v, columns.get(c).dtype()).length();
    }

    private static void appendRow(DataFrame df, StringBuilder sb, int r, int[] widths) {
        List<Column> columns = df.columnsInternal();
        sb.append(String.format(java.util.Locale.ROOT, "%-" + widths[0] + "s",
            String.valueOf(df.index().get(r)))).append(' ');
        for (int c = 0; c < columns.size(); c++) {
            Object v = columns.get(c).get(r);
            String s = v == null ? "<NA>" : formatVal(v, columns.get(c).dtype());
            sb.append(String.format(java.util.Locale.ROOT, "%-" + widths[c + 1] + "s",
                trunc(s, widths[c + 1]))).append(' ');
        }
        sb.append('\n');
    }

    /** DATETIME 默认显示格式 YYYY-MM-DD HH:MM:SS(24 小时制,空格分隔,对齐 pandas)。 */
    private static final java.time.format.DateTimeFormatter DATETIME_FMT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
    /** DATETIME 含小数秒时的显示格式(毫秒级;.SSS;纳秒更高位截断到毫秒)。 */
    private static final java.time.format.DateTimeFormatter DATETIME_FMT_MILLIS =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    private static String formatVal(Object v, DType dt) {
        if (dt == DType.DOUBLE) return String.format(java.util.Locale.ROOT, "%.6g", ((Number) v).doubleValue());
        // DATETIME 按 YYYY-MM-DD HH:MM:SS 显示(LocalDateTime.toString() 输出 ISO T 分隔
        // 且整分省略秒 "12:00",不符合默认格式约定)
        if (dt == DType.DATETIME && v instanceof java.time.LocalDateTime lt) {
            return lt.getNano() == 0 ? lt.format(DATETIME_FMT) : lt.format(DATETIME_FMT_MILLIS);
        }
        return String.valueOf(v);
    }

    private static String trunc(String s, int w) {
        if (s.length() <= w) return s;
        if (w <= 3) return s.substring(0, w);
        return s.substring(0, w - 3) + "...";
    }
}
