package jian.export;

import jian.core.DataFrame;
import jian.core.DType;

import java.util.List;

// ┌─ What : ConsoleRenderer —— 控制台 repr(对齐 pandas df.__repr__ + 中文按 2 宽对齐)
// │  Why  : 规范 04 §3.5;DataFrame.toString 是基础版,本类支持东亚字符宽度对齐
// │  Who  : 用户经 df.show() 或 ConsoleRenderer.render(df) 调用
// │  When : REPL/控制台输出表格
// │  Where: jian-export/ConsoleRenderer.java
// │  How  : 数据走向:DataFrame → 按列扫显示宽度(东亚字符 2 宽)→ 对齐拼字符串。
// │         关键变量变化:
// │           - 显示宽度用 Character.UnicodeBlock 判定 CJK;
// │           - 缺失值显示 <NA>;
// │           - 大表截断 + head/tail。
/**
 * 控制台表格 repr,对齐 pandas df.__repr__。
 *
 * <p><b>中文按 2 宽</b>计算对齐(规范 §3.5):CJK 表意文字、全角符号占 2 列。
 *
 * <p>用法:
 * <pre>{@code
 * System.out.println(ConsoleRenderer.render(df));
 * }</pre>
 */
public final class ConsoleRenderer {

    private ConsoleRenderer() {}

    /**
     * 默认 maxRows=60, maxColWidth=30。
     *
     * @param df DataFrame 待渲染的 DataFrame,非 null
     * @return String 控制台对齐表格文本
     */
    public static String render(DataFrame df) {
        return render(df, 60, 30);
    }

    /**
     * 渲染对齐表格。
     *
     * @param df DataFrame 待渲染的 DataFrame,非 null
     * @param maxRows int 显示最大行数(超过 head/tail 截断),正整数
     * @param maxColWidth int 每列最大宽度(字符,超则截断加 ...)
     * @return String 控制台对齐表格文本(含 CJK 宽度对齐 + 行列数摘要)
     */
    /** 渲染为控制台文本(缺失显示空字符串,对齐 AGENTS §3.5.2)。 */
    public static String render(DataFrame df, int maxRows, int maxColWidth) {
        return render(df, maxRows, maxColWidth, "");
    }

    /**
     * 渲染为控制台文本(可配置缺失占位)。
     * <p>默认空串对齐 AGENTS §3.5.2;需要 &lt;NA&gt; 展示的用户显式传参。
     * @param df DataFrame 目标表,非 null
     * @param maxRows int 最多渲染行数
     * @param maxColWidth int 单列最大显示宽度
     * @param naRep String 缺失占位文本;null 按 "" 处理
     * @return String 对齐的控制台文本
     */
    public static String render(DataFrame df, int maxRows, int maxColWidth, String naRep) {
        if (df.rowCount() == 0) {
            return "Empty DataFrame\n列:" + df.columnNames();
        }
        StringBuilder sb = new StringBuilder();
        List<String> cols = df.columnNames();
        int n = df.rowCount();

        boolean truncate = n > maxRows;
        int headN = truncate ? (maxRows + 1) / 2 : n;
        int tailN = truncate ? maxRows / 2 : 0;

        // 列宽:列名 + 数据显示宽度,取 max,封顶 maxColWidth
        int[] widths = new int[cols.size() + 1];
        widths[0] = 4;  // 索引列
        for (int c = 0; c < cols.size(); c++) {
            widths[c + 1] = Math.min(Math.max(displayWidth(cols.get(c)), 4), maxColWidth);
        }
        for (int r = 0; r < headN; r++) {
            widths[0] = Math.max(widths[0], Math.min(displayWidth(String.valueOf(df.index().get(r))), maxColWidth));
            for (int c = 0; c < cols.size(); c++) {
                Object v = df.getColumn(cols.get(c)).get(r);
                int w = v == null ? 4 : displayWidth(String.valueOf(v));
                widths[c + 1] = Math.max(widths[c + 1], Math.min(w, maxColWidth));
            }
        }
        if (truncate) {
            for (int r = n - tailN; r < n; r++) {
                widths[0] = Math.max(widths[0], Math.min(displayWidth(String.valueOf(df.index().get(r))), maxColWidth));
                for (int c = 0; c < cols.size(); c++) {
                    Object v = df.getColumn(cols.get(c)).get(r);
                    int w = v == null ? 4 : displayWidth(String.valueOf(v));
                    widths[c + 1] = Math.max(widths[c + 1], Math.min(w, maxColWidth));
                }
            }
        }

        // 表头
        sb.append(pad("", widths[0])).append(' ');
        for (int c = 0; c < cols.size(); c++) {
            sb.append(pad(cols.get(c), widths[c + 1])).append(' ');
        }
        sb.append('\n');

        for (int r = 0; r < headN; r++) appendRow(sb, df, r, widths, cols, naRep);
        if (truncate) {
            sb.append(pad("...", widths[0])).append(' ');
            for (int c = 0; c < cols.size(); c++) sb.append(pad("...", widths[c + 1])).append(' ');
            sb.append('\n');
            for (int r = n - tailN; r < n; r++) appendRow(sb, df, r, widths, cols, naRep);
        }
        sb.append("\n[").append(n).append(" 行 × ").append(cols.size()).append(" 列]");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, DataFrame df, int r, int[] widths, List<String> cols, String naRep) {
        sb.append(pad(String.valueOf(df.index().get(r)), widths[0])).append(' ');
        for (int c = 0; c < cols.size(); c++) {
            // 用 isNull 判断缺失(DOUBLE 列 NaN 不是 null);缺失默认空字符串,对齐 AGENTS §3.5.2
            boolean missing = df.getColumn(cols.get(c)).isNull(r);
            Object v = df.getColumn(cols.get(c)).get(r);
            String s = missing ? naRep : String.valueOf(v);
            sb.append(pad(s, widths[c + 1])).append(' ');
        }
        sb.append('\n');
    }

    /** 按显示宽度右补空格(CJK 字符占 2 列)。 */
    private static String pad(String s, int width) {
        int w = displayWidth(s);
        if (w >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < width - w; i++) sb.append(' ');
        return sb.toString();
    }

    /**
     * 计算字符串的显示宽度(CJK/全角字符 2,其余 1)。
     *
     * @param s String 待测字符串,null 视为 0 宽
     * @return int 显示宽度(CJK 字符按 2 计,ASCII 按 1 计)
     */
    public static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += charWidth(s.charAt(i));
        }
        return w;
    }

    /** 单字符显示宽度:CJK 表意/全角符号 = 2,其余 = 1。 */
    private static int charWidth(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        // CJK 统一表意、扩展、日文/韩文、全角符号 → 2 宽
        if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HANGUL_JAMO
                || b == Character.UnicodeBlock.BOPOMOFO
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
            return 2;
        }
        return 1;
    }
}
