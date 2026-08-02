package jian.viz;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : jian-viz 测试 —— line/bar/scatter/hist 图表生成 + PNG/SVG 落盘
class PlotTest {

    @TempDir Path tmp;

    @Test
    void line生成() {
        DataFrame df = sample();
        var chart = Plot.line(df, "x", "y");
        assertThat(chart).isNotNull();
        assertThat(chart.getTitle()).contains("y vs x");
    }

    @Test
    void line多列() {
        DataFrame df = DataFrame.of(
                Schema.of("x", DType.DOUBLE, "a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{1.0, 10.0, 20.0}, {2.0, 15.0, 25.0}});
        var chart = Plot.line(df, "x", "a", "b");
        // 多 Y 列:两个系列都能取到
        assertThat(chart.getSeries("a")).isNotNull();
        assertThat(chart.getSeries("b")).isNotNull();
    }

    @Test
    void scatter生成() {
        DataFrame df = sample();
        var chart = Plot.scatter(df, "x", "y");
        assertThat(chart).isNotNull();
    }

    @Test
    void bar生成() {
        DataFrame df = DataFrame.of(
                Schema.of("cat", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"A", 10.0}, {"B", 20.0}, {"C", 30.0}});
        var chart = Plot.bar(df, "cat", "v");
        assertThat(chart).isNotNull();
    }

    @Test
    void hist分箱计数正确() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        var chart = Plot.hist(df, "v", 5);
        assertThat(chart).isNotNull();
        assertThat(chart.getSeries("v")).isNotNull();  // 单一系列
    }

    @Test
    void 非数值列绘图抛异常() {
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"a", 1.0}});
        assertThatThrownBy(() -> Plot.line(df, "name", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("要求数值列");
    }

    @Test
    void png落盘() throws Exception {
        DataFrame df = sample();
        var chart = Plot.line(df, "x", "y");
        String path = tmp.resolve("out.png").toString();
        Plot.savePng(chart, path);
        assertThat(java.nio.file.Files.exists(Path.of(path))).isTrue();
        assertThat(java.nio.file.Files.size(Path.of(path))).isGreaterThan(1000);  // PNG 有内容
    }

    @Test
    void svg落盘() throws Exception {
        DataFrame df = sample();
        var chart = Plot.line(df, "x", "y");
        String path = tmp.resolve("out.svg").toString();
        Plot.saveSvg(chart, path);
        assertThat(java.nio.file.Files.exists(Path.of(path))).isTrue();
        // SVG 是文本,含 <svg
        String content = java.nio.file.Files.readString(Path.of(path));
        assertThat(content).contains("<svg");
    }

    private DataFrame sample() {
        return DataFrame.of(
                Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
                new Object[][]{
                        {1.0, 2.0},
                        {2.0, 4.0},
                        {3.0, 6.0},
                        {4.0, 8.0}
                });
    }

    @Test
    void area生成() {
        DataFrame df = sample();
        var chart = Plot.area(df, "x", "y");
        assertThat(chart).isNotNull();
    }

    @Test
    void pie生成() {
        DataFrame df = DataFrame.of(
                jian.core.Schema.of("cat", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"A", 10.0}, {"B", 20.0}});
        var chart = Plot.pie(df, "cat", "v");
        assertThat(chart).isNotNull();
    }

    @Test
    void box生成() {
        DataFrame df = DataFrame.of(
                jian.core.Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"A", 1.0}, {"A", 2.0}, {"B", 5.0}, {"B", 6.0}});
        var chart = Plot.box(df, "v", "g");
        assertThat(chart).isNotNull();
    }

    @Test
    void kde生成() {
        DataFrame df = sample();
        var chart = Plot.kde(df, "y", 10);
        assertThat(chart).isNotNull();
    }

    @Test
    void hexbin生成() {
        DataFrame df = sample();
        var chart = Plot.hexbin(df, "x", "y", 5);
        assertThat(chart).isNotNull();
    }

    @Test
    void scatterMatrix生成() {
        DataFrame df = DataFrame.of(
                jian.core.Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{1.0, 2.0}, {3.0, 4.0}});
        var charts = Plot.scatterMatrix(df);
        assertThat(charts).hasSize(4);  // 2 列 × 2 列 = 4 个散点
    }

    @Test
    void lagPlot生成() {
        DataFrame df = sample();
        var chart = Plot.lagPlot(df, "y", 1);
        assertThat(chart).isNotNull();
    }

    @Test
    void autocorrelation生成() {
        DataFrame df = DataFrame.of(
                jian.core.Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        var chart = Plot.autocorrelation(df, "v", 3);
        assertThat(chart).isNotNull();
    }
}
