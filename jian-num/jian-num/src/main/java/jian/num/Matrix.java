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

    /**
     * 从二维数组构造(拷贝入参)。
     * <p>因为参差行会落进 Commons Math 抛 DimensionMismatchException、0 行数组裸抛
     * ArrayIndexOutOfBoundsException(均无教学信息),所以统一改抛教学型 IllegalArgumentException;
     * jian Matrix 对齐 numpy ndarray:矩形结构(各行长度必须一致)。
     *
     * @param data double[][] 二维数组,data[i][j] 为第 i 行第 j 列元素;约束:不能为 null;
     *             至少 1 行;各行长度必须一致(矩形)
     * @return Matrix 包装的矩阵对象
     * @throws IllegalArgumentException 当 data 为 null、长度为 0(无法确定列数)、
     *                                  或存在参差行(某行长度与第 0 行不同)时抛出
     */
    public static Matrix of(double[][] data) {
        // 伪代码:
        //   1. null → IAE(原有)
        //   2. 0 行 → IAE(无列数信息,矩阵形状未定义)
        //   3. 逐行比对长度,与第 0 行不等 → IAE(带行号与两侧列数的消息)
        //   4. 全矩形 → Array2DRowRealMatrix(copy=true)
        if (data == null) throw new IllegalArgumentException("data 不能为 null");
        if (data.length == 0) throw new IllegalArgumentException(
                "矩阵至少需要 1 行:0 行数组无法确定列数(矩阵行列必须一致,对齐 numpy ndarray 的矩形结构);"
                        + "如需占位请用 Matrix.identity(0) 或补充行数据");
        int cols = data[0].length;
        for (int i = 1; i < data.length; i++) {
                if (data[i].length != cols) throw new IllegalArgumentException(
                        "矩阵行长度必须一致(矩形):第 0 行 " + cols + " 列,第 " + i + " 行 "
                                + data[i].length + " 列;参差数据请先补齐(numpy ndarray 同样要求矩形)");
        }
        return new Matrix(new Array2DRowRealMatrix(data, true));
    }

    /**
     * 单位矩阵 n×n。
     *
     * @param n int 矩阵维度,约束:n &gt;= 0
     * @return Matrix n×n 单位矩阵(对角线为 1,其余为 0)
     */
    public static Matrix identity(int n) {
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) d[i][i] = 1.0;
        return new Matrix(new Array2DRowRealMatrix(d, false));
    }

    // ======================== 属性 ========================

    /**
     * @return int 矩阵行数
     */
    public int rows() { return real.getRowDimension(); }

    /**
     * @return int 矩阵列数
     */
    public int cols() { return real.getColumnDimension(); }

    /**
     * @return int[] 形状数组 [rows, cols]
     */
    public int[] shape() { return new int[]{rows(), cols()}; }

    /**
     * @param i int 行索引(0 基),约束:0 &lt;= i &lt; rows()
     * @param j int 列索引(0 基),约束:0 &lt;= j &lt; cols()
     * @return double 第 (i, j) 位置的元素值
     */
    public double get(int i, int j) { return real.getEntry(i, j); }

    /**
     * @return double[][] 矩阵数据的二维数组拷贝
     */
    public double[][] toArray() { return real.getData(); }

    // ======================== 运算(返回新 Matrix)========================

    /**
     * 矩阵乘 A·B(对齐 numpy @ / np.matmul)。
     *
     * @param other Matrix 右乘矩阵,约束:不能为 null;要求 other.rows() == this.cols()
     * @return Matrix 乘积矩阵(this × other)
     */
    public Matrix mul(Matrix other) {
        return new Matrix(real.multiply(other.real));
    }

    /**
     * 加 A+B(逐元素,形状须一致)。
     *
     * @param other Matrix 加数矩阵,约束:不能为 null;要求与 this 形状一致
     * @return Matrix 逐元素相加结果
     */
    public Matrix add(Matrix other) {
        return new Matrix(real.add(other.real));
    }

    /**
     * 减 A-B(逐元素,形状须一致)。
     *
     * @param other Matrix 减数矩阵,约束:不能为 null;要求与 this 形状一致
     * @return Matrix 逐元素相减结果
     */
    public Matrix sub(Matrix other) {
        return new Matrix(real.subtract(other.real));
    }

    /**
     * 标量乘。
     *
     * @param s double 标量系数,取值范围:任意实数
     * @return Matrix 每个元素乘 s 后的新矩阵
     */
    public Matrix mul(double s) {
        return new Matrix(real.scalarMultiply(s));
    }

    /**
     * 转置(对齐 numpy .T)。
     *
     * @return Matrix 转置矩阵
     */
    public Matrix transpose() {
        return new Matrix(real.transpose());
    }

    /**
     * numpy 风格别名:a.T == a.transpose()。
     *
     * @return Matrix 转置矩阵
     */
    public Matrix T() { return transpose(); }

    /**
     * numpy 风格别名:a @ b 矩阵乘法 == a.mul(b)。
     *
     * @param other Matrix 右乘矩阵,约束:不能为 null;要求 other.rows() == this.cols()
     * @return Matrix 乘积矩阵
     */
    public Matrix matmul(Matrix other) { return mul(other); }

    /**
     * 取第 i 行(返回拷贝,对齐 numpy a[i, :])。
     *
     * @param i int 行索引(0 基),约束:0 &lt;= i &lt; rows()
     * @return double[] 第 i 行元素的拷贝数组
     * @throws IllegalArgumentException 当 i 超出 [0, rows()) 范围时抛出
     */
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
     * @param b double[] 右端向量,约束:b.length 须等于 rows();this 须为方阵
     * @return double[] 解向量 x(长度 = cols())
     * @throws IllegalArgumentException                当 b 长度不等于 rows() 或 this 非方阵时抛出
     * @throws SingularMatrixExceptionWithHint        A 奇异不可逆时抛出,建议改用 {@link #leastSquares}
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
            throw new SingularMatrixExceptionWithHint(rows(), cols(), e);
        }
    }

    /**
     * 行列式(对齐 np.linalg.det)。要求方阵。
     *
     * @return double 行列式值
     * @throws IllegalArgumentException                当 this 非方阵时抛出
     * @throws SingularMatrixExceptionWithHint        矩阵数值奇异时可能抛出
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
     *
     * @return Matrix 逆矩阵
     * @throws IllegalArgumentException                当 this 非方阵时抛出
     * @throws SingularMatrixExceptionWithHint        矩阵奇异不可逆时抛出
     */
    public Matrix inverse() {
        if (!isSquare()) {
            throw new IllegalArgumentException(
                    "inverse 要求方阵,当前 " + rows() + "x" + cols());
        }
        try {
            return new Matrix(new LUDecomposition(real).getSolver().getInverse());
        } catch (SingularMatrixException e) {
            throw new SingularMatrixExceptionWithHint(rows(), cols(), e);
        }
    }

    /**
     * 最小二乘解(对齐 np.linalg.lstsq 的求解目标)。
     * <p>解超定方程 Ax ≈ b,返回使 ||Ax-b||² 最小的 x。
     * <p><b>数值稳定性说明</b>:本方法用<b>正规方程</b>
     * (AᵀA)x = Aᵀb 求解 —— 条件数被平方(cond(AᵀA) ≈ cond(A)²),<b>病态矩阵(近共线列)
     * 精度显著受限</b>;numpy lstsq 用 SVD,病态输入下更稳。数值稳定性敏感的场景请先做
     * rank 检查(共线列去冗余)或改用 SVD 实现;良态中小规模问题两者差异可忽略。
     *
     * @param b double[] 右端向量,约束:b.length 须等于 rows()
     * @return double[] 最小二乘解 x(长度 = cols())
     * @throws IllegalArgumentException         当 b 长度不等于 rows() 时抛出
     * @throws SingularMatrixExceptionWithHint 正规方程 AᵀA 奇异(通常因列共线性)时抛出
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
            throw new SingularMatrixExceptionWithHint(rows(), cols(), e);
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
        /**
         * @param rows int 矩阵行数(用于错误提示)
         * @param cols int 矩阵列数(用于错误提示)
         */
        public SingularMatrixExceptionWithHint(int rows, int cols) {
            super("矩阵奇异(r=" + rows + ", c=" + cols + "),无法求逆/解;"
                    + "若为超定方程请用 leastSquares;若为数据共线性,请去冗余列");
        }

        /** 带原始 cause(保留 Commons Math 异常链,不丢 stack trace)。 */
        public SingularMatrixExceptionWithHint(int rows, int cols, Throwable cause) {
            super("矩阵奇异(r=" + rows + ", c=" + cols + "),无法求逆/解;"
                    + "若为超定方程请用 leastSquares;若为数据共线性,请去冗余列", cause);
        }
    }
}
