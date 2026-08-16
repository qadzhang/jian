package jian.num;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : NumRegressionTest —— jian-num 回归测试集(固化数值语义与边界教学异常行为)
// │  Why  : 因为 INT64 精确算术/类型提升/全范围 randint/NaN 与负参拒绝/zeros 语义/
// │         .str 变换缺失语义/Matrix 教学异常/inf 与常数列统计口径这些行为
// │         全部锚定 numpy/pandas 实测,所以用回归测试固化,防未来退化
// │  Who  : JUnit 5 自动执行
// │  When : mvn test(jian-num 模块)
// │  Where: jian-num/src/test/java/jian/num/NumRegressionTest.java
// │  How  : 期望值全部锚定 numpy/pandas 实测(INT64 域精确、float 提升、np.random 负参
// │         ValueError、skew/kurt 的 G1/G2 精确值、.str.len() 对 NaN 输出 NaN)。
class NumRegressionTest {

    // ======================== INT64 算术精度 ========================

    @Test
    void INT64算术纯long精确_不饱和() {
        // 纯 long 算术不经 double 中转(numpy int64 全程精确;经 double 会饱和/丢精度)
        Ndarray a = Ndarray.of(new long[]{Long.MAX_VALUE});
        Ndarray b = Ndarray.of(new long[]{1L});
        assertThat(a.sub(b).getInt(0)).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(Ndarray.of(new long[]{123456789012345678L}).mul(Ndarray.of(new long[]{1000L}))
                .getInt(0)).isEqualTo(123456789012345678L * 1000L);
    }

    @Test
    void INT64除法提升FLOAT64对齐numpy() {
        // numpy int64/int64 = true divide → float64(不整除截断)
        Ndarray q = Ndarray.of(new long[]{3L}).div(Ndarray.of(new long[]{2L}));
        assertThat(q.dtype()).isEqualTo(DType.FLOAT64);
        assertThat(q.getFloat(0)).isEqualTo(1.5);
    }

    @Test
    void 整数dtype加浮点标量提升FLOAT64() {
        // numpy 提升规则:int array + float scalar → float64([1,2]+0.5 → [1.5,2.5])
        Ndarray r = Ndarray.of(new long[]{1L, 2L}).add(0.5);
        assertThat(r.dtype()).isEqualTo(DType.FLOAT64);
        assertThat(r.getFloat(0)).isEqualTo(1.5);
        assertThat(r.getFloat(1)).isEqualTo(2.5);
    }

    @Test
    void INT64求和long域累加_超2的53次方不丢精度() {
        // 2^60 + 200 个 1 —— 逐元素 double 累加每步把 1 舍到 2^60(结果 2^60);
        // long 域累加得 2^60+200,转 double 舍入到最近可表示数 2^60+256(ulp=256)
        // numpy 实测:np.sum(int64 数组) = 2^60+200,astype(float) → 2^60+256,一致
        long[] data = new long[201];
        data[0] = 1L << 60;
        for (int i = 1; i < data.length; i++) data[i] = 1L;
        double expected = (double) ((1L << 60) + 256);   // 2^60+200 的最近可表示 double
        assertThat(Ndarray.of(data).sum()).isEqualTo(expected);
        // mean 同走 sum()(long 累加后一次除法)
        assertThat(Ndarray.of(data).mean()).isEqualTo(expected / 201.0);
    }

    // ======================== 随机数边界 ========================

    @Test
    void randint全范围不溢出() {
        // span 用 long 尺度:全范围 [MIN,MAX] 下 int 相减溢出为负会抛 "bound must be positive"
        JianNumRandom r = new JianNumRandom(42);
        int[] v = r.randint(Integer.MIN_VALUE, Integer.MAX_VALUE, 100);
        assertThat(v).hasSize(100);
        for (int x : v) assertThat(x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE).isTrue();
    }

    @Test
    void binomial的p为NaN抛IAE() {
        // 因为 p<0||p>1 对 NaN 双 false 放行会产出全 0 序列(numpy 抛 ValueError),所以显式拒 NaN
        assertThatThrownBy(() -> new JianNumRandom(42).binomial(10, Double.NaN, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentile的q为NaN抛IAE不裸越界() {
        // NaN 显式拒为教学型 IAE(不落 Commons Math 内部裸抛 ArrayIndexOutOfBoundsException)
        assertThatThrownBy(() -> Stats.percentile(new double[]{1, 2, 3}, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[0,100]");
        assertThatThrownBy(() -> Stats.quantile(new double[]{1, 2, 3}, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void choice负k与randint负n抛教学IAE() {
        // 负参数统一教学型 IAE(不裸抛 NegativeArraySizeException)
        assertThatThrownBy(() -> new JianNumRandom(42).choice(new int[]{1, 2}, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">=0");
        assertThatThrownBy(() -> new JianNumRandom(42).randint(1, 5, -2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">=0");
    }

    @Test
    void rand与randn负参抛教学型IAE() {
        // 负长度统一教学型 IAE(不裸抛 NegativeArraySizeException)
        JianNumRandom r = new JianNumRandom(42);
        assertThatThrownBy(() -> r.rand(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n 必须 >=0");
        assertThatThrownBy(() -> r.randn(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n 必须 >=0");
        assertThatThrownBy(() -> r.randn(-1, 0.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n 必须 >=0");
    }

    @Test
    void binomial负参不再静默全零() {
        // 负 count / 负试验次数都抛教学型 IAE(负试验次数静默产出全 0 是错误数据不报错)
        JianNumRandom r = new JianNumRandom(42);
        assertThatThrownBy(() -> r.binomial(10, 0.5, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count 必须 >=0");
        assertThatThrownBy(() -> r.binomial(-5, 0.5, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("试验次数 n 必须 >=0");
        // 合法参数行为不变
        assertThat(r.binomial(10, 0.5, 100)).hasSize(100);
    }

    // ======================== zerosBool 语义 ========================

    @Test
    void zerosBool全false而非全缺失() {
        // zeros 语义 = 有值且为 false(显式填充,对齐 np.zeros(n, bool)),不是全缺失
        Ndarray z = Ndarray.zerosBool(3);
        assertThat(z.getBool(0)).isFalse();
        assertThat(z.getBool(1)).isFalse();
        assertThat(z.getBool(2)).isFalse();
        // isna 全 false(zeros 语义 = 有值且为 false,不是缺失)
        Ndarray mask = z.isna();
        assertThat(mask.getBool(0)).isFalse();
        assertThat(mask.getBool(1)).isFalse();
        assertThat(mask.getBool(2)).isFalse();
        // 参与逻辑运算不是"全 null 毒化":false or true = true
        Ndarray t = Ndarray.of(new Boolean[]{true, true, true});
        assertThat(z.or(t).getBool(0)).isTrue();
    }

    // ======================== StrOps 缺失语义 ========================

    @Test
    void strLength含null时isna与值都正确() {
        // length 返回 FLOAT64,null → NaN(isna 可识别;pandas .str.len() 对 NaN 也是 NaN)
        Ndarray a = Ndarray.ofStrings("jian", null, "ab");
        Ndarray len = a.str().length();
        assertThat(len.dtype()).isEqualTo(DType.FLOAT64);
        assertThat(len.getFloat(0)).isEqualTo(4.0);
        assertThat(len.getFloat(2)).isEqualTo(2.0);
        // null → NaN,isna 可识别
        assertThat(Double.isNaN(len.getFloat(1))).isTrue();
        assertThat(len.isna().getBool(1)).isTrue();
        assertThat(len.isna().getBool(0)).isFalse();
        // sum 跳过 NaN = 6.0
        assertThat(len.sum()).isEqualTo(6.0);
    }

    @Test
    void strCat的null分隔符按零串处理() {
        // null 分隔符按零串(sb.append(null) 会把 4 字符 "null" 字面量追加进结果)
        Ndarray a = Ndarray.ofStrings("a", "b", null, "c");
        assertThat(a.str().cat(null)).isEqualTo("abc");
        assertThat(a.str().cat("")).isEqualTo("abc");            // 空串行为不变(无间隔)
        assertThat(a.str().cat("-")).isEqualTo("a-b-c");         // 正常分隔符行为不变
        assertThat(Ndarray.ofStrings((String) null).str().cat(null)).isEmpty();  // 全 null → 空串
    }

    // ======================== Matrix 教学异常 ========================

    @Test
    void Matrix参差行抛教学型IAE() {
        // 参差行抛教学型 IAE(不落 Commons Math 的 DimensionMismatchException)
        assertThatThrownBy(() -> Matrix.of(new double[][]{{1, 2, 3}, {4, 5}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("行长度必须一致")
                .hasMessageContaining("第 0 行 3 列")
                .hasMessageContaining("第 1 行 2 列");
    }

    @Test
    void Matrix零行抛教学型IAE() {
        // 0 行数组抛教学型 IAE(不裸抛 ArrayIndexOutOfBoundsException)
        assertThatThrownBy(() -> Matrix.of(new double[0][]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 1 行");
    }

    @Test
    void Matrix矩形数据行为不变且leastSquares良态可用() {
        // leastSquares 用正规方程法(病态矩阵精度受限);良态超定方程仍精确求解,锁定行为
        Matrix a = Matrix.of(new double[][]{{1, 1}, {1, 2}, {1, 3}});
        double[] x = a.leastSquares(new double[]{1, 2, 2});
        assertThat(x[0]).isCloseTo(0.6667, within(1e-3));
        assertThat(x[1]).isCloseTo(0.5, within(1e-3));
    }

    // ======================== Stats inf 语义 / 常数列 / skew-kurt 口径 ========================

    @Test
    void sum异号inf得NaN_同号inf保持() {
        // IEEE 语义自然涌现(numpy 实测):异号 inf 相加得 NaN,同号保持
        double inf = Double.POSITIVE_INFINITY;
        assertThat(Double.isNaN(Stats.sum(new double[]{inf, -inf}))).isTrue();
        assertThat(Stats.sum(new double[]{-inf, inf})).isNaN();                  // 反序同样 NaN
        // 同号 inf 保持(numpy sum([inf,5])=inf)
        assertThat(Stats.sum(new double[]{inf, 5.0})).isEqualTo(inf);
        assertThat(Stats.sum(new double[]{-inf, 5.0})).isEqualTo(-inf);
        assertThat(Stats.sum(new double[]{5.0, inf})).isEqualTo(inf);
        // inf 在 NaN 后(skip 掉 NaN)仍是 inf
        assertThat(Stats.sum(new double[]{Double.NaN, inf})).isEqualTo(inf);
    }

    @Test
    void sum与median遇正负inf对齐numpy() {
        // sum 的 Kahan 补偿在 ±inf 域不补偿;median 手写排序取位
        // (numpy sum([inf,5])=inf、median([inf,5,5])=5.0)
        double inf = Double.POSITIVE_INFINITY;
        assertThat(Stats.sum(new double[]{inf, 5.0})).isEqualTo(inf);
        assertThat(Stats.sum(new double[]{-inf, 5.0})).isEqualTo(-inf);
        assertThat(Stats.median(new double[]{inf, 5.0, 5.0})).isEqualTo(5.0);
        assertThat(Stats.median(new double[]{inf, 5.0})).isEqualTo(inf);
    }

    @Test
    void 常数列skew与kurt返NaN对齐pandas() {
        // Commons Math 对方差~0 的列内部守卫返 0.0;jian 对齐 pandas/scipy(bias=False)返 NaN
        double[] const4 = {5.0, 5.0, 5.0, 5.0};
        assertThat(Stats.skewness(const4)).isNaN();
        assertThat(Stats.kurtosis(const4)).isNaN();
        // 混入 NaN 被跳过后仍是常数列 → NaN
        double[] constNa = {5.0, Double.NaN, 5.0, 5.0};
        assertThat(Stats.skewness(constNa)).isNaN();
        assertThat(Stats.kurtosis(constNa)).isNaN();
    }

    @Test
    void skew非对称数据精确值对齐pandas_G1口径() {
        // 期望值经 pandas 1.5.3 Series.skew() 实测(= scipy.stats.skew bias=False 的 G1)
        //   pd.Series([1,2,3,5,8]).skew()  = 0.9266785331
        //   pd.Series([1,2,2,3,3,100]).skew() = 2.4475511934
        // 手算复核 [1,2,3,5,8]:n=5, mean=3.8, S3=47.52, s=sqrt(7.7),
        //   G1 = (5/12)·S3/s³ = 0.9266785331… ✓
        assertThat(Stats.skewness(new double[]{1, 2, 3, 5, 8}))
                .isCloseTo(0.9266785331, within(1e-6));
        assertThat(Stats.skewness(new double[]{1, 2, 2, 3, 3, 100}))
                .isCloseTo(2.4475511934, within(1e-6));
        // 对称数据 skew 恰为 0([1..8] 关于 4.5 对称,三阶中心矩抵消)
        assertThat(Stats.skewness(new double[]{1, 2, 3, 4, 5, 6, 7, 8}))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void kurt非对称数据精确值对齐pandas_G2口径() {
        // 期望值经 pandas 1.5.3 Series.kurt() 实测(= scipy.stats.kurtosis bias=False 的 G2 超额峰度)
        //   pd.Series([1,2,3,5,8]).kurt()       = 0.1298701299
        //   pd.Series([1,2,2,3,3,100]).kurt()   = 5.9926403701
        //   pd.Series([1..8]).kurt()            = -1.2000000000(近似均匀分布)
        // 手算复核 [1,2,3,5,8]:S4=385.616, s⁴=59.29,
        //   G2 = 1.25·(385.616/59.29) − 8 = 0.1298701299… ✓
        assertThat(Stats.kurtosis(new double[]{1, 2, 3, 5, 8}))
                .isCloseTo(0.1298701299, within(1e-6));
        assertThat(Stats.kurtosis(new double[]{1, 2, 2, 3, 3, 100}))
                .isCloseTo(5.9926403701, within(1e-6));
        assertThat(Stats.kurtosis(new double[]{1, 2, 3, 4, 5, 6, 7, 8}))
                .isCloseTo(-1.2, within(1e-6));
    }
}
