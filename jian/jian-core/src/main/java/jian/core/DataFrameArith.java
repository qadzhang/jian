package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : DataFrameArith —— 列级二元算术(对齐 pandas §3.4:add/sub/mul/div/pow 及列间运算)
// │  Why  : 规范要求 df.add 等;独立 companion 避免 DataFrame 主类超长
// │  Who  : DataFrame 的 colAdd/colSub/colMul/colDiv/multiply 等委托此类
// │  When : 派生数值列(如 total = price * qty)
// │  Where: jian-core/DataFrameArith.java
// │  How  : 数据走向:取两数值列 → 逐行 applyOp → 新 DoubleColumn。
// │         关键变量变化:NaN 传播(任一缺失 → 结果 NaN)。
/**
 * 列级二元算术工具,对齐 pandas DataFrame/Series 算术。
 *
 * @see DataFrame#colAdd(String, String, String) 等
 */
public final class DataFrameArith {

    private DataFrameArith() {}

    /** 列间加,结果存到新 DoubleColumn(对齐 Series + Series)。 */
    public static DoubleColumn add(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '+');
    }
    public static DoubleColumn sub(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '-');
    }
    public static DoubleColumn mul(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '*');
    }
    public static DoubleColumn div(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '/');
    }

    /**
     * 列 ± 标量,返回新 DoubleColumn(对齐 Series + scalar)。
     */
    public static DoubleColumn addScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '+');
    }
    public static DoubleColumn subScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '-');
    }
    public static DoubleColumn mulScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '*');
    }
    public static DoubleColumn divScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '/');
    }

    /** 算术内部:列间运算,NaN 传播。 */
    private static DoubleColumn applyOp(DataFrame df, String leftCol, String rightCol, char op) {
        Column a = df.getColumn(leftCol);
        Column b = df.getColumn(rightCol);
        requireNumeric(leftCol, a);
        requireNumeric(rightCol, b);
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("列长度不一致:" + leftCol + "=" + a.size()
                    + ", " + rightCol + "=" + b.size());
        }
        int n = a.size();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            if (a.isNull(i) || b.isNull(i)) { r[i] = Double.NaN; continue; }
            r[i] = apply(op, a.getDouble(i), b.getDouble(i));
        }
        return new DoubleColumn(null, r);
    }

    private static DoubleColumn applyScalar(DataFrame df, String col, double s, char op) {
        Column a = df.getColumn(col);
        requireNumeric(col, a);
        int n = a.size();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = a.isNull(i) ? Double.NaN : apply(op, a.getDouble(i), s);
        }
        return new DoubleColumn(null, r);
    }

    private static double apply(char op, double a, double b) {
        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> a / b;
            default -> throw new IllegalArgumentException("未知 op " + op);
        };
    }

    private static void requireNumeric(String name, Column c) {
        if (!c.dtype().isNumeric()) {
            throw new IllegalArgumentException("算术要求数值列,列 \"" + name + "\" 是 " + c.dtype());
        }
    }
}
