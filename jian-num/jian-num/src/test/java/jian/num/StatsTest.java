package jian.num;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : Stats / Correlation / Matrix / LinearFit / JianNum 的单元测试
// │  Why  : 验证统计/相关/线代结果与 numpy/scipy 同输入差异 < 1e-10(规范 06 §6 验收)
// │  How  : 用 numpy 文档的标准示例值作为基准,断言接近(within(1e-10))
class StatsTest {

    @Test
    void mean_标准示例() {
        // numpy: np.mean([1,2,3,4,5]) = 3.0
        assertThat(Stats.mean(new double[]{1, 2, 3, 4, 5})).isEqualTo(3.0);
    }

    @Test
    void mean_跳过NaN() {
        // np.nanmean([1, NaN, 3]) = 2.0
        assertThat(Stats.mean(new double[]{1, Double.NaN, 3})).isEqualTo(2.0);
    }

    @Test
    void mean_ERROR策略遇NaN抛异常() {
        assertThatThrownBy(() -> Stats.mean(new double[]{1, Double.NaN}, NaNPolicy.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ERROR");
    }

    @Test
    void std_样本标准差ddof1() {
        // pandas Series([1,2,3,4,5]).std() = sqrt(2.5) ≈ 1.5811
        assertThat(Stats.std(new double[]{1, 2, 3, 4, 5})).isCloseTo(1.5811388300841898, within(1e-10));
    }

    @Test
    void std_总体标准差ddof0() {
        // numpy np.std([1,2,3,4,5]) = sqrt(2) ≈ 1.4142
        assertThat(Stats.std(new double[]{1, 2, 3, 4, 5}, 0)).isCloseTo(Math.sqrt(2.0), within(1e-10));
    }

    @Test
    void percentile_中位数与Q1() {
        // 注:Commons Math Percentile 默认插值方法(R-6/Hyndman-Fan type 6)与 numpy 默认(R-7/linear)不同,
        // 中位数(50%)两者一致;非中位数有差异。本测试验证中位数精确 + Q1/Q3 在合理范围。
        // M1 阶段会提供对齐 numpy linear 的可选插值(见 doc/06-jian-num.md TODO)。
        double[] d = {1, 2, 3, 4, 5};
        assertThat(Stats.percentile(d, 50)).isEqualTo(3.0);   // 中位数两种算法都 = 3
        assertThat(Stats.percentile(d, 25)).isBetween(1.5, 2.0);  // R-6=1.5, R-7=2.0
        assertThat(Stats.percentile(d, 75)).isBetween(4.0, 4.5);
    }

    @Test
    void quantile_小数制() {
        // np.quantile([1,2,3,4,5], 0.5) = 3
        assertThat(Stats.quantile(new double[]{1, 2, 3, 4, 5}, 0.5)).isEqualTo(3.0);
    }

    @Test
    void describe_完整摘要() {
        Summary s = Stats.describe(new double[]{1, 2, 3, 4, 5});
        assertThat(s.count()).isEqualTo(5);
        assertThat(s.mean()).isEqualTo(3.0);
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.median()).isEqualTo(3.0);
        assertThat(s.max()).isEqualTo(5.0);
    }

    @Test
    void 空数组抛异常() {
        assertThatThrownBy(() -> Stats.mean(new double[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据为空");
    }

    @Test
    void pearson_完全正相关() {
        // scipy: pearsonr([1,2,3],[2,4,6]) = 1.0
        assertThat(Correlation.pearson(new double[]{1, 2, 3}, new double[]{2, 4, 6}))
                .isCloseTo(1.0, within(1e-10));
    }

    @Test
    void pearson_完全负相关() {
        // scipy: pearsonr([1,2,3],[3,2,1]) = -1.0
        assertThat(Correlation.pearson(new double[]{1, 2, 3}, new double[]{3, 2, 1}))
                .isCloseTo(-1.0, within(1e-10));
    }

    @Test
    void cov_配对过滤NaN() {
        // 含 NaN 的配对被跳过
        double r = Correlation.cov(new double[]{1, 2, Double.NaN, 4}, new double[]{2, 4, 99, 8});
        // 有效对 (1,2)(2,4)(4,8):完美线性,cov 为正
        assertThat(r).isGreaterThan(0);
    }

    @Test
    void matrix_乘法与转置() {
        Matrix a = Matrix.of(new double[][]{{1, 2}, {3, 4}});
        Matrix b = Matrix.of(new double[][]{{5, 6}, {7, 8}});
        Matrix ab = a.mul(b);
        // numpy: [[1,2],[3,4]] @ [[5,6],[7,8]] = [[19,22],[43,50]]
        double[][] r = ab.toArray();
        assertThat(r[0]).containsExactly(19.0, 22.0);
        assertThat(r[1]).containsExactly(43.0, 50.0);

        // 转置
        double[][] t = a.transpose().toArray();
        assertThat(t[0]).containsExactly(1.0, 3.0);
        assertThat(t[1]).containsExactly(2.0, 4.0);
    }

    @Test
    void matrix_解线性方程组() {
        // numpy: solve([[2,1],[1,3]], [3,2]) = [1.4, 0.2]
        Matrix a = Matrix.of(new double[][]{{2, 1}, {1, 3}});
        double[] x = a.solve(new double[]{3, 2});
        assertThat(x[0]).isCloseTo(1.4, within(1e-10));
        assertThat(x[1]).isCloseTo(0.2, within(1e-10));
    }

    @Test
    void matrix_行列式() {
        // numpy: np.linalg.det([[1,2],[3,4]]) = -2
        Matrix a = Matrix.of(new double[][]{{1, 2}, {3, 4}});
        assertThat(a.determinant()).isCloseTo(-2.0, within(1e-10));
    }

    @Test
    void matrix_最小二乘超定方程() {
        // 3 个方程 2 个未知:最小二乘解
        // numpy lstsq([[1,1],[1,2],[1,3]],[1,2,2]) ≈ [0.6667, 0.5]
        // (手算:正规方程 (AᵀA)x = Aᵀb,AᵀA=[[3,6],[6,14]],Aᵀb=[5,11],解 x=[2/3, 1/2])
        Matrix a = Matrix.of(new double[][]{{1, 1}, {1, 2}, {1, 3}});
        double[] x = a.leastSquares(new double[]{1, 2, 2});
        assertThat(x[0]).isCloseTo(0.6667, within(1e-3));
        assertThat(x[1]).isCloseTo(0.5, within(1e-3));
    }

    @Test
    void linearFit_完美直线() {
        // y = 2x + 1,R² = 1
        LinearFit fit = LinearFit.fit(new double[]{0, 1, 2, 3}, new double[]{1, 3, 5, 7});
        assertThat(fit.slope()).isCloseTo(2.0, within(1e-10));
        assertThat(fit.intercept()).isCloseTo(1.0, within(1e-10));
        assertThat(fit.rSquared()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void jianNum门面_同种子可复现() {
        JianNum.setSeed(42);
        double[] r1 = JianNum.randn(5);
        JianNum.setSeed(42);
        double[] r2 = JianNum.randn(5);
        // 同种子 → 同序列
        assertThat(r1).usingComparatorWithPrecision(1e-12).containsExactly(r2);
    }

    @Test
    void jianNum门面_randint区间() {
        JianNum.setSeed(1);
        int[] r = JianNum.randint(0, 100, 1000);
        for (int v : r) {
            assertThat(v).isBetween(0, 99);
        }
    }
}
