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
     * @return double 相关系数 ∈ [-1,1];方差为 0 返回 0
     */
    @Override public double pearson(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
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
        return denom == 0 ? 0 : sxy / denom;
    }

    /**
     * 斯皮尔曼秩相关(简化:秩转换后调用 pearson)。
     * @return double 秩相关系数 ∈ [-1,1]
     */
    @Override public double spearman(double[] x, double[] y) {
        // 简化:秩转换后用 pearson
        return pearson(rank(x), rank(y));
    }

    /**
     * 协方差(无偏样本,ddof=1)。
     * @return double 协方差;n &lt; 2 返回 NaN
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
     * 偏度(简单实现,总体矩)。
     * @return double 偏度;n &lt; 3 返回 NaN
     */
    @Override public double skewness(double[] data) {
        int n = data.length;
        if (n < 3) return Double.NaN;
        double m = 0;
        for (double v : data) m += v; m /= n;
        double s2 = 0, s3 = 0;
        for (double v : data) { double d = v - m; s2 += d * d; s3 += d * d * d; }
        s2 /= n; s3 /= n;
        return s2 == 0 ? 0 : s3 / Math.pow(s2, 1.5);
    }

    /**
     * 峰度(超额,正态分布为 0)。
     * @return double 超额峰度;n &lt; 4 返回 NaN
     */
    @Override public double kurtosis(double[] data) {
        int n = data.length;
        if (n < 4) return Double.NaN;
        double m = 0;
        for (double v : data) m += v; m /= n;
        double s2 = 0, s4 = 0;
        for (double v : data) { double d = v - m; s2 += d * d; s4 += d * d * d * d; }
        s2 /= n; s4 /= n;
        return s2 == 0 ? 0 : s4 / (s2 * s2) - 3;  // 超额峰度
    }

    /** @return String 固定 "simple-builtin" */
    @Override public String name() { return "simple-builtin"; }

    /**
     * 计算秩(简化:每个元素的"小于它的元素个数",并列值不取平均)。
     * @param data double[] 输入
     * @return double[] 同长度秩数组
     */
    private static double[] rank(double[] data) {
        int n = data.length;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            int rank = 0;
            for (int j = 0; j < n; j++) if (data[j] < data[i]) rank++;
            r[i] = rank;
        }
        return r;
    }

    // ===== 2026-08-09 阶段 B:SPI 默认实现辅助(供 StatsProvider 接口 default 方法调用)=====

    /**
     * 秩(SPI 默认实现;支持 method=average/min/max/first/dense)。
     * <p>对齐 pandas Series.rank:NaN 位置返回 NaN。
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

    /** 平均绝对偏差(SPI 默认实现)。 */
    public static double madDefault(double[] data) {
        double sum = 0; int n = 0;
        for (double v : data) if (!Double.isNaN(v)) { sum += v; n++; }
        if (n == 0) return Double.NaN;
        double mean = sum / n;
        double absSum = 0;
        for (double v : data) if (!Double.isNaN(v)) absSum += Math.abs(v - mean);
        return absSum / n;
    }

    /** 标准误差(SPI 默认实现;ddof=1)。 */
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
