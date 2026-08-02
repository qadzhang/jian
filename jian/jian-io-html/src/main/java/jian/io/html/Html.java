package jian.io.html;

import jian.core.DataFrame;
import jian.core.Schema;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Html —— HTML 表格读取(对齐 pandas.read_html,基于 jsoup 1.18.3)
// │  Why  : 规范 02 §3.4;从 HTML 文件/URL/字符串提取所有 <table>,逐个转 DataFrame
// │  Who  : 用户经 Jian.readHtml 或 Html.read 调用
// │  When : 网页表格抓取、报告解析
// │  Where: jian-io-html/Html.java
// │  How  : 数据走向:Path/URL/字符串 → jsoup parse → select("table") → 逐 table 提取 thead/tbody → Object[][] + 推断。
// │         关键变量变化:
// │           - tables:jsoup 解析出的 Elements,每个 element 一个表;
// │           - match:正则筛表(对齐 pandas match 参数)。
// │         逻辑路线:
// │           路径 A(读文件)→ Files.readString → Jsoup.parse;
// │           路径 B(读 URL)→ Jsoup.connect(url).get(自带 HTTP);
// │           路径 C(match 筛选)→ 表的文本含 match 才保留;
// │           路径 D(thead/tbody 缺失)→ 首行作表头兜底。
/**
 * HTML 表格读取,对齐 pandas.read_html(基于 jsoup)。
 *
 * <p>用法:
 * <pre>{@code
 * List<DataFrame> tables = Html.read("page.html").match(".*用户.*").go();
 * List<DataFrame> fromUrl = Html.readUrl("https://example.com").go();
 * }</pre>
 *
 * <p>写 HTML 用 jian-export 的 HtmlRenderer(纯 JDK)。
 */
public final class Html {

    private Html() {}

    /** 直接读(无配置):返回全部表格;等价 read(path).go()。 */
    public static java.util.List<DataFrame> readAll(String path) throws java.io.IOException {
        return new HtmlReader(Path.of(path), false).go();
    }

    /** 读 HTML 的 builder(与其它 io 模块统一:read(path).config().go())。 */
    public static HtmlReader read(String path) { return new HtmlReader(Path.of(path), false); }

    /** 从 URL 读 HTML 的 builder(需要 match 配置时用)。链式配置后 .go()。 */
    public static HtmlReader readUrl(String url) { return new HtmlReader(Path.of(url), true); }

    /** 从 HTML 字符串提取所有表格。 */
    public static List<DataFrame> parse(String html, String match) {
        Document doc = Jsoup.parse(html);
        Elements tables = doc.select("table");
        List<DataFrame> out = new ArrayList<>();
        for (Element table : tables) {
            DataFrame df = tableToDataFrame(table);
            if (df == null) continue;
            if (match == null || df.toString().matches(".*" + match + ".*") || table.text().matches(".*" + match + ".*")) {
                out.add(df);
            }
        }
        return out;
    }

    public static final class HtmlReader {
        private final Path path;
        private final boolean isUrl;
        private String match = null;

        HtmlReader(Path p, boolean url) { this.path = p; this.isUrl = url; }

        /** 正则筛表(对齐 pandas match)。 */
        public HtmlReader match(String regex) { this.match = regex; return this; }

        /** 提取所有匹配的表(统一终结符,与其它 io 模块的 .go() 一致)。 */
        public List<DataFrame> go() throws IOException {
            String html;
            if (isUrl) {
                html = Jsoup.connect(path.toString()).get().html();
            } else {
                html = Files.readString(path, StandardCharsets.UTF_8);
            }
            return parse(html, match);
        }
    }

    /** 单个 <table> 转 DataFrame。 */
    private static DataFrame tableToDataFrame(Element table) {
        // 表头:thead tr th,或首个 tr 的 td
        List<String> headers = new ArrayList<>();
        Element thead = table.selectFirst("thead");
        Element firstTr = table.selectFirst("tr");
        if (thead != null) {
            for (Element th : thead.select("th")) headers.add(th.text());
        }
        // 表体
        List<Element> bodyRows = new ArrayList<>();
        Element tbody = table.selectFirst("tbody");
        if (tbody != null) {
            bodyRows.addAll(tbody.select("tr"));
        } else {
            // 无 thead/tbody,所有 tr 视为数据(首行作表头)
            Elements allTr = table.select("tr");
            if (allTr.isEmpty()) return null;
            if (headers.isEmpty() && !allTr.isEmpty()) {
                // 用首行作表头
                for (Element cell : allTr.get(0).select("th,td")) headers.add(cell.text());
                for (int i = 1; i < allTr.size(); i++) bodyRows.add(allTr.get(i));
            } else {
                for (Element tr : allTr) bodyRows.add(tr);
            }
        }
        if (headers.isEmpty()) {
            // 全无表头:用 _0,_1,...
            int cols = bodyRows.isEmpty() ? 0 : bodyRows.get(0).select("th,td").size();
            for (int c = 0; c < cols; c++) headers.add("_" + c);
        }
        if (bodyRows.isEmpty()) return null;
        int cols = headers.size();
        Object[][] rows = new Object[bodyRows.size()][cols];
        for (int r = 0; r < bodyRows.size(); r++) {
            Elements cells = bodyRows.get(r).select("th,td");
            for (int c = 0; c < cols; c++) {
                String v = c < cells.size() ? cells.get(c).text() : null;
                rows[r][c] = (v == null || v.isEmpty()) ? null : v;
            }
        }
        return DataFrame.of(Schema.infer(headers, rows), rows);
    }
}
