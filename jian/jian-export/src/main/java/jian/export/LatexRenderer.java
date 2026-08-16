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
        // 占位符冲突防御(escape 用 U+0001-U+0008/U+000B/U+000C 作
        // 中间占位符,数据含同款控制字符会被二次替换成转义序列=静默损坏;jian-io-latex
        // 的 LatexIo.assertNoPlaceholderConflict 已有同款防御,本入口此前缺失,
        // 两处同根因实现,修改须同步)
        assertNoPlaceholderConflict();
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
        // booktabs=false 时表头下补 \hline(无任何横线的表格不可读)
        if (booktabs) sb.append("\\midrule\n");
        else sb.append("\\hline\n");
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
    // ┌─ What : 数据含 U+0001-U+0008/U+000B/U+000C 占位符字符时 fail-fast(与 LatexIo 同款)
    // │  Why  : escape 以这批控制字符作中间占位符,输入含同款字符会被二次替换成
    // │         \textbackslash{} 等转义序列 = 数据静默损坏;LatexIo 有此防御而本类
    // │         此前没有,双入口行为必须一致。
    // │  How  : 扫 caption/label/列名/index/全部非空单元格,命中占位符字符即抛
    // │         教学式 IAE(带 U+ 编码与预览,提示先清洗)。
    private void assertNoPlaceholderConflict() {
        java.util.List<String> texts = new java.util.ArrayList<>();
        if (caption != null) texts.add(caption);
        if (label != null) texts.add(label);
        texts.addAll(df.columnNames());
        if (index) {
            for (int r = 0; r < df.rowCount(); r++) texts.add(String.valueOf(df.index().get(r)));
        }
        List<String> cols = df.columnNames();
        for (int r = 0; r < df.rowCount(); r++) {
            for (int c = 0; c < cols.size(); c++) {
                if (!df.getColumn(cols.get(c)).isNull(r)) texts.add(String.valueOf(df.get(r, c)));
            }
        }
        for (String t : texts) {
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch >= '\u0001' && ch <= '\u0008' || ch == '\u000B' || ch == '\u000C') {
                    throw new IllegalArgumentException("LaTeX 渲染失败:数据含控制字符 U+"
                            + String.format("%04X", (int) ch)
                            + "(与转义占位符冲突,渲染会静默损坏数据)。请先清洗该值,预览:"
                            + t.substring(Math.max(0, i - 5), Math.min(t.length(), i + 6)));
                }
            }
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
            // 占位符三阶段替换:因为先 replace 反斜杠为 textbackslash{} 再替换 { 的话,
            // 前者产物里的 {} 会被二次转义,渲染错;所以先全部换成不可冲突控制字符占位符,最后统一还原。
            // 两处同根因实现(LatexIo / LatexRenderer)互指,修改须同步。
            char P_BS='\u0001',P_AMP='\u0002',P_PCT='\u0003',P_DOL='\u0004',P_HSH='\u0005',P_USC='\u0006',P_LBR='\u0007',P_RBR='\u0008',P_TLD='\u000B',P_CRT='\u000C';
            return s.replace("\\",String.valueOf(P_BS)).replace("&",String.valueOf(P_AMP))
                    .replace("%",String.valueOf(P_PCT)).replace("$",String.valueOf(P_DOL))
                    .replace("#",String.valueOf(P_HSH)).replace("_",String.valueOf(P_USC))
                    .replace("{",String.valueOf(P_LBR)).replace("}",String.valueOf(P_RBR))
                    .replace("~",String.valueOf(P_TLD)).replace("^",String.valueOf(P_CRT))
                    .replace(String.valueOf(P_BS),"\\textbackslash{}")
                    .replace(String.valueOf(P_AMP),"\\&")
                    .replace(String.valueOf(P_PCT),"\\%")
                    .replace(String.valueOf(P_DOL),"\\$")
                    .replace(String.valueOf(P_HSH),"\\#")
                    .replace(String.valueOf(P_USC),"\\_")
                    .replace(String.valueOf(P_LBR),"\\{")
                    .replace(String.valueOf(P_RBR),"\\}")
                    .replace(String.valueOf(P_TLD),"\\textasciitilde{}")
                    .replace(String.valueOf(P_CRT),"\\textasciicircum{}");
    }
}
