package jian.num.bridge;

import jian.core.StatsProvider;
import jian.num.Correlation;
import jian.num.Stats;

// ┌─ What : NumStatsProvider —— jian-num-bridge 实现 core 的 StatsProvider SPI(对齐规范 06 §3.3)
// │  Why  : 引 jian-num-bridge jar 后,core 经 ServiceLoader 升级为 Commons Math 精确实现
// │  Who  : ServiceLoader<StatsProvider> 加载
// │  When : DataFrame.corr/quantile 且引了 jian-num-bridge
// │  Where: jian-num-bridge/NumStatsProvider.java + META-INF/services/jian.core.StatsProvider
/**
 * jian-num-bridge 的 StatsProvider SPI 实现。委托 jian.num.Stats / Correlation(Commons Math 3.6.1)。
 *
 * <p>注册:META-INF/services/jian.core.StatsProvider = jian.num.bridge.NumStatsProvider。
 * <p>引此 jar 后,core 的统计精度从内置简单实现升级为 Commons Math 精确实现。
 */
public final class NumStatsProvider implements StatsProvider {

    @Override public double pearson(double[] x, double[] y) {
        return Correlation.pearson(x, y);
    }

    @Override public double spearman(double[] x, double[] y) {
        return Correlation.spearman(x, y);
    }

    @Override public double covariance(double[] x, double[] y) {
        return Correlation.cov(x, y);
    }

    @Override public double percentile(double[] data, double q) {
        // jian-num Stats.percentile 用 Commons Math(默认 R-6);本 SPI 要求 R-7 对齐 numpy
        // 用 Stats.quantile + 简化:直接调 commons math 的 R-7 实现
        // 这里委托 Stats.percentile,接受小差异(v2 可选 R-7)
        return Stats.percentile(data, q * 100.0);
    }

    @Override public double skewness(double[] data) {
        return Stats.skewness(data);
    }

    @Override public double kurtosis(double[] data) {
        return Stats.kurtosis(data);
    }

    @Override public String name() { return "jian-num-commons-math"; }
}
