package jian.core;

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
 * <p><b>警告</b>:各实现的 {@code dataInPlace()} 直接暴露内部可变数组,
 * 仅限库内部热路径(如 DataFrameMerge 的 fast path)使用 —— 库外调用方一律视为
 * 只读视图,不得写元素,否则会静默破坏 DataFrame 的不可变承诺。
 *
 * @see DType 支持的列类型
 */
public interface Column {

    /**
     * 列的数据类型。
     * @return DType 枚举值(INT/LONG/DOUBLE/BOOL/STRING/DATE/DATETIME/CATEGORY/OBJECT 之一),永不为 null
     *         (jian v1 无 BYTE/FLOAT 这两种 dtype)
     */
    DType dtype();

    /**
     * 列名。
     * @return String 列名,非 null;未命名时为空串 ""
     */
    String name();

    /**
     * 改列名(变换后改名用,不可变优先 → 返回新 Column)。
     * @param newName String 新列名,非 null,允许空串(表示匿名列)
     * @return Column 同 dtype 同数据、仅 name 不同的**新实例**(本对象不变)
     */
    Column rename(String newName);

    /**
     * 行数(与所属 DataFrame 的 nRows 一致)。
     * @return int 行数,≥ 0;空列为 0
     */
    int size();

    // ======================== 取值(通用)========================

    /**
     * 取第 i 行的值(任意 dtype,统一装箱为 Object 返回)。
     * @param i int 行下标,范围 [0, size());越界抛 IndexOutOfBoundsException
     * @return Object 该行值:数值列返回 Long/Double 装箱、字符串列返回 String、缺失返回 null;
     *         特殊:DOUBLE 列的 NaN 返回 Double.NaN(不是 null)
     */
    Object get(int i);

    /**
     * 取第 i 行的 double 值(仅 DOUBLE/INT/LONG/CATEGORY 适用)。
     * @param i int 行下标,范围 [0, size());越界抛 IndexOutOfBoundsException
     * @return double 该行 double 值;缺失返回 Double.NaN
     * @throws IllegalStateException 该列 dtype 不支持 double 取值(如 STRING/BOOL/DATE)
     */
    double getDouble(int i);

    /**
     * 取第 i 行的 long 值(仅 LONG/INT/CATEGORY 适用)。
     * @param i int 行下标,范围 [0, size());越界抛 IndexOutOfBoundsException
     * @return long 该行 long 值;缺失列不应调用此方法(行为未定义)
     * @throws IllegalStateException 该列 dtype 不支持 long 取值(如 DOUBLE/STRING/BOOL/DATE)
     */
    long getLong(int i);

    /**
     * 取第 i 行是否缺失。
     * @param i int 行下标,范围 [0, size());越界抛 IndexOutOfBoundsException
     * @return boolean true=该行缺失;false=有值。
     *         缺失定义:有 nullMask 且对应位为 1;或 DOUBLE 列值为 NaN
     */
    boolean isNull(int i);

    // ======================== 缺失值统计 ========================

    /**
     * 缺失值个数(整列扫描)。
     * @return int 缺失行数,范围 [0, size()]
     */
    int nullCount();

    // ======================== 变换(返回新 Column)========================

    /**
     * 切片 [start, end)(对齐 pandas 行切片,左闭右开)。
     * @param start int 起始行下标(含),范围 [0, size()]
     * @param end   int 结束行下标(不含),范围 [start, size()]
     * @return Column 新实例,长度 = end - start,保留原 dtype/name
     * @throws IndexOutOfBoundsException start/end 越界或 start > end
     */
    Column slice(int start, int end);

    /**
     * 按布尔掩码筛选(保留 mask[i]==true 的行)。
     * @param mask boolean[] 掩码数组,长度必须 == size(),否则抛 IllegalArgumentException
     * @return Column 新实例,仅含 mask 为 true 的行;保留原 dtype/name
     */
    Column filter(boolean[] mask);

    /**
     * 按行下标选取(对齐 pandas take/iloc)。
     * @param indices int[] 行下标数组,每个元素范围 [0, size());允许重复、允许乱序;非 null
     * @return Column 新实例,长度 = indices.length,按 indices 顺序取行
     */
    Column take(int[] indices);

    /**
     * 复制(深拷贝内部数组,保证返回实例可独立修改)。
     * @return Column 新实例,数据与原列相同但内部数组是独立副本
     */
    Column copy();

    // ======================== 转数组(供 IO/export 用)========================

    /**
     * 转为 Object[](每元素装箱,供 IO/export 模块使用)。
     * @return Object[] 长度 == size() 的装箱数组:
     *         缺失统一为 null;DOUBLE 列的 NaN 也转 null(便于 JSON 等格式输出)
     */
    Object[] toObjectArray();
}
