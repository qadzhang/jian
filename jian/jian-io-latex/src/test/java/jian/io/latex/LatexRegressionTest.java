package jian.io.latex;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : LatexRegressionTest —— LaTeX 写出回归测试集
// │  Why  : 固化 LaTeX 写出行为(因为反斜杠转义不得二次转义花括号、占位符控制字符
// │         必须在渲染前 fail-fast、常规数据转义不回归等边界行为一旦退化会静默损坏
// │         数据或产出非法 .tex,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-latex)
// │  When : 改动 LatexIo.write 的占位符/转义逻辑后必须跑
// │  Where: jian-io-latex/src/test/java/jian/io/latex/LatexRegressionTest.java
// │  How  : 数据走向:含特殊字符的 DataFrame → LatexIo.write().go() → 读回 .tex 文件
// │           → 断言转义形态 / IAE 且文件不落盘;jian-export/LatexRenderer 同款行为已各自测试。
class LatexRegressionTest {

    @TempDir Path tmp;

    // ======================== 反斜杠转义:不二次转义花括号 ========================

    @Test
    void 反斜杠转义不被二次转义() throws Exception {
        // "a\b" → a\textbackslash{}b(不二次转义花括号)
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING), new Object[][]{{"a\\b"}});
        Path p = tmp.resolve("bs.tex");
        LatexIo.write(df, p.toString()).go();
        String out = Files.readString(p);
        assertThat(out).contains("a\\textbackslash{}b");
        assertThat(out).doesNotContain("textbackslash\\{");
    }

    // ======================== 占位符控制字符:fail-fast ========================

    @Test
    void 数据含占位符控制字符抛IAE不落盘() throws Exception {
        // 因为占位符用的 U+0001-U+0008/U+000B/U+000C 控制字符若与数据自含字符冲突,
        // 静默替换会把 "\u0001x" 变成 "\textbackslash{}x"(数据损坏且无报错),
        // 所以整个文档 fail-fast 抛 IAE(拦截发生在渲染之前,目标文件不落盘)
        DataFrame df = DataFrame.of(
                Schema.of("s", DType.STRING),
                new Object[][]{{"a\u0001b"}});
        Path p = tmp.resolve("bad.tex");
        assertThatThrownBy(() -> LatexIo.write(df, p.toString()).go())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制字符")
                .hasMessageContaining("U+0001");
        assertThat(Files.exists(p)).as("拦截发生在渲染之前,目标文件不应被写坏").isFalse();
    }

    @Test
    void 各区间占位符字符都拦截() throws Exception {
        // 占位符全集:\u0001-\u0008、\u000B、\u000C(逐个抽样头/中/尾)
        for (char ch : new char[]{'\u0001', '\u0004', '\u0008', '\u000B', '\u000C'}) {
            DataFrame df = DataFrame.of(
                    Schema.of("s", DType.STRING),
                    new Object[][]{{"x" + ch + "y"}});
            Path p = tmp.resolve("bad_" + (int) ch + ".tex");
            assertThatThrownBy(() -> LatexIo.write(df, p.toString()).go())
                    .as("U+%04X 应被拦截".formatted((int) ch))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("控制字符");
        }
    }

    @Test
    void 列名与caption含占位符同样拦截() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("c\u0002ol", DType.STRING),
                new Object[][]{{"ok"}});
        assertThatThrownBy(() -> LatexIo.write(df, tmp.resolve("c1.tex").toString()).go())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U+0002");
        DataFrame df2 = DataFrame.of(Schema.of("s", DType.STRING), new Object[][]{{"ok"}});
        assertThatThrownBy(() -> LatexIo.write(df2, tmp.resolve("c2.tex").toString()).caption("题\u0003头").go())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U+0003");
    }

    // ======================== 常规数据写出:不回归 ========================

    @Test
    void 常规数据写出不回归() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "a&b"}, {2L, "50%_off"}});
        Path p = tmp.resolve("ok.tex");
        LatexIo.write(df, p.toString()).caption("员工").go();
        String content = Files.readString(p);
        assertThat(content).contains("\\begin{tabular}");
        assertThat(content).contains("a\\&b");        // 常规转义不回归
        assertThat(content).contains("50\\%\\_off");
        assertThat(content).doesNotContain("U+000");  // 无占位符残留可见化文本
    }
}
