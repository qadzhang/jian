package jian.io.latex;

import jian.core.DataFrame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// ┌─ What : LatexIo —— LaTeX 表格写出(对齐 pandas.to_latex,纯 JDK 自写)
// │  Why  : 规范 02 §3.11;LaTeX 表格仅写出(无读),纯 JDK
// │  Who  : 用户经 df.toLatex 或 LatexIo.write 调用
// │  When : 学术报告、论文
// │  Where: jian-io-latex/LatexIo.java
// │  How  : 数据走向:DataFrame → \begin{tabular}{对齐} ... \end{tabular} → 文件。
// │         注:与 jian-export 的 LatexRenderer 互补 —— 那个含 Styler 集成,这里做 io 入口(对齐 pandas to_latex)。
/**
 * LaTeX 表格写出,对齐 pandas.to_latex(纯 JDK 自写,读不支持)。
 *
 * <p>用法:
 * <pre>{@code
 * LatexIo.write(df, "out.tex").caption("员工表").go();
 * }</pre>
 *
 * <p>读取 LaTeX 表格不在 pandas 范围,本模块也不提供。
 */
public final class LatexIo {

    private LatexIo() {}


    /**
     * 写 LaTeX 表格的 builder。
     * @param df DataFrame 要写出的数据帧,不允许 null
     * @param path String 输出 .tex 文件路径,需为合法可写路径,不允许 null
     * @return LatexWriter 配置器,链式调用 .caption/.label/.index/.booktabs 后 .go() 执行
     */
    public static LatexWriter write(DataFrame df, String path) {
        return new LatexWriter(df, Path.of(path));
    }

    public static final class LatexWriter {
        private final DataFrame df;
        private final Path path;
        private boolean index = false;
        private boolean booktabs = true;
        private String caption = null;
        private String label = null;

        LatexWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        /**
         * 是否输出行索引列。
         * @param v boolean true=首列输出 DataFrame 行索引(对齐 pandas to_latex index);false=不输出(默认)
         * @return LatexWriter 当前配置器,便于链式调用
         */
        public LatexWriter index(boolean v) { this.index = v; return this; }

        /**
         * 是否使用 booktabs 三线表规则线(\toprule/\midrule/\bottomrule)。
         * @param v boolean true=使用 booktabs 三线表(默认);false=不输出规则线
         * @return LatexWriter 当前配置器,便于链式调用
         */
        public LatexWriter booktabs(boolean v) { this.booktabs = v; return this; }

        /**
         * 设置表格标题(\caption{...})。
         * @param v String 标题文本;会自动转义 LaTeX 特殊字符;null 表示不输出 caption(默认)
         * @return LatexWriter 当前配置器,便于链式调用
         */
        public LatexWriter caption(String v) { this.caption = v; return this; }

        /**
         * 设置表格交叉引用标签(\label{...})。
         * @param v String 标签文本(如 "tab:example");会自动转义 LaTeX 特殊字符;null 表示不输出 label(默认)
         * @return LatexWriter 当前配置器,便于链式调用
         */
        public LatexWriter label(String v) { this.label = v; return this; }

        /**
         * 执行写出。
         * <p>因为 escape 用 U+0001-U+0008/U+000B/U+000C 控制字符作替换占位符,
         * 数据本身含这些字符时会被静默替换成 {@code \%}、{@code \&} 等(数据损坏
         * 而非报错),所以写出前先扫描全部待转义文本,fail-fast 抛
         * {@link IllegalArgumentException}(数据损坏防御,提示清洗)。
         * @throws IOException 目标路径不可写或写出过程发生 IO 错误时抛出
         * @throws IllegalArgumentException 数据/列名/标题/索引值含 U+0001-U+0008/U+000B/U+000C
         *         控制字符(与 LaTeX 转义占位符冲突)时抛出
         */
        public void go() throws IOException {
            // 本 io 模块内联生成 LaTeX(不依赖 jian-export,避免 io → export 跨库依赖)
            assertNoPlaceholderConflict();
            String content = renderInline();
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }

        // ┌─ What : assertNoPlaceholderConflict —— 占位符控制字符冲突防御
        // │  Why  : 因为 escape 用 \u0001-\u0008/\u000B/\u000C 作两阶段替换的中间占位符,
        // │         输入含同款字符时会被二次替换成 \% \& 等转义序列 —— 静默损坏数据,
        // │         所以选 fail-fast 抛 IAE(简单安全),消息带定位(文本预览 + 码点)便于清洗。
        // │  Who  : LatexWriter.go()(render 之前)
        // │  When : 每次 go() 写出前
        // │  Where: jian-io-latex/LatexIo.java
        // │  How  : 伪代码:
        // │           1. 收集全部会经过 escape 的文本:caption/label/列名/索引值(index=true)/单元格值
        // │           2. 逐文本逐字符扫 [\u0001-\u0008\u000B\u000C],命中即抛 IAE
        // │         关键变量变化:扫描游标 ch —— 属占位符区间 → 立即构造异常退出;
        // │           全部文本扫完无命中 → 正常返回,渲染照旧。
        // │         逻辑路线(两条路径):
        // │           路径 A(任一文本含占位符字符)→ IllegalArgumentException(含文本预览与码点),
        // │             且在 render 之前抛,目标文件不被写坏;
        // │           路径 B(无冲突)→ 返回,继续 renderInline。
        // │         数据走向:df 各列值/列名/caption/label → 本扫描(只读)→ 放行或拦截。
        private void assertNoPlaceholderConflict() {
            java.util.List<String> texts = new java.util.ArrayList<>();
            if (caption != null) texts.add(caption);
            if (label != null) texts.add(label);
            texts.addAll(df.columnNames());
            if (index) {
                for (int r = 0; r < df.rowCount(); r++) texts.add(String.valueOf(df.index().get(r)));
            }
            java.util.List<String> cols = df.columnNames();
            for (int r = 0; r < df.rowCount(); r++) {
                for (int c = 0; c < cols.size(); c++) {
                    if (!df.getColumn(cols.get(c)).isNull(r)) texts.add(String.valueOf(df.get(r, c)));
                }
            }
            for (String t : texts) {
                for (int i = 0; i < t.length(); i++) {
                    char ch = t.charAt(i);
                    if (ch >= '\u0001' && ch <= '\u0008' || ch == '\u000B' || ch == '\u000C') {
                        throw new IllegalArgumentException("LaTeX 写出失败:数据含控制字符 U+"
                                + String.format("%04X", (int) ch)
                                + "(与转义占位符冲突,写出会静默损坏数据)。请先清洗该值,预览:"
                                + preview(t));
                    }
                }
            }
        }

        /** 异常消息用的文本预览(截断到 40 字符,控制字符可见化,防异常本身携带不可见字符)。 */
        private static String preview(String t) {
            String s = t.length() <= 40 ? t : t.substring(0, 40) + "...";
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch < ' ') sb.append(String.format("\\u%04X", (int) ch));
                else sb.append(ch);
            }
            return sb.append('"').toString();
        }

        private String renderInline() {
            StringBuilder sb = new StringBuilder();
            java.util.List<String> cols = df.columnNames();
            java.util.List<jian.core.DType> dtypes = df.dtypes();
            StringBuilder align = new StringBuilder();
            if (index) align.append('l');
            for (jian.core.DType d : dtypes) align.append(d.isNumeric() ? 'r' : 'l');
            sb.append("\\begin{table}\n\\centering\n");
            if (caption != null) sb.append("\\caption{").append(escape(caption)).append("}\n");
            if (label != null) sb.append("\\label{").append(escape(label)).append("}\n");
            sb.append("\\begin{tabular}{").append(align).append("}\n");
            if (booktabs) sb.append("\\toprule\n");
            if (index) sb.append(" & ");
            for (int c = 0; c < cols.size(); c++) sb.append(escape(cols.get(c))).append(c == cols.size() - 1 ? " \\\\\n" : " & ");
            if (booktabs) sb.append("\\midrule\n");
            for (int r = 0; r < df.rowCount(); r++) {
                if (index) sb.append(escape(String.valueOf(df.index().get(r)))).append(" & ");
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

        private static String escape(String s) {
            if (s == null) return "";
            // 因为先 replace 反斜杠为 textbackslash{} 再替换 { 的话,前者产物里的 {}
            // 会被二次转义、渲染错,所以采用占位符三阶段替换:先全部换成不可冲突的
            // 控制字符占位符,最后统一还原。两处同根因实现(LatexIo / LatexRenderer)互指,修改须同步。
            // 因为占位符字符与数据同字符冲突会静默损坏数据,
            // 本类已在 go() 入口 assertNoPlaceholderConflict() fail-fast 拦截
            // (jian-export 的 LatexRenderer 仍靠调用方保证输入干净)。
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
}
