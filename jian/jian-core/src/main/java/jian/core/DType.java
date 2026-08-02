package jian.core;

// ┌─ What : DType —— DataFrame 列的数据类型枚举(对齐 pandas dtype + 规范 01 §2.1)
// │  Why  : jian-core 不依赖 jian-num(规范 §4.1),所以 core 有自己的 DType;语义与 jian-num.DType 对齐
// │  Who  : 由 Column 子类持有;Schema 推断/类型转换时引用
// │  When : 创建列、类型推断、astype、跨列运算
// │  Where: jian-core/DType.java
// │  How  : 数据走向:外部数据 → Schema.infer 推断 DType → 选对应 Column 子类存储 → 运算/IO 时按 DType 分发。
// │         关键变量变化:无(纯枚举)。
// │         逻辑路线:
// │           路径 A(数值运算)→ INT/LONG/DOUBLE 参与,向上提升 INT→LONG→DOUBLE;
// │           路径 B(逻辑运算)→ BOOL 参与 and/or/not;
// │           路径 C(字符串/日期)→ 各有专属操作,不参与数值运算;
// │           路径 D(OBJECT 兜底)→ 任意引用,二进制/嵌套/未知类型。
/**
 * DataFrame 列的数据类型,对齐 pandas dtype 体系(规范 01 §2.1)。
 *
 * <p>9 种 dtype,覆盖 pandas 主流列类型:
 * <table>
 *   <tr><th>DType</th><th>内部存储</th><th>缺失值</th><th>对齐 pandas</th></tr>
 *   <tr><td>{@link #INT}</td><td>int[] + null 标记位</td><td>null(用 nullMask 位图)</td><td>Int32</td></tr>
 *   <tr><td>{@link #LONG}</td><td>long[] + null 标记位</td><td>null</td><td>Int64</td></tr>
 *   <tr><td>{@link #DOUBLE}</td><td>double[]</td><td>NaN</td><td>float64</td></tr>
 *   <tr><td>{@link #BOOL}</td><td>boolean[] + null 标记位</td><td>null</td><td>boolean</td></tr>
 *   <tr><td>{@link #STRING}</td><td>String[]</td><td>null</td><td>object(str)</td></tr>
 *   <tr><td>{@link #DATETIME}</td><td>LocalDateTime[]</td><td>null</td><td>datetime64</td></tr>
 *   <tr><td>{@link #DATE}</td><td>LocalDate[]</td><td>null</td><td>datetime64[日期]</td></tr>
 *   <tr><td>{@link #CATEGORY}</td><td>int[](码) + 值表</td><td>-1 码</td><td>category</td></tr>
 *   <tr><td>{@link #OBJECT}</td><td>Object[]</td><td>null</td><td>object</td></tr>
 * </table>
 *
 * <p><b>设计要点</b>(与 jian-num.DType 对齐,因 core 不依赖 num):
 * <ul>
 *   <li>整数独立(INT/LONG)保留精度,大整数/ID 不丢精度;</li>
 *   <li>字符串独立(STRING),与 OBJECT 区分(STRING 是高频优化路径,OBJECT 是兜底);</li>
 *   <li>日期分 DATETIME/DATE 两类,对齐 pandas datetime64 与 date 列。</li>
 * </ul>
 */
public enum DType {
    /** 32 位整数。存 int[] + null 位图(原生 int 不能表 null)。 */
    INT,
    /** 64 位整数。存 long[] + null 位图。订单号/大 ID 用此,避免转 double 失真。 */
    LONG,
    /** 64 位浮点。存 double[],缺失用 NaN(与 pandas 一致)。 */
    DOUBLE,
    /** 布尔。存 boolean[] + null 位图。 */
    BOOL,
    /** 字符串。存 String[],缺失 null。高频优化列。 */
    STRING,
    /** 日期时间。存 LocalDateTime[],缺失 null。 */
    DATETIME,
    /** 日期(无时间)。存 LocalDate[],缺失 null。 */
    DATE,
    /** 分类(有限离散值)。存 int[](码) + 值表,缺失 -1。对齐 pandas category。 */
    CATEGORY,
    /** 任意引用兜底。存 Object[](byte[]/嵌套/未知类型)。缺失 null。 */
    OBJECT;

    /** 是否整数类型(INT/LONG)。 */
    public boolean isInt() { return this == INT || this == LONG; }

    /** 是否数值类型(INT/LONG/DOUBLE)。 */
    public boolean isNumeric() { return this == INT || this == LONG || this == DOUBLE; }

    /** 是否浮点。 */
    public boolean isFloat() { return this == DOUBLE; }

    /** 是否时间类型(DATETIME/DATE)。 */
    public boolean isTemporal() { return this == DATETIME || this == DATE; }

    /**
     * 数值类型向上提升(对齐 numpy promotion):
     * INT+INT→INT, INT+LONG→LONG, 任意数值+DOUBLE→DOUBLE;同类型→自身;
     * 非数值混合或类型不兼容 → 抛 IllegalArgumentException。
     */
    public static DType promote(DType a, DType b) {
        if (a == b) return a;
        if (a == OBJECT || b == OBJECT) return OBJECT;  // OBJECT 兜底
        if (a.isNumeric() && b.isNumeric()) {
            if (a == DOUBLE || b == DOUBLE) return DOUBLE;
            if (a == LONG || b == LONG) return LONG;
            return INT;
        }
        throw new IllegalArgumentException(
                "无法提升类型 " + a + " 与 " + b + "(类型不兼容,如 BOOL/STRING/DATETIME 混合)");
    }
}
