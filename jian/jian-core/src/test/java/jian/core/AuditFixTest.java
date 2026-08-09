package jian.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AI 测试方法学指南 审查发现的 6 个严重问题修复测试。
 * 对应测试方法学指南铁律:1(关系/不变量) / 3(重现代码) / 4(严格断言) / 5(混合 dtype 边界)。
 */
class AuditFixTest {

    // ===== 严重 1: covMatrix / combineFirst 零测试 → 补 =====

    @Test
    void covMatrix_两列协方差() {
        DataFrame df = DataFrame.of(
            Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
            new Object[][]{{1.0, 2.0}, {2.0, 4.0}, {3.0, 6.0}, {4.0, 8.0}});
        DataFrame m = df.covMatrix();
        assertThat(m.rowCount()).isEqualTo(2);  // 2 个数值列
        assertThat(m.columnCount()).isEqualTo(3);  // _index_ + x + y
        // 自协方差(x,x)= var(x) = 1.667(ddof=1)
        assertThat((double) m.get(0, "x")).isCloseTo(1.6667, within(1e-3));
    }

    @Test
    void combineFirst_用other填空() {
        DataFrame self = DataFrame.of(
            Schema.of("v", DType.OBJECT),
            new Object[][]{{1}, {null}, {3}});
        DataFrame other = DataFrame.of(
            Schema.of("v", DType.OBJECT),
            new Object[][]{{10}, {20}, {30}});
        DataFrame r = self.combineFirst(other);
        // self 第 2 行缺失 → 用 other 的 20 填
        assertThat(r.get(0, "v")).isEqualTo(1);   // 非缺失保留
        assertThat(r.get(1, "v")).isEqualTo(20);  // 缺失用 other
        assertThat(r.get(2, "v")).isEqualTo(3);   // 非缺失保留
    }

    // ===== 严重 2: resample_12H 行数弱断言 → 钉死 + 补全 =====

    @Test
    void resample_12H_行数精确() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[][]{
                {java.time.LocalDateTime.of(2026, 1, 1, 0, 0), 10.0},
                {java.time.LocalDateTime.of(2026, 1, 1, 6, 0), 20.0},
                {java.time.LocalDateTime.of(2026, 1, 1, 12, 0), 30.0},
                {java.time.LocalDateTime.of(2026, 1, 1, 18, 0), 40.0},
                {java.time.LocalDateTime.of(2026, 1, 2, 0, 0), 50.0}});
        DataFrame r = df.resample("ts", "12H").sum();
        // 网格:00:00, 12:00, 24:00(次日0点), 36:00(endpoint)
        // bucket 0 [00,12):10+20=30
        // bucket 1 [12,24):30+40=70
        // bucket 2 [24,36):50
        assertThat(r.rowCount()).isEqualTo(3);  // 精确 3 个(不再是 ≥3)
        assertThat(r.getDoubleColumn("v_sum").getDouble(0)).isEqualTo(30.0);
        assertThat(r.getDoubleColumn("v_sum").getDouble(1)).isEqualTo(70.0);
        assertThat(r.getDoubleColumn("v_sum").getDouble(2)).isEqualTo(50.0);
    }

    // ===== 严重 3: colKurt 弱断言 → 先 isNotNaN 再 isCloseTo =====

    @Test
    void colKurt_严格断言_先非NaN再近值() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        double k = df.colKurt("v");
        assertThat(k).isNotNaN();  // 先钉死非 NaN(原来用 || 放过 NaN)
        assertThat(k).isCloseTo(-1.36, within(0.1));  // 等距数列超额峰度
    }

    // ===== 严重 4: merge_asof 时间列含 null =====

    @Test
    void mergeAsof_时间列含null_不NPE不崩溃() {
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.LONG, "lv", DType.STRING),
            new Object[][]{{10L, "a"}, {20L, "b"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.LONG, "rv", DType.STRING),
            new Object[][]{
                {5L, "x"},
                {null, "should_skip"},  // null 时间点应被跳过
                {15L, "y"}});
        DataFrame r = left.mergeAsof(right, "ts");
        assertThat(r.rowCount()).isEqualTo(2);  // left 行数
        // ts=10 → ≤10 的最后 right = 5(rv=x);null right 被跳过
        assertThat(r.get(0, "rv")).isEqualTo("x");
        // ts=20 → ≤20 的最后 = 15(rv=y)
        assertThat(r.get(1, "rv")).isEqualTo("y");
    }

    // ===== 严重 5: DataFrameSort 稳定性(同键保原序)=====

    @Test
    void sortBy_同键保原序_稳定排序验证() {
        // 构造:v 全是 5(同键),id = [3, 1, 2](原序)
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{
                {3L, 5.0},
                {1L, 5.0},
                {2L, 5.0}});
        DataFrame r = df.sortBy("v", true);  // 升序(同键)
        // 稳定排序:id 列应保持原序 [3, 1, 2]
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(3L);
        assertThat(r.getLongColumn("id").getLong(1)).isEqualTo(1L);
        assertThat(r.getLongColumn("id").getLong(2)).isEqualTo(2L);
    }

    @Test
    void sortBy_同键保原序_降序也稳定() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{
                {3L, 5.0},
                {1L, 5.0},
                {2L, 5.0}});
        DataFrame r = df.sortBy("v", false);  // 降序(同键)
        // 稳定排序:同键降序时也应保原序 [3, 1, 2]
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(3L);
        assertThat(r.getLongColumn("id").getLong(1)).isEqualTo(1L);
        assertThat(r.getLongColumn("id").getLong(2)).isEqualTo(2L);
    }

    // ===== 严重 6: 跨类型 isin 边界(钉死已知差异)=====

    @Test
    void isin_跨类型_Long列_Double值_数值比较生效() {
        // L 列:Long,值列表含 Double —— jian 应走数值比较(doubleValue()==)
        DataFrame df = DataFrame.of(Schema.of("k", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        boolean[] mask = df.isin(1.0, 2.0);  // Double 值
        // jian 修复后走 doubleValue 比较:Long 1 == Double 1.0 → true
        assertThat(mask[0]).isTrue();   // 1L == 1.0
        assertThat(mask[1]).isTrue();   // 2L == 2.0
        assertThat(mask[2]).isFalse();  // 3L ≠ 1.0/2.0
    }

    @Test
    void isin_跨类型_Double列_Long值_数值比较生效() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}});
        boolean[] mask = df.isin(1L, 2L);  // Long 值
        assertThat(mask[0]).isTrue();
        assertThat(mask[1]).isTrue();
        assertThat(mask[2]).isFalse();
    }

    @Test
    void isin_零值边界_正零与负零等价() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.DOUBLE),
            new Object[][]{{0.0}, {-0.0}});
        boolean[] mask = df.isin(0.0);  // +0.0 vs -0.0
        assertThat(mask[0]).isTrue();
        assertThat(mask[1]).isTrue();  // -0.0 == +0.0(数值比较)
    }

    // ===== 补:cummax/cummin/cumprod 缺 pandas 对照(JUnit 内用硬编码 oracle)=====

    @Test
    void colCummax_精确值验证() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {5.0}, {2.0}, {Double.NaN}, {1.0}});
        DoubleColumn r = df.colCummax("v", "cm");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(3.0);  // max(3,1)=3
        assertThat(r.getDouble(2)).isEqualTo(5.0);  // max(3,5)=5
        assertThat(r.getDouble(3)).isEqualTo(5.0);  // max(5,2)=5
        assertThat(Double.isNaN(r.getDouble(4))).isTrue();  // NaN 保持
        assertThat(r.getDouble(5)).isEqualTo(5.0);  // NaN 后继续 max(last_valid=5, 1)=5
    }

    @Test
    void colCummin_精确值验证() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {5.0}});
        DoubleColumn r = df.colCummin("v", "cm");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(1.0);
        assertThat(r.getDouble(2)).isEqualTo(1.0);  // min(1,5)=1
    }

    @Test
    void colCumprod_精确值验证() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        DoubleColumn r = df.colCumprod("v", "cp");
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDouble(1)).isEqualTo(2.0);
        assertThat(r.getDouble(2)).isEqualTo(6.0);
        assertThat(r.getDouble(3)).isEqualTo(24.0);
    }

    // ===== 补:GroupBy fast path/generic path 多聚合差分(不只 sum)=====

    @Test
    void groupBy_fastPath与genericPath_多聚合等价() {
        // LONG key(走 fast path)
        DataFrame longDf = DataFrame.of(
            Schema.of("k", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{{1L, 10.0}, {1L, 20.0}, {2L, 30.0}, {2L, 40.0}});
        // STRING key(走 generic path)
        DataFrame strDf = DataFrame.of(
            Schema.of("k", DType.STRING, "v", DType.DOUBLE),
            new Object[][]{{"1", 10.0}, {"1", 20.0}, {"2", 30.0}, {"2", 40.0}});

        // count
        double longCount = longDf.groupBy("k").agg("v", "count").getDoubleColumn("v_count").getDouble(0);
        double strCount = strDf.groupBy("k").agg("v", "count").getDoubleColumn("v_count").getDouble(0);
        assertThat(longCount).isEqualTo(strCount);  // 两路径 count 等价

        // mean
        double longMean = longDf.groupBy("k").agg("v", "mean").getDoubleColumn("v_mean").getDouble(0);
        double strMean = strDf.groupBy("k").agg("v", "mean").getDoubleColumn("v_mean").getDouble(0);
        assertThat(longMean).isCloseTo(strMean, within(1e-9));

        // min
        double longMin = longDf.groupBy("k").agg("v", "min").getDoubleColumn("v_min").getDouble(0);
        double strMin = strDf.groupBy("k").agg("v", "min").getDoubleColumn("v_min").getDouble(0);
        assertThat(longMin).isEqualTo(strMin);

        // max
        double longMax = longDf.groupBy("k").agg("v", "max").getDoubleColumn("v_max").getDouble(0);
        double strMax = strDf.groupBy("k").agg("v", "max").getDoubleColumn("v_max").getDouble(0);
        assertThat(longMax).isEqualTo(strMax);
    }

    // ===== 补:astype STRING→LONG 解析失败抛 IAE =====

    @Test
    void astype_STRING_to_LONG_非法字符串抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"abc"}});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> df.astype("v", DType.LONG))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void astype_STRING_to_LONG_部分非法抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"123"}, {"abc"}});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> df.astype("v", DType.LONG))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
