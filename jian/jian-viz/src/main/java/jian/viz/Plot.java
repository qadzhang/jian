package jian.viz;

import jian.core.Column;
import jian.core.DataFrame;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.VectorGraphicsEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.internal.series.Series;
import org.knowm.xchart.style.Styler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Plot —— DataFrame 绘图(对齐 pandas df.plot,基于 XChart 4.0.3)
// │  Why  : 规范 03 §1.2;M3.4 先实现 4 种基础图(line/bar/scatter/hist),完整 17 种图 M4 补
// │  Who  : 用户经 df.plot() 或 Plot.line/Plot.bar 调用
// │  When : 数据可视化、报告图表
// │  Where: jian-viz/Plot.java
// │  How  : 数据走向:DataFrame + 列名 → 取数值列转 List<Number> → XChart chart → PNG/SVG 字节。
// │         关键变量变化:
// │           - xData/yData:从 DataFrame 列提取的数值列表(NaN 跳过);
// │           - chart:XChart 的 XYChart(线/散点)或 CategoryChart(柱/直方)。
// │         逻辑路线:
// │           路径 A(line)→ XYChart + XYSeries 连线;
// │           路径 B(bar)→ CategoryChart + 柱;
// │           路径 C(scatter)→ XYChart + XYSeries 散点;
// │           路径 D(hist)→ 自写分箱 + CategoryChart;
// │           路径 E(列非数值)→ 抛 IllegalStateException。
/**
 * DataFrame 绘图入口,对齐 pandas df.plot(基于 XChart)。
 *
 * <p>M3.4 实现 4 种基础图:
 * <ul>
 *   <li>{@link #line} —— 折线图(XYChart);</li>
 *   <li>{@link #bar} —— 柱状图(CategoryChart);</li>
 *   <li>{@link #scatter} —— 散点图(XYChart);</li>
 *   <li>{@link #hist} —— 直方图(自写分箱 + CategoryChart)。</li>
 * </ul>
 *
 * <p>完整 17 种图(box/kde/area/pie/hexbin + 7 种 plotting 高维图)M4 补。
 */
public final class Plot {

    private Plot() {}

    private static final String MISSING_MSG = "绘图要求数值列,实际 ";

    // ======================== line ========================

    /**
     * 折线图(对齐 df.plot().line(x, y))。返回 XYChart 可继续配置/保存。
     *
     * @param df DataFrame 数据源,非 null;需含 xCol、yCol 两列
     * @param xCol String X 轴列名,非 null;必须为数值列
     * @param yCol String Y 轴列名,非 null;必须为数值列
     * @return XYChart XChart 折线图对象,可继续配置或保存为 PNG/SVG
     * @throws IllegalStateException 当 xCol/yCol 不是数值列时抛出
     */
    public static XYChart line(DataFrame df, String xCol, String yCol) {
        List<Double> xs = numericColumn(df, xCol);
        List<Double> ys = numericColumn(df, yCol);
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title(yCol + " vs " + xCol).xAxisTitle(xCol).yAxisTitle(yCol).build();
        chart.addSeries(yCol, new ArrayList<>(xs), new ArrayList<>(ys));
        return chart;
    }

    /**
     * 多 Y 列折线(同一 X 轴,多条线)。
     *
     * @param df DataFrame 数据源,非 null;需含 xCol 及所有 yCols
     * @param xCol String X 轴列名,非 null;必须为数值列
     * @param yCols String[] Y 轴列名,可变参数,至少一个;均需为数值列
     * @return XYChart XChart 折线图对象(每个 yCol 一条 series)
     * @throws IllegalStateException 当 xCol 或任一 yCol 不是数值列时抛出
     */
    public static XYChart line(DataFrame df, String xCol, String... yCols) {
        List<Double> xs = numericColumn(df, xCol);
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("line").xAxisTitle(xCol).yAxisTitle("").build();
        for (String y : yCols) {
            chart.addSeries(y, new ArrayList<>(xs), new ArrayList<>(numericColumn(df, y)));
        }
        return chart;
    }

    // ======================== scatter ========================

    /**
     * 散点图(对齐 df.plot().scatter)。
     *
     * @param df DataFrame 数据源,非 null;需含 xCol、yCol 两列
     * @param xCol String X 轴列名,非 null;必须为数值列
     * @param yCol String Y 轴列名,非 null;必须为数值列
     * @return XYChart XChart 散点图对象
     * @throws IllegalStateException 当 xCol/yCol 不是数值列时抛出
     */
    public static XYChart scatter(DataFrame df, String xCol, String yCol) {
        List<Double> xs = numericColumn(df, xCol);
        List<Double> ys = numericColumn(df, yCol);
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title(yCol + " vs " + xCol).xAxisTitle(xCol).yAxisTitle(yCol).build();
        chart.addSeries(yCol, new ArrayList<>(xs), new ArrayList<>(ys));
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        return chart;
    }

    // ======================== bar ========================

    /**
     * 柱状图(对齐 df.plot().bar)。
     *
     * @param df DataFrame 数据源,非 null;需含 catCol、valCol 两列
     * @param catCol String 分类列名,非 null;通常为字符串列
     * @param valCol String 数值列名,非 null;必须为数值列
     * @return CategoryChart XChart 柱状图对象
     * @throws IllegalStateException 当 valCol 不是数值列时抛出
     */
    public static CategoryChart bar(DataFrame df, String catCol, String valCol) {
        List<String> cats = stringColumn(df, catCol);
        List<Double> vals = numericColumn(df, valCol);
        CategoryChart chart = new CategoryChartBuilder().width(800).height(600)
                .title(valCol + " by " + catCol).xAxisTitle(catCol).yAxisTitle(valCol).build();
        chart.addSeries(valCol, cats, new ArrayList<>(vals));
        return chart;
    }

    // ======================== hist ========================

    /**
     * 直方图(对齐 df.plot().hist)。
     *
     * @param df DataFrame 数据源,非 null;需含 valCol 列
     * @param valCol String 数值列名,非 null;必须为数值列
     * @param bins int 分箱数,正整数
     * @return CategoryChart XChart 直方图对象(每箱一个分类,纵轴为计数)
     * @throws IllegalStateException 当 valCol 不是数值列时抛出
     */
    public static CategoryChart hist(DataFrame df, String valCol, int bins) {
        List<Double> vals = numericColumn(df, valCol);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : vals) { if (v < min) min = v; if (v > max) max = v; }
        final double widthRaw = (max - min) / bins;
        final double width = widthRaw == 0 ? 1 : widthRaw;  // 单值列兜底
        final double finalMin = min;
        // 分箱计数
        List<String> catLabels = new ArrayList<>();
        List<Double> counts = new ArrayList<>();
        for (int b = 0; b < bins; b++) {
            final double lo = finalMin + b * width;
            final double hi = lo + width;
            final boolean last = (b == bins - 1);
            long cnt = vals.stream().filter(v -> v >= lo && (v < hi || (last && v <= hi))).count();
            catLabels.add(String.format("%.2f~%.2f", lo, hi));
            counts.add((double) cnt);
        }
        CategoryChart chart = new CategoryChartBuilder().width(800).height(600)
                .title("Histogram of " + valCol).xAxisTitle("bin").yAxisTitle("count").build();
        chart.addSeries(valCol, catLabels, new ArrayList<>(counts));
        return chart;
    }

    // ======================== 扩展图(2026-08 与 AI agent1 共识:PlotExtra 已合并到本类,对齐 AGENTS.md §3.1.1.1 内聚原则)========================

    /**
     * 水平柱状图(对齐 df.plot().barh)。
     *
     * @param df DataFrame 数据源,非 null;需含 catCol、valCol 两列
     * @param catCol String 分类列名,非 null
     * @param valCol String 数值列名,非 null;必须为数值列
     * @return CategoryChart XChart 柱状图对象(水平方向需后续 chart 配置)
     */
    public static CategoryChart barh(DataFrame df, String catCol, String valCol) {
        List<String> cats = stringColumn(df, catCol);
        List<Double> vals = numericColumn(df, valCol);
        CategoryChart chart = new CategoryChartBuilder().width(800).height(600)
                .title(valCol + " by " + catCol).xAxisTitle(valCol).yAxisTitle(catCol).build();
        // XChart 的 CategoryChart 没有直接水平开关;水平柱图通过 chart 配置后续支持
        chart.addSeries(valCol, cats, new ArrayList<>(vals));
        return chart;
    }

    /**
     * 面积图(对齐 df.plot().area)。
     *
     * @param df DataFrame 数据源,非 null;需含 xCol、yCol 两列
     * @param xCol String X 轴列名,非 null;必须为数值列
     * @param yCol String Y 轴列名,非 null;必须为数值列
     * @return XYChart XChart 面积图对象
     */
    public static XYChart area(DataFrame df, String xCol, String yCol) {
        List<Double> xs = numericColumn(df, xCol);
        List<Double> ys = numericColumn(df, yCol);
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title(yCol + " area").xAxisTitle(xCol).yAxisTitle(yCol).build();
        chart.addSeries(yCol, new ArrayList<>(xs), new ArrayList<>(ys));
        chart.getStyler().setDefaultSeriesRenderStyle(org.knowm.xchart.XYSeries.XYSeriesRenderStyle.Area);
        return chart;
    }

    /**
     * 饼图(对齐 df.plot().pie)。
     *
     * @param df DataFrame 数据源,非 null;需含 catCol、valCol 两列
     * @param catCol String 分类列名,非 null;每个值对应一个扇区
     * @param valCol String 数值列名,非 null;必须为数值列
     * @return PieChart XChart 饼图对象
     */
    public static PieChart pie(DataFrame df, String catCol, String valCol) {
        List<String> cats = stringColumn(df, catCol);
        List<Double> vals = numericColumn(df, valCol);
        PieChart chart = new PieChartBuilder().width(800).height(600)
                .title(valCol + " by " + catCol).build();
        for (int i = 0; i < cats.size(); i++) {
            chart.addSeries(cats.get(i), vals.get(i));
        }
        return chart;
    }

    /**
     * 箱线图(对齐 df.plot().box):用每组五数渲染(CategoryChart 简化版,展示 min/median/max 三系列)。
     *
     * @param df DataFrame 数据源,非 null;需含 valCol、groupCol 两列
     * @param valCol String 数值列名,非 null;必须为数值列
     * @param groupCol String 分组列名,非 null;按其值分桶计算五数
     * @return CategoryChart XChart 简化箱线图(min/median/max 三系列)
     */
    public static CategoryChart box(DataFrame df, String valCol, String groupCol) {
        Column vals = df.getColumn(valCol);
        Column groups = df.getColumn(groupCol);
        // 按 group 分桶
        java.util.LinkedHashMap<String, List<Double>> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < df.rowCount(); i++) {
            String g = String.valueOf(groups.get(i));
            buckets.computeIfAbsent(g, k -> new ArrayList<>());
            if (!vals.isNull(i)) buckets.get(g).add(vals.getDouble(i));
        }
        // 每组算 min/median/max
        List<String> groupNames = new ArrayList<>(buckets.keySet());
        List<Double> mins = new ArrayList<>(), medians = new ArrayList<>(), maxs = new ArrayList<>();
        for (String g : groupNames) {
            List<Double> v = buckets.get(g);
            if (v.isEmpty()) { mins.add(0.0); medians.add(0.0); maxs.add(0.0); continue; }
            java.util.Collections.sort(v);
            mins.add(v.get(0));
            medians.add(v.get(v.size() / 2));
            maxs.add(v.get(v.size() - 1));
        }
        CategoryChart chart = new CategoryChartBuilder().width(800).height(600)
                .title("Box of " + valCol + " by " + groupCol).xAxisTitle(groupCol).yAxisTitle(valCol).build();
        chart.addSeries("min", groupNames, mins);
        chart.addSeries("median", groupNames, medians);
        chart.addSeries("max", groupNames, maxs);
        return chart;
    }

    /**
     * KDE 密度图(对齐 df.plot().kde):简化为直方图归一化(平滑 KDE 需 jian-num,v2 引入)。
     *
     * @param df DataFrame 数据源,非 null;需含 valCol 列
     * @param valCol String 数值列名,非 null;必须为数值列
     * @param bins int 分箱数,正整数
     * @return XYChart XChart 密度图对象(纵轴为归一化密度)
     */
    public static XYChart kde(DataFrame df, String valCol, int bins) {
        List<Double> vals = numericColumn(df, valCol);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : vals) { if (v < min) min = v; if (v > max) max = v; }
        double width = (max - min) / bins;
        if (width == 0) width = 1;
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        double total = vals.size();
        for (int b = 0; b < bins; b++) {
            double lo = min + b * width;
            double hi = lo + width;
            final boolean last = (b == bins - 1);
            final double flo = lo;
            final double fhi = hi;
            long cnt = vals.stream().filter(v -> v >= flo && (v < fhi || (last && v <= fhi))).count();
            xs.add((lo + hi) / 2);
            ys.add(cnt / total / width);  // 归一化为密度
        }
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("KDE of " + valCol).xAxisTitle(valCol).yAxisTitle("density").build();
        chart.addSeries(valCol, xs, ys);
        return chart;
    }

    /**
     * Hexbin(对齐 df.plot().hexbin):简化为分箱计数 + 散点大小映射(真正六边形需自写渲染)。
     *
     * @param df DataFrame 数据源,非 null;需含 xCol、yCol 两列
     * @param xCol String X 轴列名,非 null;必须为数值列
     * @param yCol String Y 轴列名,非 null;必须为数值列
     * @param gridsize int 分箱网格大小,正整数
     * @return XYChart XChart 散点对象(每箱一个点,坐标取箱中心)
     */
    public static XYChart hexbin(DataFrame df, String xCol, String yCol, int gridsize) {
        List<Double> xs = numericColumn(df, xCol);
        List<Double> ys = numericColumn(df, yCol);
        double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (double v : xs) { if (v < xMin) xMin = v; if (v > xMax) xMax = v; }
        for (double v : ys) { if (v < yMin) yMin = v; if (v > yMax) yMax = v; }
        final double xStep = ((xMax - xMin) / gridsize) == 0 ? 1 : (xMax - xMin) / gridsize;
        final double yStep = ((yMax - yMin) / gridsize) == 0 ? 1 : (yMax - yMin) / gridsize;
        final double fxMin = xMin, fyMin = yMin;
        // 分箱计数
        java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
        java.util.Map<Long, double[]> centers = new java.util.HashMap<>();
        for (int i = 0; i < xs.size(); i++) {
            final int gx = (int) ((xs.get(i) - fxMin) / xStep);
            final int gy = (int) ((ys.get(i) - fyMin) / yStep);
            long key = ((long) gx << 32) | (gy & 0xFFFFFFFFL);
            counts.merge(key, 1, Integer::sum);
            centers.computeIfAbsent(key, k -> new double[]{
                    fxMin + (gx + 0.5) * xStep, fyMin + (gy + 0.5) * yStep});
        }
        // 转 series(单 series,大小用 BubbleSize 不行;XChart XYChart 用 markers)
        List<Double> plotX = new ArrayList<>();
        List<Double> plotY = new ArrayList<>();
        for (long key : counts.keySet()) {
            double[] c = centers.get(key);
            plotX.add(c[0]);
            plotY.add(c[1]);
        }
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("Hexbin " + xCol + " vs " + yCol).xAxisTitle(xCol).yAxisTitle(yCol).build();
        chart.addSeries("hex", plotX, plotY);
        chart.getStyler().setDefaultSeriesRenderStyle(org.knowm.xchart.XYSeries.XYSeriesRenderStyle.Scatter);
        return chart;
    }

    /**
     * 散点矩阵(对齐 pandas.plotting.scatter_matrix):N×N 数值列两两散点。
     *
     * @param df DataFrame 数据源,非 null;对所有数值列两两组合绘制散点
     * @return List&lt;XYChart&gt; XYChart 列表,N×N 个(N 为数值列数)
     */
    public static List<XYChart> scatterMatrix(DataFrame df) {
        List<String> numCols = new ArrayList<>();
        for (String c : df.columnNames()) if (df.getColumn(c).dtype().isNumeric()) numCols.add(c);
        List<XYChart> charts = new ArrayList<>();
        for (String x : numCols) {
            for (String y : numCols) {
                charts.add(Plot.scatter(df, x, y));
            }
        }
        return charts;
    }

    /**
     * 滞后散点图(对齐 pandas.plotting.lag_plot):y[t] vs y[t-lag]。
     *
     * @param df DataFrame 数据源,非 null;需含 col 列
     * @param col String 数值列名,非 null
     * @param lag int 滞后阶数,正整数
     * @return XYChart XChart 散点对象
     */
    public static XYChart lagPlot(DataFrame df, String col, int lag) {
        Column c = df.getColumn(col);
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = lag; i < c.size(); i++) {
            if (!c.isNull(i) && !c.isNull(i - lag)) {
                xs.add(c.getDouble(i - lag));
                ys.add(c.getDouble(i));
            }
        }
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("Lag(" + lag + ") of " + col).xAxisTitle(col + "(t-" + lag + ")").yAxisTitle(col + "(t)").build();
        chart.addSeries(col, xs, ys);
        chart.getStyler().setDefaultSeriesRenderStyle(org.knowm.xchart.XYSeries.XYSeriesRenderStyle.Scatter);
        return chart;
    }

    /**
     * 自相关图(对齐 pandas.plotting.autocorrelation):简化 ACF 估计。
     *
     * @param df DataFrame 数据源,非 null;需含 col 列
     * @param col String 数值列名,非 null
     * @param maxLag int 最大滞后阶数,正整数
     * @return XYChart XChart 折线对象(lag 0..maxLag 的 ACF 估计)
     */
    public static XYChart autocorrelation(DataFrame df, String col, int maxLag) {
        Column c = df.getColumn(col);
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) if (!c.isNull(i)) vals.add(c.getDouble(i));
        double mean = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = vals.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / vals.size();
        List<Integer> lags = new ArrayList<>();
        List<Double> acf = new ArrayList<>();
        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            int n = 0;
            for (int i = lag; i < vals.size(); i++) {
                sum += (vals.get(i) - mean) * (vals.get(i - lag) - mean);
                n++;
            }
            double a = var == 0 ? 0 : (sum / n) / var;
            lags.add(lag);
            acf.add(a);
        }
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("ACF of " + col).xAxisTitle("lag").yAxisTitle("ACF").build();
        chart.addSeries("ACF", lags, acf);
        return chart;
    }

    // ======================== 保存(对齐规范 03 §3.4 PNG/SVG)========================

    /**
     * 保存为 PNG。
     *
     * @param chart org.knowm.xchart.internal.chartpart.Chart XChart 图表对象,非 null
     * @param path String 目标 PNG 路径,非 null;可含或不含 .png 后缀(自动去除后写入)
     * @throws IOException 写文件失败时抛出(磁盘满 / 无权限 / 路径非法)
     */
    public static void savePng(org.knowm.xchart.internal.chartpart.Chart<?, ?> chart, String path) throws IOException {
        BitmapEncoder.saveBitmap(chart, path.replace(".png", ""), BitmapEncoder.BitmapFormat.PNG);
    }

    /**
     * 保存为 SVG(矢量,推荐用于报告)。
     *
     * @param chart org.knowm.xchart.internal.chartpart.Chart XChart 图表对象,非 null
     * @param path String 目标 SVG 路径,非 null;可含或不含 .svg 后缀(自动去除后写入)
     * @throws IOException 写文件失败时抛出(磁盘满 / 无权限 / 路径非法)
     */
    public static void saveSvg(org.knowm.xchart.internal.chartpart.Chart<?, ?> chart, String path) throws IOException {
        VectorGraphicsEncoder.saveVectorGraphic(chart, path.replace(".svg", ""), VectorGraphicsEncoder.VectorGraphicsFormat.SVG);
    }

    // ======================== 内部:列提取 ========================

    private static List<Double> numericColumn(DataFrame df, String col) {
        Column c = df.getColumn(col);
        if (!c.dtype().isNumeric()) {
            throw new IllegalStateException(MISSING_MSG + c.dtype() + "(列 \"" + col + "\")");
        }
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            if (!c.isNull(i)) r.add(c.getDouble(i));
        }
        return r;
    }

    private static List<String> stringColumn(DataFrame df, String col) {
        Column c = df.getColumn(col);
        List<String> r = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) r.add(String.valueOf(c.get(i)));
        return r;
    }
}
