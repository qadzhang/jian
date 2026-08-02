package jian.num;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;

import java.util.Arrays;

// ┌─ What : Stats —— 描述统计封装(对齐 numpy 的 mean/std/var/median/percentile/quantile/describe)
// │  Why  : 规范 06 §1.3 要求复用 Commons Math 3.6.1 的最成熟部分,自写薄封装提供 numpy 风格 API
// │  Who  : 用户直接调静态方法;被 Ndarray 的统计便捷方法、jian-core 的 StatsProvider SPI 复用
// │  When : 任何描述统计场景
// │  Where: jian-num/Stats.java
// │  How  : 数据走向:double[] 入参 →(按 NaNPolicy 过滤)→ 干净 double[]
// │         → Commons Math DescriptiveStatistics / StatUtils / Percentile → 结果。
// │         关键变量变化:
// │           - clean:输入 data 经 filterNaN 后的非 NaN 值数组(长度 ≤ data.length);
// │           - DescriptiveStatistics 内部维护滚动窗口,addValue 后可取 mean/std/percentile 等。
// │         逻辑路线(三条路径):
// │           路径 A(空数组)→ 抛 IllegalArgumentException("数据为空");
// │           路径 B(NaNPolicy.ERROR 且含 NaN)→ 抛 IllegalArgumentException 带 NaN 个数;
// │           路径 C(正常)→ 调 Commons Math 计算,返回 double / Summary。
// │         安全:统一用 java.lang.Math(规范 06 §3.2,规避 MATH-1457 FastMath.exp 越界);
// │         不暴露 K-S 检验(规范 06 §1.2,规避 MATH-1502)。
/**
 * 描述统计封装,对齐 numpy 的核心统计函数。
 *
 * <p>所有方法默认 {@link NaNPolicy#SKIP}(跳过 NaN,对齐 np.nanmean / nansum)。
 * <p><b>不使用 FastMath</b>:本类自身不调 Commons Math 的 FastMath;调 Commons Math 统计 API 时,
 * 统计场景的输入范围有限,不会触发 MATH-1457(详见规范 06 §3.2)。
 */
public final class Stats {

    private Stats() {}

    // ======================== 基础统计 ========================

    /** 均值(默认跳过 NaN,对齐 np.nanmean)。 */
    public static double mean(double[] data) {
        return mean(data, NaNPolicy.DEFAULT);
    }

    public static double mean(double[] data, NaNPolicy policy) {
        double[] clean = filterNaN(data, policy);
        requireNonEmpty(clean);
        double sum = 0.0;
        for (double v : clean) sum += v;
        return sum / clean.length;
    }

    /** 求和(默认跳过 NaN,对齐 np.nansum)。 */
    public static double sum(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        // Kahan 补偿求和(提高大数组/大小悬殊时的精度,opencode #9)
        double sum = 0.0, c = 0.0;
        for (double v : clean) {
            double y = v - c;
            double t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
        return sum;
    }

    /** 最小值(默认跳过 NaN)。 */
    public static double min(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        double m = clean[0];
        for (double v : clean) if (v < m) m = v;
        return m;
    }

    /** 最大值(默认跳过 NaN)。 */
    public static double max(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        double m = clean[0];
        for (double v : clean) if (v > m) m = v;
        return m;
    }

    /** 计数(非 NaN 个数,对齐 pandas count)。 */
    public static long count(double[] data) {
        long c = 0;
        for (double v : data) if (!Double.isNaN(v)) c++;
        return c;
    }

    // ======================== 离散度 ========================

    /**
     * 样本标准差(ddof=1,默认,对齐 pandas/numpy 默认 std)。
     *
     * @see #std(double[], int) 可指定自由度修正
     */
    public static double std(double[] data) {
        return std(data, 1);
    }

    /**
     * 标准差,可指定自由度修正 ddof。
     * <ul>
     *   <li>ddof=0:总体标准差(对齐 np.std 默认 / np.nanstd)</li>
     *   <li>ddof=1:样本标准差(对齐 pandas Series.std 默认)</li>
     * </ul>
     */
    public static double std(double[] data, int ddof) {
        return java.lang.Math.sqrt(var(data, ddof));
    }

    /**
     * 方差,可指定自由度修正 ddof(对齐 np.var / pandas var)。
     */
    public static double var(double[] data, int ddof) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        int n = clean.length;
        if (n - ddof <= 0) {
            throw new IllegalArgumentException(
                    "样本数 " + n + " 不足以计算 ddof=" + ddof + " 的方差(需 n > ddof)");
        }
        double m = 0.0;
        for (double v : clean) m += v;
        m /= n;
        double s = 0.0;
        for (double v : clean) {
            double d = v - m;
            s += d * d;
        }
        return s / (n - ddof);
    }

    // ======================== 分位数 ========================

    /**
     * 分位数(百分制,对齐 np.percentile)。
     *
     * @param q 百分位 [0, 100],如 25 表示 Q1
     */
    public static double percentile(double[] data, double q) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        // Commons Math Percentile 默认插值方法与 numpy 'linear' 一致
        return new Percentile().evaluate(clean, q);
    }

    /**
     * 分位数(小数制,对齐 np.quantile)。
     *
     * @param q [0, 1],如 0.95 表示 95 分位
     */
    public static double quantile(double[] data, double q) {
        if (q < 0 || q > 1) throw new IllegalArgumentException("q 必须在 [0,1],实际=" + q);
        return percentile(data, q * 100.0);
    }

    /** 中位数(等价 percentile(data, 50))。 */
    public static double median(double[] data) {
        return percentile(data, 50.0);
    }

    // ======================== 形状统计 ========================

    /**
     * 偏度(Skewness,对齐 pandas Series.skew / scipy.stats.skew)。
     * <p>基于 Commons Math {@link DescriptiveStatistics#getSkewness}。
     */
    public static double skewness(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (double v : clean) ds.addValue(v);
        return ds.getSkewness();
    }

    /**
     * 峰度(Kurtosis,对齐 pandas Series.kurt)。
     * <p>基于 Commons Math {@link DescriptiveStatistics#getKurtosis}(返回超额峰度,与 pandas 一致)。
     */
    public static double kurtosis(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (double v : clean) ds.addValue(v);
        return ds.getKurtosis();
    }

    // ======================== 一次返回全部(describe)========================

    /**
     * 描述统计摘要(对齐 pandas Series.describe())。
     * <p>返回 count / mean / std / min / Q1 / median / Q3 / max,std 用 ddof=1。
     */
    public static Summary describe(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (double v : clean) ds.addValue(v);
        return new Summary(
                clean.length,
                ds.getMean(),
                ds.getStandardDeviation(),
                ds.getMin(),
                new Percentile().evaluate(clean, 25.0),
                new Percentile().evaluate(clean, 50.0),
                new Percentile().evaluate(clean, 75.0),
                ds.getMax()
        );
    }

    // ======================== 内部:NaN 过滤 ========================

    /**
     * 按策略处理 NaN:SKIP 返回去 NaN 后的数组;ERROR 遇 NaN 抛异常;PROPAGATE 原样返回。
     *
     * <p>数据走向:data(原始)→ 计 NaN 个数 →(SKIP)紧凑拷贝去 NaN / (ERROR)抛异常 / (PROPAGATE)原样 → 干净数组。
     */
    private static double[] filterNaN(double[] data, NaNPolicy policy) {
        if (data == null) throw new IllegalArgumentException("data 不能为 null");
        if (policy == NaNPolicy.PROPAGATE) return data;
        // 先数 NaN 个数,决定是否需要紧凑拷贝
        int nanCount = 0;
        for (double v : data) if (Double.isNaN(v)) nanCount++;
        if (nanCount == 0) return data;  // 无 NaN,直接返回原数组(只读场景安全)
        if (policy == NaNPolicy.ERROR) {
            throw new IllegalArgumentException(
                    "数据含 " + nanCount + " 个 NaN,当前 NaNPolicy=ERROR 拒绝计算;可改用 SKIP");
        }
        // SKIP:紧凑拷贝去 NaN
        double[] clean = new double[data.length - nanCount];
        int j = 0;
        for (double v : data) if (!Double.isNaN(v)) clean[j++] = v;
        return clean;
    }

    private static void requireNonEmpty(double[] data) {
        if (data.length == 0) {
            throw new IllegalArgumentException("数据为空(或全为 NaN 且 policy=SKIP),无法计算");
        }
    }
}
