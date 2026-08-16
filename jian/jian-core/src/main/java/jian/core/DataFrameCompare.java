package jian.core;

// ┌─ What : DataFrameCompare —— 列与常数的比较器(compare/cmp,从 DataFrame.java 拆出,§3.1 红线)
// │  Why  : compare+cmp+isIntegralNumber ~50 行,自包含(cmp 只被 compare 调);
// │         cmp 承载混型对齐 pandas、BigDecimal 精确比较、null/NaN 传播三大契约,独立便于守护。
// │  Who  : 由 DataFrame.compare 委托调用(colGt/colLt/colGe/colLe/colEq/colNe 经 compare)
// │  When : 任何列级比较(colGt 族 / query 底层)
// │  Where: jian-core/DataFrameCompare.java
// │  How  : 数据走向:df.requireColumn 取列 → 逐行 isNull 过滤(缺失行:==/顺序比较 false,!= true,见 compare)→ cmp(v, op, value) → BoolColumn 掩码。
// │         cmp 三段式:BigDecimal compareTo 精确 → Number(双整数 long / double)→ 同型 Comparable compareTo
// │           → 混型(== false / != true / 顺序抛 IAE,对齐 pandas TypeError)。
public final class DataFrameCompare {
    private DataFrameCompare() {}

    /**
     * 比较某列与常数(对齐 pandas Series > 调用),返回 BoolColumn 作 mask。
     *
     * @param df      DataFrame 目标表;非 null
     * @param colName String 列名(须数值或字符串);必须存在
     * @param op      String 运算符 {@code > < >= <= == !=}
     * @param value   Object 常数(Number 或 String)
     * @return BoolColumn 同长度掩码;缺失行:== 与顺序比较为 false、!= 为 true
     *         (对齐 pandas NaN!=x→True 与 query 双引擎,同库两入口语义一致)
     */
    static BoolColumn compare(DataFrame df, String colName, String op, Object value) {
        df.requireColumn(colName);
        Column c = df.getColumn(colName);
        int nRows = df.rowCount();
        boolean[] m = new boolean[nRows];
        for (int r = 0; r < nRows; r++) {
            // 用 isNull 判断缺失(get() 对 NaN 返回 Double.NaN 不是 null,§3.5)
            // 缺失行 != 为 true(与 cmp 内部 null 契约、query 双引擎、pandas 三方一致),
            // == 与顺序比较仍 false(NaN 传播)
            if (c.isNull(r)) { m[r] = "!=".equals(op); continue; }
            Object v = c.get(r);
            m[r] = cmp(v, op, value);
        }
        return new BoolColumn(colName, m, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean cmp(Object a, String op, Object b) {
        // ┌─ What : 通用比较器,处理 > < >= <= == != 六种运算符,支持 Number / 同型 Comparable / 混型
        // │  Why  : 三大契约(pandas 实测对齐):
        // │           ① 同型 Number → 数值比(±0.0 等价,NaN≠NaN,IEEE/pandas 一致)
        // │           ② 严格同型 Comparable → compareTo(String/LocalDateTime/BigDecimal 等)
        // │           ③ 混型 → == 恒 false / != 恒 true;> < >= <= 抛 IAE(对齐 pandas TypeError,
        // │              不做 String 字典序回落——那是未声明偏离)
        // │  How  : 四段式分支(BigDecimal 特化 → Number → 同型 Comparable → 混型/null);
        //          null → == false、!= true、顺序 false(NaN 传播,§10.16)。
        // BigDecimal 特化:compareTo 精确,避免 doubleValue 丢精度。
        if (a instanceof java.math.BigDecimal ba && b instanceof java.math.BigDecimal bb) {
            int c = ba.compareTo(bb);
            return switch (op) { case "==" -> c == 0; case "!=" -> c != 0;
                case ">" -> c > 0; case "<" -> c < 0; case ">=" -> c >= 0; case "<=" -> c <= 0;
                default -> throw new IllegalArgumentException("未知 op " + op); };
        }
        if (a instanceof Number na && b instanceof Number nb) {
            // 双整数用 long 精确比较(Long.MAX_VALUE 等大值不丢精度)
            if (isIntegralNumber(na) && isIntegralNumber(nb)) {
                long x = na.longValue(), y = nb.longValue();
                return switch (op) { case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y;
                    case "<=" -> x <= y; case "==" -> x == y; case "!=" -> x != y;
                    default -> throw new IllegalArgumentException("未知 op " + op); };
            }
            // 一方 BigDecimal/BigInteger:统一转 BigDecimal 精确比(修复:原先 BigDecimal×Long/Double
            // 落 doubleValue 路径丢精度;BigInteger 同理 —— 值 > 2^53 时 doubleValue 折叠;
            // BigDecimal.valueOf(long) 精确、new BigDecimal(double) 是该 double 的精确二进制值,
            // new BigDecimal(BigInteger) 天然精确,compareTo 结果数学正确)
            if (a instanceof java.math.BigDecimal || b instanceof java.math.BigDecimal
                    || a instanceof java.math.BigInteger || b instanceof java.math.BigInteger) {
                int c = toBigDecimal(a).compareTo(toBigDecimal(b));
                return switch (op) { case "==" -> c == 0; case "!=" -> c != 0;
                    case ">" -> c > 0; case "<" -> c < 0; case ">=" -> c >= 0; case "<=" -> c <= 0;
                    default -> throw new IllegalArgumentException("未知 op " + op); };
            }
            // 整数家族 × 浮点混型:精确比较(修复:原先 doubleValue 直比,Long > 2^53 与
            // 相邻 Double 误判相等,pandas 同输入 Python 精确 int 比较不误判)
            if (isIntegralNumber(na) != isIntegralNumber(nb)) {
                boolean aInt = isIntegralNumber(na);
                long l = (aInt ? na : nb).longValue();
                double d = (aInt ? nb : na).doubleValue();
                if (Double.isNaN(d)) {
                    // NaN 无序:== false、!= true、顺序比较 false(NaN 传播,与双浮点路径一致)
                    return switch (op) { case "==" -> false; case "!=" -> true; default -> false; };
                }
                // compareLongVsDouble 统一按"整数侧 vs 浮点侧"给符号;
                // a 为浮点侧时须取反,保证符号表达的是 a op b
                int c = aInt ? compareLongVsDouble(l, d) : -compareLongVsDouble(l, d);
                return switch (op) { case "==" -> c == 0; case "!=" -> c != 0;
                    case ">" -> c > 0; case "<" -> c < 0; case ">=" -> c >= 0; case "<=" -> c <= 0;
                    default -> throw new IllegalArgumentException("未知 op " + op); };
            }
            double x = na.doubleValue(), y = nb.doubleValue();
            return switch (op) { case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y;
                case "<=" -> x <= y; case "==" -> x == y; case "!=" -> x != y;
                default -> throw new IllegalArgumentException("未知 op " + op); };
        }
        // 同型且同为 Comparable → 用 T 的 compareTo(此时 b 必同型,不会 CCE)
        if (a != null && b != null && a.getClass() == b.getClass() && a instanceof Comparable ca) {
            int c = ((Comparable<Object>) ca).compareTo(b);
            return switch (op) { case "==" -> c == 0; case "!=" -> c != 0;
                case ">" -> c > 0; case "<" -> c < 0; case ">=" -> c >= 0; case "<=" -> c <= 0;
                default -> throw new IllegalArgumentException("未知 op " + op); };
        }
        // 混型 / 不可比(含 null),对齐 pandas 1.5.3:
        //   null(缺失) → == false、!= true、顺序 false(NaN 传播);
        //   非空混型 → == false、!= true;> < >= <= 抛 IAE(对齐 TypeError)
        if (a == null || b == null) {
            return switch (op) { case "==" -> false; case "!=" -> true; default -> false; };
        }
        return switch (op) {
            case "==" -> false;
            case "!=" -> true;
            default -> throw new IllegalArgumentException(
                "不支持 " + a.getClass().getSimpleName() + " 与 " + b.getClass().getSimpleName()
                + " 的 '" + op + "' 顺序比较(混型);请先统一类型(对齐 pandas TypeError)");
        };
    }

    // ┌─ What : 三个公共判定(从 SimpleQueryParser/PrattEngine 收敛的同体重复)
    // │  Why  : 全仓扫描发现 valueEquals/isIntegralNumber/isCmpOp 在 core 兜底解析器与
    // │         jian-dsl Pratt 引擎各有一份**完全相同**的实现 —— 按 §3.1.1.1 内聚到比较主题类
    /**
     * 是否整数家族(Long/Integer/Short/Byte;双整数比较走 long 精确路径)。
     * @param n Number 待判定数值,非 null
     * @return boolean true = 整数家族
     */
    public static boolean isIntegralNumber(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }

    /**
     * 跨数值类型相等比较(Long 30 与 Double 30.0 视为相等;对齐 pandas 元素级相等)。
     * <p>精度契约(修复版):双整数走 long 精确;整数×浮点走 {@link #compareLongVsDouble}
     * 精确判定(浮点侧必须是数学整数值且等于 long 才相等,Long &gt; 2^53 与相邻 Double 不再误判);
     * BigDecimal 参与时统一 compareTo;双浮点维持 IEEE(NaN==NaN → false)。
     * @param a Object 左值,非 null
     * @param b Object 右值,非 null
     * @return boolean true = 相等
     */
    public static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (isIntegralNumber(na) && isIntegralNumber(nb)) {
                return na.longValue() == nb.longValue();
            }
            if (na instanceof java.math.BigDecimal || nb instanceof java.math.BigDecimal
                    || na instanceof java.math.BigInteger || nb instanceof java.math.BigInteger) {
                return toBigDecimal(na).compareTo(toBigDecimal(nb)) == 0;
            }
            if (isIntegralNumber(na) != isIntegralNumber(nb)) {
                boolean aInt = isIntegralNumber(na);
                long l = (aInt ? na : nb).longValue();
                double d = (aInt ? nb : na).doubleValue();
                if (Double.isNaN(d)) return false;   // NaN 与任何值不等(IEEE)
                return compareLongVsDouble(l, d) == 0;
            }
            return na.doubleValue() == nb.doubleValue();   // 双浮点(NaN==NaN → false,IEEE)
        }
        return a.equals(b);
    }

    /**
     * 是否比较运算符(&gt; &lt; &gt;= &lt;= == !=)。
     * @param s String 运算符文本,非 null
     * @return boolean true = 比较运算符
     */
    public static boolean isCmpOp(String s) {
        return s.equals(">") || s.equals("<") || s.equals(">=") || s.equals("<=")
                || s.equals("==") || s.equals("!=");
    }

    // ┌─ What : long 与 double 的精确大小比较(返回负/零/正,Java compare 风格)
    // │  Why  : 混型数值比较若直接 doubleValue 会把 > 2^53 的不同 long 折叠成同一 double
    // │         (9007199254740993 与 9007199254740992 都映射 9007199254740992.0),
    // │         == / in 谓词随之误匹配;pandas(Python 任意精度 int)同输入不误判。
    // │  How  : 数据走向:有限 y 先按 ±2^63 边界裁剪(域外直接定符号)→ 域内截断取
    // │         (long) y 与 x 比 long → 相等时再看 y 是否恰为该整数值(截断相等但
    // │         带小数则按 y 相对 yi 的方向)。NaN/无穷由调用方前置处理,不进本函数
    // │         的"有限"前提;无穷在入口短路(x 永远小于 +∞、大于 -∞)。
    // │  关键变量变化:x(原始 long)与 yi=y 截断整数:yi≠x → Long.compare;
    // │         yi==x 且 y==yi → 0(数学整数相等);yi==x 且 y≠yi → y 带小数,
    // │         y>yi(正数截断)→ x<y 返 -1;y<yi(负数向零截断)→ x>y 返 1。
    /**
     * long 与有限 double 的精确比较(调用方须先排 NaN;无穷在此短路)。
     * <p><b>边界说明(IEEE 754 固有,非实现缺陷)</b>:53 位尾数下 2^63-1(Long.MAX_VALUE)
     * 与 2^63 共用同一 double 表示 9.223372036854776E18,因此
     * {@code cmp(Long.MAX_VALUE, "==", (double) Long.MAX_VALUE)} 为 <b>false</b>
     * (2^63-1 &lt; 2^63,严格数学不等;Python 任意精度 int 与 float 的精确比较同此结论)。
     * 大整数(&gt; 2^53)跨类型比较请改用 BigInteger/BigDecimal 参与(走 BigDecimal 精确分支)。
     * @param x long 整数侧
     * @param y double 浮点侧(NaN 不允许;±∞ 会正确短路)
     * @return int 负=x&lt;y,0=相等(数学意义上),正=x&gt;y
     */
    static int compareLongVsDouble(long x, double y) {
        if (y == Double.POSITIVE_INFINITY) return -1;
        if (y == Double.NEGATIVE_INFINITY) return 1;
        if (y >= 9.223372036854775808E18) return -1;   // y ≥ 2^63,超出 long 域,x 必小
        if (y < -9.223372036854775808E18) return 1;    // y < -2^63,超出 long 域,x 必大
        long yi = (long) y;                              // 域内截断(向零)
        if (yi != x) return Long.compare(x, yi);
        return y == yi ? 0 : (y > yi ? -1 : 1);         // 截断相等:恰为整数值则等,否则按小数方向
    }

    /**
     * 任意 Number 转 BigDecimal(整数家族 valueOf(long) 精确;BigInteger 天然精确;
     * 浮点取其精确二进制值)。
     * @param o Object 须为 Number,非 null
     * @return BigDecimal 可精确 compareTo 的等价表示
     */
    private static java.math.BigDecimal toBigDecimal(Object o) {
        if (o instanceof java.math.BigDecimal bd) return bd;
        if (o instanceof java.math.BigInteger bi) return new java.math.BigDecimal(bi);
        if (isIntegralNumber((Number) o)) {
            return java.math.BigDecimal.valueOf(((Number) o).longValue());
        }
        return new java.math.BigDecimal(((Number) o).doubleValue());
    }
}
