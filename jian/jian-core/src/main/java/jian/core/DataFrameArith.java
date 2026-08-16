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

    /**
     * 列间加,结果存到新 DoubleColumn(对齐 Series + Series)。
     * @param df       DataFrame 目标表,非 null
     * @param leftCol  String 左列名,必须存在且数值类型;非 null
     * @param rightCol String 右列名,必须存在且数值类型;非 null
     * @return DoubleColumn 同长度新列(leftCol + rightCol,逐行);任一缺失行结果为 NaN
     */
    public static DoubleColumn add(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '+');
    }
    /**
     * 列间减(参数语义同 {@link #add})。
     * @return DoubleColumn leftCol - rightCol
     * @param df DataFrame 目标表;非 null
     * @param leftCol 参数;非 null
     * @param rightCol 参数;非 null
     */
    public static DoubleColumn sub(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '-');
    }
    /**
     * 列间乘(参数语义同 {@link #add})。
     * @param df       DataFrame 目标表,非 null
     * @param leftCol  String 左列名,数值类型;非 null
     * @param rightCol String 右列名,数值类型;非 null
     * @return DoubleColumn leftCol * rightCol
     */
    public static DoubleColumn mul(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '*');
    }
    /**
     * 列间除(参数语义同 {@link #add})。
     * @param df       DataFrame 目标表,非 null
     * @param leftCol  String 左列名,数值类型;非 null
     * @param rightCol String 右列名,数值类型;非 null
     * @return DoubleColumn leftCol / rightCol;除以 0 得 ±Infinity(IEEE 754)
     */
    public static DoubleColumn div(DataFrame df, String leftCol, String rightCol) {
        return applyOp(df, leftCol, rightCol, '/');
    }

    /**
     * 列 + 标量,返回新 DoubleColumn(对齐 Series + scalar)。
     * @param df  DataFrame 目标表,非 null
     * @param col String 列名,必须存在且数值类型;非 null
     * @param s   double 标量值
     * @return DoubleColumn 同长度新列(col + s);缺失行结果为 NaN
     */
    public static DoubleColumn addScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '+');
    }
    /**
     * 列 - 标量(参数语义同 {@link #addScalar})。
     * @param df DataFrame 目标表;非 null
     * @param col String 列名;非 null
     * @param s String 字符串
     */
    public static DoubleColumn subScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '-');
    }
    /**
     * 列 * 标量(参数语义同 {@link #addScalar})。
     * @param df DataFrame 目标表;非 null
     * @param col String 列名;非 null
     * @param s String 字符串
     */
    public static DoubleColumn mulScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '*');
    }
    /**
     * 列 / 标量(参数语义同 {@link #addScalar})。
     * @param df DataFrame 目标表;非 null
     * @param col String 列名;非 null
     * @param s String 字符串
     */
    public static DoubleColumn divScalar(DataFrame df, String col, double s) {
        return applyScalar(df, col, s, '/');
    }

    /**
     * 算术内部:列间运算,NaN 传播。
     * @param df       DataFrame 目标表
     * @param leftCol  String 左列名
     * @param rightCol String 右列名
     * @param op       char 运算符,取值 '+'/'-'/'*'/'/'
     * @return DoubleColumn 同长度结果列(null 列名,调用方负责 rename)
     * @throws IllegalArgumentException 列非数值,或两列长度不一致
     */
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
        // 算术结果列不用 null 名(null 名下游 toString NPE),给 "a+b" 形式(pandas 语义近似)
        return new DoubleColumn(leftCol + op + rightCol, r);
    }

    /**
     * 列与标量运算。
     * @param df  DataFrame 目标表
     * @param col String 列名
     * @param s   double 标量
     * @param op  char 运算符
     * @return DoubleColumn 同长度结果列
     */
    private static DoubleColumn applyScalar(DataFrame df, String col, double s, char op) {
        Column a = df.getColumn(col);
        requireNumeric(col, a);
        int n = a.size();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = a.isNull(i) ? Double.NaN : apply(op, a.getDouble(i), s);
        }
        // 标量算术结果列名 "a*2" 形式(null 名下游 toString NPE)
        return new DoubleColumn(col + op + formatScalar(s), r);
    }

    /** 标量列名格式化:整数值显示为整数(2.0 → 2),避免列名 "a*2.0" 的浮点尾。 */
    private static String formatScalar(double s) {
        return s == Math.rint(s) && !Double.isInfinite(s) ? String.valueOf((long) s) : String.valueOf(s);
    }

    /**
     * 单次运算。
     * @param op char 运算符 '+'/'-'/'*'/'/'
     * @param a  double 左操作数
     * @param b  double 右操作数
     * @return double 运算结果
     * @throws IllegalArgumentException op 不在四种支持范围内
     */
    private static double apply(char op, double a, double b) {
        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> a / b;
            default -> throw new IllegalArgumentException("未知 op " + op);
        };
    }

    /**
     * 校验列是数值类型。
     * @param name String 列名(用于错误消息)
     * @param c    Column 待校验列
     * @throws IllegalArgumentException 列 dtype 不是数值(INT/LONG/DOUBLE)
     */
    private static void requireNumeric(String name, Column c) {
        if (!c.dtype().isNumeric()) {
            throw new IllegalArgumentException("算术要求数值列,列 \"" + name + "\" 是 " + c.dtype());
        }
    }

    // ======================== 二元运算扩展(按 §3.1.1.1 内聚到此类)========================

    // ┌─ What : 整 DataFrame 元素级二元运算(对齐 pandas DataFrame.add/sub/mul/div/pow/mod)
    // │  Why  : 既有 add/sub/... 都是 colA 与 colB 列级运算;新增是整 df 与标量的逐列运算
    // │  How  : ① 对每个数值列 ② 用 scalar 与 colXxx 算子 ③ 返回新 DataFrame
    /**
     * DataFrame 与标量的逐列加法(对齐 pandas DataFrame.add(scalar))。
     * <p>对每个数值列产生新列 {col}_add;非数值列跳过。
     * @param df DataFrame 目标表,非 null
     * @param scalar double 标量
     * @return DataFrame 原列 + 各数值列 + "_add" 后缀的新列
     */
    public static DataFrame addScalarAllColumns(DataFrame df, double scalar) {
        return applyArithToAllColumns(df, "add", scalar);
    }
    /**
     * DataFrame 与标量的逐列减法。
     * @param df DataFrame 目标表;非 null
     * @param scalar double 标量
     */
    public static DataFrame subScalarAllColumns(DataFrame df, double scalar) {
        return applyArithToAllColumns(df, "sub", scalar);
    }
    /**
     * DataFrame 与标量的逐列乘法。
     * @param df DataFrame 目标表;非 null
     * @param scalar double 标量
     */
    public static DataFrame mulScalarAllColumns(DataFrame df, double scalar) {
        return applyArithToAllColumns(df, "mul", scalar);
    }
    /**
     * DataFrame 与标量的逐列除法。
     * @param df DataFrame 目标表;非 null
     * @param scalar double 标量
     */
    public static DataFrame divScalarAllColumns(DataFrame df, double scalar) {
        return applyArithToAllColumns(df, "div", scalar);
    }

    /** 通用工具:对 df 的每个数值列应用 op 与 scalar,产生新列。 */
    private static DataFrame applyArithToAllColumns(DataFrame df, String op, double scalar) {
        java.util.List<Column> newCols = new java.util.ArrayList<>();
        for (String name : df.columnNames()) {
            Column c = df.getColumn(name);
            newCols.add(c);
            if (c.dtype().isNumeric()) {
                String newName = name + "_" + op;
                double[] arr = new double[c.size()];
                for (int i = 0; i < c.size(); i++) {
                    if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                        arr[i] = Double.NaN;
                    } else {
                        double v = c.getDouble(i);
                        arr[i] = switch (op) {
                            case "add" -> v + scalar;
                            case "sub" -> v - scalar;
                            case "mul" -> v * scalar;
                            case "div" -> v / scalar;  // 除 0 自动得 Infinity/NaN(对齐 pandas)
                            default -> throw new IllegalArgumentException("未知 op:" + op);
                        };
                    }
                }
                newCols.add(new DoubleColumn(newName, arr));
            }
        }
        return DataFrame.ofColumnsDirect(newCols);
    }

    // ======================== 补全:dot/combine/combine_first/mode/abs/value_counts/nunique ========================

    /**
     * 矩阵乘法(对齐 pandas df.dot;需要 jian-num Matrix)。
     * <p>简化:两表都为单数值列时做点积;多列场景需 jian-num Matrix,留 v2。
     * @param left DataFrame 左表(需至少 1 数值列)
     * @param right DataFrame 右表(需至少 1 数值列;行数 == 左表行数)
     * @return double 点积结果(若两表都单列);多列暂抛 IAE
     */
    public static double dot(DataFrame left, DataFrame right) {
        // 找数值列
        String lc = findFirstNumeric(left);
        String rc = findFirstNumeric(right);
        if (lc == null || rc == null) throw new IllegalArgumentException("dot 需数值列");
        Column l = left.getColumn(lc);
        Column r = right.getColumn(rc);
        if (l.size() != r.size()) throw new IllegalArgumentException(
            "dot 长度不一致:" + l.size() + " vs " + r.size());
        double s = 0;
        for (int i = 0; i < l.size(); i++) {
            if (!l.isNull(i) && !r.isNull(i)) s += l.getDouble(i) * r.getDouble(i);
        }
        return s;
    }

    /**
     * 逐列取绝对值(对齐 pandas df.abs);返回新 DataFrame,数值列取 abs,非数值列原样。
     * 结果列保留原列名(pandas df.abs() 是同名替换;加 "_abs" 后缀会让下游按列名取值失败)。
     * @param df DataFrame 目标表;非 null
     */
    public static DataFrame abs(DataFrame df) {
        java.util.List<Column> newCols = new java.util.ArrayList<>();
        for (String c : df.columnNames()) {
            Column col = df.getColumn(c);
            if (col.dtype().isNumeric()) {
                double[] arr = new double[col.size()];
                for (int i = 0; i < col.size(); i++) {
                    arr[i] = (col.isNull(i) || Double.isNaN(col.getDouble(i)))
                        ? Double.NaN : Math.abs(col.getDouble(i));
                }
                newCols.add(new DoubleColumn(c, arr));
            } else {
                newCols.add(col);
            }
        }
        return DataFrame.ofColumnsDirect(newCols);
    }

    /**
     * combine_first(对齐 pandas df.combine_first):用 other 的非空值替换 self 对应位置的空值。
     * 同 dtype 列保留原 dtype(全降 OBJECT 会让后续聚合/astype 失败);
     * 类型不同时按 DType.promote 提升,无法提升才用 OBJECT。
     * @param self 参数;非 null
     * @param other Object 替换值(缺失行用)
     */
    public static DataFrame combineFirst(DataFrame self, DataFrame other) {
        java.util.List<Column> newCols = new java.util.ArrayList<>();
        for (String c : self.columnNames()) {
            Column sc = self.getColumn(c);
            if (other.columnIndex(c) < 0) { newCols.add(sc); continue; }
            Column oc = other.getColumn(c);
            Object[] arr = new Object[sc.size()];
            for (int i = 0; i < sc.size(); i++) {
                boolean selfMissing = sc.isNull(i);
                arr[i] = selfMissing ? oc.get(i) : sc.get(i);
            }
            DType dt;
            try { dt = DType.promote(sc.dtype(), oc.dtype()); }
            catch (IllegalArgumentException e) { dt = DType.OBJECT; }
            newCols.add(toTypedColumn(c, arr, dt));
        }
        return DataFrame.ofColumnsDirect(newCols);
    }

    /** Object[] → 指定 dtype 的 Column(combineFirst 用;不支持时回退 OBJECT)。 */
    private static Column toTypedColumn(String name, Object[] arr, DType dt) {
        int n = arr.length;
        switch (dt) {
            case DOUBLE: {
                double[] d = new double[n];
                for (int i = 0; i < n; i++) d[i] = arr[i] == null ? Double.NaN : ((Number) arr[i]).doubleValue();
                return new DoubleColumn(name, d);
            }
            case LONG: {
                long[] d = new long[n]; boolean[] m = new boolean[n];
                for (int i = 0; i < n; i++) { if (arr[i] == null) m[i] = true; else d[i] = ((Number) arr[i]).longValue(); }
                return new LongColumn(name, d, m);
            }
            case INT: {
                int[] d = new int[n]; boolean[] m = new boolean[n];
                for (int i = 0; i < n; i++) { if (arr[i] == null) m[i] = true; else d[i] = ((Number) arr[i]).intValue(); }
                return new IntColumn(name, d, m);
            }
            case STRING: {
                String[] d = new String[n];
                for (int i = 0; i < n; i++) d[i] = arr[i] == null ? null : arr[i].toString();
                return new StringColumn(name, d);
            }
            case BOOL: {
                boolean[] d = new boolean[n]; boolean[] m = new boolean[n];
                for (int i = 0; i < n; i++) { if (arr[i] == null) m[i] = true; else d[i] = (Boolean) arr[i]; }
                return new BoolColumn(name, d, m);
            }
            case DATETIME: {
                java.time.LocalDateTime[] d = new java.time.LocalDateTime[n];
                for (int i = 0; i < n; i++) d[i] = (java.time.LocalDateTime) arr[i];
                return new DateTimeColumn(name, d);
            }
            case DATE: {
                java.time.LocalDate[] d = new java.time.LocalDate[n];
                for (int i = 0; i < n; i++) d[i] = (java.time.LocalDate) arr[i];
                return new DateColumn(name, d);
            }
            default: return new ObjectColumn(name, arr);
        }
    }

    /** 找第一个数值列名;无返回 null。 */
    private static String findFirstNumeric(DataFrame df) {
        for (String c : df.columnNames()) {
            DType dt = df.getColumn(c).dtype();
            if (dt == DType.DOUBLE || dt == DType.LONG || dt == DType.INT) return c;
        }
        return null;
    }

    /**
     * 众数(对齐 pandas Series.mode);返回某列出现频次最高的值(可能有多个,取最先出现)。
     * <p>计数容器用 LinkedHashMap(保首次出现序)—— 因为 HashMap 迭代序由 hash 桶决定,
     * 并列众数的赢家不确定,违背"取第一个"的语义;所以并列时取首次出现的值
     * (严格大于才换,与 valueCounts 的 LinkedHashMap 口径一致)。
     * @param c Column 列;非 null
     */
    public static Object mode(Column c) {
        // 伪代码:LinkedHashMap 按首次出现序计数 → 一次遍历取最大计数(并列保先现)
        java.util.Map<Object, Integer> cnt = new java.util.LinkedHashMap<>();
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;
            // ±0.0 合并计数(pandas value_counts 单键 {0.0:2})
            cnt.merge(DataFrameStats.normUniqueKey(c.get(i)), 1, Integer::sum);
        }
        Object best = null; int bestCnt = 0;
        for (var e : cnt.entrySet()) {
            if (e.getValue() > bestCnt) { best = e.getKey(); bestCnt = e.getValue(); }
        }
        return best;
    }

    /**
     * 值计数(对齐 pandas Series.value_counts);返回某列各值 → 出现次数(降序)。
     * @param c Column 列;非 null
     */
    public static java.util.Map<Object, Integer> valueCounts(Column c) {
        java.util.Map<Object, Integer> cnt = new java.util.LinkedHashMap<>();
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;
            // ±0.0 合并计数(pandas value_counts 单键 {0.0:2})
            cnt.merge(DataFrameStats.normUniqueKey(c.get(i)), 1, Integer::sum);
        }
        // 按频次降序排
        java.util.List<java.util.Map.Entry<Object, Integer>> sorted = new java.util.ArrayList<>(cnt.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        java.util.Map<Object, Integer> out = new java.util.LinkedHashMap<>();
        for (var e : sorted) out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * 唯一值数(对齐 pandas Series.nunique);skip null/NaN。
     * @param c Column 列;非 null
     */
    public static int nunique(Column c) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (int i = 0; i < c.size(); i++) {
            // ±0.0 数值等价归一(同 DataFrameStats.normUniqueKey)
            if (!c.isNull(i)) seen.add(DataFrameStats.normUniqueKey(c.get(i)));
        }
        return seen.size();
    }
}
