package jian.io.html;

import jian.core.DataFrame;
import jian.core.DType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : HtmlRegressionTest —— HTML 读写回归测试集
// │  Why  : 固化 HTML 表格读取行为(因为 <NA> 缺失值对称、首行 <th> 表头识别、
// │         readUrl 跨平台构造与 scheme 白名单等边界行为一旦回归会污染 dtype 或
// │         破坏安全基线,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-html)
// │  When : 改动 Html.readUrl / HtmlReader / tableToDataFrame 后
// │  Where: jian-io-html/src/test/java/jian/io/html/HtmlRegressionTest.java
// │  How  : 数据走向:手写 HTML 文件 / URL 字符串 → Html.read/readUrl → go() →
// │         断言列名/dtype/缺失值/异常;网络相关仅静态验证,不发真实请求。
// │  注:HTML 内容手写(jian-io-html 不依赖 jian-export,读端独立测试写端约定的 <NA> 形态)
class HtmlRegressionTest {

    @TempDir Path tmp;

    // ======================== 读取:缺失值 / 表头识别 ========================

    @Test
    void NA读回为null且不污染dtype() throws Exception {
        Path p = tmp.resolve("na.html");
        Files.writeString(p, "<html><body><table><thead><tr><th>v</th></tr></thead>\n"
                + "<tbody><tr><td>1.5</td></tr><tr><td>&lt;NA&gt;</td></tr></tbody></table></body></html>");
        List<DataFrame> tables = Html.read(p.toString()).go();
        assertThat(tables).hasSize(1);
        DataFrame back = tables.get(0);
        assertThat(back.getColumn("v").dtype()).as("<NA> 不污染推断").isEqualTo(DType.DOUBLE);
        assertThat(back.getColumn("v").isNull(1)).as("<NA>=缺失").isTrue();
        assertThat(back.getColumn("v").getDouble(0)).isEqualTo(1.5);
    }

    @Test
    void 无thead首行th作为表头列名() throws Exception {
        // 因为手写 HTML 无显式 thead/tbody 时 jsoup 会把 tr 自动归入 tbody,
        // 首行含 <th> 是明确表头信号,所以识别首行 th 作表头(否则列名退化为 _0,_1)
        Path p = tmp.resolve("th.html");
        Files.writeString(p,
                "<html><body><table><tr><th>a</th><th>b</th></tr>"
                        + "<tr><td>1</td><td>x</td></tr></table></body></html>");
        List<DataFrame> back = Html.read(p.toString()).go();
        assertThat(back.get(0).columnNames()).as("首行 th → 表头").containsExactly("a", "b");
        assertThat(back.get(0).rowCount()).isEqualTo(1);
        assertThat(back.get(0).getColumn("a").get(0)).isEqualTo(1);
    }

    // ======================== readUrl:跨平台构造 + scheme 白名单 ========================

    @Test
    void readUrl构造不抛路径解析异常_scheme校验照旧() {
        // 因为 URL 若经 Path.of 解析,Windows 上 "https://" 的冒号会直接
        // InvalidPathException,所以 URL 走字符串字段模式,三平台一致。
        Html.HtmlReader r = Html.readUrl("https://example.com/table.html");
        assertThat(r).as("合法 https URL 构造期不得抛路径解析异常").isNotNull();
        assertThat(Html.readUrl("http://example.com")).isNotNull();

        // 工厂入口的 scheme 白名单不回归
        assertThatThrownBy(() -> Html.readUrl("ftp://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> Html.readUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> Html.readUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void go内scheme二道防线在字符串模式下仍生效() throws Exception {
        // 用包私有构造器绕过工厂校验直接构造非法 scheme,go() 的第二道校验应拦截
        // (file:// 一律拒绝,防 SSRF/本地文件读取 —— 且不发起任何网络请求)。
        Html.HtmlReader r = new Html.HtmlReader("file:///etc/passwd");
        assertThatThrownBy(r::go)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
    }

    // ======================== 表头去重 ========================

    @Test
    void 重复th表头_自动加后缀去重不再抛列名重复() throws Exception {
        // 合并单元格/手写表格常见重复 th;修复前 Schema 校验抛"列名重复"整表拒读
        List<DataFrame> tables = Html.parse(
            "<table><tr><th>a</th><th>a</th><th>b</th></tr>"
            + "<tr><td>1</td><td>2</td><td>3</td></tr></table>", null);
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).columnNames()).containsExactly("a", "a_1", "b");
        assertThat(((Number) tables.get(0).get(0, "a_1")).intValue()).isEqualTo(2);   // "1/2/3" 被 infer 为整数
    }
}
