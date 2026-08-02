package jian.io.html;

import jian.core.DataFrame;
import jian.core.DType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTest {

    @TempDir Path tmp;

    @Test
    void 从HTML字符串提取表格() {
        String html = "<html><body>"
                + "<table><thead><tr><th>name</th><th>age</th></tr></thead>"
                + "<tbody><tr><td>alice</td><td>30</td></tr><tr><td>bob</td><td>25</td></tr></tbody>"
                + "</table></body></html>";
        List<DataFrame> tables = Html.parse(html, null);
        assertThat(tables).hasSize(1);
        DataFrame df = tables.get(0);
        assertThat(df.rowCount()).isEqualTo(2);
        assertThat(df.columnNames()).containsExactly("name", "age");
        assertThat(df.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void match正则筛表() {
        String html = "<table><tr><th>x</th></tr><tr><td>1</td></tr></table>"
                + "<table><tr><th>员工表</th></tr><tr><td>alice</td></tr></table>";
        List<DataFrame> all = Html.parse(html, null);
        assertThat(all).hasSize(2);
        List<DataFrame> filtered = Html.parse(html, "员工");
        assertThat(filtered).hasSize(1);
    }

    @Test
    void 多表共存() {
        String html = "<table><tr><th>a</th></tr><tr><td>1</td></tr></table>"
                + "<table><tr><th>b</th></tr><tr><td>2</td></tr></table>";
        List<DataFrame> tables = Html.parse(html, null);
        assertThat(tables).hasSize(2);
    }

    @Test
    void 文件读取() throws Exception {
        Path p = tmp.resolve("page.html");
        java.nio.file.Files.writeString(p,
                "<table><thead><tr><th>v</th></tr></thead><tbody><tr><td>10</td></tr></tbody></table>");
        List<DataFrame> tables = Html.readAll(p.toString());
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).rowCount()).isEqualTo(1);
    }

    @Test
    void 无表头自动用下划线列名() {
        String html = "<table><tr><td>alice</td><td>30</td></tr><tr><td>bob</td><td>25</td></tr></table>";
        List<DataFrame> tables = Html.parse(html, null);
        DataFrame df = tables.get(0);
        assertThat(df.columnNames()).containsExactly("_0", "_1");
        assertThat(df.rowCount()).isEqualTo(2);
    }
}
