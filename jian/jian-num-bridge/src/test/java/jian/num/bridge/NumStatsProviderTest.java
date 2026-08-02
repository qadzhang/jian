package jian.num.bridge;

import jian.core.StatsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NumStatsProviderTest {

    @Test
    void SPI加载NumStatsProvider() {
        // 引了 jian-num-bridge,current() 应返回 NumStatsProvider(非内置 SimpleStatsProvider)
        StatsProvider p = StatsProvider.current();
        assertThat(p).isInstanceOf(NumStatsProvider.class);
        assertThat(p.name()).isEqualTo("jian-num-commons-math");
    }

    @Test
    void pearson完全正相关() {
        StatsProvider p = StatsProvider.current();
        double r = p.pearson(new double[]{1, 2, 3}, new double[]{2, 4, 6});
        assertThat(r).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void pearson完全负相关() {
        StatsProvider p = StatsProvider.current();
        double r = p.pearson(new double[]{1, 2, 3}, new double[]{3, 2, 1});
        assertThat(r).isCloseTo(-1.0, within(1e-10));
    }

    @Test
    void covariance正() {
        StatsProvider p = StatsProvider.current();
        double c = p.covariance(new double[]{1, 2, 3, 4}, new double[]{2, 4, 6, 8});
        assertThat(c).isGreaterThan(0);
    }

    @Test
    void skewness正偏() {
        StatsProvider p = StatsProvider.current();
        // 右偏分布(几个小值 + 一个大值)
        double s = p.skewness(new double[]{1, 2, 2, 3, 3, 100});
        assertThat(s).isGreaterThan(0);  // 正偏
    }

    @Test
    void kurtosis超额() {
        StatsProvider p = StatsProvider.current();
        double k = p.kurtosis(new double[]{1, 2, 3, 4, 5});
        // 均匀分布超额峰度接近 -1.2(对齐 pandas/scipy)
        assertThat(k).isCloseTo(-1.3, within(0.1));
    }
}
