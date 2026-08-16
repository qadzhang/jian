package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : SimpleStatsProvider —— core 内置兜底统计(对齐规范 06 §1.4 找不到 jian-num 时用)
// │  Why  : 用户只引 core 也能算相关/分位;引 jian-num-bridge 升级为 Commons Math 精确实现
// │  Who  : StatsProvider.current() 在未找到外部实现时返回
// │  When : corr/cov/quantile 且未引 jian-num
/**
 * core 内置兜底统计(简单实现,精度略低)。完整精度引 jian-num-bridge。
 */
public final class SimpleStatsProvider implements StatsProvider {

    /**
     * 皮尔逊相关(简单实现,无 NaN 处理)。
     * 说明:相关是尺度无关量,sample/总体方差归一数学等价,本实现公式与 pandas corr 一致;
     * NaN 由上层 DataFrameStats.corr 的配对过滤剔除。
     * 因为 N&lt;2 或零方差(全常量列)在统计学上相关系数无定义(pandas 实测均返回 NaN,
     * 返回 0 会误导"完全不相关"),所以返回 NaN。
     * @return double 相关系数 ∈ [-1,1];N&lt;2 或方差为 0 返回 NaN
     * @param x Object 值
     * @param y 参数;非 null
     */
    @Override public double pearson(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return Double.NaN;   // N=1 相关系数无定义(对齐 pandas)
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        double denom = Math.sqrt(sxx * syy);
        return denom == 0 ? Double.NaN : sxy / denom;   // 全常量列 → NaN(对齐 pandas)
    }

    /**
     * 斯皮尔曼秩相关(并列值取<b>平均秩</b>,对齐 pandas/scipy spearmanr)。
     * <p>因为并列值用 min 秩("小于它的元素个数")会与 pandas 数值分歧
     * (例 x=[1,1,3,2], y=[1,2,3,4]:min 秩 0.7746 vs pandas 0.7379),
     * 所以复用同文件 {@link #rankDefault}(method="average",正确实现平均秩),NaN 位置秩为 NaN
     * (上游 DataFrameStats.corr 已做配对过滤,正常路径输入无 NaN)。
     * @return double 秩相关系数 ∈ [-1,1]
     * @param x Object 值
     * @param y 参数;非 null
     */
    @Override public double spearman(double[] x, double[] y) {
        // 伪代码:两列各自取平均秩(并列值平均)→ 对秩向量算 pearson
        return pearson(rankDefault(x, "average"), rankDefault(y, "average"));
    }

    /**
     * 协方差(无偏样本,ddof=1)。
     * @return double 协方差;n &lt; 2 返回 NaN
     * @param x Object 值
     * @param y 参数;非 null
     */
    @Override public double covariance(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return Double.NaN;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;
        double s = 0;
        for (int i = 0; i < n; i++) s += (x[i] - mx) * (y[i] - my);
        return s / (n - 1);  // ddof=1
    }

    /**
     * 分位数(R-7 linear,对齐 numpy 默认;skip NaN)。
     * @param data double[] 数据,非 null
     * @param q    double 分位点 ∈ [0.0, 1.0]
     * @return double 分位数值;空数据返回 NaN
     */
    @Override public double percentile(double[] data, double q) {
        List<Double> vals = new ArrayList<>();
        for (double v : data) if (!Double.isNaN(v)) vals.add(v);
        if (vals.isEmpty()) return Double.NaN;
        vals.sort(Double::compare);
        double pos = q * (vals.size() - 1);  // R-7 linear(对齐 numpy)
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, vals.size() - 1);
        double frac = pos - lo;
        return vals.get(lo) * (1 - frac) + vals.get(hi) * frac;
    }

    /**
     * 偏度(无偏估计 G1,对齐 pandas Series.skew / scipy skew(bias=False))。
     * @return double 偏度;n &lt; 3 返回 NaN;零方差返 NaN
     * @param data double[] 数据;非 null
     */
    @Override public double skewness(double[] data) {
        int n = data.length;
        if (n < 3) return Double.NaN;
        double m = 0;
        for (double v : data) m += v; m /= n;
        double ss = 0;
        for (double v : data) { double d = v - m; ss += d * d; }
        // 零方差(全常量)返 NaN 对齐 pandas
        if (ss == 0) return Double.NaN;
        // 因为 m3/m2^1.5 是【有偏】样本矩 g1(对称数据如 [1..8] 恰好=0 看似一致,
        // 非对称数据会偏离 pandas),所以改无偏 G1:
        //   G1 = n/((n-1)(n-2)) · Σ((x-μ)/s)³,s = sqrt(Σ(x-μ)²/(n-1))。
        double sd = Math.sqrt(ss / (n - 1));
        double z3 = 0;
        for (double v : data) { double z = (v - m) / sd; z3 += z * z * z; }
        return n / ((double) (n - 1) * (n - 2)) * z3;
    }

    /**
     * 峰度(无偏估计 G2,Fisher 超额,正态分布为 0;对齐 pandas Series.kurt / scipy kurtosis(bias=False, fisher=True))。
     * @return double 超额峰度;n &lt; 4 返回 NaN;零方差返 NaN
     * @param data double[] 数据;非 null
     */
    @Override public double kurtosis(double[] data) {
        int n = data.length;
        if (n < 4) return Double.NaN;
        double m = 0;
        for (double v : data) m += v; m /= n;
        double ss = 0;
        for (double v : data) { double d = v - m; ss += d * d; }
        if (ss == 0) return Double.NaN;
        // 因为 m4/m2²-3 是【有偏】g2(总体矩,[1..8] 得 -1.238 vs pandas -1.200),
        // 所以改无偏 G2:
        //   G2 = n(n+1)/((n-1)(n-2)(n-3))·Σ((x-μ)/s)⁴ - 3(n-1)²/((n-2)(n-3))。
        double sd = Math.sqrt(ss / (n - 1));
        double z4 = 0;
        for (double v : data) { double z = (v - m) / sd; z4 += z * z * z * z; }
        double t1 = (double) n * (n + 1) / ((double) (n - 1) * (n - 2) * (n - 3)) * z4;
        double t2 = 3.0 * (n - 1) * (n - 1) / ((double) (n - 2) * (n - 3));
        return t1 - t2;
    }

    /** @return String 固定 "simple-builtin" */
    @Override public String name() { return "simple-builtin"; }

    // ===== SPI 默认实现辅助(供 StatsProvider 接口 default 方法调用)=====
    // spearman 复用 rankDefault(平均秩)

    /**
     * 秩(SPI 默认实现;支持 method=average/min/max/first/dense)。
     * <p>对齐 pandas Series.rank:NaN 位置返回 NaN。
     * @param data double[] 数据;非 null
     * @param method String 方法(pearson/spearman)
     */
    public static double[] rankDefault(double[] data, String method) {
        int n = data.length;
        double[] out = new double[n];
        String m = method == null ? "average" : method;
        java.util.List<int[]> indexed = new java.util.ArrayList<>();
        java.util.List<Double> vals = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(data[i])) {
                indexed.add(new int[]{i});
                vals.add(data[i]);
            } else {
                out[i] = Double.NaN;
            }
        }
        int valid = vals.size();
        if (valid == 0) return out;
        Integer[] order = new Integer[valid];
        for (int i = 0; i < valid; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(vals::get));
        switch (m) {
            case "average": {
                int i = 0;
                while (i < valid) {
                    int j = i;
                    while (j + 1 < valid && vals.get(order[j + 1]).equals(vals.get(order[i]))) j++;
                    double avgRank = (i + 1 + j + 1) / 2.0;
                    for (int k = i; k <= j; k++) out[indexed.get(order[k])[0]] = avgRank;
                    i = j + 1;
                }
                break;
            }
            case "min": {
                int i = 0;
                while (i < valid) {
                    int j = i;
                    while (j + 1 < valid && vals.get(order[j + 1]).equals(vals.get(order[i]))) j++;
                    for (int k = i; k <= j; k++) out[indexed.get(order[k])[0]] = i + 1;
                    i = j + 1;
                }
                break;
            }
            case "max": {
                int i = 0;
                while (i < valid) {
                    int j = i;
                    while (j + 1 < valid && vals.get(order[j + 1]).equals(vals.get(order[i]))) j++;
                    for (int k = i; k <= j; k++) out[indexed.get(order[k])[0]] = j + 1;
                    i = j + 1;
                }
                break;
            }
            case "dense": {
                int i = 0;
                int denseRank = 0;
                Double prev = null;
                while (i < valid) {
                    if (prev == null || !prev.equals(vals.get(order[i]))) denseRank++;
                    prev = vals.get(order[i]);
                    out[indexed.get(order[i])[0]] = denseRank;
                    i++;
                }
                break;
            }
            case "first": {
                for (int k = 0; k < valid; k++) out[indexed.get(order[k])[0]] = k + 1;
                break;
            }
            default:
                throw new IllegalArgumentException("rank method 不支持:" + m
                    + "(支持:average/min/max/first/dense)");
        }
        return out;
    }

    /** 平均绝对偏差(SPI 默认实现)。
     *  pandas Series.mad() 官方定义即 mean(|x - mean|)(平均绝对偏差),
     * @param data double[] 数据;非 null
     *  本实现与 pandas 一致;"median 版本"是 scipy.stats.median_abs_deviation,不属于 pandas mad。 */
    public static double madDefault(double[] data) {
        double sum = 0; int n = 0;
        for (double v : data) if (!Double.isNaN(v)) { sum += v; n++; }
        if (n == 0) return Double.NaN;
        double mean = sum / n;
        double absSum = 0;
        for (double v : data) if (!Double.isNaN(v)) absSum += Math.abs(v - mean);
        return absSum / n;
    }

    /**
     * 标准误差(SPI 默认实现;ddof=1)。
     * @param data double[] 数据
     */
    public static double semDefault(double[] data) {
        double s = 0; int n = 0;
        for (double v : data) if (!Double.isNaN(v)) { s += v; n++; }
        if (n < 2) return Double.NaN;
        double mean = s / n;
        double ss = 0;
        for (double v : data) if (!Double.isNaN(v)) { double d = v - mean; ss += d * d; }
        double std = Math.sqrt(ss / (n - 1));
        return std / Math.sqrt(n);
    }
}
