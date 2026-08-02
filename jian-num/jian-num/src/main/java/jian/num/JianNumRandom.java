package jian.num;

import java.util.Random;

// ┌─ What : JianNumRandom —— 可复现的随机数生成(对齐 numpy.random 子集)
// │  Why  : 规范 06 §1.3 要求支持 uniform/normal/binomial 等 + 种子可复现(同种子同输出)
// │  Who  : 用户通过 JianNum.rand/randn 调;被 jian-core 的 sample() / jian-viz 的 bootstrap 复用
// │  When : 需要随机抽样、噪声生成、统计自助法
// │  Where: jian-num/JianNumRandom.java
// │  How  : 数据走向:种子 → java.util.Random → 各种分布的 nextXxx → double[]/int[]。
// │         关键变量变化:
// │           - rng:内部 java.util.Random,setSeed 后确定性;
// │           - 默认种子用系统时间(不可复现),显式 setSeed 后可复现。
// │         逻辑路线:
// │           路径 A(setSeed 后调用)→ 所有方法返回可复现序列;
// │           路径 B(未 setSeed)→ 用默认随机种子,每次运行结果不同。
/**
 * 可复现的随机数生成器,对齐 numpy.random 子集。
 *
 * <p><b>复现性</b>:同种子 → 同序列(规范 06 §6 验收:种子可复现)。
 * <p><b>线程安全</b>:实例方法非线程安全,建议每个线程独立实例;或用全局 {@link JianNum#setSeed}。
 */
public final class JianNumRandom {

    private Random rng;

    public JianNumRandom() {
        this.rng = new Random();  // 默认随机种子
    }

    public JianNumRandom(long seed) {
        this.rng = new Random(seed);
    }

    /** 重设种子(后续序列确定)。 */
    public void setSeed(long seed) {
        this.rng = new Random(seed);
    }

    /** 生成 n 个 uniform[0,1) 随机数(对齐 np.random.rand)。 */
    public double[] rand(int n) {
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = rng.nextDouble();
        return r;
    }

    /**
     * 生成 n 个正态分布 N(mu, sigma²) 随机数(对齐 np.random.randn / normal)。
     *
     * @param mu    均值
     * @param sigma 标准差(>0)
     */
    public double[] randn(int n, double mu, double sigma) {
        if (sigma <= 0) throw new IllegalArgumentException("sigma 必须 >0,实际=" + sigma);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = mu + sigma * rng.nextGaussian();
        return r;
    }

    /** 标准正态 N(0,1) n 个(对齐 np.random.randn)。 */
    public double[] randn(int n) {
        return randn(n, 0.0, 1.0);
    }

    /**
     * 生成 n 个 [low, high) 整数(对齐 np.random.randint)。
     *
     * @param low  含
     * @param high 不含
     */
    public int[] randint(int low, int high, int n) {
        if (high <= low) throw new IllegalArgumentException("high 必须 >low:low=" + low + ", high=" + high);
        int[] r = new int[n];
        int span = high - low;
        for (int i = 0; i < n; i++) r[i] = low + rng.nextInt(span);
        return r;
    }

    /**
     * 二项分布采样(对齐 np.random.binomial,单次)。
     *
     * @param n     试验次数
     * @param p     成功概率 [0,1]
     * @param count 生成个数
     */
    public int[] binomial(int n, double p, int count) {
        if (p < 0 || p > 1) throw new IllegalArgumentException("p 必须在 [0,1],实际=" + p);
        int[] r = new int[count];
        for (int k = 0; k < count; k++) {
            int succ = 0;
            for (int i = 0; i < n; i++) if (rng.nextDouble() < p) succ++;
            r[k] = succ;
        }
        return r;
    }

    /** 从数组无放回随机取 k 个(对齐 np.random.choice replace=False 简版)。 */
    public int[] choice(int[] pool, int k) {
        if (k > pool.length) throw new IllegalArgumentException(
                "无放回采样 k=" + k + " 超过 pool 长度 " + pool.length);
        // Fisher-Yates 部分洗牌
        int[] copy = pool.clone();
        for (int i = 0; i < k; i++) {
            int j = i + rng.nextInt(copy.length - i);
            int tmp = copy[i]; copy[i] = copy[j]; copy[j] = tmp;
        }
        return java.util.Arrays.copyOf(copy, k);
    }
}
