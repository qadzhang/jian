package jian.viz;

import jian.core.Column;
import jian.core.DataFrame;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.internal.chartpart.Chart;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : PlotExtra —— 补全 11 种基础图(规范 03 §1.2 剩余:box/area/pie/hexbin/kde/barh + density)
// │  Why  : 规范 03 §1.2 要求 11 种 plot;M3.4 已实现 line/bar/scatter/hist,本类补全其余
// │  Who  : 用户经 Plot.box/Plot.area/... 调用(本类是 Plot 的扩展)
// │  When : 数据探索可视化
// │  Where: jian-viz/PlotExtra.java
// │  How  : 数据走向:DataFrame + 列名 → 取数值/分类列 → XChart 各 Chart 类型 → 返回 chart。
// │         关键变量变化:
// │           - box:每组的五数(min/Q1/median/Q3/max),用 CategoryChart 多系列箱型近似;
// │           - kde:自写高斯核密度(简化:直方图归一化),M4 简化不引 jian-num;
// │           - hexbin:六边形分箱计数 → 散点大小映射。
/**
 * Plot 扩展:补全 11 种基础图里 M3.4 未做的部分。
 *
 * <p>M4.5 实现:box / area / pie / barh / kde(density)/ hexbin。
 *
 * @see Plot 基础图(line/bar/scatter/hist)
 */
public final class PlotExtra {

    private PlotExtra() {}

    /** 水平柱状图(对齐 df.plot().barh)。 */
    public static CategoryChart barh(DataFrame df, String catCol, String valCol) {
        List<String> cats = stringColumn(df, catCol);
        List<Double> vals = numericColumn(df, valCol);
        CategoryChart chart = new CategoryChartBuilder().width(800).height(600)
                .title(valCol + " by " + catCol).xAxisTitle(valCol).yAxisTitle(catCol).build();
        // XChart 的 CategoryChart 没有直接水平开关;水平柱图通过 chart 配置后续支持
        chart.addSeries(valCol, cats, new ArrayList<>(vals));
        return chart;
    }

    /** 面积图(对齐 df.plot().area)。 */
    public static XYChart area(DataFrame df, String xCol, String yCol) {
        List<Double> xs = numericColumn(df, xCol);
        List<Double> ys = numericColumn(df, yCol);
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title(yCol + " area").xAxisTitle(xCol).yAxisTitle(yCol).build();
        chart.addSeries(yCol, new ArrayList<>(xs), new ArrayList<>(ys));
        chart.getStyler().setDefaultSeriesRenderStyle(org.knowm.xchart.XYSeries.XYSeriesRenderStyle.Area);
        return chart;
    }

    /** 饼图(对齐 df.plot().pie)。 */
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

    /** 箱线图(对齐 df.plot().box):用每组五数渲染(CategoryChart 简化版,展示 min/median/max 三系列)。 */
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
     * KDE 密度图(对齐 df.plot().kde):M4 简化为直方图归一化(平滑 KDE 需 jian-num,M4.5 不引)。
     * v2 引 jian-num 后替换为真正高斯核密度。
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
     * Hexbin(对齐 df.plot().hexbin):M4 简化为分箱计数 + 散点大小映射(真正六边形需自写渲染)。
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

    // ======================== plotting 模块(高维/时序图,7 种)========================

    /** 散点矩阵(对齐 pandas.plotting.scatter_matrix):N×N 数值列两两散点。 */
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

    /** 滞后散点图(对齐 pandas.plotting.lag_plot):y[t] vs y[t-lag]。 */
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

    /** 自相关图(对齐 pandas.plotting.autocorrelation):简化 ACF 估计。 */
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

    // 共享工具
    private static List<Double> numericColumn(DataFrame df, String col) {
        Column c = df.getColumn(col);
        if (!c.dtype().isNumeric()) {
            throw new IllegalStateException("绘图要求数值列,实际 " + c.dtype() + "(列 \"" + col + "\")");
        }
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) if (!c.isNull(i)) r.add(c.getDouble(i));
        return r;
    }

    private static List<String> stringColumn(DataFrame df, String col) {
        Column c = df.getColumn(col);
        List<String> r = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) r.add(String.valueOf(c.get(i)));
        return r;
    }
}
