package jian.export;

import jian.core.DataFrame;
import jian.core.DType;

import java.util.List;

// ┌─ What : LatexRenderer —— DataFrame → LaTeX 表格(对齐 pandas df.to_latex,booktabs 风格)
// │  Why  : 规范 04 §3.3;LaTeX 学术报告常用,纯 JDK 自写
// │  Who  : 用户经 df.toLatex 或 LatexRenderer.of(df) 调用
// │  When : 论文、学术报告
// │  Where: jian-export/LatexRenderer.java
// │  How  : 数据走向:DataFrame → \begin{tabular}{对齐} + \toprule ... \bottomrule → String。
// │         关键变量变化:
// │           - columnFormat:每列对齐字符(数值 r、文本 l);
// │           - 自动转义 _ % & # $ { }。
/**
 * DataFrame → LaTeX 表格,对齐 pandas.to_latex(默认 booktabs)。
 *
 * <p>用法:
 * <pre>{@code
 * String tex = LatexRenderer.of(df).caption("员工表").label("tab:users").render();
 * }</pre>
 */
public final class LatexRenderer {

    private final DataFrame df;
    private boolean index = false;
    private boolean booktabs = true;
    private String caption = null;
    private String label = null;

    private LatexRenderer(DataFrame df) { this.df = df; }

    public static LatexRenderer of(DataFrame df) { return new LatexRenderer(df); }

    public LatexRenderer index(boolean v) { this.index = v; return this; }
    public LatexRenderer booktabs(boolean v) { this.booktabs = v; return this; }
    public LatexRenderer caption(String v) { this.caption = v; return this; }
    public LatexRenderer label(String v) { this.label = v; return this; }

    public String render() {
        List<String> cols = df.columnNames();
        List<DType> dtypes = df.dtypes();
        StringBuilder sb = new StringBuilder();

        // 构造列对齐(数值 r,文本 l;index 列 l)
        StringBuilder align = new StringBuilder();
        if (index) align.append('l');
        for (DType d : dtypes) align.append(d.isNumeric() ? 'r' : 'l');

        sb.append("\\begin{table}\n");
        sb.append("\\centering\n");
        if (caption != null) sb.append("\\caption{").append(escape(caption)).append("}\n");
        if (label != null) sb.append("\\label{").append(escape(label)).append("}\n");
        sb.append("\\begin{tabular}{").append(align).append("}\n");

        if (booktabs) sb.append("\\toprule\n");
        // 表头
        if (index) sb.append(escape("")).append(" & ");
        for (int c = 0; c < cols.size(); c++) {
            sb.append(escape(cols.get(c)));
            sb.append(c == cols.size() - 1 ? " \\\\\n" : " & ");
        }
        if (booktabs) sb.append("\\midrule\n");
        // 数据行
        for (int r = 0; r < df.rowCount(); r++) {
            if (index) {
                sb.append(escape(String.valueOf(df.index().get(r)))).append(" & ");
            }
            for (int c = 0; c < cols.size(); c++) {
                Object v = df.get(r, c);
                sb.append(v == null ? "" : escape(String.valueOf(v)));
                sb.append(c == cols.size() - 1 ? " \\\\\n" : " & ");
            }
        }
        if (booktabs) sb.append("\\bottomrule\n");
        sb.append("\\end{tabular}\n\\end{table}\n");
        return sb.toString();
    }

    /** LaTeX 转义:特殊字符前加反斜杠(对齐规范 04 §5)。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("$", "\\$")
                .replace("#", "\\#")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("~", "\\textasciitilde{}")
                .replace("^", "\\textasciicircum{}");
    }
}
