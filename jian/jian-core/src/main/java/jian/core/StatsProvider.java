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

    /**
     * 皮尔逊相关系数。
     * @param x double[] 第一样本数组,非 null;长度 ≥ 2(否则结果为 NaN)
     * @param y double[] 第二样本数组,非 null;**长度必须 == x.length**
     * @return double 相关系数 ∈ [-1.0, 1.0];含 NaN 或长度不匹配返回 NaN
     */
    double pearson(double[] x, double[] y);

    /**
     * 斯皮尔曼秩相关系数。
     * @param x double[] 第一样本,非 null;长度 ≥ 2
     * @param y double[] 第二样本,非 null;长度必须 == x.length
     * @return double 秩相关系数 ∈ [-1.0, 1.0]
     */
    double spearman(double[] x, double[] y);

    /**
     * 协方差(无偏样本协方差,除以 n-1)。
     * @param x double[] 第一样本,非 null;长度 ≥ 2
     * @param y double[] 第二样本,非 null;长度必须 == x.length
     * @return double 协方差(可正可负可为 0)
     */
    double covariance(double[] x, double[] y);

    /**
     * 精确分位数(numpy 'linear' R-7,对齐 pandas/numpy)。
     * @param data double[] 数据数组,非 null;长度 ≥ 1
     * @param q    double 分位点,范围 [0.0, 1.0];越界抛异常
     * @return double 分位数值
     */
    double percentile(double[] data, double q);

    /**
     * 偏度(sample skewness)。
     * @param data double[] 数据数组,非 null;长度 ≥ 3(否则返回 NaN)
     * @return double 偏度;正=右偏,负=左偏,0=对称
     */
    double skewness(double[] data);

    /**
     * 峰度(超额峰度,excess kurtosis,正态分布为 0)。
     * @param data double[] 数据数组,非 null;长度 ≥ 4
     * @return double 超额峰度;正=比正态尖,负=比正态平
     */
    double kurtosis(double[] data);

    // ===== 2026-08-09 阶段 B 扩展(默认实现兜底,避免破坏现有实现类)=====

    /**
     * 秩(对齐 pandas Series.rank,默认 method="average" 平均秩,NaN 排最后不计入)。
     * @param data   double[] 数据数组,非 null
     * @param method String "average"(同秩取平均,默认)/ "min"(同秩取最小)/ "max"/ "first"(出现顺序)/ "dense"
     * @return double[] 同长度秩数组;NaN 位置保留 NaN
     */
    default double[] rank(double[] data, String method) {
        // SPI 默认实现:average 秩;子类可覆盖以提供精确实现
        return SimpleStatsProvider.rankDefault(data, method);
    }

    /**
     * 平均绝对偏差(对齐 pandas Series.mad,均值绝对偏差)。
     * @param data double[] 数据数组,非 null;长度 ≥ 1
     * @return double 平均绝对偏差;skip NaN
     */
    default double mad(double[] data) {
        // SPI 默认实现
        return SimpleStatsProvider.madDefault(data);
    }

    /**
     * 标准误差(样本均值的标准误,= std / sqrt(n),ddof=1)。
     * @param data double[] 数据数组,非 null;长度 ≥ 2
     * @return double 标准误;skip NaN
     */
    default double sem(double[] data) {
        return SimpleStatsProvider.semDefault(data);
    }

    /**
     * 提供方名(用于识别/调试)。
     * @return String 名,如 "SimpleStatsProvider"/"CommonsMathStatsProvider";非 null
     */
    String name();

    /** 内置兜底:core 的 DataFrameStats(简单实现)。 */
    StatsProvider BUILTIN = new SimpleStatsProvider();

    /**
     * 取当前可用 provider(优先 jian-num-bridge,回退内置)。
     *
     * <p><b>Web 安全修复(2026-08-08)</b>:不再用 static ServiceLoader 缓存(同 DslEngine,避免 Tomcat redeploy 内存泄漏)。
     *
     * @return StatsProvider 实例:优先返回第一个非 SimpleStatsProvider(jian-num-bridge 引入时);
     *         否则返回 BUILTIN
     */
    static StatsProvider current() {
        ServiceLoader<StatsProvider> loader = ServiceLoader.load(StatsProvider.class);
        for (StatsProvider p : loader) {
            if (!(p instanceof SimpleStatsProvider)) return p;
        }
        return BUILTIN;
    }
}
