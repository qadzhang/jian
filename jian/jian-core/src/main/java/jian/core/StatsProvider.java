package jian.core;

import java.util.ServiceLoader;

// ┌─ What : StatsProvider —— 统计计算 SPI(对齐规范 06 §1.4 / 10.2 jian-num 可选加载)
// │  Why  : core 默认内置简单统计;引 jian-num 后经 SPI 升级为基于 Commons Math 的精确实现
// │  Who  : DataFrame.corr/cov/quantile 等经 LOADER 取 provider;找不到用 BUILTIN
// │  When : 数值统计、相关、协方差
// │  Where: jian-core/StatsProvider.java
// │  How  : 数据走向:ServiceLoader<StatsProvider> 扫 META-INF/services → 找到第一个非兜底就用,否则 BUILTIN。
/**
 * 统计计算 SPI。core 内置兜底;jian-num-bridge 经 ServiceLoader 升级为 Commons Math 实现(规范 06 §1.4)。
 */
public interface StatsProvider {

    /** 皮尔逊相关系数。 */
    double pearson(double[] x, double[] y);

    /** 斯皮尔曼秩相关系数。 */
    double spearman(double[] x, double[] y);

    /** 协方差。 */
    double covariance(double[] x, double[] y);

    /** 精确分位数(numpy 'linear' R-7,对齐 pandas/numpy)。 */
    double percentile(double[] data, double q);

    /** 偏度。 */
    double skewness(double[] data);

    /** 峰度(超额)。 */
    double kurtosis(double[] data);

    /** 提供方名(用于识别)。 */
    String name();

    /** 内置兜底:core 的 DataFrameStats(简单实现)。 */
    StatsProvider BUILTIN = new SimpleStatsProvider();

    ServiceLoader<StatsProvider> LOADER = ServiceLoader.load(StatsProvider.class);

    /** 取当前可用 provider(优先 jian-num-bridge,回退内置)。 */
    static StatsProvider current() {
        for (StatsProvider p : LOADER) {
            if (!(p instanceof SimpleStatsProvider)) return p;
        }
        return BUILTIN;
    }
}
