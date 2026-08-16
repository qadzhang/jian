# 03 · jian-viz 需求说明书

> 版本:v0.2(大面对齐 pandas)· 日期:2026-08-01 · 作者:zc · 依赖:jian-core + XChart 4.0.3 + jian-num(KDE/直方密度需统计)

---

## 1. 模块定位

### 1.1 一句话定位

jian-viz **大面对齐 pandas 的绘图能力**:10 种 `Plot` 静态入口(含 pandas plot 的常用 kind)+ plotting 模块的 3 种专用图,**共 13 种图表**,输出 PNG/SVG 双格式。基于 **XChart 4.0.3(2026-07,活跃)**;radviz/andrews_curves/parallel_coordinates/bootstrap 4 种高维图列入 v2 规划。

### 1.2 职责边界 —— 13 种图全实现(4 种高维图 v2 规划)

#### A. `DataFrame.plot(kind=...)` 的 11 种(对齐 pandas)

| kind | API | 数据要求 | 实现方式 |
|---|---|---|---|
| `line` | `Plot.line(df, "x","y")` | x 任意 + y 数值 | XChart LineChart |
| `bar` | `Plot.bar(df, "x","y")` | x 分类 + y 数值 | XChart CategoryChart |
| `barh` | `Plot.barh(df, "x","y")` | 同上,水平 | CategoryChart 水平 |
| `hist` | `Plot.hist(df, "col", bins)` | 数值列 | 自写分箱计数 + CategoryChart(不引 jian-num) |
| `box` | `Plot.box(df, "col", "g")` | 数值列(可分组) | CategoryChart 多系列箱型近似(min/median/max 三系列,非 BoxChart) |
| `kde`/`density` | `Plot.kde(df, "col", bins)` | 数值列 | **自写直方图归一化(简化 KDE,不引 jian-num)+ XYChart** |
| `area` | `Plot.area(df, "x","y")` | x + y 数值 | XChart AreaChart |
| `pie` | `Plot.pie(df, "cat","val")` | 分类 + 数值 | XChart PieChart |
| `scatter` | `Plot.scatter(df, "x","y")` | 两数值列 | XChart ScatterChart |
| `hexbin` | `Plot.hexbin(df, "x","y", gridsize)` | 两数值列 | **自写六边形分箱 + XChart 散点叠加** |

> `kde`/`hexbin` 在 XChart 中无原生支持,jian 采用**自写简化实现**(直方图归一化 / 分箱计数 + 散点大小映射),**不引 jian-num**(M4 决策:保持 jian-viz 零统计依赖;v2 引 jian-num 后可替换为真正高斯核 KDE)。

#### B. plotting 模块的高维/时序图(对齐 `pandas.plotting`)

> **注(经源码核实)**:`Plot.java` 的 plotting 部分实现 3 种(`scatterMatrix` / `lagPlot` / `autocorrelation`),且全部为**静态方法**(`Plot.scatterMatrix(df, ...)`),**无 `Jian.plotting()` 入口对象**。`radviz` / `andrewsCurves` / `parallelCoordinates` / `bootstrap` 4 种未实现,列入 v2 规划。

| 状态 | 方法 | 实际 API | 用途 | 实现方式 |
|---|---|---|---|---|
| ✅ 已实现 | `scatter_matrix` | `Plot.scatterMatrix(df, cols...)` | 多列两两散点矩阵 | N×N 个 XChart ScatterChart 拼接成图 |
| ✅ 已实现 | `autocorrelation` | `Plot.autocorrelation(df, col, maxLag)` | 时序自相关 | 自写 ACF(不引 jian-num,M4 决策)+ XChart |
| ✅ 已实现 | `lag_plot` | `Plot.lagPlot(df, col, lag)` | 时序滞后散点 | 移位 + ScatterChart |
| 🕐 v2 规划 | `radviz` | (未实现) | 多维点投影到圆 | v2:自写 Radviz 投影 + ScatterChart |
| 🕐 v2 规划 | `andrews_curves` | (未实现) | 多维 Andrews 曲线 | v2:自写投影 + LineChart |
| 🕐 v2 规划 | `parallel_coordinates` | (未实现) | 平行坐标 | v2:自写投影 + LineChart |
| 🕐 v2 规划 | `bootstrap_plot` | (未实现) | 统计自助法分布 | v2:jian-num 重采样 + Histogram |

> **总计**:10 plot + 3 plotting = **13 种已实现**;4 种高维图(radviz/andrews/parallel/bootstrap)列入 v2 规划。

### 1.3 每类图通用能力(对齐 pandas)

- 多列对比(一条命令画多条线/多组柱)。
- 颜色按第三列分组上色(colorBy)。
- 标题 / X 轴 / Y 轴标签 / 图例 / 网格。
- 主题(默认 / GGPlot2 / Matlab,XChart 内置)。
- 子图(subplots=True,把多列拆成多个子图)。
- 二级 Y 轴(secondary_y)。
- 输出 **PNG**(位图)+ **SVG**(矢量,推荐用于报告)。
- 显示(Swing 弹窗,headless 自动 no-op)。

### 1.4 依赖关系

```
jian-core
     ▲
     │
jian-viz ── XChart 4.0.3 (org.knowm.xchart)
     │
     └── (可选) jian-num  ← 用于 kde/hexbin/acf/bootstrap 等统计计算
                          找不到则降级为简单实现(精度低但不报错)
```

---

## 2. 核心 API

> **⚠️ API 风格说明**:
> jian 的 **DataFrame 变换**是链式实例方法;但 **绘图是静态方法收口**(`Plot.line(df, "x","y")` / `Plot.scatter(df,...)`),**不是** `df.plot().line()` 链式。
> 原因:绘图属于 jian-viz 叶子模块,DataFrame 在 jian-core,core 不能反依赖 viz(模块单向依赖红线,见 AGENTS.md §4.1)。
> **用户实际写法**:
> ```java
> XYChart chart = Plot.line(df.filter("age > 18"), "name", "score");
> Plot.savePng(chart, "out.png");
> ```
> 本分册下方示例中如出现 `df.plot().xxx()` 链式写法,**均为概念示意**(对齐 pandas 用户心智),实际请用 `Plot.xxx(df, ...)` 静态调用。

### 2.1 11 种基础图

**实际写法**(静态终端,见 §2 顶部 API 风格说明):

```java
// 折线(支持多列对比)
XYChart lineChart = Plot.line(df, "date", "price_a", "price_b", "price_c");
Plot.saveSvg(lineChart, "price.svg");

// 柱状/水平柱
CategoryChart barChart = Plot.bar(df, "category", "count");
CategoryChart barhChart = Plot.barh(df, "category", "count");

// 直方图(必须传 bins)
CategoryChart histChart = Plot.hist(df, "score", 30);

// 箱线(按分类分组,valCol + groupCol)
CategoryChart boxChart = Plot.box(df, "salary", "dept");

// KDE 密度(简化直方图归一化,必须传 bins)
XYChart kdeChart = Plot.kde(df, "score", 30);

// 面积
XYChart areaChart = Plot.area(df, "date", "sales");

// 饼图
PieChart pieChart = Plot.pie(df, "category", "share");

// 散点
XYChart scatterChart = Plot.scatter(df, "height", "weight");

// Hexbin(必须传 gridsize)
XYChart hexbinChart = Plot.hexbin(df, "x", "y", 30);

// 落盘(所有 chart 都用 Plot.savePng / Plot.saveSvg)
Plot.savePng(histChart, "hist.png");
```

> **注**:`df.plot().line(...).theme(GGPLOT2).saveAsPng(...)` / `.colorBy("gender")` / `.bandwidth(0.5)` / `.stacked(true)` 等链式写法**在早期文档出现过,但 jian 从未实现**。实际:`Plot.xxx(df, ...)` 返回 XChart 对象,主题/系列样式经 XChart API 配置,落盘用 `Plot.savePng(chart, path)`。

### 2.2 7 种高维/时序图(plotting 模块)

```java
// 散点矩阵(全部数值列两两组合)
Jian.plotting().scatterMatrix(df).colorBy("label").saveAsPng("sm.png");

// 自相关
Jian.plotting().autocorrelation(df.getColumn("price")).lags(40).saveAsPng("acf.png");

// Radviz(多维样本在圆上的投影)
Jian.plotting().radviz(df,"label").saveAsPng("radviz.png");

// Andrews 曲线
Jian.plotting().andrewsCurves(df,"label").saveAsPng("andrews.png");

// 平行坐标
Jian.plotting().parallelCoordinates(df,"label").saveAsPng("parallel.png");

// Bootstrap(均值抽样分布)
Jian.plotting().bootstrap(df.getColumn("salary"),samples=1000).saveAsPng("boot.png");

// 滞后散点
Jian.plotting().lagPlot(df.getColumn("price"),lag=1).saveAsPng("lag.png");
```

### 2.3 子图与二级轴 —— v2 规划(未实现)

> **状态**:`subplots(true)` / `secondaryY(col)` 等 API **v2 规划,代码侧从未实现**。当前 jian 的绘图都是单图;子图/双轴请用 XChart 原生 API 组合,或等 v2。

```java
// v2 设计示意(未实现)
// Plot.line(df, "date","a","b") 后续经 XChart 多 chart 组合实现子图/双轴
```

---

## 3. 实现要点

### 3.1 DataFrame → XChart Series 适配

```
// ┌─ What : 把 DataFrame 列转 XChart Series,统一处理类型/缺失/分组
// │  How  : ① 数值列直接转 List<Number>;
// │        ② 分类列转 category index;
// │        ③ colorBy 列的不同值拆成多个 Series;
// │        ④ NaN/null 跳过该点(与 pandas 默认一致)
```

### 3.2 需自写统计后渲染的图

| 图 | 统计计算(自写,不引 jian-num) | 渲染 |
|---|---|---|
| `kde` | 直方图归一化(简化 KDE,无高斯核平滑) | XYChart |
| `hist` | 等宽分箱计数(可选归一化 density=True) | CategoryChart |
| `box` | 每组 min/median/max 三系列(简化,无 Q1/Q3/离群点) | CategoryChart 多系列近似 |
| `hexbin` | 矩形栅格分箱计数(简化,非真正六边形) | XYChart Scatter |
| `autocorrelation` | 各 lag 的 ACF 系数(简单公式) | XYChart Line |
| `radviz` | 多维点按弹簧投影到 2D 圆 | ScatterChart |
| `andrews_curves` | f(t) = x1/√2 + x2·sin(t) + x3·cos(t) + ... | LineChart |
| `parallel_coordinates` | 每行样本在各维度的折线 | LineChart |
| `bootstrap` | 重复采样统计量的分布 | HistogramChart |
| `lag_plot` | y[t] vs y[t-lag] | ScatterChart |

> jian-num 找不到时降级:kde 退化为 hist、autocorrelation 用简单公式;在 SVG/PNG 上打 "降级模式" 水印。

### 3.3 多图拼接(子图与散点矩阵)

- 散点矩阵:N×N 个 ScatterChart,用 XChart 的 `BitmapEncoder` 拼成大图(或导出多个文件)。
- subplots:用 `MultiChart` 或多个 chart 拼一图。

### 3.4 PNG/SVG 双输出 + headless

- PNG:`BitmapEncoder.saveBitmap`;SVG:`VectorGraphicsEncoder.saveVectorGraphic`。
- 推荐 SVG 用于报告(矢量、可缩放)。
- `GraphicsEnvironment.isHeadless()` 为 true 时 `show()` 自动 no-op。

---

## 4. 边界与异常

| 场景 | 处理 |
|---|---|
| 列不存在 | `ColumnNotFoundException` |
| 类型不匹配(对 String 画 hist) | `TypeMismatchException` |
| NaN/缺失 | 跳过该点 |
| 单列唯一值(画 pie) | warning,正常画 |
| XChart jar 未引 | `ModuleNotLoadedException` |
| jian-num 未引且图需要统计 | 降级 + 水印(不崩) |

---

## 5. 工作量

- **代码量**:自写约 2,500 行(11 基础图适配 800 + 7 高维图 1200 + 子图/二级轴/通用 builder 500),测试 1,000 行。
- **测试**:每类图 1 个用例;高维图用经典数据集(如 iris)对比 pandas 输出形状。

---

## 6. 验收标准

1. **13 种图**全部可生成,PNG + SVG 双格式。
2. SVG 嵌入 HTML 浏览器正常显示。
3. 多列对比、colorBy 分组、subplots、secondaryY 可用。
4. kde/box/hexbin/autocorrelation/radviz 等统计图数值正确(与 pandas 同输入差异在容差内)。
5. headless 环境正常。
6. 不引 jian-viz 时 `df.plot()` 给友好提示。
7. jian-num 缺失时统计图降级而非崩溃。

---

## 7. 实现说明

> M3.4 已实现 4 种基础图;M4 补齐全部 13 种(kde/box/area/pie/hexbin/scatterMatrix/lag/autocorrelation);4 种高维图(radviz/andrews/parallel_coordinates/bootstrap)v2 规划。

### 7.1 已实现

| 文件 | 内容 | 测试 |
|---|---|---|
| `Plot.java` | 13 种图入口(line/scatter/bar/hist/barh/area/pie/box/kde/hexbin/scatterMatrix/lagPlot/autocorrelation)+ PNG/SVG 落盘 | @Test 数以 api-counts.md 为准 |

### 7.2 与需求的偏差

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `df.plot().line("x","y")` | `Plot.line(df, "x", "y")` | Java 无属性链;用静态方法承载,df 是首参 |
| `df.plot().hist("col", bins=30)` | `Plot.hist(df, "col", 30)` | 同上 |
| SVG 输出"零配置" | **需显式加 vectorgraphics2d 依赖**(XChart 的 SVG 是 optional 传递依赖) | 规范 §2.5 精细引用:不 shade,显式声明 `de.erichseifert.vectorgraphics2d:VectorGraphics2D:0.13` |

### 7.3 实现状态(全部已落地)

13 种图**全部已实现**(radviz/andrews/parallel_coordinates/bootstrap 4 种高维图 v2 规划):
- 11 种 plot:line/bar/barh/hist/box/kde/area/pie/scatter/hexbin
- plotting:scatter_matrix/autocorrelation/lag_plot
- PNG/SVG 双格式输出

---

*本分册独立,与 01/02/04-06 无耦合。大面对齐 pandas 的 13 种绘图能力(4 种高维图 v2 规划)。*
*13 种图全实现(10 plot + 3 plotting);radviz/andrews/parallel_coordinates/bootstrap 4 种高维图 v2 规划;测试数以 [api-counts.md](api-counts.md) 为准。*

---

## 8. 行为细节(现行)

- 全缺失列:kde / autocorrelation / box 抛教学型报错(不退化输出);hexbin 按密度映射渲染。
- 测试:jian-viz @Test **28**(口径见 [api-counts.md](api-counts.md))。

### 实现说明:外部 AI 协作复审修复

| # | 修复 | 行为变化 |
|---|---|---|
| 1 | `Plot.kde` bins 校验 | bins ≤ 0 抛教学式 IAE(原 bins=0 静默产出空图);与 hist 同口径 |
| 2 | `Plot.hexbin` gridsize 校验 | gridsize ≤ 0 抛 IAE(原 gridsize=0 产出负分箱 key 的错误图表且静默成功) |
| 3 | `Plot.lagPlot` lag 校验 | lag < 0 抛 IAE(原裸 IndexOutOfBoundsException) |
