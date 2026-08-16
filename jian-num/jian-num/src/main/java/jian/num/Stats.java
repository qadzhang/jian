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

    /**
     * 均值(默认跳过 NaN,对齐 np.nanmean)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 算术均值
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 且 policy=SKIP 时抛出
     */
    public static double mean(double[] data) {
        return mean(data, NaNPolicy.DEFAULT);
    }

    /**
     * 均值,可指定 NaN 处理策略。
     *
     * @param data   double[] 输入数据,约束:不能为 null;可含 NaN(按 policy 处理)
     * @param policy NaNPolicy NaN 处理策略,取值范围:SKIP / ERROR / PROPAGATE
     * @return double 算术均值
     * @throws IllegalArgumentException 当 data 为 null、空、或 policy=ERROR 且含 NaN 时抛出
     */
    public static double mean(double[] data, NaNPolicy policy) {
        double[] clean = filterNaN(data, policy);
        requireNonEmpty(clean);
        double sum = 0.0;
        for (double v : clean) sum += v;
        return sum / clean.length;
    }

    /**
     * 求和(默认跳过 NaN,对齐 np.nansum)。
     * <p>使用 Kahan 补偿求和(提高大数组/大小悬殊时的精度)。
     * <p>因为 Kahan 的补偿项在 inf 参与下是 inf-inf=NaN(会毒化累加),
     * 所以 ±inf 域内不做补偿,让 IEEE 加法自身决定语义:
     * inf+5=inf、(-inf)+5=-inf、inf+(-inf)=NaN(对齐 numpy)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double Kahan 补偿求和结果;含异号 ±inf 时为 NaN(对齐 numpy)
     * @throws IllegalArgumentException 当 data 为 null 或 policy=ERROR 且含 NaN 时抛出
     */
    public static double sum(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        // Kahan 补偿求和(提高大数组/大小悬殊时的精度)
        // 伪代码:逐项补偿累加;t 一旦落入 ±inf 域则补偿项 c 清零(不补偿),
        //        让 IEEE 加法自身决定 inf/NaN 语义;inf+(-inf) 自然得 NaN。
        double sum = 0.0, c = 0.0;
        for (double v : clean) {
            double y = v - c;
            double t = sum + y;
            if (Double.isInfinite(t)) {
                // ±inf 域 —— 补偿项 (t-sum)-y 是 inf-inf=NaN,会毒化后续累加,故 c=0
                sum = t; c = 0;
            } else {
                c = (t - sum) - y;
                sum = t;
            }
        }
        return sum;
    }

    /**
     * 最小值(默认跳过 NaN)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 最小值
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 且 policy=SKIP 时抛出
     */
    public static double min(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        double m = clean[0];
        for (double v : clean) if (v < m) m = v;
        return m;
    }

    /**
     * 最大值(默认跳过 NaN)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 最大值
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 且 policy=SKIP 时抛出
     */
    public static double max(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        double m = clean[0];
        for (double v : clean) if (v > m) m = v;
        return m;
    }

    /**
     * 计数(非 NaN 个数,对齐 pandas count)。
     *
     * @param data double[] 输入数据,约束:不能为 null
     * @return long 非 NaN 值的个数
     */
    public static long count(double[] data) {
        long c = 0;
        for (double v : data) if (!Double.isNaN(v)) c++;
        return c;
    }

    // ======================== 离散度 ========================

    /**
     * 样本标准差(ddof=1,默认,对齐 pandas/numpy 默认 std)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 样本标准差(ddof=1)
     * @throws IllegalArgumentException 当 data 为 null、空、或样本数不足以计算方差时抛出
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
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @param ddof int 自由度修正,取值范围:0(总体)或 1(样本);需满足 n &gt; ddof
     * @return double 标准差
     * @throws IllegalArgumentException 当 data 为 null、空、或 n &lt;= ddof 时抛出
     */
    public static double std(double[] data, int ddof) {
        return java.lang.Math.sqrt(var(data, ddof));
    }

    /**
     * 方差,可指定自由度修正 ddof(对齐 np.var / pandas var)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @param ddof int 自由度修正,取值范围:0(总体)或 1(样本);需满足 n &gt; ddof
     * @return double 方差
     * @throws IllegalArgumentException 当 data 为 null、空、或 n &lt;= ddof 时抛出
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
     * 分位数(百分制,API 形式对齐 np.percentile)。
     *
     * <p><b>插值口径</b>:本方法返回 Commons Math
     * {@link Percentile} 默认插值(R-6,h=(n+1)·p),与 numpy 默认 'linear'(R-7,h=(n-1)·p+1)
     * <b>在中位数以外的分位有差异</b>(如 [1,2,3,4,5] 的 Q1:本方法 1.5,numpy 2.0)。
     * 需 numpy/pandas 口径请用 jian-core 的 {@code DataFrameStats.percentile}(自写 R-7 线性插值)。
     * 模块定位是 Commons Math 薄封装(规范 06 §1.3),差异取舍声明见 doc/06-jian-num.md §取舍。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @param q    double 百分位数,取值范围:[0, 100],如 25 表示 Q1
     * @return double 第 q 百分位的值(Commons Math 默认 R-6 线性插值;中位数与 numpy 一致)
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 时抛出
     */
    public static double percentile(double[] data, double q) {
        // 百分制 [0,100] 校验(与 quantile 的 [0,1] 制各自独立,勿混用);
        // 因为 q=NaN 时 "q<0||q>100" 双 false 放行后 Commons Math 内部裸抛
        // ArrayIndexOutOfBoundsException(Index -1),所以显式拒 NaN(numpy percentile 抛 ValueError)
        if (Double.isNaN(q) || q < 0 || q > 100) throw new IllegalArgumentException("percentile q 必须在 [0,100],实际=" + q);
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        // Commons Math Percentile 默认 R-6 插值(与 numpy 'linear'/R-7 非中位数分位有差异,见方法 javadoc)
        return new Percentile().evaluate(clean, q);
    }

    /**
     * 分位数(小数制,API 形式对齐 np.quantile;插值口径同 {@link #percentile}:Commons Math 默认 R-6)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @param q    double 分位数,取值范围:[0, 1],如 0.95 表示 95 分位
     * @return double 第 q 分位的值
     * @throws IllegalArgumentException 当 q 不在 [0,1] 范围内,或 data 为 null、空、全 NaN 时抛出
     */
    public static double quantile(double[] data, double q) {
        if (q < 0 || q > 1) throw new IllegalArgumentException("q 必须在 [0,1],实际=" + q);
        return percentile(data, q * 100.0);
    }

    /**
     * 中位数(等价 percentile(data, 50))。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 中位数
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 时抛出
     */
    public static double median(double[] data) {
        return median(data, NaNPolicy.DEFAULT);
    }

    /**
     * 中位数(可指定 NaN 策略)。
     * NaNPolicy 重载仅 mean/median 可控;var/std 等默认 SKIP 已够用,
     * 不逐统计量加 policy(避免稀释 API)。
     * @param data   double[] 输入数据,约束:不能为 null;可含 NaN
     * @param policy NaNPolicy NaN 处理策略,取值范围:SKIP / ERROR / PROPAGATE
     * @return double 中位数
     */
    public static double median(double[] data, NaNPolicy policy) {
        // 因为 Commons Math Percentile 对含 ±inf 的数据返回 NaN(numpy median([inf,5,5])=5.0),
        // 所以中位数手写排序取位 —— 精确且 inf 安全
        // (排序比较对 ±inf 语义天然正确:inf 排末尾、-inf 排首位)
        double[] clean = filterNaN(data, policy);
        requireNonEmpty(clean);
        double[] sorted = clean.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return (n & 1) == 1 ? sorted[n >> 1] : (sorted[(n >> 1) - 1] + sorted[n >> 1]) / 2.0;
    }

    // ======================== 形状统计 ========================

    /**
     * 偏度(Skewness,对齐 pandas Series.skew / scipy.stats.skew,G1 口径)。
     * <p>基于 Commons Math {@link DescriptiveStatistics#getSkewness}。
     * <p>因为 Commons Math 内部 "方差 &lt; 1e-19 返 0" 的守卫让它对常数列返回 0.0,
     * 与 pandas/scipy(bias=False)的 NaN 分歧,所以调用前判方差为 0 直接返 NaN。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 偏度;正值右偏,负值左偏,0 对称;常数列返回 NaN
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 时抛出
     */
    public static double skewness(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        if (isConstantColumn(clean)) return Double.NaN;   // 常数列 → NaN(对齐 pandas/scipy)
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (double v : clean) ds.addValue(v);
        return ds.getSkewness();
    }

    /**
     * 峰度(Kurtosis,对齐 pandas Series.kurt,G2 超额峰度口径)。
     * <p>基于 Commons Math {@link DescriptiveStatistics#getKurtosis}(返回超额峰度,与 pandas 一致)。
     * <p>常数列(样本方差为 0)返回 NaN(理由同 {@link #skewness})。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return double 超额峰度;正态分布约为 0;常数列返回 NaN
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 时抛出
     */
    public static double kurtosis(double[] data) {
        double[] clean = filterNaN(data, NaNPolicy.DEFAULT);
        requireNonEmpty(clean);
        if (isConstantColumn(clean)) return Double.NaN;   // 常数列 → NaN(对齐 pandas/scipy)
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (double v : clean) ds.addValue(v);
        return ds.getKurtosis();
    }

    /**
     * 是否常数列(样本方差为 0;供 skew/kurt 前置判断)。
     * <p>n &lt; 2 视为"无方差可言"返回 true(调用方据此返 NaN,与 pandas n&lt;3 返 NaN 口径一致)。
     *
     * @param clean double[] 已去 NaN 的数据,约束:非 null 且非空
     * @return boolean 所有值相等(样本二阶中心矩为 0)时 true;否则 false
     */
    private static boolean isConstantColumn(double[] clean) {
        // 伪代码:n<2 → true;算均值;累加中心距平方 s2;s2==0(精确全等)→ true。
        // 关键变量变化:m(0→均值)、s2(0→Σ(v-m)²);全等值时 d 恒 0,s2 精确为 0。
        if (clean.length < 2) return true;
        double m = 0.0;
        for (double v : clean) m += v;
        m /= clean.length;
        double s2 = 0.0;
        for (double v : clean) {
            double d = v - m;
            s2 += d * d;
        }
        return s2 == 0.0;
    }

    // ======================== 一次返回全部(describe)========================

    /**
     * 描述统计摘要(对齐 pandas Series.describe())。
     * <p>返回 count / mean / std / min / Q1 / median / Q3 / max,std 用 ddof=1。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(默认 SKIP 跳过)
     * @return Summary 描述统计摘要记录
     * @throws IllegalArgumentException 当 data 为 null、空、或全 NaN 时抛出
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
