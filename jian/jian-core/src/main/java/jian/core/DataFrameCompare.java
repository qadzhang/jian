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
     * @param a Object 左值,非 null
     * @param b Object 右值,非 null
     * @return boolean true = 相等
     */
    public static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
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
}
