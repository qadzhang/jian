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

    /**
     * @param x Object 值
     * @param y 参数;非 null
     */
    @Override public double pearson(double[] x, double[] y) {
        return Correlation.pearson(x, y);
    }

    /**
     * @param x Object 值
     * @param y 参数;非 null
     */
    @Override public double spearman(double[] x, double[] y) {
        return Correlation.spearman(x, y);
    }

    /**
     * @param x Object 值
     * @param y 参数;非 null
     */
    @Override public double covariance(double[] x, double[] y) {
        return Correlation.cov(x, y);
    }

    /**
     * 精确分位数(R-7 linear,对齐 SPI 契约 {@link StatsProvider#percentile} 与 pandas/numpy)。
     *
     * <p>因为委托 jian.num.Stats.percentile 会走 Commons Math 默认的 <b>R-6</b>
     * ([1..5] q=0.25 得 1.5 而非 pandas 的 2.0,装上"升级" jar 反而让 df.quantile
     * 偏离 pandas,行为随 classpath 翻转),所以这里直接实现 R-7(numpy 'linear'):
     * 排序后 pos=q*(n-1),lower=floor(pos),frac=pos-lower,
     * 值 = arr[lower] + frac * (arr[lower+1] - arr[lower])(边界 clamp)。
     *
     * <p>缺失语义:跳过 NaN;空数组/全 NaN 返 NaN(对齐 core 兜底 SimpleStatsProvider,不抛,
     * 保证全缺失列的 quantile 行为不随 bridge 是否在场而不同)。
     *
     * @param data double[] 数据数组,非 null;NaN 视为缺失跳过
     * @param q double 分位点 [0.0, 1.0]
     * @return double R-7 分位数值;无有效数据时 NaN
     */
    @Override public double percentile(double[] data, double q) {
        // 伪代码:
        //   1. 复制并过滤 NaN → vals(n 个有效值)
        //   2. n == 0 → NaN(对齐 core 兜底,不抛)
        //   3. 排序;pos = q*(n-1);lower = floor(pos);upper = min(lower+1, n-1)(clamp)
        //   4. 返回 vals[lower] + frac * (vals[upper] - vals[lower]),frac = pos - lower
        double[] vals = new double[data.length];
        int n = 0;
        for (double v : data) {
            if (!Double.isNaN(v)) vals[n++] = v;
        }
        if (n == 0) return Double.NaN;
        java.util.Arrays.sort(vals, 0, n);
        double pos = q * (n - 1);                 // R-7 linear(对齐 numpy/pandas)
        int lower = (int) Math.floor(pos);
        int upper = Math.min(lower + 1, n - 1);   // 边界 clamp:q=1 时 pos=n-1,upper 不越界
        double frac = pos - lower;
        return vals[lower] + frac * (vals[upper] - vals[lower]);
    }

    /**
     * @param data double[] 数据;非 null
     */
    @Override public double skewness(double[] data) {
        return Stats.skewness(data);
    }

    /**
     * @param data double[] 数据;非 null
     */
    @Override public double kurtosis(double[] data) {
        return Stats.kurtosis(data);
    }

    @Override public String name() { return "jian-num-commons-math"; }
}
