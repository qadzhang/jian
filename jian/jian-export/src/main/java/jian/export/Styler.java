package jian.export;

import jian.core.Column;
import jian.core.DataFrame;
import jian.core.DType;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : Styler —— DataFrame 样式子系统(对齐 pandas.io.formats.style.Styler)
// │  Why  : 规范 04 §2;Styler 是独立子系统:format/highlight/gradient/bar/setTableStyles/caption/hide
// │  Who  : 用户经 df.style() 创建,链式叠加规则,最后 toHtml/toLatex/toExcel
// │  When : 报表美化、数据高亮
// │  Where: jian-export/Styler.java
// │  How  : 数据走向:DataFrame + List<StyleRule> → 渲染时按 (rowIdx, colIdx) 遍历,
// │         逐个应用匹配规则,合并最终样式 → 输出带样式的 HTML/LaTeX。
// │         关键变量变化:
// │           - rules:List<StyleRule>,顺序保留(链式叠加);
// │           - 求值时机:渲染时才扫列算 min/max(gradient)、阈值(highlight)。
// │         逻辑路线:
// │           路径 A(format)→ 数值格式化(printf 模式);
// │           路径 B(highlightMax/Min)→ 扫列算极值,极值位上色;
// │           路径 C(backgroundGradient)→ 扫列算 min/max,线性插值查色;
// │           路径 D(bar)→ 扫列算范围,渲染条宽百分比。
/**
 * DataFrame 样式子系统,对齐 pandas.Styler。
 *
 * <p>用法:
 * <pre>{@code
 * String html = df.style()
 *     .format("#,##0.00", "salary")
 *     .backgroundGradient("salary", ColorMap.GREEN_YELLOW_RED)
 *     .highlightMax("salary", "#ffff00")
 *     .setCaption("员工表")
 *     .hideIndex()
 *     .toHtml();
 * }</pre>
 *
 * <p>M4 实现核心子集:format / highlightMax / highlightMin / highlightNull /
 * backgroundGradient / bar / setCaption / hideIndex / hideColumns。
 */
public final class Styler {

    private final DataFrame df;
    private final List<StyleRule> rules = new ArrayList<>();
    private String caption = null;
    private boolean hideIndex = false;
    private final List<String> hiddenColumns = new ArrayList<>();
    private final List<String> tableStyles = new ArrayList<>();
    private boolean autoWidth = true;   // toExcel 自动列宽默认开
    // HTML 缺失值默认 "<NA>" —— 与 HtmlRenderer.naRep 默认
    // 一致(§3.5.2 契约:HTML 默认 <NA>,不得输出裸 NaN/空串);输出前经 escape
    private String naRep = "<NA>";

    Styler(DataFrame df) { this.df = df; }

    /**
     * 创建 Styler(df.style())。
     *
     * @param df DataFrame 被美化的 DataFrame,非 null
     * @return Styler 新建的可链式叠加规则的 Styler 实例
     */
    public static Styler of(DataFrame df) { return new Styler(df); }

    // ======================== 规则 ========================

    /**
     * 数值格式化(对齐 .format),printf 风格如 "#,##0.00" 或 "%.2f"。
     *
     * @param pattern String 格式模式,如 "#,##0.00"、"%.2f"、"0.00%",非 null
     * @param cols String[] 作用于的列名,可变参数,可为空(空则不施加任何列)
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler format(String pattern, String... cols) {
        for (String c : cols) rules.add(new FormatRule(c, pattern));
        return this;
    }

    /**
     * 全表数值格式化。
     *
     * @param pattern String 格式模式,如 "#,##0.00"、"%.2f",非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler format(String pattern) {
        for (String c : df.columnNames()) rules.add(new FormatRule(c, pattern));
        return this;
    }

    /**
     * 高亮最大值单元格(对齐 .highlight_max)。
     *
     * @param col String 列名,非 null;必须存在于 DataFrame 中
     * @param color String 颜色值,十六进制(如 "#ffff00")或色图常量,非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler highlightMax(String col, String color) {
        rules.add(new HighlightExtremeRule(col, color, true));
        return this;
    }

    /**
     * 高亮最小值单元格。
     *
     * @param col String 列名,非 null;必须存在于 DataFrame 中
     * @param color String 颜色值,十六进制(如 "#ffff00")或色图常量,非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler highlightMin(String col, String color) {
        rules.add(new HighlightExtremeRule(col, color, false));
        return this;
    }

    /**
     * 高亮缺失值。
     *
     * @param color String 颜色值,十六进制(如 "#ffff00"),非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler highlightNull(String color) {
        rules.add(new HighlightNullRule(color));
        return this;
    }

    /**
     * 背景颜色渐变(对齐 .background_gradient)。
     *
     * @param col String 列名,非 null;必须存在于 DataFrame 中且为数值列
     * @param colorMap String 色图字符串,冒号分隔(如 ColorMap.GREEN_YELLOW_RED),非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler backgroundGradient(String col, String colorMap) {
        rules.add(new GradientRule(col, colorMap));
        return this;
    }

    /**
     * 单元格内条形(对齐 .bar)。
     *
     * @param col String 列名,非 null;必须存在于 DataFrame 中且为数值列
     * @param color String 条形颜色,十六进制(如 "#0066cc"),非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler bar(String col, String color) {
        rules.add(new BarRule(col, color));
        return this;
    }

    /**
     * 设置表标题(对齐 .set_caption)。
     *
     * @param cap String 标题文本,非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler setCaption(String cap) { this.caption = cap; return this; }

    /**
     * 隐藏索引列(对齐 .hide_index)。
     *
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler hideIndex() { this.hideIndex = true; return this; }

    /**
     * 隐藏指定列(对齐 .hide_columns)。
     *
     * @param cols String[] 要隐藏的列名,可变参数,可为空
     * @return Styler 当前 Styler(支持链式调用)
     */
    public Styler hideColumns(String... cols) {
        for (String c : cols) hiddenColumns.add(c);
        return this;
    }

    /**
     * 设置全局 CSS 样式(对齐 .set_table_styles),写入 &lt;style&gt; 块。
     *
     * @param css String[] CSS 文本,可变参数,每条独立一行;非 null
     * @return Styler 当前 Styler(支持链式调用)
     */
    // ======================== 字体样式(字体颜色/加粗,对齐 pandas Styler.applymap)========================

    /**
     * 整列字体颜色(HTML 输出 color:;Excel 输出 POI Font.setColor)。
     * @param col String 列名,必须存在;非 null
     * @param color String 颜色(#rrggbb);非 null
     * @return Styler 自身(链式)
     */
    public Styler fontColor(String col, String color) {
        rules.add(new FontRule(col, color, false, null));
        return this;
    }

    /**
     * 条件字体颜色(谓词命中行的字面着色,对齐 pandas applymap(lambda v: 'color:red' if v&lt;0))。
     * @param col String 列名,必须存在;非 null
     * @param color String 颜色(#rrggbb);非 null
     * @param cond java.util.function.Predicate&lt;Object&gt; 值谓词(缺失值不参与判定);非 null
     * @return Styler 自身(链式)
     */
    public Styler fontColorIf(String col, String color, java.util.function.Predicate<Object> cond) {
        rules.add(new FontRule(col, color, false, cond));
        return this;
    }

    /**
     * 整列加粗。
     * @param col String 列名,必须存在;非 null
     * @return Styler 自身(链式)
     */
    public Styler bold(String col) {
        rules.add(new FontRule(col, null, true, null));
        return this;
    }

    /**
     * 条件加粗(谓词命中行加粗)。
     * @param col String 列名,必须存在;非 null
     * @param cond java.util.function.Predicate&lt;Object&gt; 值谓词;非 null
     * @return Styler 自身(链式)
     */
    public Styler boldIf(String col, java.util.function.Predicate<Object> cond) {
        rules.add(new FontRule(col, null, true, cond));
        return this;
    }

    /**
     * 自动列宽开关(toExcel;默认开)。宽度 = max(表头宽, 内容宽)×1.2(中文按 2 宽),上限 80。
     * @param enable boolean true=写完自动设列宽
     * @return Styler 自身(链式)
     */
    public Styler autoColumnWidth(boolean enable) {
        this.autoWidth = enable;
        return this;
    }

    /**
     * 条件整行背景色(依据列的谓词命中 → 该行所有可见单元格染色;对齐 pandas Styler.apply(axis=1) 常用诉求,
     * 如"金额为负整行标红")。
     * @param col String 判定依据列名,必须存在;非 null
     * @param color String 背景色(#rrggbb);非 null
     * @param cond java.util.function.Predicate&lt;Object&gt; 值谓词(缺失值不参与判定);非 null
     * @return Styler 自身(链式)
     */
    public Styler rowBackgroundIf(String col, String color, java.util.function.Predicate<Object> cond) {
        rules.add(new RowBgRule(col, color, cond));
        return this;
    }

    /**
     * 整列背景色(固定色;对齐 pandas apply(axis=0))。
     * @param col String 列名,必须存在;非 null
     * @param color String 背景色(#rrggbb);非 null
     * @return Styler 自身(链式)
     */
    public Styler columnBackground(String col, String color) {
        rules.add(new ColumnBgRule(col, color));
        return this;
    }

    public Styler setTableStyles(String... css) {
        for (String s : css) tableStyles.add(s);
        return this;
    }

    /**
     * 设置 HTML 缺失值表示:默认 {@code "<NA>"}(对齐 HtmlRenderer.naRep
     * 与 §3.5.2 契约);输出前经 HTML 转义,自定义值含 {@code <} 也安全。仅作用于 toHtml
     * (Excel 里缺失值恒为空单元格,Excel 的"空就是空"语义不变)。
     *
     * @param v String 缺失值显示文本(如 "-"、"NA"、"&lt;NA&gt;"),非 null
     * @return Styler 自身(链式)
     */
    public Styler naRep(String v) {
        this.naRep = v;
        return this;
    }

    // ======================== 输出 HTML ========================

    /**
     * 渲染为带样式的 HTML(对齐 styler.to_html)。
     *
     * @return String HTML 文本,含 &lt;style&gt; 与 &lt;table&gt; 标签
     */
    public String toHtml() {
        // 先算各规则的辅助数据(极值/min-max)
        for (StyleRule r : rules) r.prepare(df);
        // 隐藏列过滤
        List<String> visibleCols = new ArrayList<>();
        for (String c : df.columnNames()) if (!hiddenColumns.contains(c)) visibleCols.add(c);

        StringBuilder sb = new StringBuilder();
        sb.append("<style>\n");
        for (String css : tableStyles) sb.append("  ").append(css).append('\n');
        sb.append("</style>\n");
        sb.append("<table class=\"jian-styled\">\n");
        if (caption != null) sb.append("  <caption>").append(escape(caption)).append("</caption>\n");

        // 表头
        sb.append("  <thead><tr>");
        if (!hideIndex) sb.append("<th></th>");
        for (String c : visibleCols) sb.append("<th>").append(escape(c)).append("</th>");
        sb.append("</tr></thead>\n  <tbody>\n");

        for (int r = 0; r < df.rowCount(); r++) {
            sb.append("    <tr>");
            if (!hideIndex) sb.append("<th>").append(escape(String.valueOf(df.index().get(r)))).append("</th>");
            for (String c : visibleCols) {
                int colIdx = df.columnIndex(c);
                boolean missing = colIdx >= 0 && df.getColumn(c).isNull(r);
                Object v = df.getColumn(c).get(r);
                String text = formatValue(c, r, v);
                // 缺失行传 null 给样式计算(DoubleColumn.get(NaN) 返回 Double.NaN 不是 null)
                String style = computeStyle(c, r, missing ? null : v);
                // 缺失值输出 naRep(默认 <NA>,与 HtmlRenderer 口径一致;
                // 满足 §3.5.2 "HTML 默认 <NA>";经 escape 防自定义值破坏结构)
                sb.append("<td").append(style.isEmpty() ? "" : " style=\"" + style + "\"").append(">")
                  .append(missing ? escape(naRep) : escape(text)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("  </tbody>\n</table>\n");
        return sb.toString();
    }

    /** 求某单元格的累积 inline style(各规则拼接)。 */
    private String computeStyle(String col, int row, Object value) {
        StringBuilder sb = new StringBuilder();
        // 整行背景优先传播(依据列谓词命中 → 所有可见列染色)
        for (StyleRule r : rules) {
            if (r instanceof RowBgRule rb) {
                Column base = df.getColumn(rb.col);
                Object judge = base.isNull(row) ? null : base.get(row);
                if (judge != null && rb.cond.test(judge)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append("background-color: ").append(rb.color);
                }
            }
        }
        for (StyleRule r : rules) {
            String s = r.apply(col, row, value);
            if (s != null && !s.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** 按 format 规则格式化值。 */
    private String formatValue(String col, int row, Object value) {
        if (value == null) return "";
        for (StyleRule r : rules) {
            if (r instanceof FormatRule && col.equals(((FormatRule) r).col)) {
                return ((FormatRule) r).format(value);
            }
        }
        return String.valueOf(value);
    }

    private static String escape(String s) {
        if (s == null) return "";
        // 补齐 " 与 ' 的转义(caption/属性值含引号会破坏 HTML 结构,
        // 与 HtmlRenderer.escape 的 5 字符口径一致)
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ======================== 内置色图 ========================

    /** 简单 RGB 插值(从十六进制色码)。 */
    static int[] hexRgb(String hex) {
        String h = hex.replace("#", "");
        // 支持 3 位简写 #abc → #aabbcc(不做扩展的话 substring 取 1 字符 parseInt 抛异常)
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        return new int[]{ Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16) };
    }

    static String rgbHex(int[] rgb) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** 内置色图常量。 */
    public static final class ColorMap {
        public static final String GREEN_YELLOW_RED = "#00ff00:#ffff00:#ff0000";
        public static final String BLUE_RED = "#0000ff:#ff0000";
        public static final String WHITE_BLUE = "#ffffff:#0066cc";
    }

    /** 解析色图字符串(冒号分隔的色码序列)。 */
    static String colorAt(String colorMap, double t) {
        String[] parts = colorMap.split(":");
        if (parts.length == 1) return parts[0];
        double scaled = t * (parts.length - 1);
        int i = (int) Math.floor(scaled);
        int j = Math.min(i + 1, parts.length - 1);
        double frac = scaled - i;
        int[] a = hexRgb(parts[i]);
        int[] b = hexRgb(parts[j]);
        return rgbHex(new int[]{
                (int) (a[0] * (1 - frac) + b[0] * frac),
                (int) (a[1] * (1 - frac) + b[1] * frac),
                (int) (a[2] * (1 - frac) + b[2] * frac) });
    }

    // ======================== 规则类型 ========================

    enum Type { FORMAT, HIGHLIGHT_MAX, HIGHLIGHT_MIN, HIGHLIGHT_NULL, GRADIENT, BAR, FONT, ROW_BG, COL_BG }

    static class StyleRule {
        final String col;
        final String color;
        final Type type;
        StyleRule(String c, String color, Type t) { this.col = c; this.color = color; this.type = t; }
        /** 预处理(扫列算极值/min-max),默认空。 */
        void prepare(DataFrame df) {}
        /** 返回 inline style 字符串(无则空)。 */
        String apply(String col, int row, Object value) { return ""; }
    }

    /** 数值格式化规则。 */
    static class FormatRule extends StyleRule {
        final String pattern;
        FormatRule(String col, String pattern) {
            super(col, null, Type.FORMAT);
            this.pattern = pattern;
        }
        @Override String apply(String col, int row, Object value) { return ""; }  // 格式化在 formatValue 处理
        String format(Object v) {
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                // 简化:#,##0.00 → 用 %,.2f;0.00% → 用百分数;其它直接 printf
                String p = pattern;
                if (p.contains(",")) return String.format(java.util.Locale.ROOT, "%,.2f", d);
                if (p.endsWith("%")) return String.format(java.util.Locale.ROOT, "%.2f%%", d * 100);
                if (p.equals("0") || p.equals("#")) return String.valueOf((long) d);
                try { return String.format(java.util.Locale.ROOT, p, d); } catch (Exception e) { return String.valueOf(d); }
            }
            return String.valueOf(v);
        }
    }

    /** 极值高亮规则(max 或 min)。 */
    static class HighlightExtremeRule extends StyleRule {
        final boolean isMax;
        double extreme;
        HighlightExtremeRule(String col, String color, boolean isMax) {
            super(col, color, isMax ? Type.HIGHLIGHT_MAX : Type.HIGHLIGHT_MIN);
            this.isMax = isMax;
        }
        @Override void prepare(DataFrame df) {
            Column c = df.getColumn(col);
            extreme = isMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            for (int i = 0; i < c.size(); i++) {
                if (c.isNull(i)) continue;
                double v = c.getDouble(i);
                if (isMax ? v > extreme : v < extreme) extreme = v;
            }
        }
        @Override String apply(String col, int row, Object value) {
            if (!col.equals(this.col) || value == null) return "";
            if (!(value instanceof Number)) return "";
            double v = ((Number) value).doubleValue();
            if (v == extreme) return "background-color: " + color;
            return "";
        }
    }

    /** 缺失值高亮。 */
    static class HighlightNullRule extends StyleRule {
        HighlightNullRule(String color) { super(null, color, Type.HIGHLIGHT_NULL); }
        @Override String apply(String col, int row, Object value) {
            // DOUBLE 列缺失是 NaN 不是 null,两者都高亮
            boolean missing = value == null || (value instanceof Double && ((Double) value).isNaN());
            return missing ? "background-color: " + color : "";
        }
    }

    /** 背景渐变规则。 */
    static class GradientRule extends StyleRule {
        double min, max;
        GradientRule(String col, String colorMap) {
            super(col, colorMap, Type.GRADIENT);
        }
        @Override void prepare(DataFrame df) {
            Column c = df.getColumn(col);
            min = Double.POSITIVE_INFINITY; max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < c.size(); i++) {
                if (c.isNull(i)) continue;
                double v = c.getDouble(i);
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        @Override String apply(String col, int row, Object value) {
            if (!col.equals(this.col) || value == null || !(value instanceof Number)) return "";
            double v = ((Number) value).doubleValue();
            if (max == min) return "background-color: " + colorAt(this.color, 0.5);
            double t = (v - min) / (max - min);
            return "background-color: " + colorAt(this.color, t);
        }
    }

    /** 单元格条形(背景色条,宽度按值占 max 的百分比)。 */
    static class BarRule extends StyleRule {
        double max;
        BarRule(String col, String color) { super(col, color, Type.BAR); }
        double min = 0;
        @Override void prepare(DataFrame df) {
            Column c = df.getColumn(col);
            max = Double.NEGATIVE_INFINITY; min = Double.POSITIVE_INFINITY;
            for (int i = 0; i < c.size(); i++) {
                if (c.isNull(i)) continue;
                double v = c.getDouble(i);
                if (v > max) max = v;
                if (v < min) min = v;
            }
            if (min == Double.POSITIVE_INFINITY) min = 0;
        }
        @Override String apply(String col, int row, Object value) {
            if (!col.equals(this.col) || value == null || !(value instanceof Number)) return "";
            double v = ((Number) value).doubleValue();
            // 全负列按 (v-min)/(max-min) 映射:
            // 因为 100*v/max 在 max 为负时百分比语义反转(条长与值大小相反)
            int pct;
            if (max > 0) pct = (int) (100 * v / max);
            else pct = (max == min) ? 0 : (int) (100 * (v - min) / (max - min));
            pct = Math.max(0, Math.min(100, pct));
            // 用线性渐变模拟条形
            return "background: linear-gradient(to right, " + color + " " + pct + "%, transparent " + pct + "%)";
        }
    }

    /** 字体样式规则(颜色/加粗,可带值谓词;对齐 pandas applymap)。 */
    static class FontRule extends StyleRule {
        final boolean bold;
        final java.util.function.Predicate<Object> cond;
        FontRule(String col, String fontColor, boolean bold, java.util.function.Predicate<Object> cond) {
            super(col, fontColor, Type.FONT);
            this.bold = bold;
            this.cond = cond;
        }
        @Override String apply(String col, int row, Object value) {
            if (!col.equals(this.col) || value == null) return "";
            if (cond != null && !cond.test(value)) return "";
            StringBuilder sb = new StringBuilder();
            if (color != null) sb.append("color: ").append(color);
            if (bold) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("font-weight: bold");
            }
            return sb.toString();
        }
    }

    /** 条件整行背景(依据列谓词命中 → 整行所有列染色)。 */
    static class RowBgRule extends StyleRule {
        final java.util.function.Predicate<Object> cond;
        RowBgRule(String col, String color, java.util.function.Predicate<Object> cond) {
            super(col, color, Type.ROW_BG);
            this.cond = cond;
        }
        @Override String apply(String col, int row, Object value) {
            // value 是当前遍历列的值,判定依据是 rule.col 列的值 —— 见 computeStyle 的整行传播
            return "";
        }
    }

    /** 整列固定背景。 */
    static class ColumnBgRule extends StyleRule {
        ColumnBgRule(String col, String color) {
            super(col, color, Type.COL_BG);
        }
        @Override String apply(String col, int row, Object value) {
            return col.equals(this.col) ? "background-color: " + color : "";
        }
    }

    // ======================== 输出 Excel(POI 条件格式,对齐 styler.to_excel)========================

    /**
     * 渲染为 Excel 文件(对齐 pandas styler.to_excel)。把 Styler 规则应用到单元格:
     * <ul>
     *   <li>highlightMax/Min → 极值单元格背景色(直接设 CellStyle);</li>
     *   <li>backgroundGradient → 按 min/max 插值,每单元格背景色(简化为三段色:低/中/高);</li>
     *   <li>bar → M5 简化不实现(POI DataBar API 繁琐,v2 补);</li>
     *   <li>highlightNull → 空单元格背景色;</li>
     *   <li>format → DataFormat 数字格式。</li>
     * </ul>
     *
     * @param file java.io.File 目标 .xlsx 文件路径,非 null;父目录需可写
     * @throws java.io.IOException 写文件失败时抛出(磁盘满 / 无权限 / 路径非法)
     */
    public void toExcel(java.io.File file) throws java.io.IOException {
        for (StyleRule r : rules) r.prepare(df);
        List<String> visibleCols = new ArrayList<>();
        for (String c : df.columnNames()) if (!hiddenColumns.contains(c)) visibleCols.add(c);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(caption != null ? sanitizeSheetName(caption) : "jian");
            org.apache.poi.ss.usermodel.DataFormat dataFormat = wb.createDataFormat();
            // 表头
            org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            int colOff = hideIndex ? 0 : 1;
            if (!hideIndex) header.createCell(0).setCellValue("");
            for (int c = 0; c < visibleCols.size(); c++) {
                org.apache.poi.ss.usermodel.Cell hc = header.createCell(c + colOff);
                hc.setCellValue(visibleCols.get(c));
                hc.setCellStyle(headerStyle);
            }
            // 数据行 + 应用规则
            for (int r = 0; r < df.rowCount(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                // 整型索引按数值写入 Excel(字符串文本无法聚合)
                if (!hideIndex) {
                    Object lbl = df.index().get(r);
                    org.apache.poi.ss.usermodel.Cell ic = row.createCell(0);
                    if (lbl instanceof Number) ic.setCellValue(((Number) lbl).doubleValue());
                    else ic.setCellValue(String.valueOf(lbl));
                }
                for (int c = 0; c < visibleCols.size(); c++) {
                    String colName = visibleCols.get(c);
                    boolean missing = df.getColumn(colName).isNull(r);
                    Object v = df.getColumn(colName).get(r);
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c + colOff);
                    // 缺失行:空单元格(不写 "NaN";Excel 里空就是空)
                    if (!missing) {
                        if (v instanceof Number) cell.setCellValue(((Number) v).doubleValue());
                        else if (v instanceof Boolean) cell.setCellValue((Boolean) v);
                        else {
                            // Styler.toExcel 是独立的第二条 Excel 写出路径,必须有公式注入防护
                            // ("=cmd|' /C calc'!A0" 这类值原样写入会违反 AGENTS §3.7.3)。
                            // 防护与 jian-io-excel/jian-io-csv 对齐(6 字符跳过集 + ' 前缀);
                            // 三处实现互指:Excel.startsWithFormulaAfterWhitespace /
                            // Csv.startsWithFormulaAfterWhitespace / 本类 startsWithFormulaAfterWhitespace
                            String sv = String.valueOf(v);
                            cell.setCellValue(startsWithFormulaAfterWhitespace(sv) ? "'" + sv : sv);
                        }
                    }
                    // 计算该单元格的样式(累积所有匹配规则)
                    // 缺失行:传 null 给样式计算(避免 NaN 值误触发数值高亮规则)
                    Object vForStyle = missing ? null : v;
                    String bg = computeExcelBg(colName, r, vForStyle);
                    String numFmt = excelNumFormat(colName);
                    FontRule fontRule = matchFontRule(colName, vForStyle);
                    if (bg != null || numFmt != null || fontRule != null) {
                        org.apache.poi.ss.usermodel.CellStyle cs = wb.createCellStyle();
                        if (bg != null) {
                            cs.setFillForegroundColor(toIndexedColor(bg));
                            cs.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                        }
                        if (numFmt != null) cs.setDataFormat(dataFormat.getFormat(numFmt));
                        if (fontRule != null) {
                            // 字体颜色/加粗(POI Font 挂在 CellStyle 上)
                            org.apache.poi.ss.usermodel.Font f = wb.createFont();
                            if (fontRule.color != null) f.setColor(toIndexedColor(fontRule.color));
                            if (fontRule.bold) f.setBold(true);
                            cs.setFont(f);
                        }
                        cell.setCellStyle(cs);
                    }
                }
            }
            // 自动列宽 —— 宽度 = max(表头, 内容)×1.2,中文按 2 宽,上限 80(Excel 宽度单位=1/256 字符)
            if (autoWidth) {
                int[] widths = new int[visibleCols.size() + colOff];
                for (int c = 0; c < visibleCols.size(); c++) {
                    int w = displayWidth(visibleCols.get(c));
                    Column col = df.getColumn(visibleCols.get(c));
                    for (int r = 0; r < df.rowCount() && r < 200; r++) {   // 前 200 行采样,防全表扫描
                        w = Math.max(w, displayWidth(formatValue(visibleCols.get(c), r, col.get(r))));
                    }
                    widths[c + colOff] = Math.max(8, Math.min(80, (int) (w * 1.2)));   // 不小于默认 8:只放宽不缩窄
                }
                if (!hideIndex && df.rowCount() > 0) widths[0] = Math.min(80, (int) (displayWidth(String.valueOf(df.index().get(0))) * 1.2));
                for (int c = 0; c < widths.length; c++) {
                    if (widths[c] > 0) sheet.setColumnWidth(c, widths[c] * 256);
                }
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                wb.write(fos);
            }
        }
    }

    /**
     * 路径便捷重载。
     *
     * @param path String 目标 .xlsx 文件路径字符串,非 null
     * @throws java.io.IOException 写文件失败时抛出(磁盘满 / 无权限 / 路径非法)
     */
    public void toExcel(String path) throws java.io.IOException { toExcel(new java.io.File(path)); }

    /** 计算某单元格的 Excel 背景色(累积所有规则;取最后匹配的)。 */
    private String computeExcelBg(String col, int row, Object value) {
        String result = null;
        // 整行背景(依据列谓词)与整列背景先行,单元格级规则后到优先
        for (StyleRule r : rules) {
            if (r instanceof RowBgRule rb) {
                Column base = df.getColumn(rb.col);
                Object judge = base.isNull(row) ? null : base.get(row);
                if (judge != null && rb.cond.test(judge)) result = rb.color;
            } else if (r instanceof ColumnBgRule cb && col.equals(cb.col)) {
                result = cb.color;
            }
        }
        for (StyleRule r : rules) {
            if (r instanceof HighlightExtremeRule he && col.equals(he.col)) {
                if (value instanceof Number && ((Number) value).doubleValue() == he.extreme) result = he.color;
            } else if (r instanceof HighlightNullRule && value == null) {
                result = r.color;
            } else if (r instanceof GradientRule gr && col.equals(gr.col)
                    && value instanceof Number) {
                double v = ((Number) value).doubleValue();
                if (gr.max != gr.min) {
                    double t = (v - gr.min) / (gr.max - gr.min);
                    // 简化:用色图离散三段(低/中/高)
                    String[] parts = gr.color.split(":");
                    if (parts.length >= 3) {
                        result = t < 0.33 ? parts[0] : (t < 0.66 ? parts[1] : parts[parts.length - 1]);
                    } else if (parts.length == 2) {
                        result = t < 0.5 ? parts[0] : parts[1];
                    }
                }
            }
        }
        return result;
    }

    /** 取某列的 Excel 数字格式(从 format 规则)。
     *  增强:#,##0.00 / 0.00% / 0.00 等标准 Excel 格式串【原样透传】
     *  (只认含 , 和结尾 % 两族会丢弃 0.00 这类);非标准串维持两族映射。 */
    private String excelNumFormat(String col) {
        for (StyleRule r : rules) {
            if (r instanceof FormatRule fr && col.equals(fr.col)) {
                String p = fr.pattern;
                if (p.matches("[#0][#0.,%]*")) return p;   // 标准 Excel 数字格式串:直接透传
                if (p.contains(",")) return "#,##0.00";
                if (p.endsWith("%")) return "0.00%";
                return null;
            }
        }
        return null;
    }

    /**
     * 命中该单元格的<b>全部</b>字体规则并合并(颜色/加粗;条件谓词不满足的单条跳过)。
     *
     * <p>返回命中的全部字体规则并合并(只返回首条命中会让 {@code .fontColor(...)} +
     * {@code .boldIf(...)} 两条规则在 Excel 只剩颜色、丢加粗;HTML 的 computeStyle
     * 是合并语义,两条路径必须一致)。合并语义对齐 computeStyle:
     * bold 任一命中即真;color 取最后命中规则的非空色(后命中覆盖先命中)。
     *
     * @param col String 当前列名,非 null
     * @param value Object 当前行该列的值(缺失为 null),可为 null
     * @return FontRule 合并后的字体规则(无任何命中返回 null)
     */
    private FontRule matchFontRule(String col, Object value) {
        // 伪代码:
        //   1. 遍历全部 FontRule:列名相等 + 值非 null + 谓词(有则)通过 → 记一次命中
        //   2. 命中规则合并:bold 取或;color 取最后命中的非空色(后覆盖先)
        //   3. 零命中 → null
        String color = null;
        boolean bold = false;
        boolean hit = false;
        for (StyleRule r : rules) {
            if (r instanceof FontRule fr && col.equals(fr.col)) {
                if (value == null) continue;
                if (fr.cond == null || fr.cond.test(value)) {
                    hit = true;
                    if (fr.color != null) color = fr.color;
                    bold |= fr.bold;
                }
            }
        }
        return hit ? new FontRule(col, color, bold, null) : null;
    }

    /** 显示宽度(CJK/全角按 2,其余 1;与控制台对齐口径一致)。 */
    private static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (char ch : s.toCharArray()) {
            Character.UnicodeScript us = Character.UnicodeScript.of(ch);
            boolean wide = us == Character.UnicodeScript.HAN || us == Character.UnicodeScript.HANGUL
                    || us == Character.UnicodeScript.KATAKANA || us == Character.UnicodeScript.HIRAGANA
                    || (ch >= 0xFF01 && ch <= 0xFF60);   // 全角 ASCII/标点区段
            w += wide ? 2 : 1;
        }
        return w;
    }

    /** 转 POI IndexedColors(常见色码映射;未知用 YELLOW 兜底)。 */
    private static short toIndexedColor(String hex) {
        String h = hex.replace("#", "").toLowerCase();
        return switch (h) {
            case "ffff00" -> org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex();
            case "ff0000" -> org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex();
            case "00ff00" -> org.apache.poi.ss.usermodel.IndexedColors.BRIGHT_GREEN.getIndex();
            case "0066cc", "0000ff" -> org.apache.poi.ss.usermodel.IndexedColors.BLUE.getIndex();
            case "cccccc", "c0c0c0" -> org.apache.poi.ss.usermodel.IndexedColors.GREY_50_PERCENT.getIndex();
            case "ff69b4", "ffc0cb" -> org.apache.poi.ss.usermodel.IndexedColors.PINK.getIndex();
            case "90ee90", "98fb98" -> org.apache.poi.ss.usermodel.IndexedColors.LIGHT_GREEN.getIndex();
            default -> org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex();
        };
    }

    /** Excel sheet 名不能含特殊字符且 ≤ 31 字符。 */
    private static String sanitizeSheetName(String name) {
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
    /**
     * 公式注入检测(OWASP):跳过前导空白类字符 + NUL/BOM(空格、Tab、CR、LF、\u0000、\uFEFF)
     * 后,首字符为 {@code = + - @} 即视为公式载荷。
     * <p>三处同款实现互指:jian-io-csv Csv#startsWithFormulaAfterWhitespace、
     * jian-io-excel Excel#startsWithFormulaAfterWhitespace、本方法(Styler.toExcel 路径)。
     * @param s String 待检测单元格文本,非 null
     * @return boolean true 表示需加单引号前缀防护
     */
    private static boolean startsWithFormulaAfterWhitespace(String s) {
        if (s.isEmpty()) return false;
        int i = 0;
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
