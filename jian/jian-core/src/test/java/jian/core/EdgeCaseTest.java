package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : 边界用例测试 —— 空 DataFrame / 单行 / 全缺失 / 大数(补 opencode 第二轮发现的测试盲区)
class EdgeCaseTest {

    @Test
    void 空DataFrame不崩溃() {
        DataFrame df = DataFrame.of(Schema.of("a", DType.LONG), new Object[0][]);
        assertThat(df.rowCount()).isEqualTo(0);
        assertThat(df.columnCount()).isEqualTo(1);
        assertThat(df.isEmpty()).isTrue();
        // toString 不崩
        assertThat(df.toString()).contains("Empty");
        // 变换返回空
        assertThat(df.head(5).rowCount()).isEqualTo(0);
        assertThat(df.drop("a").columnCount()).isEqualTo(0);
    }

    @Test
    void 空DataFrame统计返回NaN() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[0][]);
        // 空列的 mean 返回 NaN(不抛异常,与 pandas 一致)
        double m = df.colMean("v");
        assertThat(Double.isNaN(m)).isTrue();
    }

    @Test
    void 空DataFramegroupBy不崩() {
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.STRING, "v", DType.DOUBLE),
                new Object[0][]);
        DataFrame r = df.groupBy("k").agg("v", "mean");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void 单行DataFrame() {
        DataFrame df = DataFrame.of(
                Schema.of("x", DType.DOUBLE, "y", DType.STRING),
                new Object[][]{{42.0, "hello"}});
        assertThat(df.rowCount()).isEqualTo(1);
        assertThat(df.getDoubleColumn("x").getDouble(0)).isEqualTo(42.0);
        assertThat(df.getStringColumn("y").get(0)).isEqualTo("hello");
        // 排序
        assertThat(df.sortBy("x", true).rowCount()).isEqualTo(1);
        // describe
        DataFrame desc = df.describe();
        assertThat(desc).isNotNull();
    }

    @Test
    void 全缺失列() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{null}, {null}, {null}});
        assertThat(df.getDoubleColumn("v").nullCount()).isEqualTo(3);
        assertThat(Double.isNaN(df.colMean("v"))).isTrue();
        // dropna any → 空表
        assertThat(df.dropna().rowCount()).isEqualTo(0);
        // fillna
        DataFrame filled = df.fillna(0.0);
        assertThat(filled.getDoubleColumn("v").getDouble(0)).isEqualTo(0.0);
    }

    @Test
    void 大整数精度() {
        // long 边界值
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG),
                new Object[][]{{Long.MAX_VALUE}, {Long.MIN_VALUE}, {0L}});
        assertThat(df.getLongColumn("id").getLong(0)).isEqualTo(Long.MAX_VALUE);
        assertThat(df.getLongColumn("id").getLong(1)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void 空DataFrame查询() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[0][]);
        // query 在空表上不崩
        DataFrame r = df.query("v > 10");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void 空DataFramemerge() {
        DataFrame a = DataFrame.of(Schema.of("id", DType.LONG), new Object[0][]);
        DataFrame b = DataFrame.of(Schema.of("id", DType.LONG, "v", DType.STRING),
                new Object[][]{{1L, "x"}});
        DataFrame r = a.merge(b, "inner", "id");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void null值过滤() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.STRING),
                new Object[][]{{"a"}, {null}, {"b"}});
        DataFrame filtered = df.query("v is not null");
        assertThat(filtered.rowCount()).isEqualTo(2);
        assertThat(filtered.getStringColumn("v").get(0)).isEqualTo("a");
    }

    @Test
    void Series空列操作() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[0][]);
        Series s = df.getSeries("v");
        assertThat(s.size()).isEqualTo(0);
    }
}
