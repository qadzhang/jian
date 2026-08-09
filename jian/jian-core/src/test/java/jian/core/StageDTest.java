package jian.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : 阶段 D 时序重型测试 —— Resampler + shift + at_time + between_time + asof
// │  Why  : 时序算子是 §3.16 路线图最大头;A 级断言
// │  Who  : 阶段 D 落地回归
// │  When : 2026-08-09 阶段 D
// │  Where: jian-core/src/test/java/jian/core/StageDTest.java
class StageDTest {

    private DataFrame hourlyDf() {
        return DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), 10.0},
                {LocalDateTime.of(2026, 1, 1, 6, 0), 20.0},
                {LocalDateTime.of(2026, 1, 1, 12, 0), 30.0},
                {LocalDateTime.of(2026, 1, 1, 18, 0), 40.0},
                {LocalDateTime.of(2026, 1, 2, 0, 0), 50.0}});
    }

    // ======================== Resampler(日聚合)========================

    @Test
    void resample_1D_sum_合并同日() {
        DataFrame df = hourlyDf();
        DataFrame r = df.resample("ts", "1D").sum();
        // 5 个小时点跨 2 天(2026-01-01 4 个,2026-01-02 1 个)
        // 网格从 2026-01-01 到 2026-01-02,共 2 个 bucket(含 2026-01-02 endpoint 共 3 网格点)
        // sum:2026-01-01 = 10+20+30+40 = 100;2026-01-02 = 50
        assertThat(r.rowCount()).isEqualTo(2);  // 2 个 bucket
        // 验证:v_sum 列的两个值
        DoubleColumn sum = r.getDoubleColumn("v_sum");
        assertThat(sum.getDouble(0)).isCloseTo(100.0, within(1e-9));
        assertThat(sum.getDouble(1)).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void resample_1D_mean() {
        DataFrame df = hourlyDf();
        DataFrame r = df.resample("ts", "1D").mean();
        DoubleColumn mean = r.getDoubleColumn("v_mean");
        assertThat(mean.getDouble(0)).isCloseTo(25.0, within(1e-9));  // 100/4
        assertThat(mean.getDouble(1)).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void resample_1D_count() {
        DataFrame df = hourlyDf();
        DataFrame r = df.resample("ts", "1D").count();
        DoubleColumn cnt = r.getDoubleColumn("v_count");
        assertThat(cnt.getDouble(0)).isEqualTo(4.0);
        assertThat(cnt.getDouble(1)).isEqualTo(1.0);
    }

    @Test
    void resample_1D_min_max() {
        DataFrame df = hourlyDf();
        DataFrame minDf = df.resample("ts", "1D").min();
        DataFrame maxDf = df.resample("ts", "1D").max();
        assertThat(minDf.getDoubleColumn("v_min").getDouble(0)).isEqualTo(10.0);
        assertThat(maxDf.getDoubleColumn("v_max").getDouble(0)).isEqualTo(40.0);
    }

    @Test
    void resample_1D_ohlc() {
        DataFrame df = hourlyDf();
        DataFrame r = df.resample("ts", "1D").ohlc("v");
        assertThat(r.columnCount()).isEqualTo(5);  // bucket + open + high + low + close
        assertThat(r.columnNames()).contains("v_open", "v_high", "v_low", "v_close");
        // 第一日:open=10(首行),high=40,low=10,close=40(末行)
        DoubleColumn open = r.getDoubleColumn("v_open");
        DoubleColumn high = r.getDoubleColumn("v_high");
        DoubleColumn low = r.getDoubleColumn("v_low");
        DoubleColumn close = r.getDoubleColumn("v_close");
        assertThat(open.getDouble(0)).isEqualTo(10.0);
        assertThat(high.getDouble(0)).isEqualTo(40.0);
        assertThat(low.getDouble(0)).isEqualTo(10.0);
        assertThat(close.getDouble(0)).isEqualTo(40.0);
    }

    @Test
    void resample_agg多列多聚合() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "a", DType.DOUBLE, "b", DType.DOUBLE),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), 1.0, 100.0},
                {LocalDateTime.of(2026, 1, 1, 12, 0), 3.0, 200.0},
                {LocalDateTime.of(2026, 1, 2, 0, 0), 5.0, 300.0}});
        java.util.Map<String, String> spec = new java.util.LinkedHashMap<>();
        spec.put("a", "sum");
        spec.put("b", "mean");
        DataFrame r = df.resample("ts", "1D").agg(spec);
        assertThat(r.rowCount()).isEqualTo(2);
        // 第一日:a_sum=1+3=4;b_mean=(100+200)/2=150
        assertThat(r.getDoubleColumn("sum_a").getDouble(0)).isEqualTo(4.0);
        assertThat(r.getDoubleColumn("mean_b").getDouble(0)).isEqualTo(150.0);
    }

    @Test
    void resample_单列sum() {
        DataFrame df = hourlyDf();
        DataFrame r = df.resample("ts", "1D").sum("v");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getDoubleColumn("v_sum").getDouble(0)).isEqualTo(100.0);
    }

    @Test
    void resample_emptyDf() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[0][]);
        DataFrame r = df.resample("ts", "1D").sum();
        // 空表 → 空 Resampler
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void resample_不支持的聚合抛IAE() {
        DataFrame df = hourlyDf();
        // agg 时第一日 bucket 含 4 行数据,bucketAggregate 会进入 default 分支抛 IAE
        assertThatThrownBy(() -> df.resample("ts", "1D").agg(java.util.Map.of("v", "unknown")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Resampler 不支持聚合");
    }

    @Test
    void resample_12H细分() {
        DataFrame df = hourlyDf();
        DataFrame r = df.resample("ts", "12H").sum();
        // 12 小时网格:0:00, 12:00,次日 0:00,次日 12:00(endpoint)
        // bucket 0 [00, 12):10+20=30(0:00 + 6:00);bucket 1 [12, 24):30+40=70(12:00+18:00);
        // bucket 2 [24, 36):50
        assertThat(r.rowCount()).isGreaterThanOrEqualTo(3);  // 至少 3 个 bucket
        assertThat(r.getDoubleColumn("v_sum").getDouble(0)).isEqualTo(30.0);
        assertThat(r.getDoubleColumn("v_sum").getDouble(1)).isEqualTo(70.0);
        assertThat(r.getDoubleColumn("v_sum").getDouble(2)).isEqualTo(50.0);
    }

    // ======================== shift(列位移)========================

    @Test
    void shift_periods1向下位移() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}});
        DoubleColumn r = df.shift("v", 1, "v_s");
        assertThat(Double.isNaN(r.getDouble(0))).isTrue();   // 首行变 NaN
        assertThat(r.getDouble(1)).isEqualTo(1.0);
        assertThat(r.getDouble(2)).isEqualTo(2.0);
    }

    @Test
    void shift_periods负1向上位移() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}});
        DoubleColumn r = df.shift("v", -1, "v_s");
        assertThat(r.getDouble(0)).isEqualTo(2.0);  // src = 0 - (-1) = 1
        assertThat(r.getDouble(1)).isEqualTo(3.0);
        assertThat(Double.isNaN(r.getDouble(2))).isTrue();
    }

    @Test
    void shift_periods0抛IAE() {
        DataFrame df = hourlyDf();
        assertThatThrownBy(() -> df.shift("v", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ======================== at_time / between_time(时点筛选)========================

    @Test
    void atTime_选9点30的行() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 9, 30), 1L},
                {LocalDateTime.of(2026, 1, 2, 14, 0), 2L},
                {LocalDateTime.of(2026, 1, 3, 9, 30), 3L}});
        DataFrame r = df.atTime("ts", LocalTime.of(9, 30));
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(1L);
        assertThat(r.getLongColumn("v").getLong(1)).isEqualTo(3L);
    }

    @Test
    void betweenTime_普通区间() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 8, 0), 1L},
                {LocalDateTime.of(2026, 1, 1, 12, 0), 2L},
                {LocalDateTime.of(2026, 1, 1, 18, 0), 3L}});
        DataFrame r = df.betweenTime("ts", LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertThat(r.rowCount()).isEqualTo(1);  // 只 12:00 在 [9,17]
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(2L);
    }

    @Test
    void betweenTime_跨午夜() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 23, 0), 1L},
                {LocalDateTime.of(2026, 1, 2, 1, 0), 2L},
                {LocalDateTime.of(2026, 1, 2, 10, 0), 3L}});
        DataFrame r = df.betweenTime("ts", LocalTime.of(22, 0), LocalTime.of(2, 0));
        assertThat(r.rowCount()).isEqualTo(2);  // 23:00 和 01:00 命中
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(1L);
        assertThat(r.getLongColumn("v").getLong(1)).isEqualTo(2L);
    }

    // ======================== asof(最近匹配查询)========================

    @Test
    void asof_返回小于等于label的最后行() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), 1L},
                {LocalDateTime.of(2026, 1, 2, 0, 0), 2L},
                {LocalDateTime.of(2026, 1, 4, 0, 0), 4L}});
        DataFrame r = df.asof("ts", LocalDateTime.of(2026, 1, 3, 12, 0));
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(2L);  // 2026-01-02 ≤ 2026-01-03 12:00
    }

    @Test
    void asof_label早于一切返回空表() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), 1L}});
        DataFrame r = df.asof("ts", LocalDateTime.of(2025, 12, 31, 0, 0));
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void asof_精确匹配也命中() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), 1L},
                {LocalDateTime.of(2026, 1, 2, 0, 0), 2L}});
        DataFrame r = df.asof("ts", LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(2L);  // 精确匹配 2026-01-02
    }
}
