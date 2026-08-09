package jian.num;

import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

// ┌─ What : LinearFit —— 简单线性最小二乘拟合 y = a·x + b(对齐 numpy.polyfit(degree=1) / scipy.stats.linregress)
// │  Why  : 规范 06 §1.3 要求简单曲线拟合(最小二乘),复用 Commons Math OLSMultipleLinearRegression
// │  Who  : 用户通过 JianNum.linearFit 调;被 jian-core/jian-viz 的趋势线复用
// │  When : 散点趋势分析、回归基线
// │  Where: jian-num/LinearFit.java
// │  How  : 数据走向:x[], y[] → 配对去 NaN → OLSMultipleLinearRegression → 斜率/截距/R²。
// │         关键变量变化:
// │           - OLS 内部增广 X 矩阵为 [x,1] 列,解 beta=[slope, intercept];
// │           - rSquared ∈ [0,1],越接近 1 拟合越好。
// │         逻辑路线:
// │           路径 A(长度不一致或 <2)→ 抛异常;
// │           路径 B(正常)→ OLS 计算,返回 LinearFit record。
/**
 * 简单线性最小二乘拟合结果,由 {@link #fit} 返回。
 *
 * <p>拟合模型:y = slope·x + intercept
 *
 * @param slope     斜率
 * @param intercept 截距
 * @param rSquared  决定系数 R²([0,1],越接近 1 拟合越好)
 */
public record LinearFit(double slope, double intercept, double rSquared) {

    /**
     * 拟合 y = slope·x + intercept。
     *
     * @param x double[] 自变量观测值数组,约束:不能为 null;可含 NaN(自动配对剔除);长度须与 y 一致
     * @param y double[] 因变量观测值数组,约束:不能为 null;可含 NaN(自动配对剔除);长度须与 x 一致
     * @return LinearFit 拟合结果记录(含 slope/intercept/rSquared)
     * @throws IllegalArgumentException 当 x/y 为 null、长度不一致、或非 NaN 有效样本数 &lt; 2 时抛出
     */
    public static LinearFit fit(double[] x, double[] y) {
        if (x == null || y == null) throw new IllegalArgumentException("x/y 不能为 null");
        if (x.length != y.length) {
            throw new IllegalArgumentException("x/y 长度须一致:x=" + x.length + ", y=" + y.length);
        }
        // 配对过滤 NaN
        int valid = 0;
        for (int i = 0; i < x.length; i++) if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) valid++;
        if (valid < 2) {
            throw new IllegalArgumentException(
                    "线性拟合至少需要 2 对非 NaN 样本,实际有效 " + valid);
        }
        double[] xc = new double[valid];
        double[] yc = new double[valid];
        int j = 0;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                xc[j] = x[i]; yc[j] = y[i]; j++;
            }
        }
        // OLS:把 x 当作单个自变量(二维 X = [[x1],[x2],...])
        double[][] X = new double[valid][1];
        for (int i = 0; i < valid; i++) X[i][0] = xc[i];
        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(yc, X);
        double[] beta = ols.estimateRegressionParameters();  // [intercept, slope]
        return new LinearFit(beta[1], beta[0], ols.calculateRSquared());
    }

    /**
     * 用拟合模型预测 x 处的 y。
     *
     * @param x double 自变量取值,取值范围:任意实数(包括 NaN,结果将为 NaN)
     * @return double 预测值 y = slope·x + intercept
     */
    public double predict(double x) {
        return slope * x + intercept;
    }

    @Override
    public String toString() {
        return String.format("LinearFit{y = %.6f·x + %.6f, R² = %.6f}", slope, intercept, rSquared);
    }
}
