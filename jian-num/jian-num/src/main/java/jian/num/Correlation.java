package jian.num;

import org.apache.commons.math3.stat.correlation.Covariance;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;

// ┌─ What : Correlation —— 协方差与相关系数(对齐 numpy.cov / np.corrcoef / scipy.stats.pearsonr/spearmanr)
// │  Why  : 规范 06 §1.3 要求复用 Commons Math 的 Covariance / PearsonsCorrelation / SpearmansCorrelation
// │  Who  : 用户直接调;被 jian-core 的 DataFrame.corr/cov 复用
// │  When : 两个变量的相关性分析、协方差矩阵
// │  Where: jian-num/Correlation.java
// │  How  : 数据走向:double[] x, y → 配对过滤 NaN(两边都得非 NaN)→ Commons Math 相关类 → 结果。
// │         关键变量变化:
// │           - xs/ys:输入 x/y 经配对过滤后的数组(等长,无 NaN);
// │           - n:配对有效样本数,n<3 时相关系数无意义抛异常。
// │         逻辑路线:
// │           路径 A(长度不一致)→ 抛 IllegalArgumentException;
// │           路径 B(配对有效数 < 3)→ 抛 IllegalArgumentException(相关需至少 3 点);
// │           路径 C(正常)→ 调 Commons Math 计算协方差/相关系数。
/**
 * 协方差与相关系数封装,对齐 numpy/scipy 的相关函数。
 *
 * <p>所有方法默认配对过滤 NaN(两边都非 NaN 才参与计算,对齐 pandas 默认)。
 */
public final class Correlation {

    private Correlation() {}

    /**
     * 协方差(样本,ddof=1,对齐 np.cov 默认)。
     */
    public static double cov(double[] x, double[] y) {
        double[][] pair = pairFilterNaN(x, y);
        return new Covariance().covariance(pair[0], pair[1]);
    }

    /**
     * 皮尔逊相关系数(对齐 np.corrcoef / scipy.stats.pearsonr)。
     * <p>取值 [-1, 1],1=完全正相关,-1=完全负相关,0=无线性相关。
     */
    public static double pearson(double[] x, double[] y) {
        double[][] pair = pairFilterNaN(x, y);
        return new PearsonsCorrelation().correlation(pair[0], pair[1]);
    }

    /**
     * 斯皮尔曼秩相关系数(对齐 scipy.stats.spearmanr)。
     * <p>对非线性单调关系也敏感,对离群点更稳健。
     */
    public static double spearman(double[] x, double[] y) {
        double[][] pair = pairFilterNaN(x, y);
        return new SpearmansCorrelation().correlation(pair[0], pair[1]);
    }

    /**
     * 协方差矩阵(对齐 np.cov(matrix.T))。
     *
     * @param matrix 列优先存于 double[][] 的每行(即 matrix[i] 是第 i 个变量的观测序列),
     *               返回 k×k 协方差矩阵,k = matrix.length
     */
    public static double[][] covarianceMatrix(double[][] matrix) {
        int k = matrix.length;
        double[][] cov = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = i; j < k; j++) {
                double v = cov(matrix[i], matrix[j]);
                cov[i][j] = v;
                cov[j][i] = v;  // 对称
            }
        }
        return cov;
    }

    /**
     * 相关矩阵(对齐 np.corrcoef)。
     */
    public static double[][] correlationMatrix(double[][] matrix) {
        int k = matrix.length;
        double[][] corr = new double[k][k];
        for (int i = 0; i < k; i++) {
            corr[i][i] = 1.0;  // 对角线自相关为 1
            for (int j = i + 1; j < k; j++) {
                double r = pearson(matrix[i], matrix[j]);
                corr[i][j] = r;
                corr[j][i] = r;  // 对称
            }
        }
        return corr;
    }

    // ======================== 内部:配对过滤 NaN ========================

    /**
     * 配对过滤:仅保留 x 和 y 在同一位置都非 NaN 的样本。
     *
     * <p>数据走向:x, y → 同位置配对 → 双方都非 NaN 才保留 → 等长 xs/ys。
     */
    private static double[][] pairFilterNaN(double[] x, double[] y) {
        if (x == null || y == null) throw new IllegalArgumentException("x/y 不能为 null");
        if (x.length != y.length) {
            throw new IllegalArgumentException(
                    "x 与 y 长度必须一致:x=" + x.length + ", y=" + y.length);
        }
        // 先数有效对数,再紧凑拷贝
        int valid = 0;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) valid++;
        }
        if (valid < 3) {
            throw new IllegalArgumentException(
                    "有效配对数 " + valid + " < 3,相关/协方差至少需要 3 对非 NaN 样本");
        }
        double[] xs = new double[valid];
        double[] ys = new double[valid];
        int j = 0;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                xs[j] = x[i];
                ys[j] = y[i];
                j++;
            }
        }
        return new double[][]{xs, ys};
    }
}
