package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : Window —— Rolling / Expanding / EWM / Resampler(对齐 pandas 窗口族)
// │  Why  : 规范 01 §6;时间序列 / 滚动统计是数据分析核心
// │  Who  : 用户经 df.getSeries(col).rolling(n) / df.rolling(n) 创建
// │  When : 滚动窗口统计、累积统计、指数加权、重采样
// │  Where: jian-core/Window.java
/**
 * 窗口族:Rolling / Expanding / EWM,对齐 pandas 窗口操作(规范 01 §6)。
 *
 * <p>用法:
 * <pre>{@code
 * // Rolling(滚动窗口)
 * Series s = df.getSeries("price");
 * double[] ma5 = s.rolling(5).mean();        // 5 日均线
 * double[] rv5 = s.rolling(5).std();         // 5 日波动率
 *
 * // Expanding(累积窗口)
 * double[] cumMax = s.expanding().max();
 *
 * // EWM(指数加权)
 * double[] ewma = s.ewm(0.3).mean();         // 指数加权移动平均
 * }</pre>
 *
 * <p>每个方法返回 double[](与 Series 等长,前 window-1 个位置为 NaN)。
 */
public final class Window {

    private Window() {}

    // ======================== Rolling ========================

    /**
     * Rolling 窗口(对齐 pandas.Series.rolling(window))。
     * <p>定长滑动窗口:第 i 行的窗口 = [max(0, i-window+1), i]。
     */
    public static final class Rolling {
        private final double[] data;
        private final int window;
        private final int minPeriods;

        /**
         * 从 Series 创建 Rolling。
         * window/minPeriods < 1 抛 IAE(window=0 会全 NaN 无报错)。
         * @param s          Series 数据源,非 null;转 double[](缺失→NaN)
         * @param window     int 窗口大小,≥ 1
         * @param minPeriods int 窗口内最少有效值数,≥ 1(不足则该位结果 NaN)
         */
        public Rolling(Series s, int window, int minPeriods) {
            if (window < 1) throw new IllegalArgumentException("Rolling window 必须 ≥ 1,实际:" + window);
            if (minPeriods < 1) throw new IllegalArgumentException("Rolling minPeriods 必须 ≥ 1,实际:" + minPeriods);
            this.data = toDoubleArray(s);
            this.window = window;
            this.minPeriods = minPeriods;
        }

        /**
         * 从 Series 创建 Rolling(minPeriods = max(1, window))。
         * @param s      Series 数据源
         * @param window int 窗口大小,≥ 1
         */
        public Rolling(Series s, int window) { this(s, window, Math.max(1, window)); }

        /**
         * 滚动均值。
         * @return double[] 与 data 等长;前 minPeriods-1 位为 NaN;其余为窗口内非 NaN 值的算术平均
         */
        public double[] mean() {
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                if (i < minPeriods - 1) { r[i] = Double.NaN; continue; }
                int start = Math.max(0, i - window + 1);
                double s = 0; int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) { s += data[j]; c++; }
                r[i] = c >= minPeriods ? s / c : Double.NaN;
            }
            return r;
        }

        /**
         * 滚动求和。
         * @return double[] 与 data 等长;窗口内非 NaN 值之和;不足 minPeriods 为 NaN
         */
        public double[] sum() {
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                if (i < minPeriods - 1) { r[i] = Double.NaN; continue; }
                int start = Math.max(0, i - window + 1);
                double s = 0; int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) { s += data[j]; c++; }
                r[i] = c >= minPeriods ? s : Double.NaN;
            }
            return r;
        }

        /**
         * 滚动标准差(样本,ddof=1)。
         * @return double[] 与 data 等长;窗口内非 NaN 值的样本标准差;有效值 ≤ 1 时为 NaN
         */
        public double[] std() {
            double[] m = mean();
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                if (Double.isNaN(m[i])) { r[i] = Double.NaN; continue; }
                int start = Math.max(0, i - window + 1);
                double s = 0; int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) {
                    double d = data[j] - m[i]; s += d * d; c++;
                }
                r[i] = c > 1 ? Math.sqrt(s / (c - 1)) : Double.NaN;
            }
            return r;
        }

        /** @return double[] 滚动最小值;不足 minPeriods 为 NaN */
        public double[] min() {
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                if (i < minPeriods - 1) { r[i] = Double.NaN; continue; }
                int start = Math.max(0, i - window + 1);
                double m = Double.POSITIVE_INFINITY; int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) { m = Math.min(m, data[j]); c++; }
                r[i] = c >= minPeriods ? m : Double.NaN;
            }
            return r;
        }

        /** @return double[] 滚动最大值;不足 minPeriods 为 NaN */
        public double[] max() {
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                if (i < minPeriods - 1) { r[i] = Double.NaN; continue; }
                int start = Math.max(0, i - window + 1);
                double m = Double.NEGATIVE_INFINITY; int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) { m = Math.max(m, data[j]); c++; }
                r[i] = c >= minPeriods ? m : Double.NaN;
            }
            return r;
        }

        /**
         * 滚动非空计数(对齐 pandas rolling.count)。
         * <p>含 minPeriods 门控 —— 因为从第 0 行就给 1、2、3… 会违反类头
         * "前 window-1 个位置为 NaN"承诺与 pandas 行为(rolling(3).count() 前 2 个为 NaN),
         * 所以双重门控:① 位置不足(i+1 &lt; minPeriods)→ NaN;② 窗口内有效值不足
         * (c &lt; minPeriods)→ NaN(pandas min_periods 语义,默认 == window)。
         * @return double[] 与 data 等长;窗口内非 NaN 个数(整数以 double 返回);
         *         不足 minPeriods 的位置为 NaN
         */
        public double[] count() {
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                if (i + 1 < minPeriods) { r[i] = Double.NaN; continue; }   // 位置门控
                int start = Math.max(0, i - window + 1);
                int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) c++;
                r[i] = c >= minPeriods ? c : Double.NaN;                    // 有效值门控
            }
            return r;
        }
    }

    // ======================== Expanding ========================

    /**
     * Expanding 窗口(累积式,窗口从开头到当前位置,对齐 pandas.Series.expanding)。
     */
    public static final class Expanding {
        private final double[] data;
        private final int minPeriods;

        /**
         * @param s          Series 数据源
         * @param minPeriods int 最少有效值数,≥ 1
         */
        public Expanding(Series s, int minPeriods) {
            this.data = toDoubleArray(s);
            this.minPeriods = minPeriods;
        }

        /** @param s Series 数据源(minPeriods=1) */
        public Expanding(Series s) { this(s, 1); }

        /** @return double[] 累积均值 */
        public double[] mean() {
            double[] r = new double[data.length];
            double s = 0; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { s += data[i]; c++; }
                r[i] = c >= minPeriods ? s / c : Double.NaN;
            }
            return r;
        }

        /** @return double[] 累积求和 */
        public double[] sum() {
            double[] r = new double[data.length];
            double s = 0; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { s += data[i]; c++; }
                r[i] = c >= minPeriods ? s : Double.NaN;
            }
            return r;
        }

        /** @return double[] 累积最小 */
        public double[] min() {
            double[] r = new double[data.length];
            double m = Double.POSITIVE_INFINITY; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { m = Math.min(m, data[i]); c++; }
                r[i] = c >= minPeriods ? m : Double.NaN;
            }
            return r;
        }

        /** @return double[] 累积最大 */
        public double[] max() {
            double[] r = new double[data.length];
            double m = Double.NEGATIVE_INFINITY; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { m = Math.max(m, data[i]); c++; }
                r[i] = c >= minPeriods ? m : Double.NaN;
            }
            return r;
        }
    }

    // ======================== EWM(指数加权)========================

    /**
     * EWM 指数加权(对齐 pandas.Series.ewm(alpha).mean())。
     */
    public static final class EWM {
        private final double[] data;
        private final double alpha;

        /**
         * @param s     Series 数据源
         * @param alpha double 平滑系数 ∈ (0.0, 1.0];越大越偏近期(0.3 常用)
         * @throws IllegalArgumentException alpha 不在 (0,1]
         */
        public EWM(Series s, double alpha) {
            if (alpha <= 0 || alpha > 1) throw new IllegalArgumentException("alpha 须 ∈ (0,1],实际=" + alpha);
            this.data = toDoubleArray(s);
            this.alpha = alpha;
        }

        /**
         * 指数加权移动平均(EWMA)。
         * @return double[] 与 data 等长;prev = alpha*data[i] + (1-alpha)*prev;缺失行透传前一值
         */
        public double[] mean() {
            double[] r = new double[data.length];
            double prev = Double.NaN;
            for (int i = 0; i < data.length; i++) {
                if (Double.isNaN(data[i])) { r[i] = prev; continue; }
                if (Double.isNaN(prev)) { prev = data[i]; }
                else { prev = alpha * data[i] + (1 - alpha) * prev; }
                r[i] = prev;
            }
            return r;
        }

        /**
         * 指数加权方差(**无偏估计**,方案 B 声明见 §10.16 第 14 条)。
         * <p>公式:resid = x - ewma;ewm_resid2 递推;var = ewm_resid2 / (1-(1-α)^nobs);
         * 有效观测 &lt; 2 时返回 NaN。
         * <p>设计差异(显式声明):pandas 自 0.18 起已移除 bias 参数,其 adjust=False 的 var
         * 等价旧版 bias=True(除以 nobs,有偏),与本实现的无偏公式数值不同
         * (实测 [1,2,3],α=0.5:本实现 [NaN,0.1667,0.3929] vs pandas [NaN,0.5,1.1])。
         * 本实现保留统计学无偏公式(等价 pandas 旧版 bias=False / R 与 numpy 的无偏 EWM 方差),
         * 作为**显式声明的设计差异**;ewm.mean 仍与 pandas 完全对齐(d52 对照锁定)。
         * @return double[] 与 data 等长
         */
        public double[] var() {
            double[] m = mean();
            double[] r = new double[data.length];
            double prevVar = 0;
            int nobs = 0;
            for (int i = 0; i < data.length; i++) {
                if (Double.isNaN(data[i])) { r[i] = Double.NaN; continue; }  // 缺失传播 NaN(对齐 pandas)
                nobs++;
                double d = data[i] - m[i];
                prevVar = (1 - alpha) * prevVar + alpha * d * d;
                if (nobs < 2) { r[i] = Double.NaN; continue; }   // bias=False:需 ≥ 2 个观测
                r[i] = prevVar / (1 - Math.pow(1 - alpha, nobs));
            }
            return r;
        }

        /** @return double[] 指数加权标准差(sqrt(var())) */
        public double[] std() {
            double[] v = var();
            for (int i = 0; i < v.length; i++) v[i] = Math.sqrt(v[i]);
            return v;
        }
    }

    // ======================== 辅助 ========================

    /**
     * Series → double[](NaN 表缺失)。
     * 非数值列(STRING/OBJECT 等)抛 IAE 带提示
     * (直接调 getDouble 会让 STRING 列抛难排查的 ISE)。
     * @param s Series 数据源
     * @return double[] 等长数组;缺失位为 NaN,其余为 s.getDouble(i)
     */
    private static double[] toDoubleArray(Series s) {
        if (!s.dtype().isNumeric()) {
            throw new IllegalArgumentException("窗口算子要求数值列,实际 dtype:" + s.dtype());
        }
        int n = s.size();
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            if (s.isNull(i)) d[i] = Double.NaN;
            else d[i] = s.getDouble(i);
        }
        return d;
    }
}
