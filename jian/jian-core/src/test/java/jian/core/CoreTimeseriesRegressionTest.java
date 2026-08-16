package jian.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : CoreTimeseriesRegressionTest —— 时序域回归测试集:固化 Frequency / Resampler / DatetimeIndex /
// │         merge_asof 的行为(重采样网格、月末月初锚点、ohlc、空桶、rolling 窗口、asof 查找)
// │  Why  : 因为时序算子的桶边界/锚点/缺失语义都以 pandas 实测为准,偏差会静默错分桶,
// │         所以用精确断言固化这些行为防回归(有意设计差异显式注明并引用 §10.16)
// │  Who  : jian-core 测试套件(surefire)执行
// │  When : 改动 Frequency / Resampler / DatetimeIndex / DataFrameMerge.compareAsf 相关行为后必须全绿
// │  Where: jian-core/src/test/java/jian/core/CoreTimeseriesRegressionTest.java
// │  How  : 数据走向:固定时间点 + 数值 → resample/ohlc/rolling/asofIndex → 逐桶逐列断言精确值。
// │         关键变量:桶网格(左闭右开 [grid[i], grid[i+1]),桶标签取左边界)、锚点(ME=月末、MS=月初)、
// │         空桶(jian 记缺失,pandas sum/count 返 0 —— 有意差异)。
// │         逻辑路线:网格生成 → 逐点落桶 → 聚合 → 与 pandas 实测值逐项比对。
class CoreTimeseriesRegressionTest {

    private static LocalDateTime lt(int y, int m, int d) { return LocalDateTime.of(y, m, d, 0, 0); }

    // ======================== 重采样网格(12H / 1ME / 1MS)========================

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
        assertThat(r.rowCount()).isEqualTo(3);  // 精确 3 个(不用 ≥3 弱断言)
        assertThat(r.getDoubleColumn("v_sum").getDouble(0)).isEqualTo(30.0);
        assertThat(r.getDoubleColumn("v_sum").getDouble(1)).isEqualTo(70.0);
        assertThat(r.getDoubleColumn("v_sum").getDouble(2)).isEqualTo(50.0);
    }

    @Test
    void resample_1ME月末对齐() {
        // 数据 1/15 起,1ME 桶边界 floor 到 12/31:首个 bucket = [12/31, 1/31) 含 1/15
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 15, 10, 0), 10.0},
                {LocalDateTime.of(2026, 1, 20, 10, 0), 20.0},
                {LocalDateTime.of(2026, 2, 5, 10, 0), 30.0}});
        DataFrame r = df.resample("ts", "1ME").sum();
        // 首 bucket label = 2025-12-31(月末锚点);次 bucket = 2026-01-31
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.get(0, 0)).isEqualTo(LocalDateTime.of(2025, 12, 31, 0, 0));
        assertThat(r.get(1, 0)).isEqualTo(LocalDateTime.of(2026, 1, 31, 0, 0));
        // 1/15、1/20 落 [12/31, 1/31),2/5 落 [1/31, 2/28)
        assertThat(r.getDoubleColumn("v_sum").getDouble(0)).isCloseTo(30.0, within(1e-9));
        assertThat(r.getDoubleColumn("v_sum").getDouble(1)).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void resample_1MS月初对齐() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 15, 10, 0), 10.0},
                {LocalDateTime.of(2026, 2, 5, 10, 0), 20.0}});
        DataFrame r = df.resample("ts", "1MS").sum();
        // 1MS 锚点 = 每月 1 日:1/15 落 [1/1, 2/1),2/5 落 [2/1, 3/1)
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.get(0, 0)).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(r.get(1, 0)).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        assertThat(r.getDoubleColumn("v_sum").getDouble(0)).isCloseTo(10.0, within(1e-9));
        assertThat(r.getDoubleColumn("v_sum").getDouble(1)).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void resample_1ME与1MS桶边界不同() {
        // 同输入下 1ME 与 1MS 的桶 label 必须不同(锚点不同:月末 vs 月初)
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[][]{{LocalDateTime.of(2026, 1, 15, 10, 0), 10.0}});
        DataFrame me = df.resample("ts", "1ME").sum();
        DataFrame ms = df.resample("ts", "1MS").sum();
        assertThat(me.get(0, 0)).isEqualTo(LocalDateTime.of(2025, 12, 31, 0, 0));
        assertThat(ms.get(0, 0)).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    // ======================== Frequency 月末/月初加法单调性 ========================

    /** MONTH_END 加法在短月边界必须单调前进(2/28 + 1ME 不能原地踏步,否则网格循环崩溃)。 */
    @Test
    void 月末频率plus跨短月单调前进() {
        Frequency me = Frequency.parse("1ME");
        // 锚点语义:nextMonthEnd(anchor) = anchor.withDayOfMonth(1).plusMonths(2).minusDays(1)
        assertThat(me.plus(lt(2026, 2, 28))).as("2/28 + 1ME = 3/31(单调前进)")
                .isEqualTo(lt(2026, 3, 31));
        assertThat(me.plus(lt(2026, 1, 31))).as("1/31 + 1ME = 2/28").isEqualTo(lt(2026, 2, 28));
        assertThat(me.plus(lt(2026, 12, 31))).as("12/31 + 1ME = 次年 1/31").isEqualTo(lt(2027, 1, 31));
        assertThat(me.plus(lt(2026, 4, 30))).as("4/30 + 1ME = 5/31").isEqualTo(lt(2026, 5, 31));
        // MONTH_START 单调性验证:1/1 + 1MS = 2/1;月末大月→小月(1/31 + 1MS = 2/1)也前进
        Frequency ms = Frequency.parse("1MS");
        assertThat(ms.plus(lt(2026, 1, 1))).isEqualTo(lt(2026, 2, 1));
        assertThat(ms.plus(LocalDateTime.of(2026, 1, 31, 5, 0))).isEqualTo(lt(2026, 2, 1));
    }

    /** 重采样主体用例:2026-01-15 ~ 2026-04-20 按 1ME 重采样不抛"网格点数超过 100000"且桶边界正确。 */
    @Test
    void 跨短月1ME重采样不崩溃且桶边界正确() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
                new Object[][]{
                        {lt(2026, 1, 15), 1.0}, {lt(2026, 2, 10), 2.0},
                        {lt(2026, 3, 5), 3.0}, {lt(2026, 4, 20), 4.0}});
        DataFrame r = df.resample("ts", "1ME").sum();
        // jian 桶约定:左闭右开 [grid[i], grid[i+1]),桶标签取左边界;
        // 数据划分与 pandas resample("1M").sum()(右边界标签)一致:1/31:1、2/28:2、3/31:3、4/30:4
        assertThat(r.rowCount()).as("桶数 = 4(2025-12-31/1-31/2-28/3-31 起始的桶)").isEqualTo(4);
        assertThat(r.getColumn("_bucket_").toObjectArray()).containsExactly(
                lt(2025, 12, 31), lt(2026, 1, 31), lt(2026, 2, 28), lt(2026, 3, 31));
        assertThat(r.getDoubleColumn("v_sum").data()).containsExactly(1.0, 2.0, 3.0, 4.0);
    }

    /** 补充:数据跨 2/28(2 月→3 月)不丢桶。 */
    @Test
    void 二月28跨界重采样() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
                new Object[][]{{lt(2026, 2, 27), 1.0}, {lt(2026, 3, 1), 2.0}});
        DataFrame r = df.resample("ts", "1ME").sum();
        assertThat(r.rowCount()).as("2/27 与 3/1 分属两个桶(桶边界 2/28)").isEqualTo(2);
        assertThat(r.getColumn("_bucket_").toObjectArray()).containsExactly(lt(2026, 1, 31), lt(2026, 2, 28));
        assertThat(r.getDoubleColumn("v_sum").data()).containsExactly(1.0, 2.0);
    }

    // ======================== ohlc / 空桶语义 ========================

    /** 桶首行为缺失时 open/high/low 取首个有效值(pandas 实测 [null,5] → 5/5/5/5,不被 NaN 毒化)。 */
    @Test
    void ohlc桶首缺失跳过后初始化() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.DATETIME, "p", DType.DOUBLE),
                new Object[][]{
                        {LocalDateTime.of(2026, 1, 1, 10, 0), null},   // 桶首缺失
                        {LocalDateTime.of(2026, 1, 1, 11, 0), 5.0},
                        {LocalDateTime.of(2026, 1, 2, 10, 0), 7.0},
                        {LocalDateTime.of(2026, 1, 3, 10, 0), null}}); // 全缺失桶
        DataFrame o = df.resample("ts", "1D").ohlc("p");
        assertThat(o.rowCount()).isEqualTo(3);
        double[] open = o.getDoubleColumn("p_open").data();
        double[] high = o.getDoubleColumn("p_high").data();
        double[] low = o.getDoubleColumn("p_low").data();
        double[] close = o.getDoubleColumn("p_close").data();
        // 桶 0:[null,5.0] → 5/5/5/5(缺失跳过后以首个有效值初始化)
        assertThat(open[0]).isEqualTo(5.0);
        assertThat(high[0]).isEqualTo(5.0);
        assertThat(low[0]).isEqualTo(5.0);
        assertThat(close[0]).isEqualTo(5.0);
        // 桶 1:[7.0] → 7/7/7/7
        assertThat(open[1]).isEqualTo(7.0);
        // 桶 2:全缺失 → 四值全缺失
        assertThat(o.getColumn("p_open").isNull(2)).isTrue();
        assertThat(o.getColumn("p_high").isNull(2)).isTrue();
        assertThat(o.getColumn("p_low").isNull(2)).isTrue();
        assertThat(o.getColumn("p_close").isNull(2)).isTrue();
    }

    /** 空桶 sum/count/mean 返回 null(有意设计差异:jian 无观测记缺失,pandas sum/count 返 0)。 */
    @Test
    void 空桶三聚合均为缺失() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
                new Object[][]{
                        {lt(2026, 1, 1), 1.0}, {lt(2026, 1, 2), 2.0}, {lt(2026, 1, 4), 4.0}});
        // 1/3 无数据 → 空桶
        DataFrame s = df.resample("ts", "1D").sum();
        DataFrame c = df.resample("ts", "1D").count();
        DataFrame m = df.resample("ts", "1D").mean();
        assertThat(s.rowCount()).isEqualTo(4);
        // 有观测桶与 pandas 一致
        assertThat(s.getDoubleColumn("v_sum").data()).containsExactly(1.0, 2.0, Double.NaN, 4.0);
        assertThat(c.getDoubleColumn("v_count").data()).containsExactly(1.0, 1.0, Double.NaN, 1.0);
        assertThat(m.getDoubleColumn("v_mean").data()).containsExactly(1.0, 2.0, Double.NaN, 4.0);
        // 空桶(1/3)显式锁定为缺失(isNull)
        assertThat(s.getColumn("v_sum").isNull(2)).as("空桶 sum=null(有意差异,pandas 返 0;§10.16 已声明)")
                .isTrue();
        assertThat(c.getColumn("v_count").isNull(2)).as("空桶 count=null(有意差异,pandas 返 0)").isTrue();
        assertThat(m.getColumn("v_mean").isNull(2)).as("空桶 mean=null(与 pandas NaN 一致)").isTrue();
    }

    // ======================== rolling / asof ========================

    /** rolling(3).count() 前 2 个为 NaN(窗口不足 minPeriods 记缺失,对齐 pandas min_periods 语义)。 */
    @Test
    void rollingCount前窗口不足为NaN() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        double[] c = df.getSeries("v").rolling(3).count();
        assertThat(c).containsExactly(Double.NaN, Double.NaN, 3.0, 3.0);

        // 窗口内有 NaN:有效值数 < minPeriods(=window 默认)也 NaN(对齐 pandas min_periods 语义)
        DataFrame df2 = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}, {3.0}, {4.0}});
        double[] c2 = df2.getSeries("v").rolling(3).count();
        assertThat(c2).containsExactly(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /** 乱序输入全扫描取 ≤label 的最大下标(与 DataFrame.asof 的全扫描语义一致)。 */
    @Test
    void asofIndex乱序输入全扫描() {
        // 乱序:3/1 在前、1/1 在后(freq=null 工厂不做升序校验)
        DatetimeIndex di = DatetimeIndex.of(new LocalDateTime[]{
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0)});
        // label=2/1:必须找到 1/1(≤ 2/1);不能因先遇 3/1 > 2/1 就提前返回 empty
        java.util.OptionalInt idx = di.asofIndex(LocalDateTime.of(2026, 2, 1, 10, 0));
        assertThat(idx).isEqualTo(java.util.OptionalInt.of(1));
        // 全部晚于 label → empty
        assertThat(di.asofIndex(LocalDateTime.of(2025, 12, 31, 0, 0))).isEmpty();
        // 升序输入行为不变
        DatetimeIndex sorted = DatetimeIndex.of(new LocalDateTime[]{
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDateTime.of(2026, 3, 1, 0, 0)});
        assertThat(sorted.asofIndex(LocalDateTime.of(2026, 2, 15, 0, 0))).isEqualTo(java.util.OptionalInt.of(1));
    }

    // ======================== merge_asof 边界 ========================

    @Test
    void mergeAsof_时间列含null_显式抛IAE对齐pandas() {
        // 语义演进:旧契约"null 右行静默跳过"→ 新契约"显式抛 IAE"。
        // 依据:本机 pandas 1.5.3 实测 merge_asof 对左右任一侧键含 null 抛
        // ValueError("Merge keys contain null values on ...");jian 对齐 fail-fast
        //(连带修复旧过滤 get()!=null 导致 DOUBLE 列 NaN 右键漏网)
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.LONG, "lv", DType.STRING),
            new Object[][]{{10L, "a"}, {20L, "b"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.LONG, "rv", DType.STRING),
            new Object[][]{
                {5L, "x"},
                {null, "dirty"},   // null 时间点:不再静默跳过,显式报错
                {15L, "y"}});
        assertThatThrownBy(() -> left.mergeAsof(right, "ts"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("right");
        // 清洗后(dropna)正常 backward 匹配
        DataFrame rightClean = right.dropna();
        DataFrame r = left.mergeAsof(rightClean, "ts");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.get(0, "rv")).isEqualTo("x");   // ts=10 → ≤10 的最后 = 5
        assertThat(r.get(1, "rv")).isEqualTo("y");   // ts=20 → ≤20 的最后 = 15
    }
}
