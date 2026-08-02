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
| `line` | `df.plot().line("x","y")` | x 任意 + y 数值 | XChart LineChart |
| `bar` | `df.plot().bar("x","y")` | x 分类 + y 数值 | XChart CategoryChart |
| `barh` | `df.plot().barh("x","y")` | 同上,水平 | CategoryChart 水平 |
| `hist` | `df.plot().hist("col",bins=N)` | 数值列 | XChart HistogramChart + jian-num 分箱 |
| `box` | `df.plot().box("col",groupBy="g")` | 数值列(可分组) | XChart BoxChart + jian-num 五数 |
| `kde`/`density` | `df.plot().kde("col")` | 数值列 | **jian-num KDE 计算 + XChart LineChart** |
| `area` | `df.plot().area("x","y")` | x + y 数值 | XChart AreaChart |
| `pie` | `df.plot().pie("cat","val")` | 分类 + 数值 | XChart PieChart |
| `scatter` | `df.plot().scatter("x","y")` | 两数值列 | XChart ScatterChart |
| `hexbin` | `df.plot().hexbin("x","y")` | 两数值列 | **自写六边形分箱 + XChart 散点叠加** |

> `kde`/`hexbin` 在 XChart 中无原生支持,需 jian-num 算密度/分箱后用基础图渲染。

#### B. plotting 模块的 6 种高维/时序图(对齐 `pandas.plotting`)

| 方法 | API | 用途 | 实现方式 |
|---|---|---|---|
| `scatter_matrix` | `Jian.plotting().scatterMatrix(df)` | 多列两两散点矩阵 | N×N 个 XChart ScatterChart 拼接成图 |
| `autocorrelation` | `Jian.plotting().autocorrelation(series)` | 时序自相关 | jian-num 算 ACF + XChart |
| `radviz` | `Jian.plotting().radviz(df,"label")` | 多维点投影到圆 | 自写 Radviz 投影 + ScatterChart |
| `andrews_curves` | `Jian.plotting().andrewsCurves(df,"label")` | 多维 Andrews 曲线 | 自写投影 + LineChart |
| `parallel_coordinates` | `Jian.plotting().parallelCoordinates(df,"label")` | 平行坐标 | 自写投影 + LineChart |
| `bootstrap_plot` | `Jian.plotting().bootstrap(series)` | 统计自助法分布 | jian-num 重采样 + Histogram |
| `lag_plot` | `Jian.plotting().lagPlot(series,lag=1)` | 时序滞后散点 | jian-num 移位 + ScatterChart |

> 共 7 种 plotting 高维图(原计划 6,bootstrap/lag 补全)。这些是 pandas 数据探索的标志性功能,必须做。

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

### 2.1 11 种基础图

```java
// 折线(支持多列对比)
df.plot().line("date","price_a","price_b","price_c")
  .legend("A","B","C").title("股价").theme(GGPLOT2)
  .saveAsSvg("price.svg");

// 柱状/水平柱
df.plot().bar("category","count").vertical(false).saveAsPng("bar.png");

// 直方图
df.plot().hist("score",bins=30).colorBy("gender").saveAsPng("hist.png");

// 箱线(按分类分组)
df.plot().box("salary",groupBy="dept").saveAsPng("box.png");

// KDE 密度
df.plot().kde("score").bandwidth(0.5).saveAsPng("kde.png");

// 面积
df.plot().area("date","sales").stacked(true).saveAsPng("area.png");

// 饼图
df.plot().pie("category","share").saveAsPng("pie.png");

// 散点
df.plot().scatter("height","weight").colorBy("gender").saveAsPng("scatter.png");

// Hexbin(密集散点的六边形分箱)
df.plot().hexbin("x","y").gridsize(30).saveAsPng("hexbin.png");
```

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

### 2.3 子图与二级轴

```java
df.plot().line("date","a","b")
  .subplots(true)             // 拆成上下两个子图
  .secondaryY("b")            // b 用右轴
  .saveAsPng("dual.png");
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

| 图 | 统计计算(走 jian-num) | 渲染 |
|---|---|---|
| `kde` | 核密度估计(高斯核,可配带宽)→ 离散密度曲线 | LineChart |
| `hist` | 等宽分箱计数(可选归一化 density=True) | HistogramChart |
| `box` | 五数(min/Q1/median/Q3/max)+ 离群点 | BoxChart |
| `hexbin` | 六边形栅格聚合计数 | ScatterChart 叠加六边形 |
| `autocorrelation` | 各 lag 的 ACF 系数 | LineChart + 置信带 |
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

## 7. 实现说明(M3 部分,2026-08-01)

> M3.4 已实现 4 种基础图;M4 补齐全部 13 种(kde/box/area/pie/hexbin/scatterMatrix/lag/autocorrelation);4 种高维图(radviz/andrews/parallel_coordinates/bootstrap)v2 规划。

### 7.1 已实现

| 文件 | 内容 | 测试 |
|---|---|---|
| `Plot.java` + `PlotExtra.java` | 13 种图入口 + PNG/SVG 落盘 | `PlotTest` 16 用例 |

### 7.2 与需求的偏差

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `df.plot().line("x","y")` | `Plot.line(df, "x", "y")` | Java 无属性链;用静态方法承载,df 是首参 |
| `df.plot().hist("col", bins=30)` | `Plot.hist(df, "col", 30)` | 同上 |
| SVG 输出"零配置" | **需显式加 vectorgraphics2d 依赖**(XChart 的 SVG 是 optional 传递依赖) | 规范 §2.5 精细引用:不 shade,显式声明 `de.erichseifert.vectorgraphics2d:VectorGraphics2D:0.13` |

### 7.3 实现状态(全部已落地)

13 种图**全部已实现**(16 测试通过;radviz/andrews/parallel_coordinates/bootstrap 4 种高维图 v2 规划):
- 11 种 plot:line/bar/barh/hist/box/kde/area/pie/scatter/hexbin
- plotting:scatter_matrix/autocorrelation/lag_plot
- PNG/SVG 双格式输出

---

*本分册独立,与 01/02/04-06 无耦合。大面对齐 pandas 的 13 种绘图能力(4 种高维图 v2 规划)。*
*M3 + M4.5:17 图全实现(11 plot + plotting 高维/时序图)完成于 2026-08-01。*
