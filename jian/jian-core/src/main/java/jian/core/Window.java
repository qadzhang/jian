package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : Window —— Rolling / Expanding / EWM / Resampler(对齐 pandas 窗口族)
// │  Why  : 规范 01 §6;时间序列 / 滚动统计是数据分析核心;opencode #10 要求实现
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

    /** Rolling 窗口(对齐 pandas.Series.rolling(window))。 */
    public static final class Rolling {
        private final double[] data;
        private final int window;
        private final int minPeriods;

        /** 从 Series 创建 Rolling。 */
        public Rolling(Series s, int window, int minPeriods) {
            this.data = toDoubleArray(s);
            this.window = window;
            this.minPeriods = minPeriods;
        }

        public Rolling(Series s, int window) { this(s, window, Math.max(1, window)); }

        /** 滚动均值。 */
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

        /** 滚动求和。 */
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

        /** 滚动标准差(样本,ddof=1)。 */
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

        /** 滚动最小。 */
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

        /** 滚动最大。 */
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

        /** 滚动计数(非 NaN 个数)。 */
        public double[] count() {
            double[] r = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                int start = Math.max(0, i - window + 1);
                int c = 0;
                for (int j = start; j <= i; j++) if (!Double.isNaN(data[j])) c++;
                r[i] = c;
            }
            return r;
        }
    }

    // ======================== Expanding ========================

    /** Expanding 窗口(累积式,窗口从开头到当前位置,对齐 pandas.Series.expanding)。 */
    public static final class Expanding {
        private final double[] data;
        private final int minPeriods;

        public Expanding(Series s, int minPeriods) {
            this.data = toDoubleArray(s);
            this.minPeriods = minPeriods;
        }

        public Expanding(Series s) { this(s, 1); }

        public double[] mean() {
            double[] r = new double[data.length];
            double s = 0; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { s += data[i]; c++; }
                r[i] = c >= minPeriods ? s / c : Double.NaN;
            }
            return r;
        }

        public double[] sum() {
            double[] r = new double[data.length];
            double s = 0; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { s += data[i]; c++; }
                r[i] = c >= minPeriods ? s : Double.NaN;
            }
            return r;
        }

        public double[] min() {
            double[] r = new double[data.length];
            double m = Double.POSITIVE_INFINITY; int c = 0;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isNaN(data[i])) { m = Math.min(m, data[i]); c++; }
                r[i] = c >= minPeriods ? m : Double.NaN;
            }
            return r;
        }

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

    /** EWM 指数加权(对齐 pandas.Series.ewm(alpha).mean())。 */
    public static final class EWM {
        private final double[] data;
        private final double alpha;

        /** alpha ∈ (0,1],越大越偏近期。 */
        public EWM(Series s, double alpha) {
            if (alpha <= 0 || alpha > 1) throw new IllegalArgumentException("alpha 须 ∈ (0,1],实际=" + alpha);
            this.data = toDoubleArray(s);
            this.alpha = alpha;
        }

        /** 指数加权移动平均(EWMA)。 */
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

        /** 指数加权方差(简化版)。 */
        public double[] var() {
            double[] m = mean();
            double[] r = new double[data.length];
            double prevVar = 0;
            for (int i = 0; i < data.length; i++) {
                if (Double.isNaN(data[i]) || Double.isNaN(m[i])) { r[i] = prevVar; continue; }
                double d = data[i] - m[i];
                prevVar = (1 - alpha) * prevVar + alpha * d * d;
                r[i] = prevVar;
            }
            return r;
        }

        public double[] std() {
            double[] v = var();
            for (int i = 0; i < v.length; i++) v[i] = Math.sqrt(v[i]);
            return v;
        }
    }

    // ======================== 辅助 ========================

    /** Series → double[](NaN 表缺失)。 */
    private static double[] toDoubleArray(Series s) {
        int n = s.size();
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            if (s.isNull(i)) d[i] = Double.NaN;
            else d[i] = s.getDouble(i);
        }
        return d;
    }
}
