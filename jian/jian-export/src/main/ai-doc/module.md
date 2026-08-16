# jian-export

## 基本信息
- **library**: jian
- **entryClass**: jian.export.Styler(样式子系统)/ 各 Renderer(HtmlRenderer/MarkdownRenderer/LatexRenderer/ConsoleRenderer)
- **deps**: jian-core;POI(仅 Styler.toExcel 用条件格式);其余渲染器纯 JDK
- **tests**: 33

## 摘要
DataFrame 渲染与样式子系统:HTML/Markdown/LaTeX/控制台表格输出 + 对齐 pandas.Styler 的条件染色/渐变/数据条。

## 能力
- HtmlRenderer:DataFrame → HTML `<table>`,自动转义防注入、大表 head/tail 截断、可控 border/index/naRep/maxRows/caption
- MarkdownRenderer:DataFrame → Markdown 表格(对齐 GitHub Flavored Markdown)
- LatexRenderer:DataFrame → LaTeX `tabular`(booktabs 风格)
- ConsoleRenderer:控制台 repr,CJK 字符按 2 宽对齐、`<NA>` 缺失标识、大表截断
- Styler:链式规则(format/highlightMax/Min/Null、backgroundGradient、bar、setCaption、hideIndex、hideColumns),输出 toHtml/toLatex/toExcel(POI 条件格式)

## 能力:字体样式与列宽

- `fontColor(col,"#ff0000")` / `fontColorIf(col,color,谓词)` / `bold(col)` / `boldIf(col,谓词)`(HTML+Excel 双端,对齐 pandas applymap)
- `autoColumnWidth(默认开)`:中文按 2 宽,clamp [8,80]
- `rowBackgroundIf(col,color,谓词)` 条件整行背景 / `columnBackground(col,color)` 整列背景(对齐 pandas apply(axis=0/1))
- 真实场景:财务月报样式导出(S16,亏损整行红底+千分位原生格式+渐变+加粗,POI 读回验证)
- `format("0.00")` 等标准 Excel 格式串原生透传(数值单元格保持可求和)

### 行为细节
- Styler.toExcel 公式注入防护(与 Csv/Excel 三处 6 字符跳过集一致)
- LaTeX 转义占位符三阶段(反斜杠不再二次转义);Console 缺失默认空串(§3.5.2)+ render(naRep) 可配

### 行为细节(续 1)
- toHtml 缺失值默认 `<NA>`(naRep 可配,对齐 pandas to_html 默认)
- Styler.toExcel 多条 FontRule 合并生效

## 限制
- Styler 子系统为 M4 实现核心子集,部分高级渲染(完全复刻 pandas Styler 全部模板)未覆盖
- HTML/Markdown/LaTeX 输出为表格语义,非任意富文档排版
- ConsoleRenderer 的字符宽度基于 CJK 启发式,少量组合字符/emoji 宽度可能不精确

## 快速上手
```java
import jian.export.HtmlRenderer;
import jian.export.Styler;

String html = HtmlRenderer.of(df).border(1).index(true).render();
HtmlRenderer.of(df).renderTo("report.html");

String styled = df.style()
    .format("#,##0.00", "salary")
    .backgroundGradient("salary", Styler.ColorMap.GREEN_YELLOW_RED)
    .highlightMax("salary", "#ffff00")
    .setCaption("员工表")
    .hideIndex()
    .toHtml();
```
