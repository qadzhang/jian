package jian.core;

import java.util.function.IntPredicate;

// ┌─ What : Column —— DataFrame 一列数据的抽象接口(规范 01 §2.1)
// │  Why  : DataFrame 列式存储,每列一个 Column 实现;多 dtype 各有子类,统一接口供 DataFrame 调度
// │  Who  : DataFrame 内部 List<Column> 持有;io/viz/export 通过 DataFrame.getColumn 访问
// │  When : DataFrame 的所有变换(选择/过滤/统计/apply)都按列分发
// │  Where: jian-core/Column.java
// │  How  : 数据走向:外部数据 → Column 子类内部数组存储 → 变换返回新 Column → DataFrame 重组。
// │         关键变量变化:
// │           - dtype:固定不变(astype 才换实现);
// │           - size:行数,与 DataFrame 行数一致;
// │           - nullMask:位图(可选),记录哪些行为缺失。
// │         逻辑路线:
// │           路径 A(数值列 DoubleColumn)→ getDouble/getInt 直接取;
// │           路径 B(字符串列 StringColumn)→ getObject 取 String;
// │           路径 C(类型不匹配取值)→ 抛 ClassCastException 或 IllegalStateException。
/**
 * DataFrame 的一列,各 dtype 各有实现({@link DoubleColumn}/{@link LongColumn}/{@link StringColumn}...)。
 *
 * <p><b>不可变优先</b>(规范 §4.3):变换返回新 Column,不修改自身。
 *
 * @see DType 支持的列类型
 */
public interface Column {

    /** 列的数据类型。 */
    DType dtype();

    /** 列名。 */
    String name();

    /** 列名(变换后改名用)。 */
    Column rename(String newName);

    /** 行数。 */
    int size();

    // ======================== 取值(通用)========================

    /** 取第 i 行的值(任意 dtype,装箱返回)。缺失返回 null(NaN 列返回 Double.NaN)。 */
    Object get(int i);

    /** 取第 i 行的 double 值(仅 DOUBLE/INT/LONG/CATEGORY;其它抛异常)。NaN 表示缺失。 */
    double getDouble(int i);

    /** 取第 i 行的 long 值(仅 LONG/INT/CATEGORY;其它抛异常)。 */
    long getLong(int i);

    /** 取第 i 行是否缺失。 */
    boolean isNull(int i);

    // ======================== 缺失值统计 ========================

    /** 缺失值个数。 */
    int nullCount();

    // ======================== 变换(返回新 Column)========================

    /** 切片 [start, end)(对齐 pandas 行切片)。 */
    Column slice(int start, int end);

    /** 按布尔掩码筛选(保留 mask[i]==true 的行)。 */
    Column filter(boolean[] mask);

    /** 按行下标选取(对齐 pandas take/iloc)。 */
    Column take(int[] indices);

    /** 复制(深拷贝)。 */
    Column copy();

    // ======================== 转数组(供 IO/export 用)========================

    /** 转为 Object[](每元素装箱,缺失为 null;DOUBLE 列 NaN 也转 null)。 */
    Object[] toObjectArray();
}
