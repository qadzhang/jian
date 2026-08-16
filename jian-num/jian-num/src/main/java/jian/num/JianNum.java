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

    /**
     * 重设全局随机种子(同种子 → 后续随机序列完全一致)。
     *
     * @param seed long 随机种子,取值范围:任意 long 值
     */
    public static void setSeed(long seed) {
        RANDOM = new JianNumRandom(seed);
    }

    /**
     * uniform[0,1) n 个(对齐 np.random.rand)。
     *
     * @param n int 生成个数,约束:n &gt;= 0
     * @return double[] 长度为 n 的 [0,1) 均匀分布随机数数组
     */
    public static double[] rand(int n) { return RANDOM.rand(n); }

    /**
     * 标准正态 N(0,1) n 个(对齐 np.random.randn)。
     *
     * @param n int 生成个数,约束:n &gt;= 0
     * @return double[] 长度为 n 的标准正态分布随机数数组
     */
    public static double[] randn(int n) { return RANDOM.randn(n); }

    /**
     * 正态 N(mu,sigma²) n 个。
     *
     * @param n     int 生成个数,约束:n &gt;= 0
     * @param mu    double 正态分布均值
     * @param sigma double 正态分布标准差,约束:sigma &gt;= 0
     * @return double[] 长度为 n 的 N(mu, sigma²) 随机数数组
     */
    public static double[] randn(int n, double mu, double sigma) {
        return RANDOM.randn(n, mu, sigma);
    }

    /**
     * [low,high) 整数 n 个(对齐 np.random.randint)。
     *
     * @param low  int 区间下界(含),约束:low &lt; high
     * @param high int 区间上界(不含),约束:high &gt; low
     * @param n    int 生成个数,约束:n &gt;= 0
     * @return int[] 长度为 n 的 [low, high) 均匀整数数组
     */
    public static int[] randint(int low, int high, int n) {
        return RANDOM.randint(low, high, n);
    }

    // ===== 描述统计(委托 Stats)=====

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 算术均值
     */
    public static double mean(double[] data) { return Stats.mean(data); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 总和
     */
    public static double sum(double[] data) { return Stats.sum(data); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 最小值
     */
    public static double min(double[] data) { return Stats.min(data); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 最大值
     */
    public static double max(double[] data) { return Stats.max(data); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return long 非 NaN 值的个数
     */
    public static long count(double[] data) { return Stats.count(data); }

    /**
     * 样本标准差 ddof=1(对齐 pandas Series.std)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 样本标准差(ddof=1)
     */
    public static double std(double[] data) { return Stats.std(data, 1); }

    /**
     * 标准差,可指定 ddof(0=总体,1=样本)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @param ddof int 自由度修正,取值范围:0(总体)或 1(样本)
     * @return double 标准差
     */
    public static double std(double[] data, int ddof) { return Stats.std(data, ddof); }

    /**
     * 样本方差 ddof=1。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 样本方差(ddof=1)
     */
    public static double var(double[] data) { return Stats.var(data, 1); }

    /**
     * 方差,可指定 ddof。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @param ddof int 自由度修正,取值范围:0(总体)或 1(样本)
     * @return double 方差
     */
    public static double var(double[] data, int ddof) { return Stats.var(data, ddof); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 中位数
     */
    public static double median(double[] data) { return Stats.median(data); }

    /**
     * 百分位分位(API 形式对齐 np.percentile,q∈[0,100];插值口径见 {@link Stats#percentile}:Commons Math 默认 R-6)。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @param q    double 百分位数,取值范围:[0, 100]
     * @return double 第 q 百分位的值
     */
    public static double percentile(double[] data, double q) { return Stats.percentile(data, q); }

    /**
     * 小数分位(对齐 np.quantile,q∈[0,1])。
     *
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @param q    double 分位数,取值范围:[0, 1]
     * @return double 第 q 分位的值
     */
    public static double quantile(double[] data, double q) { return Stats.quantile(data, q); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 偏度
     */
    public static double skewness(double[] data) { return Stats.skewness(data); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return double 峰度
     */
    public static double kurtosis(double[] data) { return Stats.kurtosis(data); }

    /**
     * @param data double[] 输入数据,约束:不能为 null;可含 NaN(按 NaNPolicy 处理)
     * @return Summary 描述统计摘要(count/mean/std/min/Q1/median/Q3/max)
     */
    public static Summary describe(double[] data) { return Stats.describe(data); }

    // ===== 相关与协方差(委托 Correlation)=====

    /**
     * @param x double[] 第一变量观测序列,约束:不能为 null;可含 NaN(配对剔除);长度须与 y 一致
     * @param y double[] 第二变量观测序列,约束:不能为 null;可含 NaN(配对剔除);长度须与 x 一致
     * @return double 样本协方差
     */
    public static double cov(double[] x, double[] y) { return Correlation.cov(x, y); }

    /**
     * @param x double[] 第一变量观测序列,约束:不能为 null;可含 NaN(配对剔除);长度须与 y 一致
     * @param y double[] 第二变量观测序列,约束:不能为 null;可含 NaN(配对剔除);长度须与 x 一致
     * @return double 皮尔逊相关系数 [-1, 1]
     */
    public static double pearson(double[] x, double[] y) { return Correlation.pearson(x, y); }

    /**
     * @param x double[] 第一变量观测序列,约束:不能为 null;可含 NaN(配对剔除);长度须与 y 一致
     * @param y double[] 第二变量观测序列,约束:不能为 null;可含 NaN(配对剔除);长度须与 x 一致
     * @return double 斯皮尔曼秩相关系数 [-1, 1]
     */
    public static double spearman(double[] x, double[] y) { return Correlation.spearman(x, y); }

    /**
     * 文档命名别名(规范 06 §2.3 写 pearsonCorr):等价 {@link #pearson(double[], double[])}。
     *
     * @param x double[] 第一变量观测序列
     * @param y double[] 第二变量观测序列
     * @return double 皮尔逊相关系数 [-1, 1]
     */
    public static double pearsonCorr(double[] x, double[] y) { return Correlation.pearson(x, y); }

    /**
     * 文档命名别名(规范 06 §2.3 写 spearmanCorr):等价 {@link #spearman(double[], double[])}。
     *
     * @param x double[] 第一变量观测序列
     * @param y double[] 第二变量观测序列
     * @return double 斯皮尔曼秩相关系数 [-1, 1]
     */
    public static double spearmanCorr(double[] x, double[] y) { return Correlation.spearman(x, y); }

    /**
     * @param m double[][] 输入矩阵,m[i] 是第 i 个变量观测序列;约束:不能为 null
     * @return double[][] k×k 协方差矩阵
     */
    public static double[][] covarianceMatrix(double[][] m) { return Correlation.covarianceMatrix(m); }

    /**
     * @param m double[][] 输入矩阵,m[i] 是第 i 个变量观测序列;约束:不能为 null
     * @return double[][] k×k 相关矩阵(对角线为 1.0)
     */
    public static double[][] correlationMatrix(double[][] m) { return Correlation.correlationMatrix(m); }

    // ===== 拟合(委托 LinearFit)=====

    /**
     * @param x double[] 自变量观测值,约束:不能为 null;可含 NaN(配对剔除);长度须与 y 一致
     * @param y double[] 因变量观测值,约束:不能为 null;可含 NaN(配对剔除);长度须与 x 一致
     * @return LinearFit 拟合结果(slope/intercept/rSquared)
     */
    public static LinearFit linearFit(double[] x, double[] y) { return LinearFit.fit(x, y); }

    /** 库版本。 */
    public static String version() { return "1.0.0"; }
}
