# 04 · jian-export 需求说明书

> 版本:v0.2(大面对齐 pandas)· 日期:2026-08-01 · 作者:zc · 依赖:jian-core(Styler HTML 零依赖;Excel 走 POI,与 io-excel 共用)

---

## 1. 模块定位

### 1.1 一句话定位

jian-export **大面对齐 pandas 的 `df.style` (Styler) + 多格式文档导出能力**:把 DataFrame + 样式规则 → HTML / Excel / LaTeX / Markdown / 控制台。其中 **Styler 是独立子系统**(对齐 `pandas.io.formats.style.Styler`),支持条件染色、颜色映射、数字格式、自定义 CSS。

### 1.2 职责边界

**做(对齐 pandas 的输出/样式能力)**:

| 能力 | 子组件 | 对齐 pandas |
|---|---|---|
| **Styler 样式引擎** | `Styler` 类 | `df.style` |
| 条件染色(highlight_max/min/null) | Styler API | `.highlight_max()` 等 |
| 颜色映射(背景渐变) | Styler API | `.background_gradient()`(文字渐变 text_gradient 未实现,见 §2.2 v2) |
| 数值格式化 | Styler API | `.format()` |
| 条形图嵌入单元格 | Styler API | `.bar()` |
| 自定义 CSS / caption | Styler API | `.set_caption()` / `.set_table_styles()` |
| **HTML 输出** | `Styler.to_html()` / `df.to_html()` | 完整对齐 |
| **Excel 输出(带样式)** | `Styler.to_excel()` | 条件格式 + 字体 + 颜色 |
| **LaTeX 输出** | `df.to_latex()` / `Styler.to_latex()` | booktabs 风格 |
| **Markdown 输出** | `df.to_markdown()` | GFM 表格 |
| **控制台 repr** | `df.toString()` | 对齐 pandas repr(截断/对齐) |
| 图表嵌入 HTML 报告 | 配合 viz 模块 | 内联 SVG |

**不做**:
- PDF 直接导出(需 HTML→PDF 引擎,见总览 §8;v2 通过可选依赖 OpenHTMLToPDF 实现,留接口)。
- 交互式 web(可排序/过滤的 JS 表格,v2)。
- Word/PowerPoint(归 io 或单独模块,v2)。

### 1.3 依赖关系

```
jian-core
     ▲
     │
jian-export
     │
     ├── Styler(核心)             纯 JDK,零外部依赖
     ├── HTML/Markdown/LaTeX/控制台 纯 JDK,零外部依赖
     └── Excel 样式输出            复用 poi-ooxml(与 io-excel 共用,不重复引)
```

---

## 2. Styler 子系统设计(核心)

> **⚠️ API 风格说明(2026-08-09 与 AI agent2 共识)**:
> jian 的 **Styler 入口是静态方法** `Styler.of(df)`,**不是** `df.style()` 链式。
> 原因:Styler 属于 jian-export 叶子模块,DataFrame 在 jian-core,core 不能反依赖 export(模块单向依赖红线,见 AGENTS.md §4.1)。
> **用户实际写法**:
> ```java
> Styler s = Styler.of(df).format("#,##0.00", "salary").highlightMax("salary", "#ff0000");
> String html = s.toHtml();
> ```
> 本分册下方示例中如出现 `df.style()` 链式写法,**均为概念示意**(对齐 pandas 心智),实际请用 `Styler.of(df)`。

### 2.1 Styler 概念(对齐 pandas)

`Styler` 是 DataFrame 的"视图 + 样式规则"组合,本身不改数据,只决定如何渲染:

```
Styler = DataFrame(原数据)
       + List<CellStyle>(按单元格的样式规则)
       + List<TableStyle>(整表 CSS)
       + caption / UUID / 隐藏列等元信息
```

样式规则的求值时机:渲染(to_html/to_excel)时按规则重新计算每个单元格的最终样式。

### 2.2 Styler API(对齐 pandas 方法名)

**入口**:`Styler s = Styler.of(df);`(对齐 pandas `df.style()`,但 jian 用静态 `of` 而非实例方法)

#### 已实现(2026-08-09 与 AI agent2 共识:文档如实对齐代码)

```java
Styler s = Styler.of(df);

// 数值格式化(对齐 .format)—— pattern 用 Java DecimalFormat 语法
s.format("#,##0.00", "salary");                  // salary 列保留 2 位小数
s.format(Map.of("date","yyyy-MM-dd","rate","0.00%"));

// 背景颜色渐变(对齐 .background_gradient)—— 颜色用 String(如 "#ffff00"),非 java.awt.Color
// ColorMap 仅有 3 个内置常量:GREEN_YELLOW_RED / BLUE_RED / WHITE_BLUE
s.backgroundGradient("salary", "GREEN_YELLOW_RED");   // 低 → 高 渐变

// 条件高亮(对齐 .highlight_*)—— 颜色参数是 String(如 "#ff0000" 红色)
s.highlightMax("salary", "#ff0000");                  // 最大值标红
s.highlightMin("salary", "#00ff00");                  // 最小值标绿
s.highlightNull("salary", "#999999");                 // 缺失值标灰

// 单元格内条形图(对齐 .bar)—— 简化版,仅接 color,无 min/max 参数(CSS linear-gradient 模拟)
s.bar("score", "#4a90d9");

// 整表样式
s.setCaption("2026 年度员工表");
s.setTableStyles("th {background:#333;color:white}", "tr:nth-child(even) {background:#f9f9f9}");  // varargs CSS
s.hideIndex();                                        // 隐藏行索引列
s.hideColumns("internal_id");
```

> **重要**:颜色参数统一是 **String**(CSS 颜色,如 `"#ffff00"` / `"red"` / `"rgb(255,0,0)"`),**不是** `java.awt.Color`。`ColorMap` 是枚举,仅有 `GREEN_YELLOW_RED` / `BLUE_RED` / `WHITE_BLUE` 三个常量(无 `VIRIDIS` / `COOLWARM`)。

#### v2 规划(未实现,勿抄 —— 抄了会编译失败)

以下 API 在早期文档出现过,但 `Styler.java` **从未实现**,标注为 v2 规划:

| 计划 API | 状态 | 备注 |
|---|---|---|
| `textGradient(col, ColorMap)` | ❌ v2 | 只有 `backgroundGradient`,文字渐变未做 |
| `highlightBetween(col, low, high, color)` | ❌ v2 | 区间高亮未做;可用 `apply` 自定义(v2) |
| `apply(Predicate<Row>, color, col)` | ❌ v2 | 谓词条件染色未做 |
| `setTableStyles(List<TableStyle>)` + `TableStyle` 类 | ❌ v2(已简化) | 实际是 `setTableStyles(String... css)` varargs,无 `TableStyle` 类 |
| `ColorMap.VIRIDIS` / `COOLWARM` | ❌ v2 | 仅有 3 个常量(见上) |
| `bar(col, color, min, max)` | ❌ v2(已简化) | 实际 `bar(String col, String color)` 仅接 color,无 min/max |
| `toHtml(File)` / `toLatex(File)` | ❌ v2 | `toHtml()` 返回 String;落盘用 `HtmlRenderer.renderTo(File)` |

### 2.3 渲染输出

```java
// HTML(带 CSS)—— toHtml() 返回 String;落盘用 HtmlRenderer.renderTo(File)
String html = s.toHtml();
HtmlRenderer.of(df).renderTo(new File("report.html"));

// Excel(条件格式 + 单元格颜色)—— toExcel(w:.path) 单参重载,落盘到路径
s.toExcel("styled.xlsx");

// LaTeX(booktabs)—— toLatex() 返回 String
String tex = s.toLatex();
```

---

## 3. 各输出格式实现

### 3.1 HTML 渲染(对齐 `df.to_html` 全参数)

- 支持 `index` / `columns` / `header` / `border` / `classes` / `na_rep` / `float_format` / `bold_rows` / `justify` 等参数(对齐 pandas `to_html` 签名)。
- Styler 模式:把 CellStyle 转内联 `style="..."` 或 CSS class。
- 自动 HTML 转义(`<`/`>`/`&`)。
- 表头可含 dtype 提示(可选)。
- 缺失值统一 `<NA>`(可配)。
- 大表默认截断(与 pandas `max_rows` 一致)。

### 3.2 Excel 渲染(对齐 Styler.to_excel)

- **依赖**:复用 `poi-ooxml`(与 io-excel 共用,不重复引)。
- 把 CellStyle 转成 POI 的 `CellStyle`(字体色、背景色、边框、数字格式 `DataFormat`)。
- `backgroundGradient` → POI 的 ColorScale 条件格式规则。
- `bar` → POI 的 DataBar 条件格式。
- `highlightMax/Min` → CellIsRule 条件格式。
- 多 sheet + freezeHeader 支持。

### 3.3 LaTeX 渲染(对齐 `df.to_latex`)

- 输出 `\begin{tabular}{列对齐}` ... `\end{tabular}`。
- 支持 `caption` / `label` / `index` / `column_format` / `position` / `booktabs`(默认开启)。
- Styler 的样式在 LaTeX 中映射为有限子集(背景色用 `\cellcolor{}`,需 `xcolor`/`colortbl` 宏包)。

### 3.4 Markdown 渲染(对齐 `df.to_markdown`)

- GFM 表格语法,数值右对齐/文本左对齐。
- 支持 `index` 参数。
- Styler 样式在 Markdown 中不支持(Markdown 表格无样式能力),降级为纯表格 + footnote。

### 3.5 控制台 repr(对齐 `df.__repr__`)

- 默认 `df.toString()` 输出对齐表格 + 行列摘要。
- 大表截断 head/tail 中间 `...`,尾部 `[N rows × M columns]`。
- 中文字符按 2 宽计算对齐。
- `df.show(maxRows, maxColWidth)` 配置。

---

## 4. Styler 实现要点

### 4.1 样式规则模型

```
// ┌─ What : 定义 Styler 内部如何存储与求值样式规则
// │  Why  : pandas Styler 的核心是"规则 + 求值时机分离",便于链式构建
// │  How  : 三类规则:
// │   ① CellStyleRule(scope=列/行/单元格, condition=谓词, style=CSS 或 POI 样式)
// │   ② GradientRule(列, 色图, 范围),求值时扫描该列 min/max 算插值色
// │   ③ BarRule(列, 颜色, min, max),渲染时算条宽百分比
// │  渲染时按 (rowIdx, colIdx) 遍历,逐个应用匹配的规则,合并最终样式
```

### 4.2 色图(ColorMap)

- 内置常见色图:GREEN_YELLOW_RED / BLUE_RED / VIRIDIS / COOLWARM 等(对齐 matplotlib/seaborn 常用)。
- 实现:离散化的 RGB 查找表(256 级),按值在 [min,max] 的位置查色。

### 4.3 与 io-excel 的协作

- Styler 的 Excel 渲染**不重新发明 POI 调用**,而是与 io-excel 共享一个 `ExcelStyleWriter` 内部工具(放在一个共享的内部包,两边都引)。
- 避免 io-excel 与 export 重复实现 POI 样式代码。

---

## 5. 边界与异常

| 场景 | 处理 |
|---|---|
| HTML 单元格含 `<`/`>`/`&` | 自动转义 |
| LaTeX 含 `_`/`%`/`&` | 自动转义(`\_`/`\%`/`\&`) |
| Markdown 含 `|` | 转义为 `\|` |
| 列数过多 | 控制台截断中间列;HTML 加横向滚动 |
| 行数过多 | head/tail + `...` |
| 空 DataFrame | "Empty DataFrame" + 列名 |
| Styler 条件引用不存在列 | `ColumnNotFoundException` |
| POI 未引但调 toExcel | `ModuleNotLoadedException` |

---

## 6. 工作量

- **代码量**:自写约 3,500 行(Styler 核心 1500 + 5 种渲染器 1500 + 色图/共享工具 500),测试 2,000 行。
- **测试**:与 pandas 同输入的 `df.style.to_html()` 输出对比(HTML 结构一致);Excel 用 POI 读回校验样式。

---

## 7. 验收标准

1. **Styler 全部 API**(format/gradient/highlight/bar/apply/setTableStyles/caption/hideIndex)可用。
2. HTML 输出在浏览器/邮件客户端显示正确,样式与 pandas 风格一致。
3. Excel 输出含条件格式(颜色渐变、数据条、极值高亮),Excel 打开可见。
4. LaTeX 输出在 LaTeX 编译器下正确编译(booktabs)。
5. Markdown 符合 GFM。
6. 控制台 repr 中文对齐无误。
7. Styler 链式调用流畅,可叠加多条规则。
8. 不引 POI 时 toExcel 给友好提示,其他渲染器正常。

---

## 8. 实现说明(M3.3 + M4.2,2026-08-01)

> 已实现 HTML / Markdown / LaTeX 渲染器 + **Styler 子系统核心子集**;Excel 样式输出(条件格式)与控制台 repr 留 M4.3+。

### 8.1 已实现

| 文件 | 内容 | 测试 |
|---|---|---|
| `HtmlRenderer.java` | DataFrame → HTML 表格(自动转义/缺失值/截断/caption/border/classes) | `RendererTest` 5 用例 |
| `MarkdownRenderer.java` | DataFrame → GFM 表格(列宽自适应/数值右对齐/管道符转义) | `RendererTest` 3 用例 |
| `LatexRenderer.java` | DataFrame → LaTeX 表格(booktabs/列对齐自动/caption/label/特殊字符转义) | `RendererTest` 2 用例 |
| `Styler.java` | Styler 子系统:format / highlightMax / highlightMin / highlightNull / backgroundGradient / bar / setCaption / hideIndex / hideColumns / setTableStyles + 内置 ColorMap + RGB 插值 | `StylerTest` 9 用例 |

### 8.2 与需求的偏差

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `df.to_html("out.html")` | `HtmlRenderer.of(df).renderTo("out.html")` | Java 风格 builder,配置项链式 |
| `df.style().to_html()` | `Styler.of(df)...toHtml()` | Java 无属性访问器,用 of() 工厂 + 链式 |
| `Styler.background_gradient(cmap='viridis')` | `Styler.backgroundGradient(col, ColorMap.GREEN_YELLOW_RED)` | 内置色图常量(离散 RGB 插值,非 matplotlib 全集);M4 简化 |
| `Styler.bar` 真实数据条 | 用 CSS `linear-gradient` 模拟条宽 | M4 简化(Excel 输出时再用 POI DataBar) |
| LaTeX 缺失值 | 输出空(不写 `<NA>`) | LaTeX 表格惯例,空单元格即可 |
| 缺失值 `<NA>` 经转义 | 实际输出 `&lt;NA&gt;`(HTML) | HTML 安全,与 pandas 一致(pandas 也转义) |

### 8.3 实现状态(全部已落地)

5 渲染器 + Styler 子系统**全部已实现**(23 测试通过):
- HTML/Markdown/LaTeX/**控制台 repr**(中文 2 宽对齐)/ **Styler.toExcel**(POI 条件格式 + 单元格背景色)
- Styler:format/highlightMax/Min/Null/backgroundGradient/bar/setCaption/hideIndex/hideColumns/setTableStyles
- 内置 ColorMap(GREEN_YELLOW_RED/BLUE_RED/WHITE_BLUE)+ RGB 插值

---

*本分册独立,与 01-03/05-06 无耦合。Styler HTML 零依赖;Excel 样式复用 POI。*
*M3.3 + M4.2 + M4.6:5 渲染器(HTML/Markdown/LaTeX/控制台)+ Styler 全套实现完成于 2026-08-01。*
