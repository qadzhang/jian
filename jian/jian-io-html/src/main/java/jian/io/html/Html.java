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
// │           - match:字面子串筛表(contains,不用正则,防 ReDoS;对齐 pandas read_html match)。
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
 * List<DataFrame> tables = Html.read("page.html").match("用户").go();   // match 是字面子串
 * List<DataFrame> fromUrl = Html.readUrl("https://example.com").go();
 * }</pre>
 *
 * <p>写 HTML 用 jian-export 的 HtmlRenderer(纯 JDK)。
 */
public final class Html {

    private Html() {}

    /**
     * 直接读(无配置):返回全部表格;等价 read(path).go()。
     * @param path String HTML 文件路径,需为合法可读文件,不允许 null
     * @return List&lt;DataFrame&gt; 提取出的全部表格(每个 &lt;table&gt; 一个 DataFrame),无表则空列表
     * @throws java.io.IOException 文件不存在、不可读或解析 IO 错误时抛出
     */
    public static java.util.List<DataFrame> readAll(String path) throws java.io.IOException {
        return new HtmlReader(Path.of(path), false).go();
    }

    /**
     * 读 HTML 的 builder(与其它 io 模块统一:read(path).config().go())。
     * @param path String HTML 文件路径,需为合法可读文件,不允许 null
     * @return HtmlReader 配置器,链式调用 .match 后 .go() 执行
     */
    public static HtmlReader read(String path) { return new HtmlReader(Path.of(path), false); }

    /**
     * 从 URL 读 HTML 的 builder(需要 match 配置时用)。链式配置后 .go()。
     * <p>因为 Windows 路径解析器只允许盘符位置的 ':'(把 "https://…" 塞进 Path
     * 会在 "https" 后的冒号处直接抛 InvalidPathException,连 go() 里的 scheme
     * 校验都到不了),所以 URL 以 String 字段保存、不经 Path.of。
     * @param url String 要抓取的页面 URL(http/https),需为合法可访问地址,不允许 null
     * @return HtmlReader 配置器,链式调用 .match 后 .go() 执行
     */
    public static HtmlReader readUrl(String url) {
        // scheme 白名单在【工厂入口】校验(早于任何后续处理 —— 非 http/https 的
        // url 连构造都不放行,绕过 go() 里的校验)
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("readUrl 仅允许 http/https,实际: " + url);
        }
        return new HtmlReader(url);
    }

    /**
     * 从 HTML 字符串提取所有表格。
     * @param html String HTML 文本内容,不允许 null
     * @param match String 表格文本筛选【字面子串】(表的文本内容 contains 该子串才保留;不用正则,防 ReDoS);null 表示不过滤,返回全部表
     * @return List&lt;DataFrame&gt; 通过 match 筛选后的表格列表,无匹配则空列表
     */
    public static List<DataFrame> parse(String html, String match) {
        Document doc = Jsoup.parse(html);
        Elements tables = doc.select("table");
        List<DataFrame> out = new ArrayList<>();
        for (Element table : tables) {
            DataFrame df = tableToDataFrame(table);
            if (df == null) continue;
            // 因为把用户输入当正则拼 ".*"+match+".*"(恶意正则 (a+)+ 可触发指数回溯 ReDoS),
            // 所以 match 用字面子串匹配(contains)而非正则
            if (match == null || df.toString().contains(match) || table.text().contains(match)) {
                out.add(df);
            }
        }
        return out;
    }

    public static final class HtmlReader {
        /** 文件模式(与 url 模式互斥:isUrl=true 时本字段可为 null)。 */
        private final Path path;
        /** URL 模式(字符串保存,不经 Path.of —— Windows 路径解析器对 "https://" 的 ':' 抛 InvalidPathException)。 */
        private final String url;
        private final boolean isUrl;
        private String match = null;

        HtmlReader(Path p, boolean url) { this.path = p; this.url = null; this.isUrl = url; }

        /**
         * URL 字符串模式构造器 —— URL 不落 Path,文件路径模式保留 Path。
         * @param u String 要抓取的 URL(scheme 校验在工厂 readUrl 已做,go() 内还有二道防线)
         */
        HtmlReader(String u) { this.url = u; this.path = null; this.isUrl = true; }

        /**
         * 字面子串筛表(对齐 pandas read_html 的 match 参数语义)。
         * <p>注意:match 是【字面子串筛选】而非正则(表文本 contains 该子串才保留,
         * 防 ReDoS),不要按正则写 {@code .*用户.*},那样反而匹配失败。
         * @param substr String 表格文本筛选【字面子串】(对 DataFrame 字符串或表内文本做 contains 匹配);null/不调用表示不过滤
         * @return HtmlReader 当前配置器,便于链式调用
         */
        public HtmlReader match(String substr) { this.match = substr; return this; }

        /**
         * 提取所有匹配的表(统一终结符,与其它 io 模块的 .go() 一致)。
         * @return List&lt;DataFrame&gt; 通过 match 筛选后的表格列表,无匹配则空列表
         * @throws IOException 文件不存在/不可读,或 URL 抓取失败时抛出
         */
        public List<DataFrame> go() throws IOException {
            String html;
            if (isUrl) {
                // url 优先取字符串字段(旧构造器 Path 模式兜底兼容)
                String u = url != null ? url : path.toString();
                // URL 读取三重防护 ——
                //   ① scheme 白名单(http/https;file:// 等一律拒绝,Jsoup 亦不支持);
                //   ② 显式超时(默认无超时会被慢响应挂死线程,Web 场景拖垮线程池);
                //   ③ 响应体积上限(防超大响应 OOM;SSRF 内网探测缓解见 doc/00 §10.17)
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    throw new IllegalArgumentException("readUrl 仅允许 http/https,实际: " + u);
                }
                html = Jsoup.connect(u)
                        .timeout(10_000)
                        .maxBodySize(8 * 1024 * 1024)
                        .get().html();
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
            // 因为手写 HTML 无显式 thead/tbody 时 jsoup 会自动生成 tbody,若 tbody 首个 tr
            // 是纯 th 行而不视为表头,列名会退化为 _0/_1,所以检测纯 th 行作表头
            if (headers.isEmpty() && !bodyRows.isEmpty()) {
                Element first = bodyRows.get(0);
                boolean pureTh = !first.select("th").isEmpty() && first.select("td").isEmpty();
                if (pureTh) {
                    for (Element th : first.select("th")) headers.add(th.text());
                    bodyRows.remove(0);
                }
            }
        } else {
            // 无 thead/tbody,所有 tr 视为数据(首行作表头)
            Elements allTr = table.select("tr");
            if (allTr.isEmpty()) return null;
            if (headers.isEmpty() && !allTr.isEmpty()) {
                // 用首行作表头(因为首行含 <th> 是明确表头信号——jsoup 会把无包裹的
                // tr 自动归入 tbody,仅靠 thead 分支取 th 会丢首行 th 场景的列名)
                boolean firstIsHeaderRow = !allTr.get(0).select("th").isEmpty();
                if (firstIsHeaderRow) {
                    for (Element th : allTr.get(0).select("th")) headers.add(th.text());
                } else {
                    for (Element cell : allTr.get(0).select("th,td")) headers.add(cell.text());
                }
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
        // 因为合并单元格/手写表格常见重复 th,而 Schema 校验对重名抛 IAE 会整表拒读
        //(Csv/Excel 都自动去重,唯 Html 抛错,表格型 IO 行为分裂),
        // 所以与 Csv.dedupHeaderNames 同口径去重:重名自动加 _1/_2 后缀
        //(pandas read_csv mangle_dupe_cols 同语义;jian 用 _1 而 pandas 用 .1,§10.16#16)。
        java.util.Set<String> seenH = new java.util.LinkedHashSet<>();
        for (int c = 0; c < headers.size(); c++) {
            String base = headers.get(c);
            String cand = base;
            int k = 1;
            while (!seenH.add(cand)) cand = base + "_" + k++;
            headers.set(c, cand);
        }
        if (bodyRows.isEmpty()) return null;
        int cols = headers.size();
        Object[][] rows = new Object[bodyRows.size()][cols];
        for (int r = 0; r < bodyRows.size(); r++) {
            Elements cells = bodyRows.get(r).select("th,td");
            for (int c = 0; c < cols; c++) {
                String v = c < cells.size() ? cells.get(c).text() : null;
                // 因为写出端缺失值渲染为 <NA>(export 层 naRep 约定),读回时须识别为
                // 缺失 → null(与写端对称;若把 "<NA>" 当字符串参与 Schema.infer,
                // 含缺失的数值列会整列降级 STRING)
                rows[r][c] = (v == null || v.isEmpty() || "<NA>".equals(v)) ? null : v;
            }
        }
        return DataFrame.of(Schema.infer(headers, rows), rows);
    }
}
