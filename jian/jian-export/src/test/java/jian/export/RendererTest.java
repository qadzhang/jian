package jian.export;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : jian-export 测试 —— HTML/Markdown 渲染
class RendererTest {

    @Test
    void html基础结构() {
        DataFrame df = df();
        String html = HtmlRenderer.of(df).render();
        assertThat(html).contains("<table");
        assertThat(html).contains("</table>");
        assertThat(html).contains("<th>name</th>");
        assertThat(html).contains("alice");
    }

    @Test
    void html自动转义() {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"<script>alert('x')</script>"}});
        String html = HtmlRenderer.of(df).render();
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void html缺失值默认NA() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}});
        String html = HtmlRenderer.of(df).render();
        // <NA> 经 HTML 转义为 &lt;NA&gt;
        assertThat(html).contains("&lt;NA&gt;");
    }

    @Test
    void html缺失值可配() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}});
        String html = HtmlRenderer.of(df).naRep("null").render();
        assertThat(html).contains("null");
        assertThat(html).doesNotContain("<NA>");
    }

    @Test
    void html大表截断() {
        Object[][] rows = new Object[100][];
        for (int i = 0; i < 100; i++) rows[i] = new Object[]{"name" + i};
        DataFrame df = DataFrame.of(Schema.of("name", DType.STRING), rows);
        String html = HtmlRenderer.of(df).maxRows(10).render();
        assertThat(html).contains("...");
    }

    @Test
    void markdown基础GFM() {
        DataFrame df = df();
        String md = MarkdownRenderer.of(df).render();
        // GFM 表格首尾是 |,有分隔行
        assertThat(md).startsWith("|");
        assertThat(md).contains("id");
        assertThat(md).contains("name");
        assertThat(md).contains("alice");
        // 第二行是分隔行(全是 - : |)
        String[] lines = md.split("\n");
        assertThat(lines[1]).matches("[|:-]+");
    }

    @Test
    void markdown数值列右对齐() {
        DataFrame df = df();
        String md = MarkdownRenderer.of(df).render();
        // 数值列 id/score 的分隔行应是 ---:| 形式(右对齐)
        assertThat(md).contains("---:|");
    }

    @Test
    void markdown管道符转义() {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"a|b"}});
        String md = MarkdownRenderer.of(df).render();
        assertThat(md).contains("a\\|b");
    }

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {1L, "alice", 90.5},
                        {2L, "bob", 85.0}
                });
    }

    @Test
    void latex基础booktabs() {
        DataFrame df = df();
        String tex = LatexRenderer.of(df).caption("员工表").label("tab:u").render();
        assertThat(tex).contains("\\begin{tabular}");
        assertThat(tex).contains("\\toprule");
        assertThat(tex).contains("\\bottomrule");
        assertThat(tex).contains("alice");
        assertThat(tex).contains("\\caption{员工表}");
    }

    @Test
    void latex特殊字符转义() {
        DataFrame df = DataFrame.of(jian.core.Schema.of("s", DType.STRING),
                new Object[][]{{"a_b%c"}});
        String tex = LatexRenderer.of(df).booktabs(false).render();
        assertThat(tex).contains("a\\_b\\%c");
    }

    @Test
    void console_中文按2宽对齐() {
        DataFrame df = DataFrame.of(
                jian.core.Schema.of("姓名", DType.STRING, "年龄", DType.LONG),
                new Object[][]{{"张三", 30L}, {"李四", 25L}});
        String s = ConsoleRenderer.render(df);
        // 输出含中文
        assertThat(s).contains("张三");
        assertThat(s).contains("李四");
        // 中文宽度:displayWidth("张三") == 4(每字 2 宽)
        assertThat(ConsoleRenderer.displayWidth("张三")).isEqualTo(4);
        assertThat(ConsoleRenderer.displayWidth("abc")).isEqualTo(3);
    }

    @Test
    void console_大表截断() {
        Object[][] rows = new Object[100][];
        for (int i = 0; i < 100; i++) rows[i] = new Object[]{"v" + i};
        DataFrame df = DataFrame.of(jian.core.Schema.of("x", DType.STRING), rows);
        String s = ConsoleRenderer.render(df, 10, 20);
        assertThat(s).contains("...");
        assertThat(s).contains("100 行 × 1 列");
    }
}
