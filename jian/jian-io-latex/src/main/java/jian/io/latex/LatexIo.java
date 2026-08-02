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

        public LatexWriter index(boolean v) { this.index = v; return this; }
        public LatexWriter booktabs(boolean v) { this.booktabs = v; return this; }
        public LatexWriter caption(String v) { this.caption = v; return this; }
        public LatexWriter label(String v) { this.label = v; return this; }

        public void go() throws IOException {
            // 本 io 模块内联生成 LaTeX(不依赖 jian-export,避免 io → export 跨库依赖)
            String content = renderInline();
            Files.writeString(path, content, StandardCharsets.UTF_8);
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
                    Object v = df.get(r, c);
                    sb.append(v == null ? "" : escape(String.valueOf(v)));
                    sb.append(c == cols.size() - 1 ? " \\\\\n" : " & ");
                }
            }
            if (booktabs) sb.append("\\bottomrule\n");
            sb.append("\\end{tabular}\n\\end{table}\n");
            return sb.toString();
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("&", "\\&").replace("%", "\\%").replace("$", "\\$")
                    .replace("#", "\\#").replace("_", "\\_").replace("{", "\\{").replace("}", "\\}");
        }
    }
}
