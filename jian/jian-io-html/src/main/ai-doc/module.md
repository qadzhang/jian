# jian-io-html

## 基本信息
- **library**: jian
- **entryClass**: jian.io.html.Html
- **deps**: jian-core;jsoup 1.18.3(HTML 解析 + 自带 HTTP 抓取)
- **tests**: 9

## 摘要
HTML 表格读取,对齐 pandas.read_html;从文件/URL/字符串提取全部 `<table>`,逐表转 DataFrame,支持 match 正则筛选。

## 能力
- Html.read(path):读本地 HTML 文件,返回所有 `<table>` 的 List<DataFrame>
- Html.readUrl(url):jsoup.connect 自带 HTTP 抓取,提取页面表格
- Html.readString(html):直接解析 HTML 字符串
- builder 配置:`.match(".*用户.*")` 按表文本正则筛选保留的表;thead/tbody 缺失时首行作表头兜底
- 自动类型推断(NUMERIC/STRING 等)
- readUrl 兼容 Windows 路径(URL 解析不误走 Path)

## 限制
- 仅读(写 HTML 用 jian-export 的 HtmlRenderer,纯 JDK)
- 仅识别标准 `<table>` 结构;复杂嵌套/`<div>` 表格布局不支持
- URL 抓取走 jsoup 默认 HTTP,不带认证/复杂 header/JS 渲染(动态页面不适用)

## 快速上手
```java
import jian.io.html.Html;
import java.util.List;

List<DataFrame> tables = Html.read("page.html").match(".*用户.*").go();
List<DataFrame> fromUrl = Html.readUrl("https://example.com").go();
List<DataFrame> parsed = Html.readString("<table><tr><th>a</th></tr>...</table>").go();
```
