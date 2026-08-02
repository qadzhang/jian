package jian.num;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.SingularMatrixException;

// ┌─ What : Matrix —— 矩阵与简单线性代数(对齐 numpy.linalg 子集:matmul/transpose/solve/det/inv/leastSquares)
// │  Why  : 规范 06 §1.3 要求复用 Commons Math 的 RealMatrix / LUDecomposition / OLSMultipleLinearRegression
// │  Who  : 用户直接调;被 jian-core 的 DataFrame 矩阵运算复用
// │  When : 解线性方程组、最小二乘拟合、矩阵求逆/行列式
// │  Where: jian-num/Matrix.java
// │  How  : 数据走向:double[][] 入参 → Array2DRowRealMatrix(内部存储)→ LU 分解/直接乘 → 结果。
// │         关键变量变化:
// │           - real:Commons Math RealMatrix 包装的内部矩阵;
// │           - det/solve 结果直接返回 double / double[][]。
// │         逻辑路线:
// │           路径 A(方阵求行列式/逆)→ LU 分解;奇异矩阵抛 SingularMatrixException → 包装提示;
// │           路径 B(解 Ax=b)→ 要求 A 方阵;非方阵抛异常;
// │           路径 C(最小二乘)→ A 列数 ≤ 行数;超定方程最小二乘解。
/**
 * 矩阵与简单线性代数封装,对齐 numpy.linalg 子集。
 *
 * <p>基于 Commons Math {@link RealMatrix} / {@link LUDecomposition}。
 * <p>本类不处理 NaN:NaN 输入会传播到结果(对齐 numpy.linalg 默认)。
 */
public final class Matrix {

    private final RealMatrix real;

    private Matrix(RealMatrix real) { this.real = real; }

    /** 从二维数组构造(拷贝入参)。 */
    public static Matrix of(double[][] data) {
        if (data == null) throw new IllegalArgumentException("data 不能为 null");
        return new Matrix(new Array2DRowRealMatrix(data, true));
    }

    /** 单位矩阵 n×n。 */
    public static Matrix identity(int n) {
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) d[i][i] = 1.0;
        return new Matrix(new Array2DRowRealMatrix(d, false));
    }

    // ======================== 属性 ========================

    public int rows() { return real.getRowDimension(); }
    public int cols() { return real.getColumnDimension(); }
    public int[] shape() { return new int[]{rows(), cols()}; }

    public double get(int i, int j) { return real.getEntry(i, j); }

    public double[][] toArray() { return real.getData(); }

    // ======================== 运算(返回新 Matrix)========================

    /** 矩阵乘 A·B(对齐 numpy @ / np.matmul)。 */
    public Matrix mul(Matrix other) {
        return new Matrix(real.multiply(other.real));
    }

    /** 加 A+B(逐元素,形状须一致)。 */
    public Matrix add(Matrix other) {
        return new Matrix(real.add(other.real));
    }

    /** 减 A-B(逐元素,形状须一致)。 */
    public Matrix sub(Matrix other) {
        return new Matrix(real.subtract(other.real));
    }

    /** 标量乘。 */
    public Matrix mul(double s) {
        return new Matrix(real.scalarMultiply(s));
    }

    /** 转置(对齐 numpy .T)。 */
    public Matrix transpose() {
        return new Matrix(real.transpose());
    }

    /** numpy 风格别名:a.T == a.transpose()。 */
    public Matrix T() { return transpose(); }

    /** numpy 风格别名:a @ b 矩阵乘法 == a.mul(b)。 */
    public Matrix matmul(Matrix other) { return mul(other); }

    /** 取第 i 行(返回拷贝,对齐 numpy a[i, :])。 */
    public double[] row(int i) {
        if (i < 0 || i >= rows()) {
            throw new IllegalArgumentException("行号 " + i + " 超出范围 [0, " + rows() + ")");
        }
        return real.getRow(i).clone();
    }

    // ======================== 线性代数(返回结果)========================

    /**
     * 解线性方程组 Ax = b(对齐 np.linalg.solve)。
     *
     * @param b 右端向量,长度须等于 rows()
     * @return 解向量 x
     * @throws SingularMatrixException A 奇异不可逆时,建议改用 {@link #leastSquares}
     */
    public double[] solve(double[] b) {
        if (b.length != rows()) {
            throw new IllegalArgumentException(
                    "b 长度 " + b.length + " 必须等于 A 行数 " + rows());
        }
        if (!isSquare()) {
            throw new IllegalArgumentException(
                    "solve 要求方阵,当前 " + rows() + "x" + cols() + ";超定/欠定方程请用 leastSquares");
        }
        try {
            return new LUDecomposition(real).getSolver().solve(toRealVector(b)).toArray();
        } catch (SingularMatrixException e) {
            throw new SingularMatrixExceptionWithHint(rows(), cols());
        }
    }

    /**
     * 行列式(对齐 np.linalg.det)。要求方阵。
     */
    public double determinant() {
        if (!isSquare()) {
            throw new IllegalArgumentException(
                    "determinant 要求方阵,当前 " + rows() + "x" + cols());
        }
        return new LUDecomposition(real).getDeterminant();
    }

    /**
     * 逆矩阵(对齐 np.linalg.inv)。要求方阵非奇异。
     */
    public Matrix inverse() {
        if (!isSquare()) {
            throw new IllegalArgumentException(
                    "inverse 要求方阵,当前 " + rows() + "x" + cols());
        }
        try {
            return new Matrix(new LUDecomposition(real).getSolver().getInverse());
        } catch (SingularMatrixException e) {
            throw new SingularMatrixExceptionWithHint(rows(), cols());
        }
    }

    /**
     * 最小二乘解(对齐 np.linalg.lstsq)。
     * <p>解超定方程 Ax ≈ b,返回使 ||Ax-b||² 最小的 x。
     *
     * @param b 右端向量,长度须等于 rows()
     * @return 最小二乘解 x(长度 = cols())
     */
    public double[] leastSquares(double[] b) {
        if (b.length != rows()) {
            throw new IllegalArgumentException(
                    "b 长度 " + b.length + " 必须等于 A 行数 " + rows());
        }
        // 用正规方程 (AᵀA)x = Aᵀb,简单且对中小规模够用
        RealMatrix at = real.transpose();
        RealMatrix ata = at.multiply(real);
        RealVector atb = at.operate(toRealVector(b));
        try {
            return new LUDecomposition(ata).getSolver().solve(atb).toArray();
        } catch (SingularMatrixException e) {
            throw new SingularMatrixExceptionWithHint(rows(), cols());
        }
    }

    // ======================== 内部 ========================

    private boolean isSquare() { return rows() == cols(); }

    private static RealVector toRealVector(double[] b) {
        return new ArrayRealVector(b, false);
    }

    @Override
    public String toString() {
        return real.toString();
    }

    /**
     * 带提示的奇异矩阵异常:把 Commons Math 的裸异常包装成带中文建议。
     */
    public static final class SingularMatrixExceptionWithHint extends RuntimeException {
        public SingularMatrixExceptionWithHint(int rows, int cols) {
            super("矩阵奇异(r=" + rows + ", c=" + cols + "),无法求逆/解;"
                    + "若为超定方程请用 leastSquares;若为数据共线性,请去冗余列");
        }
    }
}
