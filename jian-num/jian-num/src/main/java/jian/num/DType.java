package jian.num;

// ┌─ What : DType —— Ndarray 支持的数据类型枚举(对齐 numpy dtype 的统计实用子集)
// │  Why  : 规范升级后 Ndarray 是多 dtype 引擎,需要显式类型标记,决定内部存储与运算行为
// │  Who  : 由 Ndarray 构造/推断时持有;被 jian-core 的 Column 映射(IntColumn→INT64, DoubleColumn→FLOAT64...)
// │  When : 创建 Ndarray、类型推断、跨 dtype 运算时
// │  Where: jian-num/DType.java
// │  How  : 关键变量变化:无(纯枚举)。
// │         逻辑路线:
// │           路径 A(数值运算)→ INT64 / FLOAT64 参与,INT64⊕FLOAT64 → FLOAT64(向上转型);
// │           路径 B(逻辑运算)→ BOOL 参与 and/or/not;
// │           路径 C(日期运算)→ DATETIME64 支持与 INT64(秒数)加减;
// │           路径 D(OBJECT)→ 不参与算术,仅支持字符串专属操作 / 相等比较。
/**
 * Ndarray 支持的数据类型,对标 numpy dtype 的统计实用子集。
 *
 * <p>选型理由(规范 06 §1.3 + 用户确认"统计实用集"):
 * <ul>
 *   <li>{@link #INT64} —— 整数独立保留精度(避免大整数转 double 失真,如订单号/ID);</li>
 *   <li>{@link #FLOAT64} —— 浮点主力,缺失用 NaN;</li>
 *   <li>{@link #BOOL} —— 布尔,用于掩码/条件;</li>
 *   <li>{@link #DATETIME64} —— 日期时间,内部 epoch 秒(long),支持时间运算;</li>
 *   <li>{@link #OBJECT} —— 任意引用兜底,字符串/长文本/二进制/嵌套结构都用它。
 *       <b>字符串是最高频</b>,提供 {@link StrOps} 便捷方法。</li>
 * </ul>
 */
public enum DType {
    /** 64 位整数。存储 long[]。缺失值:Long 装箱 null(详见 Ndarray 内部约定)。 */
    INT64,

    /** 64 位浮点。存储 double[]。缺失值:NaN(与 pandas/numpy 一致)。 */
    FLOAT64,

    /** 布尔。存储 boolean[](紧凑)或 Boolean[](允许 null)。 */
    BOOL,

    /**
     * 日期时间(对齐 numpy datetime64)。内部用 long[] 存 epoch 秒(UTC)。
     * 缺失值:Long.MIN_VALUE 标记(null)。
     */
    DATETIME64,

    /**
     * 任意引用(对齐 numpy object dtype)。存储 Object[]。
     * 字符串(含 10M 长文本)、二进制(byte[])、嵌套对象都用此 dtype。
     * 缺失值:Java null。
     */
    OBJECT;

    /** 是否数值类型(INT64/FLOAT64)。 */
    public boolean isNumeric() { return this == INT64 || this == FLOAT64; }

    /** 是否浮点。 */
    public boolean isFloat() { return this == FLOAT64; }

    /** 是否整数。 */
    public boolean isInt() { return this == INT64; }

    /**
     * 数值类型向上转型规则(对齐 numpy promotion):
     * INT64 + FLOAT64 → FLOAT64;同类型 → 同类型;非数值 → 抛异常。
     */
    public static DType promote(DType a, DType b) {
        if (a == b) return a;
        if (a.isNumeric() && b.isNumeric()) return FLOAT64;  // 整数+浮点 → 浮点
        throw new IllegalArgumentException(
                "无法提升类型 " + a + " 与 " + b + "(至少一个非数值,或 BOOL/DATETIME64/OBJECT 混合)");
    }
}
