package jian.viz;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : VizRegressionTest —— jian-viz 回归测试集:固化 Plot 缺失值与密度渲染行为
// │  Why  : 因为含缺失值的数据若让 XChart 抛尺寸/越界异常,或全缺失列产出
// │         NaN 垃圾图、伪造 0.0,都会直接误导读图,所以用强断言锁住正确行为
// │  Who  : jian-viz 模块测试套件
// │  When : mvn test(jian-viz 模块)
// │  Where: jian-viz/src/test/java/jian/viz/VizRegressionTest.java
// │  How  : ①bar/line/pie 含缺失成对/全列对齐跳过(断言真实点数);②hist/kde/autocorrelation
// │         全缺失列与非法 bins 抛教学 IAE;③box 全缺失组整组跳过(不伪造 0.0);
// │         ④hexbin 密度接入渲染(每箱一系列 + 按计数缩放 marker)。
class VizRegressionTest {

    static DataFrame df() {
        return DataFrame.of(Schema.of("cat", DType.STRING, "v", DType.DOUBLE, "w", DType.DOUBLE),
                new Object[][]{{"a", 1.0, 10.0}, {"b", null, 20.0}, {"c", 3.0, 30.0}});
    }

    // ======================== 成对收集(含缺失不崩)========================

    @Test
    void bar含缺失不崩_成对跳过() {
        // 成对收集后 cat=v 的行只剩 a/c 两行(b 行 v 缺失整行跳过);
        // 各列独立收集会触发 "X and Y-Axis sizes are not the same!!!"
        var chart = Plot.bar(df(), "cat", "v");
        org.knowm.xchart.CategorySeries s = chart.getSeries("v");
        assertThat(s).isNotNull();
        assertThat(s.getXData()).hasSize(2);   // a、c(b 的 v 缺失,整行跳过)
        assertThat(s.getYData()).hasSize(2);
        assertThat(s.getYData()).containsExactly(1.0, 3.0);
    }

    @Test
    void line多Y含缺失不崩_全列对齐() {
        // x/w 完整、v 含 null → 该行整体跳过,两 series 与 xs 等长
        // 单 Y 列形态:v/w 成对,v 缺失行(b)整体跳过 → 2 点
        var chart = Plot.line(df(), "v", "w");
        org.knowm.xchart.XYSeries s = chart.getSeries("w");
        assertThat(s).isNotNull();
        assertThat(s.getXData()).hasSize(2);
        assertThat(s.getYData()).hasSize(2);
        assertThat(s.getXData()[0]).isEqualTo(1.0);
        assertThat(s.getYData()[0]).isEqualTo(10.0);
    }

    @Test
    void pie含缺失不崩() {
        // 成对收集后只剩 a/c 两个扇区(b 的 v 缺失被跳过;
        // 各列独立收集会触发 IndexOutOfBoundsException)
        var chart = Plot.pie(df(), "cat", "v");
        assertThat(chart.getSeriesCollection()).hasSize(2);
        assertThat(chart.getSeries("a")).isNotNull();
        assertThat(chart.getSeries("c")).isNotNull();
        assertThat(chart.getSeries("b")).isNull();
    }

    // ======================== 全缺失 fail-fast ========================

    @Test
    void hist全空列抛教学IAE() {
        // 全缺失列不产出 NaN~NaN 标签的退化图,直接教学式报错
        DataFrame allNull = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{null}, {null}});
        assertThatThrownBy(() -> Plot.hist(allNull, "v", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全为缺失");
    }

    @Test
    void kde全缺失列抛教学IAE() {
        DataFrame allNull = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{null}, {null}});
        assertThatThrownBy(() -> Plot.kde(allNull, "v", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全为缺失");
    }

    @Test
    void autocorrelation全缺失列抛教学IAE() {
        DataFrame allNull = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{null}, {null}});
        assertThatThrownBy(() -> Plot.autocorrelation(allNull, "v", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全为缺失");
    }

    @Test
    void box全缺失组跳过不伪造0() {
        // g1 组值全缺失 → 整组不进系列(不伪造 min/median/max = 0.0);g2 组正常
        DataFrame df = DataFrame.of(
                Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"g1", null}, {"g1", null}, {"g2", 5.0}, {"g2", 7.0}});
        var chart = Plot.box(df, "v", "g");
        // 三系列(min/median/max)的分组类别只剩 g2 一个
        assertThat(chart.getSeries("min").getXData()).hasSize(1);
        assertThat(chart.getSeries("median").getXData()).hasSize(1);
        assertThat(chart.getSeries("max").getXData()).hasSize(1);
        // 不伪造 0.0:g2 的 min=5.0
        assertThat(chart.getSeries("min").getYData()).containsExactly(5.0);
        assertThat(chart.getSeries("max").getYData()).containsExactly(7.0);
    }

    @Test
    void box全部组缺失抛教学IAE() {
        DataFrame df = DataFrame.of(
                Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"g1", null}, {"g2", null}});
        assertThatThrownBy(() -> Plot.box(df, "v", "g"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全部分组均为缺失");
    }

    @Test
    void hist_bins非正数抛教学IAE() {
        // (max-min)/bins 为 0/负时不能漏到 XChart 深层异常,入口教学式报错
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}});
        assertThatThrownBy(() -> Plot.hist(df, "v", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bins 必须为正整数");
        assertThatThrownBy(() -> Plot.hist(df, "v", -3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bins 必须为正整数");
    }

    // ======================== hexbin 密度接入渲染 ========================

    @Test
    void hexbin密度接入渲染_每箱一系列() {
        // (0,0) 落同一箱 3 次,(9,9) 落另一箱 1 次 → 2 个非空箱
        DataFrame df = DataFrame.of(
                Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
                new Object[][]{{0.0, 0.0}, {0.1, 0.1}, {0.2, 0.0}, {9.0, 9.0}});
        var chart = Plot.hexbin(df, "x", "y", 10);
        // 每个非空箱一个 series(全部点进单 series 会让 counts 无法进入渲染)
        assertThat(chart.getSeriesCollection()).hasSize(2);
        // 每个非空箱一个数据点
        for (Object s : chart.getSeriesCollection()) {
            org.knowm.xchart.XYSeries xs = (org.knowm.xchart.XYSeries) s;
            assertThat(xs.getXData()).hasSize(1);
            assertThat(xs.getYData()).hasSize(1);
        }
    }

    @Test
    void hexbin密度大的箱marker倍率更大() {
        DataFrame df = DataFrame.of(
                Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
                new Object[][]{{0.0, 0.0}, {0.1, 0.1}, {0.2, 0.0}, {9.0, 9.0}});
        var chart = Plot.hexbin(df, "x", "y", 10);
        // 图级 marker 数组按 series 顺序逐个对应(Styler.setSeriesMarkers),密度信息真实进入渲染配置
        org.knowm.xchart.style.markers.Marker[] markers = chart.getStyler().getSeriesMarkers();
        assertThat(markers).isNotNull();
        assertThat(markers.length).isEqualTo(2);
        // marker 全部是按计数缩放的实现(不是全图同尺寸)
        for (org.knowm.xchart.style.markers.Marker m : markers) {
            assertThat(m).isInstanceOf(Plot.CountScaledCircleMarker.class);
        }
        // 图例已关(一箱一系列,防刷屏)
        assertThat(chart.getStyler().isLegendVisible()).isFalse();
    }

    @Test
    void CountScaledCircleMarker_倍率影响绘制尺寸() {
        // paint 的尺寸 = markerSize * scale(下限 3):构造大小两个 marker 直接对比绘制产物
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(50, 50,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            Plot.CountScaledCircleMarker big = new Plot.CountScaledCircleMarker(2.5);
            Plot.CountScaledCircleMarker small = new Plot.CountScaledCircleMarker(0.3);
            big.paint(g, 25, 25, 8);
            int bigPixels = countLit(img);
            g.setBackground(new java.awt.Color(0, 0, 0, 0));
            g.clearRect(0, 0, 50, 50);
            small.paint(g, 25, 25, 8);
            int smallPixels = countLit(img);
            // 大倍率圆面积(20×20)显著大于小倍率(下限 3×3)
            assertThat(bigPixels).isGreaterThan(smallPixels * 4);
        } finally {
            g.dispose();
        }
    }

    /** 统计图像中非透明像素数(ARGB 初始全 0 = 全透明;fill 后被着色)。 */
    private static int countLit(java.awt.image.BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) n++;
            }
        }
        return n;
    }

    // ======================== 参数校验补齐 ========================

    @Test
    void kde_bins非正_教学式IAE而非静默空图() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}, {2.0}});
        assertThatThrownBy(() -> Plot.kde(df, "v", 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bins");
        assertThatThrownBy(() -> Plot.kde(df, "v", -3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hexbin_gridsize非正_教学式IAE而非负分箱() {
        DataFrame df = DataFrame.of(Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
            new Object[][]{{1.0, 2.0}, {3.0, 4.0}});
        assertThatThrownBy(() -> Plot.hexbin(df, "x", "y", 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gridsize");
        assertThatThrownBy(() -> Plot.hexbin(df, "x", "y", -2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lagPlot负lag_教学式IAE而非裸越界() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}, {2.0}, {3.0}});
        assertThatThrownBy(() -> Plot.lagPlot(df, "v", -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lag");
    }
}
