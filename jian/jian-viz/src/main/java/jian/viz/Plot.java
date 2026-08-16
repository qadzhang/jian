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
        // 成对收集(任一侧缺失整行跳过,两列恒等长)
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        pairedNumeric(df, xCol, yCol, xs, ys);
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
        // x 或任一 y 缺失 → 整行跳过(全列对齐过滤):
        // 因为逐列独立跳 NaN 会让含缺失的 y 列长度短于 xs 而崩 XChart,
        // 所以保证每条 series 与 xs 等长
        Column xc = df.getColumn(xCol);
        if (!xc.dtype().isNumeric()) throw new IllegalStateException(MISSING_MSG + xc.dtype() + "(列 \"" + xCol + "\")");
        List<Column> ycs = new ArrayList<>();
        for (String y : yCols) {
            Column yc = df.getColumn(y);
            if (!yc.dtype().isNumeric()) throw new IllegalStateException(MISSING_MSG + yc.dtype() + "(列 \"" + y + "\")");
            ycs.add(yc);
        }
        List<Double> xs = new ArrayList<>();
        List<List<Double>> yss = new ArrayList<>();
        for (String y : yCols) yss.add(new ArrayList<>());
        for (int i = 0; i < df.rowCount(); i++) {
            if (xc.isNull(i)) continue;
            boolean anyMissing = false;
            for (Column yc : ycs) if (yc.isNull(i)) { anyMissing = true; break; }
            if (anyMissing) continue;
            xs.add(xc.getDouble(i));
            for (int k = 0; k < ycs.size(); k++) yss.get(k).add(ycs.get(k).getDouble(i));
        }
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("line").xAxisTitle(xCol).yAxisTitle("").build();
        for (int k = 0; k < yCols.length; k++) {
            chart.addSeries(yCols[k], new ArrayList<>(xs), new ArrayList<>(yss.get(k)));
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
        // R6:同 line,成对收集(同类隐患一并修)
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        pairedNumeric(df, xCol, yCol, xs, ys);
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
        // 成对收集(cats/vals 同步跳缺失,恒等长)
        List<String> cats = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        pairedCatVal(df, catCol, valCol, cats, vals);
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
        // 因为全缺失列的 min/max 会保持 ±Inf、产出 NaN~NaN 桶标签的退化图
        // (matplotlib 全 NaN 给空图/警告),所以 fail-fast 教学式报错
        if (vals.isEmpty()) {
            throw new IllegalArgumentException("列 \"" + valCol + "\" 全为缺失,无法分箱;"
                    + "请先 dropna 或检查数据来源");
        }
        // 因为 (max-min)/bins 为 0/负/NaN 时会直接漏到 XChart 深层异常,
        // 所以 bins 非正数在入口 fail-fast、教学式报错
        if (bins <= 0) {
            throw new IllegalArgumentException("bins 必须为正整数,实际:" + bins);
        }
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

    // ======================== 扩展图(同域图种内聚于本类,对齐 AGENTS.md §3.1.1.1 内聚原则)========================

    /**
     * 水平柱状图(对齐 df.plot().barh)。
     *
     * @param df DataFrame 数据源,非 null;需含 catCol、valCol 两列
     * @param catCol String 分类列名,非 null
     * @param valCol String 数值列名,非 null;必须为数值列
     * @return CategoryChart XChart 柱状图对象(水平方向需后续 chart 配置)
     */
    public static CategoryChart barh(DataFrame df, String catCol, String valCol) {
        // 成对收集(barh 与 bar 同语义,多入口同步)
        List<String> cats = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        pairedCatVal(df, catCol, valCol, cats, vals);
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
        // R6:同 line,成对收集(多入口同步)
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        pairedNumeric(df, xCol, yCol, xs, ys);
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
        // 成对收集(labels/values 同步跳缺失,恒等长)
        List<String> cats = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        pairedCatVal(df, catCol, valCol, cats, vals);
        // 负值/NaN 在入口校验(不让脏值传到 XChart 渲染时才抛错)
        for (double v : vals) {
            if (Double.isNaN(v) || v < 0) {
                throw new IllegalArgumentException("pie 值必须 ≥ 0 且非 NaN,实际:" + v);
            }
        }
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
    /**
     * 箱线图(对齐 df.plot().box):用每组五数渲染(CategoryChart 简化版,展示 min/median/max 三系列)。
     *
     * <p>值全缺失的组<b>整组跳过</b>(不参与任何系列)——
     * 为对齐三系列长度伪造 min/median/max = 0.0 会产出 0 处伪箱线误导读图;
     * 所有组全缺失时抛教学式 IAE(对齐 hist 的 fail-fast 风格)。
     *
     * @param df DataFrame 数据源,非 null;需含 valCol、groupCol 两列
     * @param valCol String 数值列名,非 null;必须为数值列
     * @param groupCol String 分组列名,非 null;按其值分桶计算五数
     * @return CategoryChart XChart 简化箱线图(min/median/max 三系列)
     * @throws IllegalArgumentException 值列全为缺失时(无组可画)
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
        // 全缺失组直接不进系列(不伪造 0.0);全部组缺失 → 教学 IAE
        List<String> groupNames = new ArrayList<>();
        for (java.util.Map.Entry<String, List<Double>> e : buckets.entrySet()) {
            if (!e.getValue().isEmpty()) groupNames.add(e.getKey());
        }
        if (groupNames.isEmpty()) {
            throw new IllegalArgumentException("列 \"" + valCol + "\" 在全部分组均为缺失,无法画箱线图;"
                    + "请先 dropna 或检查数据来源");
        }
        // 每组算 min/median/max
        List<Double> mins = new ArrayList<>(), medians = new ArrayList<>(), maxs = new ArrayList<>();
        for (String g : groupNames) {
            List<Double> v = buckets.get(g);
            java.util.Collections.sort(v);
            mins.add(v.get(0));
            // 偶数长度取上下中位平均(与 pandas/numpy 线性插值一致)
            int mid = v.size() / 2;
            medians.add(v.size() % 2 == 1 ? v.get(mid) : (v.get(mid - 1) + v.get(mid)) / 2);
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
    /**
     * KDE 密度图(对齐 df.plot().kde):简化为直方图归一化(平滑 KDE 需 jian-num,v2 引入)。
     *
     * <p>全缺失列 fail-fast(对齐 hist 的风格)——
     * min/max 保持 ±Inf 会产出 NaN 密度的垃圾图。
     *
     * @param df DataFrame 数据源,非 null;需含 valCol 列
     * @param valCol String 数值列名,非 null;必须为数值列
     * @param bins int 分箱数,正整数
     * @return XYChart XChart 密度图对象(纵轴为归一化密度)
     * @throws IllegalArgumentException 列全为缺失时
     */
    public static XYChart kde(DataFrame df, String valCol, int bins) {
        List<Double> vals = numericColumn(df, valCol);
        // bins 非正数 fail-fast(与 hist 同口径——bins=0 时
        // (max-min)/0=Infinity、循环零次,静默产出空图而非教学式报错)
        if (bins <= 0) {
            throw new IllegalArgumentException("bins 必须为正整数,实际:" + bins);
        }
        // 全缺失列 fail-fast(否则产出 NaN~NaN 退化图)
        if (vals.isEmpty()) {
            throw new IllegalArgumentException("列 \"" + valCol + "\" 全为缺失,无法估计密度;"
                    + "请先 dropna 或检查数据来源");
        }
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
     * <p><b>实现方式(诚实声明)</b>:XChart 4.x 的 XYChart
     * <b>不支持每点半径</b>(markerSize 是图级全局,extraValues 仅作误差棒)——
     * 密度经「一箱一个 series + 自定义 {@link CountScaledCircleMarker}(绘制直径 ∝ 归一化
     * 计数,0.3~2.5 倍基准 markerSize)」映射到点大小;因 series 数量 = 非空箱数,图例
     * 已关闭(防刷屏)。counts 真实进入渲染(经归一化映射驱动每点大小,兑现
     * "散点大小映射"的语义)。
     *
     * @param df DataFrame 数据源,非 null;需含 xCol、yCol 两列
     * @param xCol String X 轴列名,非 null;必须为数值列
     * @param yCol String Y 轴列名,非 null;必须为数值列
     * @param gridsize int 分箱网格大小,正整数
     * @return XYChart XChart 散点对象(每箱一个点,坐标取箱中心,点大小 ∝ 箱计数)
     */
    public static XYChart hexbin(DataFrame df, String xCol, String yCol, int gridsize) {
        // gridsize 非正数 fail-fast(gridsize=0 时步长=Infinity、
        // gx=Math.min(-1,0)=-1 产出负分箱 key,图表内容错误且静默成功)
        if (gridsize <= 0) {
            throw new IllegalArgumentException("gridsize 必须为正整数,实际:" + gridsize);
        }
        // R6:同 line,成对收集(多入口同步)
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        pairedNumeric(df, xCol, yCol, xs, ys);
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
            // 边界点(xs==xMax)clamp 到 gridsize-1,防越界
            final int gx = Math.min(gridsize - 1, (int) ((xs.get(i) - fxMin) / xStep));
            final int gy = Math.min(gridsize - 1, (int) ((ys.get(i) - fyMin) / yStep));
            long key = ((long) gx << 32) | (gy & 0xFFFFFFFFL);
            counts.merge(key, 1, Integer::sum);
            centers.computeIfAbsent(key, k -> new double[]{
                    fxMin + (gx + 0.5) * xStep, fyMin + (gy + 0.5) * yStep});
        }
        // counts 归一化 → 每箱一个 series + 按计数缩放的 Circle marker(见 javadoc 实现声明)
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("Hexbin " + xCol + " vs " + yCol).xAxisTitle(xCol).yAxisTitle(yCol).build();
        int maxCount = 1;
        for (int c : counts.values()) maxCount = Math.max(maxCount, c);
        List<java.util.Map.Entry<Long, Integer>> entries = new ArrayList<>(counts.entrySet());
        org.knowm.xchart.style.markers.Marker[] markers =
                new org.knowm.xchart.style.markers.Marker[entries.size()];
        for (int k = 0; k < entries.size(); k++) {
            java.util.Map.Entry<Long, Integer> e = entries.get(k);
            double[] c = centers.get(e.getKey());
            // 归一化计数 → 0.3~2.5 倍基准 markerSize(空箱不出现,最低 0.3 保底可见)
            double scale = 0.3 + 2.2 * (e.getValue() / (double) maxCount);
            chart.addSeries("bin" + k, List.of(c[0]), List.of(c[1]));
            markers[k] = new CountScaledCircleMarker(scale);
        }
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        // markers 按 series 加入顺序逐个对应(Styler.setSeriesMarkers 是按序数组)
        chart.getStyler().setSeriesMarkers(markers);
        // 一箱一系列 → 图例只会刷屏,关闭
        chart.getStyler().setLegendVisible(false);
        return chart;
    }

    /**
     * 按计数缩放的圆形 marker(hexbin 密度 → 点大小映射)。
     *
     * <p>XChart 的 Marker.paint 收到的 markerSize 来自图级 Styler(全局一致),本类在
     * paint 内部按构造时给定的倍率缩放,实现"每箱一个 series、每 series 一个尺寸"。
     *
     * <p>绘制口径:xchart 内建 Circle 同款 —— 以 (xOffset, yOffset) 为圆心的正圆填充。
     */
    static final class CountScaledCircleMarker extends org.knowm.xchart.style.markers.Marker {
        private final double scale;

        /**
         * @param scale double 相对基准 markerSize 的倍率(&gt;0;hexbin 归一化到 0.3~2.5)
         */
        CountScaledCircleMarker(double scale) { this.scale = scale; }

        @Override public void paint(java.awt.Graphics2D g, double xOffset, double yOffset, int markerSize) {
            int size = Math.max(3, (int) Math.round(markerSize * scale));
            double half = size / 2.0;
            java.awt.geom.Ellipse2D.Double circle =
                    new java.awt.geom.Ellipse2D.Double(xOffset - half, yOffset - half, size, size);
            g.fill(circle);
        }
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
                if (x.equals(y)) {
                    // 对角线画该列直方图(参考 seaborn.pairplot),
                    // 不再画无意义的自相关散点
                    charts.add(diagonalHistogram(df, x));
                } else {
                    charts.add(Plot.scatter(df, x, y));
                }
            }
        }
        return charts;
    }

    /** 对角线直方图(XYChart Bar 渲染;桶口径与 {@link #hist} 一致:闭-开,末桶含 max)。 */
    private static XYChart diagonalHistogram(DataFrame df, String col) {
        List<Double> vals = numericColumn(df, col);
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        if (!vals.isEmpty()) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : vals) { if (v < min) min = v; if (v > max) max = v; }
            int bins = Math.max(1, (int) Math.sqrt(vals.size()));
            final double width = ((max - min) / bins) == 0 ? 1 : (max - min) / bins;
            for (int b = 0; b < bins; b++) {
                final double lo = min + b * width, hi = lo + width;
                final boolean last = (b == bins - 1);
                long cnt = vals.stream().filter(v -> v >= lo && (v < hi || (last && v <= hi))).count();
                xs.add(lo + width / 2);
                ys.add((double) cnt);
            }
        }
        XYChart chart = new XYChartBuilder().width(800).height(600)
                .title("Hist of " + col).xAxisTitle(col).yAxisTitle("count").build();
        chart.addSeries(col, xs, ys);
        // XChart 4.x XYChart 无 Bar 样式(仅 Line/Area/Step/StepArea/Scatter),用 StepArea 近似直方图
        chart.getStyler().setDefaultSeriesRenderStyle(org.knowm.xchart.XYSeries.XYSeriesRenderStyle.StepArea);
        return chart;
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
        // 负 lag fail-fast(旧实现循环从负下标起,c.isNull(-1)
        // 抛裸 IndexOutOfBoundsException,而非教学式 IAE)
        if (lag < 0) {
            throw new IllegalArgumentException("lag 必须为非负整数,实际:" + lag);
        }
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
    /**
     * 自相关图(对齐 pandas.plotting.autocorrelation):简化 ACF 估计。
     *
     * <p>全缺失列 fail-fast(对齐 hist 的风格)——
     * mean/var 对空序列得 0/0,会产出全 NaN ACF 的垃圾图。
     *
     * @param df DataFrame 数据源,非 null;需含 col 列
     * @param col String 数值列名,非 null
     * @param maxLag int 最大滞后阶数,正整数
     * @return XYChart XChart 折线对象(lag 0..maxLag 的 ACF 估计)
     * @throws IllegalArgumentException 列全为缺失时
     */
    public static XYChart autocorrelation(DataFrame df, String col, int maxLag) {
        Column c = df.getColumn(col);
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) if (!c.isNull(i)) vals.add(c.getDouble(i));
        // 全缺失列 fail-fast(否则 var=0 → ACF 全 0/NaN 垃圾图)
        if (vals.isEmpty()) {
            throw new IllegalArgumentException("列 \"" + col + "\" 全为缺失,无法估计自相关;"
                    + "请先 dropna 或检查数据来源");
        }
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

    // ======================== 成对收集器 ========================

    // 因为 x/y(或 cat/val)两列若各自独立收集(数值列跳缺失、分类列不跳),列长不等直接喂
    // XChart 会抛 "X and Y-Axis sizes are not the same!!!"/IOOBE,所以成对收集:任一侧缺失
    // 整行同步跳过(matplotlib 对 NaN 画空/断线,不崩);对 line 多 Y 采用"x 或任一 y 缺失
    // 即整行跳过"的全列对齐过滤,保证每条 series 与 xs 等长。

    /** 数值×数值成对收集(任一侧缺失整行跳过)。 */
    private static void pairedNumeric(DataFrame df, String xCol, String yCol,
                                      List<Double> xs, List<Double> ys) {
        Column xc = df.getColumn(xCol), yc = df.getColumn(yCol);
        if (!xc.dtype().isNumeric() || !yc.dtype().isNumeric()) {
            throw new IllegalStateException(MISSING_MSG + "要求两列均数值:" + xCol + "/" + yCol);
        }
        for (int i = 0; i < df.rowCount(); i++) {
            if (xc.isNull(i) || yc.isNull(i)) continue;
            xs.add(xc.getDouble(i));
            ys.add(yc.getDouble(i));
        }
    }

    /** 分类×数值成对收集(任一侧缺失整行跳过)。 */
    private static void pairedCatVal(DataFrame df, String catCol, String valCol,
                                     List<String> cats, List<Double> vals) {
        Column cc = df.getColumn(catCol), vc = df.getColumn(valCol);
        if (!vc.dtype().isNumeric()) {
            throw new IllegalStateException(MISSING_MSG + vc.dtype() + "(列 \"" + valCol + "\")");
        }
        for (int i = 0; i < df.rowCount(); i++) {
            if (cc.isNull(i) || vc.isNull(i)) continue;
            cats.add(String.valueOf(cc.get(i)));
            vals.add(vc.getDouble(i));
        }
    }
}
