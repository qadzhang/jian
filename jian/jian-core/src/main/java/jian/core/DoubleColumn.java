package jian.core;

import java.util.Arrays;

// ┌─ What : DoubleColumn —— 64 位浮点列实现(Column 子类,DType.DOUBLE)
// │  Why  : 规范 01 §2.1;对齐 pandas float64,**缺失值用 NaN 表示**(与 pandas 一致),故无需 nullMask 位图
// │  Who  : DataFrame.columns 持有;IO/merge/Stats 读取;DataFrameMerge 单 double key fast path 走 dataInPlace()
// │  When : DataFrame 构造/查询/算术/统计
// │  Where: jian-core/DoubleColumn.java
// │  How  : 数据走向:外部 double[] → 构造(默认 clone)→ 变换返回新 DoubleColumn。
// │         关键变量变化:
// │           - data:double[] 主体(永不 null);
// │           - 缺失值:在 data 内用 Double.NaN 表示(无 nullMask,省内存)。
// │         逻辑路线:
// │           构造 A(public 两参)→ clone data;
// │           构造 B(private + noCopy)→ 直接引用(hot path);
// │           构造 C(wrapNoCopy)→ 暴露给性能敏感内部调用;
// │           isNull/get/nullCount 全靠 Double.isNaN 判定。
/**
 * 64 位浮点列,存 double[];**缺失值用 NaN 表示**(对齐 pandas float64,规范 01 §2.1)。
 * <p><b>不可变</b>:变换返回新 DoubleColumn(规范 §4.3)。
 */
public final class DoubleColumn implements Column {

    private final String name;
    final double[] data;

    /**
     * 公开构造(默认拷贝)。
     * @param name String 列名,非 null
     * @param data double[] 主体,非 null;会被 clone;缺失值需用 Double.NaN 表示
     */
    public DoubleColumn(String name, double[] data) {
        this.name = name;
        this.data = data.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用(调用方保证此后不修改入参);false=clone
     */
    private DoubleColumn(String name, double[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    /**
     * 零拷贝构造(高性能内部 API)。
     * @param name String 列名
     * @param data double[] 主体,**调用方此后不得修改**;非 null
     * @return DoubleColumn 直接引用入参数组的新实例
     */
    public static DoubleColumn wrapNoCopy(String name, double[] data) {
        return new DoubleColumn(name, data, true);
    }

    /**
     * @return double[] data 的克隆副本,长度 == size();修改副本不影响本列
     */
    public double[] data() { return data.clone(); }

    /**
     * @return double[] 内部 data 的直接引用,**不得修改**;仅供 hot path
     */
    public double[] dataInPlace() { return data; }

    /** @return DType.DOUBLE(恒定) */
    @Override public DType dtype() { return DType.DOUBLE; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /** @return Column 改名后的新实例(noCopy) */
    @Override public Column rename(String newName) { return new DoubleColumn(newName, data, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }

    /**
     * 取第 i 行的值。
     *
     * <p><b>NaN 不再返回 null</b>(2026-08-08 修复):
     * 之前 NaN 在 get() 中被转换为 null,导致下游传递丢失"这是 NaN(计算产生的非数)
     * 而非缺失"的语义。现在 get() 对 NaN 返回 Double.NaN 对象,与 getDouble() 行为一致。
     * <ul>
     *   <li><b>内部计算/传递</b>:NaN 透传为 Double.NaN,不失真</li>
     *   <li><b>IO 边界</b>:{@link #toObjectArray()} 把 NaN 转为 null(因为 JSON/CSV 不支持 NaN);
     *       读入时 null 再转回 NaN。边界处理集中在 IO 层</li>
     *   <li><b>isNull()</b>:NaN 仍视为缺失(返回 true),与 pandas 一致</li>
     * </ul>
     * @param i int 行下标 ∈ [0, size())
     * @return Object Double(NaN 时返回 Double.NaN 对象,不再返回 null)
     */
    @Override public Object get(int i) {
        return data[i];   // NaN 原样返回(double 装箱为 Double.NaN),不再转 null
    }
    /**
     * @param i int 行下标
     * @return double data[i] 原值(NaN 即缺失,不额外转换)
     */
    @Override public double getDouble(int i) { return data[i]; }
    /**
     * @param i int 行下标
     * @return long (long) data[i]。**缺失行(NaN)返回 Long.MIN_VALUE** 作为缺失标记,
     *         下游可用 `== Long.MIN_VALUE` 或 `isNull(i)` 识别。不抛异常——保留缺失状态供后续处理。
     */
    @Override public long getLong(int i) {
        if (Double.isNaN(data[i])) return Long.MIN_VALUE;
        return (long) data[i];
    }
    /**
     * @param i int 行下标
     * @return boolean true=data[i] 是 NaN(缺失);false=有值
     */
    @Override public boolean isNull(int i) { return Double.isNaN(data[i]); }

    /**
     * @return int NaN 个数 ∈ [0, size()]
     */
    @Override public int nullCount() {
        int c = 0;
        for (double v : data) if (Double.isNaN(v)) c++;
        return c;
    }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 DoubleColumn,长度 = end-start
     */
    @Override public Column slice(int start, int end) {
        return new DoubleColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 DoubleColumn
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        double[] out = new double[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new DoubleColumn(name, out, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 DoubleColumn
     */
    @Override public Column take(int[] indices) {
        double[] out = new double[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new DoubleColumn(name, out, true);
    }

    /** @return Column 深拷贝 */
    @Override public Column copy() { return new DoubleColumn(name, data); }

    /**
     * @return Object[] 长度 == size();NaN 转 null(便于 JSON/CSV 等输出),非 NaN 为 Double
     */
    @Override public Object[] toObjectArray() {
        Object[] o = new Object[data.length];
        for (int i = 0; i < data.length; i++) o[i] = Double.isNaN(data[i]) ? null : data[i];
        return o;
    }

    /** @return String "DoubleColumn[name, len=N]" */
    @Override public String toString() {
        return "DoubleColumn[" + name + ", len=" + data.length + "]";
    }
}
