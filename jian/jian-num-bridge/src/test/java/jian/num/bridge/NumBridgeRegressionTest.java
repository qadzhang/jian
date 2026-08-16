package jian.num.bridge;

import jian.core.StatsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : NumBridgeRegressionTest —— jian-num-bridge 回归测试集:固化 StatsProvider SPI 实现的
// │         percentile 行为(R-7 分位、NaN 跳过、空输入、与 core 兜底逐点一致)
// │  Why  : 因为 bridge 的 percentile 必须满足 StatsProvider"R-7 对齐 pandas/numpy"契约,
// │         否则装上"升级" jar 反而让 df.quantile 偏离 pandas(行为随 classpath 翻转),
// │         所以用固定输入对照 pandas 已知值 + 与 core 内置 SimpleStatsProvider(同为 R-7)
// │         逐点一致性强断言固化
// │  Who  : jian-num-bridge 测试套件(surefire)执行
// │  When : 改动 NumStatsProvider.percentile 相关行为后必须全绿
// │  Where: jian-num-bridge/src/test/java/jian/num/bridge/NumBridgeRegressionTest.java
// │  How  : 数据走向:固定 double[](含 NaN/空/全 NaN 边界)→ StatsProvider.current().percentile
// │         → 对照 pandas/numpy 已知值断言;再与 core 兜底 SimpleStatsProvider 逐 q 一致。
// │         关键变量:pos = q*(n-1)(R-7 linear 插值)、NaN(缺失跳过)、n(有效值数)。
// │         逻辑路线:有数据 → R-7 插值;n=0(空/全 NaN)→ NaN 不抛;两实现 → 逐点相等。
class NumBridgeRegressionTest {

    private final StatsProvider p = StatsProvider.current();

    @Test
    void percentile_R7对齐pandas_numpy() {
        // [1..5] q=0.25:numpy/pandas = 2.0(R-6 会得 1.5)
        assertThat(p.percentile(new double[]{1, 2, 3, 4, 5}, 0.25)).isCloseTo(2.0, within(1e-12));
        // q=0.5 → 3.0(中位数)
        assertThat(p.percentile(new double[]{1, 2, 3, 4, 5}, 0.5)).isCloseTo(3.0, within(1e-12));
        // 插值位:q=0.75 → pos=3.0 → 4.0
        assertThat(p.percentile(new double[]{1, 2, 3, 4, 5}, 0.75)).isCloseTo(4.0, within(1e-12));
    }

    @Test
    void percentile_边界q0与q1() {
        assertThat(p.percentile(new double[]{1, 2, 3, 4, 5}, 0.0)).isCloseTo(1.0, within(1e-12));
        assertThat(p.percentile(new double[]{1, 2, 3, 4, 5}, 1.0)).isCloseTo(5.0, within(1e-12));
    }

    @Test
    void percentile_含NaN跳过() {
        // NaN 视为缺失跳过:有效值 [1,2,3,4,5] 的 q=0.25 仍 2.0(对齐 core 兜底语义)
        assertThat(p.percentile(new double[]{1, Double.NaN, 2, 3, Double.NaN, 4, 5}, 0.25))
                .isCloseTo(2.0, within(1e-12));
    }

    @Test
    void percentile_空数组与全NaN返NaN不抛() {
        // 空/全 NaN 与 core 兜底同口径返 NaN(不抛,保证行为不随 bridge 是否在场而不同)
        assertThat(p.percentile(new double[]{}, 0.5)).isNaN();
        assertThat(p.percentile(new double[]{Double.NaN, Double.NaN}, 0.5)).isNaN();
    }

    @Test
    void percentile_与core兜底SimpleStatsProvider逐点一致() {
        // 装上 bridge 后 df.quantile 不得随 classpath 翻转:与内置 R-7 实现全点一致
        jian.core.StatsProvider builtin = new jian.core.SimpleStatsProvider();
        double[] data = {3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5};
        for (double q = 0.0; q <= 1.0; q += 0.05) {
            assertThat(p.percentile(data, q))
                    .as("q=%s", q)
                    .isCloseTo(builtin.percentile(data, q), within(1e-12));
        }
    }
}
