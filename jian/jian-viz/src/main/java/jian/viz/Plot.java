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

    /** 折线图(对齐 df.plot().line(x, y))。返回 XYChart 可继续配置/保存。 */
    public static XYChart line(DataFrame df, String xCol, String yCol) {
        List<Double> xs = numericColumn(df, xCol);
        List<Double> ys = numericColumn(df, yCol);
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title(yCol + " vs " + xCol).xAxisTitle(xCol).yAxisTitle(yCol).build();
        chart.addSeries(yCol, new ArrayList<>(xs), new ArrayList<>(ys));
        return chart;
    }

    /** 多 Y 列折线(同一 X 轴,多条线)。 */
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
     * @param bins 分箱数
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

    // ======================== 扩展图(委托 PlotExtra,对齐 11 种 plot 完整)========================

    /** 水平柱状图(对齐 df.plot().barh)。 */
    public static CategoryChart barh(DataFrame df, String catCol, String valCol) {
        return PlotExtra.barh(df, catCol, valCol);
    }

    /** 面积图(对齐 df.plot().area)。 */
    public static XYChart area(DataFrame df, String xCol, String yCol) {
        return PlotExtra.area(df, xCol, yCol);
    }

    /** 饼图(对齐 df.plot().pie)。 */
    public static PieChart pie(DataFrame df, String catCol, String valCol) {
        return PlotExtra.pie(df, catCol, valCol);
    }

    /** 箱线图(对齐 df.plot().box)。 */
    public static CategoryChart box(DataFrame df, String valCol, String groupCol) {
        return PlotExtra.box(df, valCol, groupCol);
    }

    /** KDE 密度图(对齐 df.plot().kde)。 */
    public static XYChart kde(DataFrame df, String valCol, int bins) {
        return PlotExtra.kde(df, valCol, bins);
    }

    /** Hexbin(对齐 df.plot().hexbin)。 */
    public static XYChart hexbin(DataFrame df, String xCol, String yCol, int gridsize) {
        return PlotExtra.hexbin(df, xCol, yCol, gridsize);
    }

    /** 散点矩阵(对齐 pandas.plotting.scatter_matrix)。 */
    public static List<XYChart> scatterMatrix(DataFrame df) {
        return PlotExtra.scatterMatrix(df);
    }

    /** 滞后散点图(对齐 pandas.plotting.lag_plot)。 */
    public static XYChart lagPlot(DataFrame df, String col, int lag) {
        return PlotExtra.lagPlot(df, col, lag);
    }

    /** 自相关图(对齐 pandas.plotting.autocorrelation)。 */
    public static XYChart autocorrelation(DataFrame df, String col, int maxLag) {
        return PlotExtra.autocorrelation(df, col, maxLag);
    }

    // ======================== 保存(对齐规范 03 §3.4 PNG/SVG)========================

    /** 保存为 PNG。 */
    public static void savePng(org.knowm.xchart.internal.chartpart.Chart<?, ?> chart, String path) throws IOException {
        BitmapEncoder.saveBitmap(chart, path.replace(".png", ""), BitmapEncoder.BitmapFormat.PNG);
    }

    /** 保存为 SVG(矢量,推荐用于报告)。 */
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
