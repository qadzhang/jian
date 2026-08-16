package jian.num;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : Matrix 别名与 JianNum 相关别名的单元测试(规范 06 §2.1/§2.3)
class MatrixTest {

    @Test
    void T与matmul与row() {
        Matrix m = Matrix.of(new double[][]{{1, 2}, {3, 4}});
        assertThat(m.T().toArray()).isDeepEqualTo(new double[][]{{1, 3}, {2, 4}});
        Matrix n = Matrix.of(new double[][]{{2, 0}, {0, 2}});
        assertThat(m.matmul(n).toArray()).isDeepEqualTo(new double[][]{{2, 4}, {6, 8}});
        assertThat(m.row(0)).containsExactly(1.0, 2.0);
        assertThatThrownBy(() -> m.row(5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void JianNum相关别名() {
        double[] x = {1.0, 2.0, 3.0, 4.0};
        double[] y = {2.0, 4.0, 6.0, 8.0};
        assertThat(JianNum.pearsonCorr(x, y)).isEqualTo(1.0);
        assertThat(JianNum.pearson(x, y)).isEqualTo(JianNum.pearsonCorr(x, y));
        assertThat(JianNum.spearmanCorr(x, y)).isEqualTo(1.0);
    }
}
