package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : 阶段 B 统计变换测试 —— 验证 corr/cov/skew/kurt/cumsum/diff/pct_change/quantile/rank/clip/round/all/any/prod/nunique
// │  Why  : §3.16 路线图移过来的统计方法,A 级断言 + numpy/scipy 基准值差分
// │  Who  : 阶段 B 落地回归测试
// │  When : jian-core 测试套件常规执行
// │  Where: jian-core/src/test/java/jian/core/StageBTest.java
class StageBTest {

    private DataFrame sampleDf() {
        return DataFrame.of(Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
            new Object[][]{
                {1.0, 2.0},
                {2.0, 4.0},
                {3.0, 6.0},
                {4.0, 8.0}});
    }

    // ===== SPI 单列统计(skewness / kurtosis / mad / sem / quantile)=====

    @Test
    void colSkew_完全对称返回约0() {
        // 对称数据 [1,2,3,4,5] skewness 应 ≈ 0
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        assertThat(df.colSkew("v")).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void colSkew_非对称无偏偏度对齐pandas() {
        // 因为对称数据的 skew=0 掩盖有偏/无偏差异,所以用非对称数据验证无偏 G1。
        // [1,2,2,3,3,3,4,5,9] 无偏偏度 = 1.7281842(对齐 pandas Series.skew)。
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {2.0}, {3.0}, {3.0}, {3.0}, {4.0}, {5.0}, {9.0}});
        assertThat(df.colSkew("v")).isCloseTo(1.7281842, within(1e-6));
    }

    @Test
    void colKurt_非对称无偏峰度对齐pandas() {
        // 无偏 G2(Fisher 超额)对齐 pandas Series.kurt()。
        // 用非对称数据 + 精确无偏值 3.6483494(有偏口径会得不同值)。
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {2.0}, {3.0}, {3.0}, {3.0}, {4.0}, {5.0}, {9.0}});
        assertThat(df.colKurt("v")).isCloseTo(3.6483494, within(1e-6));
    }

    @Test
    void colMad_平均绝对偏差() {
        // [1,2,3,4,5] mean=3,MAD = (|−2|+|−1|+0+1+2)/5 = 6/5 = 1.2
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        assertThat(df.colMad("v")).isCloseTo(1.2, within(1e-9));
    }

    @Test
    void colSem_标准误() {
        // [1,2,3,4,5] std(ddof=1)=sqrt(2.5)=1.581, sem = 1.581/sqrt(5) = 0.707
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        assertThat(df.colSem("v")).isCloseTo(0.7071, within(1e-3));
    }

    @Test
    void colQuantile_中位数等于0_5() {
        DataFrame df = sampleDf();
        // [1,2,3,4] 中位数(quantile 0.5)= 2.5
        assertThat(df.colQuantile("x", 0.5)).isCloseTo(2.5, within(1e-9));
    }

    @Test
    void colQuantile_越界抛IAE() {
        DataFrame df = sampleDf();
        assertThatThrownBy(() -> df.colQuantile("x", 1.5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void colVar_方差() {
        // [1,2,3,4] var(ddof=1)=1.667
        DataFrame df = sampleDf();
        assertThat(df.colVar("x")).isCloseTo(1.6667, within(1e-3));
    }

    @Test
    void colProd_累积() {
        // [1,2,3,4] prod = 24
        DataFrame df = sampleDf();
        assertThat(df.colProd("x")).isCloseTo(24.0, within(1e-9));
    }

    @Test
    void colNunique_唯一值数() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"a"}, {"b"}, {"a"}, {"c"}, {null}});
        assertThat(df.colNunique("v")).isEqualTo(3);  // a/b/c,null 跳过
    }

    @Test
    void colAll_全非0为true() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        assertThat(df.colAll("v")).isTrue();
    }

    @Test
    void colAll_含0为false() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {0L}, {3L}});
        assertThat(df.colAll("v")).isFalse();
    }

    @Test
    void colAny_含非0为true() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
            new Object[][]{{0L}, {0L}, {3L}});
        assertThat(df.colAny("v")).isTrue();
    }

    @Test
    void colAny_全0为false() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
            new Object[][]{{0L}, {0L}});
        assertThat(df.colAny("v")).isFalse();
    }

    // ===== 双列相关与协方差(corr / cov)=====

    @Test
    void colCorr_完全正相关等于1() {
        // y = 2x,pearson 应 == 1.0
        DataFrame df = sampleDf();
        assertThat(df.colCorr("x", "y")).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void colCorr_完全负相关等于负1() {
        DataFrame df = DataFrame.of(Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
            new Object[][]{{1.0, 5.0}, {2.0, 4.0}, {3.0, 3.0}, {4.0, 2.0}, {5.0, 1.0}});
        assertThat(df.colCorr("x", "y")).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void colCorr_spearman秩相关等于1() {
        DataFrame df = sampleDf();
        assertThat(df.colCorr("x", "y", "spearman")).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void colCov_协方差() {
        // x=[1,2,3,4] mean=2.5, y=[2,4,6,8] mean=5
        // sum((x-mx)(y-my))/(n-1) = ((-1.5)(-3)+(-0.5)(-1)+(0.5)(1)+(1.5)(3))/3
        // = (4.5+0.5+0.5+4.5)/3 = 10/3 ≈ 3.333
        DataFrame df = sampleDf();
        assertThat(df.colCov("x", "y")).isCloseTo(3.3333, within(1e-3));
    }

    @Test
    void corrMatrix_全数值列对称矩阵() {
        DataFrame df = sampleDf();
        DataFrame m = df.corrMatrix();
        // 4 列:_index_ + x + y
        assertThat(m.columnCount()).isEqualTo(3);
        assertThat(m.rowCount()).isEqualTo(2);  // 2 个数值列
        // 对角线(自相关)= 1.0
        // x 行(0): _index_=x, x=1.0
        assertThat(m.get(0, "x")).isEqualTo(1.0);
        assertThat(m.get(1, "y")).isEqualTo(1.0);
    }

    // ===== 列内秩(rank)=====

    @Test
    void colRank_average方法同秩取平均() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {2.0}, {2.0}});  // 3,1,2,2
        DoubleColumn r = df.colRank("v");
        // average 排:1→1, 2 和 2 → (2+3)/2=2.5, 3 → 4
        assertThat(r.getDouble(0)).isEqualTo(4.0);
        assertThat(r.getDouble(1)).isEqualTo(1.0);
        assertThat(r.getDouble(2)).isEqualTo(2.5);
        assertThat(r.getDouble(3)).isEqualTo(2.5);
    }

    @Test
    void colRank_min方法同秩取最小() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {2.0}});
        DoubleColumn r = df.colRank("v", "min", "rk");
        // min 排:1→1, 2 和 2 → 2
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDouble(1)).isEqualTo(2.0);
        assertThat(r.getDouble(2)).isEqualTo(2.0);
    }

    @Test
    void colRank_dense方法() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {2.0}, {3.0}});
        DoubleColumn r = df.colRank("v", "dense", "rk");
        // dense:1→1, 2 和 2 → 2, 3 → 3
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDouble(2)).isEqualTo(2.0);
        assertThat(r.getDouble(3)).isEqualTo(3.0);
    }

    @Test
    void colRank_NaN位置保留NaN() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {Double.NaN}, {1.0}});
        DoubleColumn r = df.colRank("v");
        assertThat(Double.isNaN(r.getDouble(1))).isTrue();
        assertThat(r.getDouble(2)).isEqualTo(1.0);
    }

    // ===== 累积运算(cumsum / cummax / cummin / cumprod)=====

    @Test
    void colCumsum_累积和() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        DoubleColumn r = df.colCumsum("v", "cum");
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDouble(1)).isEqualTo(3.0);
        assertThat(r.getDouble(2)).isEqualTo(6.0);
        assertThat(r.getDouble(3)).isEqualTo(10.0);
    }

    @Test
    void colCumsum_NaN保持NaN不参与() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {Double.NaN}, {3.0}});
        DoubleColumn r = df.colCumsum("v", "cum");
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(Double.isNaN(r.getDouble(1))).isTrue();
        // 1 + (NaN 跳过) → 第三行:仍从最后有效值 1 累加到 1+3=4(简化:NaN 后重新从 0 累加是错的)
        // jian 设计:acc 在 NaN 时不更新,但下一行仍 acc + v → 1 + 3 = 4
        assertThat(r.getDouble(2)).isEqualTo(4.0);
    }

    @Test
    void colCummax_累积最大() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {5.0}, {2.0}});
        DoubleColumn r = df.colCummax("v", "cm");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(3.0);  // max(3,1)=3
        assertThat(r.getDouble(2)).isEqualTo(5.0);
        assertThat(r.getDouble(3)).isEqualTo(5.0);
    }

    @Test
    void colCummin_累积最小() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {5.0}});
        DoubleColumn r = df.colCummin("v", "cm");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(1.0);
        assertThat(r.getDouble(2)).isEqualTo(1.0);
    }

    @Test
    void colCumprod_累积积() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        DoubleColumn r = df.colCumprod("v", "cp");
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDouble(1)).isEqualTo(2.0);
        assertThat(r.getDouble(2)).isEqualTo(6.0);
        assertThat(r.getDouble(3)).isEqualTo(24.0);
    }

    // ===== 差分类(diff / pct_change)=====

    @Test
    void colDiff_periods1() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {3.0}, {6.0}, {10.0}});
        DoubleColumn r = df.colDiff("v", 1, "d");
        assertThat(Double.isNaN(r.getDouble(0))).isTrue();
        assertThat(r.getDouble(1)).isEqualTo(2.0);   // 3-1
        assertThat(r.getDouble(2)).isEqualTo(3.0);   // 6-3
        assertThat(r.getDouble(3)).isEqualTo(4.0);   // 10-6
    }

    @Test
    void colDiff_periods负值向后差() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {3.0}, {6.0}});
        DoubleColumn r = df.colDiff("v", -1, "d");
        assertThat(r.getDouble(0)).isEqualTo(-2.0);  // 1-3
        assertThat(r.getDouble(1)).isEqualTo(-3.0);  // 3-6
        assertThat(Double.isNaN(r.getDouble(2))).isTrue();
    }

    @Test
    void colDiff_periods0抛IAE() {
        DataFrame df = sampleDf();
        assertThatThrownBy(() -> df.colDiff("x", 0, "d"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void colPctChange_periods1() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{100.0}, {110.0}, {99.0}});
        DoubleColumn r = df.colPctChange("v", 1, "pc");
        assertThat(Double.isNaN(r.getDouble(0))).isTrue();
        assertThat(r.getDouble(1)).isCloseTo(0.10, within(1e-9));   // (110-100)/100
        assertThat(r.getDouble(2)).isCloseTo(-0.10, within(1e-3));  // (99-110)/110
    }

    // ===== 裁剪 / 四舍五入(clip / round)=====

    @Test
    void colClip_上下界裁剪() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{-5.0}, {3.0}, {15.0}});
        DoubleColumn r = df.colClip("v", 0.0, 10.0, "clipped");
        assertThat(r.getDouble(0)).isEqualTo(0.0);
        assertThat(r.getDouble(1)).isEqualTo(3.0);
        assertThat(r.getDouble(2)).isEqualTo(10.0);
    }

    @Test
    void colRound_四舍五入2位() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.14159}, {2.71828}});
        DoubleColumn r = df.colRound("v", 2, "rounded");
        assertThat(r.getDouble(0)).isEqualTo(3.14);
        assertThat(r.getDouble(1)).isEqualTo(2.72);
    }

    @Test
    void colRound_0位四舍五入到整数() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.4}, {3.5}});
        DoubleColumn r = df.colRound("v", 0, "rounded");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(4.0);  // half-even:3.5 → 4(4 是偶数;该值两规则恰同值)
        // 3.5 恰避开 half-up/half-even 分歧点(4 为偶数两规则同值),
        // 补 2.5(half-even → 2,half-up → 3)锁住舍入方向对齐 pandas
        DataFrame half = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{2.5}, {-3.5}});
        DoubleColumn hr = half.colRound("v", 0, "rounded");
        assertThat(hr.getDouble(0)).as("2.5 half-even → 2.0(对齐 pandas)").isEqualTo(2.0);
        assertThat(hr.getDouble(1)).as("-3.5 half-even → -4.0").isEqualTo(-4.0);
    }
}
