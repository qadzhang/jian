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

    @Override public double spearman(double[] x, double[] y) {
        // 简化:秩转换后用 pearson
        return pearson(rank(x), rank(y));
    }

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

    @Override public String name() { return "simple-builtin"; }

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
}
