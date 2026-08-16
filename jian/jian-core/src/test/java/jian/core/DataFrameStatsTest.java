package jian.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : M1.3 测试 —— 描述统计 / apply / describe / 缺失值(dropna/fillna/ffill/bfill)
class DataFrameStatsTest {

    @Test
    void colMean_skipNaN() {
        DataFrame df = df();
        // score: 90, NaN, 80 → mean = 85
        assertThat(df.colMean("score")).isCloseTo(85.0, within(1e-10));
    }

    @Test
    void colSum_and_count() {
        DataFrame df = df();
        assertThat(df.colSum("score")).isEqualTo(170.0);  // 90 + 80(skip NaN)
        // 因为 colSum 返回 double(LONG 列求和也经 getDouble 口径),所以断言用 double 字面量 6.0
        assertThat(df.colSum("id")).isEqualTo(6.0);
    }

    @Test
    void colSum溢出保留Infinity不退化为NaN() {
        // 因为 Neumaier 末步 sum+comp 在 sum 溢出为 ±Infinity 时会得 NaN(comp 取到反向
        // Infinity)污染下游,所以溢出时放弃补偿保留 ±Infinity,对齐 pandas(sum 溢出 → inf)
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{Double.MAX_VALUE}, {Double.MAX_VALUE}});
        assertThat(df.colSum("v")).as("溢出 sum 应为 +Infinity").isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(df.colMean("v")).as("溢出 mean 应为 +Infinity").isEqualTo(Double.POSITIVE_INFINITY);
        DataFrame neg = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{-Double.MAX_VALUE}, {-Double.MAX_VALUE}});
        assertThat(neg.colSum("v")).as("负向溢出 sum 应为 -Infinity").isEqualTo(Double.NEGATIVE_INFINITY);
        // 溢出守卫不得破坏正常精度路径(Neumaier 精确值,见 SeriesWindowTest.kahan精度)
        DataFrame k = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1e16}, {1.0}, {2.0}, {-1e16}});
        assertThat(k.colSum("v")).isEqualTo(3.0);
    }

    @Test
    void colMedian_偶数个() {
        DataFrame df = df();
        // score 非 NaN: 90, 80 → median = 85
        assertThat(df.colMedian("score")).isCloseTo(85.0, within(1e-10));
    }

    @Test
    void colMin_colMax() {
        DataFrame df = df();
        assertThat(df.colMin("score")).isEqualTo(80.0);
        assertThat(df.colMax("score")).isEqualTo(90.0);
    }

    @Test
    void colStd_样本标准差() {
        DataFrame df = df();
        // score: 90, 80;mean=85, var=((-5)^2+(5)^2)/(2-1)=50, std=√50≈7.071
        assertThat(df.colStd("score")).isCloseTo(Math.sqrt(50), within(1e-10));
    }

    @Test
    void colPercentile_对齐numpy线性插值() {
        DataFrame df = df();
        // 验证 [1..5] 的 50% = 3,25% = 2,75% = 4(numpy 'linear')
        DataFrame d5 = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        assertThat(d5.colPercentile("v", 0.5)).isEqualTo(3.0);
        assertThat(d5.colPercentile("v", 0.25)).isEqualTo(2.0);  // 补齐 jian-num 的已知 TODO,core 用线性
        assertThat(d5.colPercentile("v", 0.75)).isEqualTo(4.0);
    }

    @Test
    void 全表mean只统计数值列() {
        DataFrame df = df();
        Map<String, Double> m = df.mean();
        assertThat(m).containsOnlyKeys("id", "score");  // name/city 非数值,不出现
        assertThat(m.get("score")).isCloseTo(85.0, within(1e-10));
    }

    @Test
    void describe_数值列统计表() {
        DataFrame d = DataFrameStats.describe(df());
        // 行:count/mean/std/min/25%/50%/75%/max
        assertThat(d.rowCount()).isEqualTo(8);
        assertThat(d.columnCount()).isEqualTo(3);  // stat + id + score
        assertThat(d.getStringColumn("stat").get(0)).isEqualTo("count");
        assertThat(d.getStringColumn("stat").get(7)).isEqualTo("max");
    }

    @Test
    void applyNumeric_每个元素乘2() {
        DataFrame df = df();
        DoubleColumn doubled = df.applyNumeric("score", x -> x * 2);
        assertThat(doubled.getDouble(0)).isEqualTo(180.0);
        assertThat(Double.isNaN(doubled.getDouble(1))).isTrue();  // NaN 透传
        assertThat(doubled.getDouble(2)).isEqualTo(160.0);
    }

    @Test
    void applyStr_数值转字符串() {
        DataFrame df = df();
        StringColumn s = df.applyStr("score", v -> v == null ? "-" : String.format("%.1f", v));
        assertThat(s.get(0)).isEqualTo("90.0");
        assertThat(s.get(1)).isEqualTo("-");
    }

    @Test
    void assign_派生新列() {
        DataFrame df = df();
        DataFrame r = df.assign("double_score", row -> {
            Double v = (Double) df.get(row, "score");
            return v == null ? null : v * 2;
        });
        assertThat(r.columnNames()).contains("id", "name", "score", "city", "double_score");
        assertThat(r.getDoubleColumn("double_score").getDouble(0)).isEqualTo(180.0);
    }

    @Test
    void dropna_any_任一列缺失即丢() {
        DataFrame df = df();
        DataFrame r = df.dropna("any", new String[]{"score"});
        assertThat(r.rowCount()).isEqualTo(2);  // NaN 那行被丢
        assertThat(r.getStringColumn("name").data()).containsExactly("alice", "carol");
    }

    @Test
    void dropna_all_全缺失才丢() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{1.0, null}, {null, null}, {null, 3.0}});
        DataFrame r = df.dropna("all", null);
        assertThat(r.rowCount()).isEqualTo(2);  // 只有第 1 行(全 NaN)被丢
    }

    @Test
    void fillna_用常量填充() {
        DataFrame df = df();
        DataFrame r = df.fillna(0.0);
        assertThat(Double.isNaN(r.getDoubleColumn("score").getDouble(1))).isFalse();
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(0.0);
    }

    @Test
    void ffill前向填充() {
        DataFrame df = df();
        DataFrame r = df.ffill();
        // score: 90, NaN, 80 → 90, 90(前向), 80
        assertThat(r.getDoubleColumn("score").getDouble(0)).isEqualTo(90.0);
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(90.0);  // 填前值
        assertThat(r.getDoubleColumn("score").getDouble(2)).isEqualTo(80.0);
    }

    @Test
    void bfill后向填充() {
        DataFrame df = df();
        DataFrame r = df.bfill();
        // score: 90, NaN, 80 → 90, 80(后向), 80
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(80.0);
    }

    @Test
    void isna_返回maskDataFrame() {
        DataFrame df = df();
        DataFrame mask = df.isna();
        assertThat(mask.getColumn("score").get(0)).isEqualTo(false);
        assertThat(mask.getColumn("score").get(1)).isEqualTo(true);  // NaN
        assertThat(mask.getColumn("score").get(2)).isEqualTo(false);
    }

    @Test
    void 链式_dropna后describe() {
        DataFrame df = df();
        DataFrame d = df.dropna("any", new String[]{"score"}).describe();
        assertThat(d.rowCount()).isEqualTo(8);
        // count 应 = 2
        assertThat(d.getDoubleColumn("score").getDouble(0)).isEqualTo(2.0);
    }

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE, "city", DType.STRING),
                new Object[][]{
                        {1L, "alice", 90.0, "SH"},
                        {2L, "bob", null, "BJ"},
                        {3L, "carol", 80.0, "SZ"}
                });
    }
}
