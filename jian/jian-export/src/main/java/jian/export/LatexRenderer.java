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

    /**
     * 创建 LatexRenderer。
     *
     * @param df DataFrame 待渲染的 DataFrame,非 null
     * @return LatexRenderer 新建的 LatexRenderer 实例(默认 index=false / booktabs=true)
     */
    public static LatexRenderer of(DataFrame df) { return new LatexRenderer(df); }

    /**
     * 是否输出索引列。
     *
     * @param v boolean true 输出索引列,false 隐藏(默认)
     * @return LatexRenderer 当前实例(链式)
     */
    public LatexRenderer index(boolean v) { this.index = v; return this; }

    /**
     * 是否使用 booktabs 风格(\toprule / \midrule / \bottomrule)。
     *
     * @param v boolean true 使用 booktabs(默认),false 用 \hline
     * @return LatexRenderer 当前实例(链式)
     */
    public LatexRenderer booktabs(boolean v) { this.booktabs = v; return this; }

    /**
     * 设置 \caption{...} 文本。
     *
     * @param v String 标题文本,null 表示无 caption;特殊字符会被自动转义
     * @return LatexRenderer 当前实例(链式)
     */
    public LatexRenderer caption(String v) { this.caption = v; return this; }

    /**
     * 设置 \label{...} 交叉引用标签。
     *
     * @param v String 标签文本,null 表示无 label;特殊字符会被自动转义
     * @return LatexRenderer 当前实例(链式)
     */
    public LatexRenderer label(String v) { this.label = v; return this; }

    /**
     * 渲染为 LaTeX 表格字符串。
     *
     * @return String LaTeX 源码(\begin{table}...\end{table},数值列右对齐 r、文本列左对齐 l)
     */
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
                boolean missing = df.getColumn(cols.get(c)).isNull(r);
                Object v = df.get(r, c);
                sb.append(missing ? "" : escape(String.valueOf(v)));
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
