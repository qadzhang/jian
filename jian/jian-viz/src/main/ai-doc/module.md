# jian-viz

## 基本信息
- **library**: jian
- **entryClass**: jian.viz.Plot(全部 13 种图 + savePng/saveSvg)
- **deps**: jian-core;XChart 4.0.3(XYChart / CategoryChart / PieChart + BitmapEncoder / VectorGraphicsEncoder)
- **tests**: 28

## 摘要
DataFrame 绘图,对齐 pandas df.plot;基于 XChart 提供 13+ 种图,导出 PNG / SVG。

## 能力
- 基础图(Plot):line / scatter / bar / hist
- 扩展图(Plot):barh / area / pie / box / kde / hexbin / scatterMatrix / lagPlot / autocorrelation(统一入口)
- 输出:`Plot.savePng(chart, path)` 落 PNG;`Plot.saveSvg(chart, path)` 落 SVG(VectorGraphicsEncoder)
- 列类型校验:非数值列传给数值图时抛 IllegalStateException
- 多列折线:`Plot.line(df, xCol, yCol1, yCol2, ...)`

### 行为细节
- bar/line/pie/barh/area/hexbin 成对收集:任一侧缺失整行跳过(避免 XChart 尺寸/越界异常)
- hist 全缺失列抛教学 IAE(不生成 NaN~NaN 退化图)

### 行为细节(续 1)
- 全缺失列:kde/autocorrelation/box 抛教学型报错(不退化输出)
- hexbin 按密度映射渲染

## 限制
- 基于 XChart,外观定制受 XChart 限制;不如 matplotlib 灵活
- kde 为简化实现(直方图归一化近似),非完整高斯核密度带宽选择
- box 用 CategoryChart 多系列近似五数,非原生 BoxPlot
- hexbin 为六边形分箱计数 + 散点大小映射,非真六边形镶嵌

## 快速上手
```java
import jian.viz.Plot;

Plot.savePng(Plot.line(df, "x", "y"), "line.png");
Plot.saveSvg(Plot.bar(df, "city", "sales"), "bar.svg");
Plot.savePng(Plot.hist(df, "score", 20), "hist.png");
Plot.savePng(Plot.scatter(df, "height", "weight"), "scatter.png");

// 多列折线
Plot.savePng(Plot.line(df, "date", "open", "close", "high"), "multi.png");
```
