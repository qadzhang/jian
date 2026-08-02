package jian.num;

// ┌─ What : JianNum —— jian-num 库的顶层门面(对齐 numpy 顶层函数式 API)
// │  Why  : 规范 06 §2 把统计/线代/随机数 API 写成 Jian-num.xxx() 风格;Java 用 JianNum 类承载静态方法
// │  Who  : 用户代码 import static jian.num.JianNum.* 后直接 mean(data)/describe(data)/...
// │  When : 任何 jian-num 调用入口
// │  Where: jian-num/JianNum.java
// │  How  : 数据走向:用户调 → 静态方法委托给 Stats/Correlation/Matrix/LinearFit → 结果。
// │         关键变量变化:无(纯门面,无状态);唯一状态是全局随机数种子。
// │         逻辑路线:
// │           路径 A(描述统计)→ 委托 Stats;
// │           路径 B(相关)→ 委托 Correlation;
// │           路径 C(线代)→ 用户直接 new Matrix / Ndarray(本门面不重复包装);
// │           路径 D(随机)→ 委托全局 RANDOM 实例。
/**
 * jian-num 库的顶层门面,聚合 {@link Stats} / {@link Correlation} / {@link LinearFit} / 随机数的便捷入口。
 *
 * <p>用法:
 * <pre>{@code
 * import static jian.num.JianNum.*;
 *
 * double[] data = {1, 2, 3, 4, 5};
 * double m = mean(data);            // 均值
 * Summary s = describe(data);       // 完整描述统计
 * double r = pearson(x, y);         // 皮尔逊相关
 * setSeed(42);                      // 设种子(后续随机可复现)
 * double[] noise = randn(100);      // 100 个标准正态噪声
 * }</pre>
 */
public final class JianNum {

    private JianNum() {}

    // ===== 全局随机数(可复现)=====
    private static volatile JianNumRandom RANDOM = new JianNumRandom();

    /** 重设全局随机种子(同种子 → 后续随机序列完全一致)。 */
    public static void setSeed(long seed) {
        RANDOM = new JianNumRandom(seed);
    }

    /** uniform[0,1) n 个(对齐 np.random.rand)。 */
    public static double[] rand(int n) { return RANDOM.rand(n); }

    /** 标准正态 N(0,1) n 个(对齐 np.random.randn)。 */
    public static double[] randn(int n) { return RANDOM.randn(n); }

    /** 正态 N(mu,sigma²) n 个。 */
    public static double[] randn(int n, double mu, double sigma) {
        return RANDOM.randn(n, mu, sigma);
    }

    /** [low,high) 整数 n 个(对齐 np.random.randint)。 */
    public static int[] randint(int low, int high, int n) {
        return RANDOM.randint(low, high, n);
    }

    // ===== 描述统计(委托 Stats)=====
    public static double mean(double[] data) { return Stats.mean(data); }
    public static double sum(double[] data) { return Stats.sum(data); }
    public static double min(double[] data) { return Stats.min(data); }
    public static double max(double[] data) { return Stats.max(data); }
    public static long count(double[] data) { return Stats.count(data); }

    /** 样本标准差 ddof=1(对齐 pandas Series.std)。 */
    public static double std(double[] data) { return Stats.std(data, 1); }

    /** 标准差,可指定 ddof(0=总体,1=样本)。 */
    public static double std(double[] data, int ddof) { return Stats.std(data, ddof); }

    /** 样本方差 ddof=1。 */
    public static double var(double[] data) { return Stats.var(data, 1); }

    /** 方差,可指定 ddof。 */
    public static double var(double[] data, int ddof) { return Stats.var(data, ddof); }

    public static double median(double[] data) { return Stats.median(data); }

    /** 百分位分位(对齐 np.percentile,q∈[0,100])。 */
    public static double percentile(double[] data, double q) { return Stats.percentile(data, q); }

    /** 小数分位(对齐 np.quantile,q∈[0,1])。 */
    public static double quantile(double[] data, double q) { return Stats.quantile(data, q); }

    public static double skewness(double[] data) { return Stats.skewness(data); }
    public static double kurtosis(double[] data) { return Stats.kurtosis(data); }
    public static Summary describe(double[] data) { return Stats.describe(data); }

    // ===== 相关与协方差(委托 Correlation)=====
    public static double cov(double[] x, double[] y) { return Correlation.cov(x, y); }
    public static double pearson(double[] x, double[] y) { return Correlation.pearson(x, y); }
    public static double spearman(double[] x, double[] y) { return Correlation.spearman(x, y); }

    /** 文档命名别名(规范 06 §2.3 写 pearsonCorr):等价 {@link #pearson(double[], double[])}。 */
    public static double pearsonCorr(double[] x, double[] y) { return Correlation.pearson(x, y); }

    /** 文档命名别名(规范 06 §2.3 写 spearmanCorr):等价 {@link #spearman(double[], double[])}。 */
    public static double spearmanCorr(double[] x, double[] y) { return Correlation.spearman(x, y); }

    public static double[][] covarianceMatrix(double[][] m) { return Correlation.covarianceMatrix(m); }
    public static double[][] correlationMatrix(double[][] m) { return Correlation.correlationMatrix(m); }

    // ===== 拟合(委托 LinearFit)=====
    public static LinearFit linearFit(double[] x, double[] y) { return LinearFit.fit(x, y); }

    /** 库版本。 */
    public static String version() { return "1.0.0"; }
}
