package jian.core;

import java.util.Arrays;

// ┌─ What : LongColumn —— 64 位整数列实现(Column 子类,DType.LONG)
// │  Why  : 规范 01 §2.1;订单号/大 ID 用 long 避免转 double 失真;原生 long 不能表 null,故 nullMask 位图记缺失
// │  Who  : DataFrame.columns 持有;IO/merge/Stats 读取;DataFrameMerge 单 long key fast path 走 dataInPlace()
// │  When : DataFrame 构造/查询/算术/JOIN key
// │  Where: jian-core/LongColumn.java
// │  How  : 数据走向:外部 long[] + 可选 nullMask → 构造(默认 clone)→ 变换返回新 LongColumn。
// │         关键变量变化:
// │           - data:long[] 主体(永不 null);
// │           - nullMask:缺失位图,null 表示全非空,非 null 时长度 == data.length。
// │         逻辑路线(三构造 + 三变换):
// │           构造 A(两参)→ 全非空;
// │           构造 B(三参 public)→ clone 双数组;
// │           构造 C(wrapNoCopy)→ 直接引用(hot path);
// │           变换 slice/filter/take 恒返回 noCopy 新实例。
/**
 * 64 位整数列,存 long[] + 可选 nullMask 位图(规范 01 §2.1)。
 * <p><b>不可变</b>:变换返回新 LongColumn(规范 §4.3)。
 */
public final class LongColumn implements Column {

    private final String name;
    final long[] data;
    final boolean[] nullMask;  // true = 缺失;null 表示全非空(常见优化)

    /**
     * 构造(无缺失值,全非空)。
     * @param name String 列名,非 null
     * @param data long[] 主体,非 null;会被 clone
     */
    public LongColumn(String name, long[] data) {
        this.name = name;
        this.data = data.clone();
        this.nullMask = null;
    }

    /**
     * 构造(带缺失位图)。
     * @param name     String 列名,非 null
     * @param data     long[] 主体,非 null;会被 clone
     * @param nullMask boolean[] 缺失位图;null 表示全非空;非 null 时长度需 == data.length,会被 clone
     */
    public LongColumn(String name, long[] data, boolean[] nullMask) {
        this.name = name;
        this.data = data.clone();
        this.nullMask = nullMask == null ? null : nullMask.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用(调用方保证此后不修改入参);false=clone
     */
    private LongColumn(String name, long[] data, boolean[] nullMask, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
        this.nullMask = nullMask;
    }

    /**
     * 零拷贝构造(高性能内部 API,供 DataFrame.ofColumnArrays / merge fast path 使用)。
     * @param name     String 列名
     * @param data     long[] 主体,**调用方此后不得修改**;非 null
     * @param nullMask boolean[] 缺失位图;允许 null
     * @return LongColumn 直接引用入参数组的新实例
     */
    public static LongColumn wrapNoCopy(String name, long[] data, boolean[] nullMask) {
        return new LongColumn(name, data, nullMask, true);
    }

    /**
     * @return long[] data 的克隆副本,长度 == size();修改副本不影响本列
     */
    public long[] data() { return data.clone(); }

    /**
     * @return long[] 内部 data 的直接引用,**不得修改**;仅供 DataFrameMerge/GroupBy/Stats hot path
     */
    public long[] dataInPlace() { return data; }

    /**
     * @return boolean[] nullMask 克隆副本;null 表示全非空
     */
    public boolean[] nullMask() { return nullMask == null ? null : nullMask.clone(); }

    /** @return DType.LONG(恒定) */
    @Override public DType dtype() { return DType.LONG; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /** @return Column 改名后的新实例(noCopy,共享引用) */
    @Override public Column rename(String newName) { return new LongColumn(newName, data, nullMask, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }

    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object Long(缺失返回 null)
     */
    @Override public Object get(int i) {
        return isNull(i) ? null : data[i];
    }
    /**
     * @param i int 行下标
     * @return double (double) data[i]。**缺失行返回 NaN**(不是垃圾值)。
     *         注意:long→double 超 2^53 会丢精度,大整数列请改用 getLong()
     */
    @Override public double getDouble(int i) {
        if (isNull(i)) return Double.NaN;
        return (double) data[i];
    }
    /**
     * @param i int 行下标
     * @return long data[i] 原值。**缺失行返回 Long.MIN_VALUE**(缺失标记,不抛异常)。
     */
    @Override public long getLong(int i) {
        if (isNull(i)) return Long.MIN_VALUE;
        return data[i];
    }
    /**
     * @param i int 行下标
     * @return boolean true=缺失;false=有值
     */
    @Override public boolean isNull(int i) { return nullMask != null && nullMask[i]; }

    /**
     * @return int 缺失数 ∈ [0, size()];nullMask==null 时为 0
     */
    @Override public int nullCount() {
        if (nullMask == null) return 0;
        int c = 0;
        for (boolean m : nullMask) if (m) c++;
        return c;
    }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 LongColumn,长度 = end-start
     */
    @Override public Column slice(int start, int end) {
        long[] d = Arrays.copyOfRange(data, start, end);
        boolean[] m = nullMask == null ? null : Arrays.copyOfRange(nullMask, start, end);
        return new LongColumn(name, d, m, true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 LongColumn
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        long[] d = new long[n];
        boolean[] mOut = nullMask == null ? null : new boolean[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (mask[i]) {
                d[j] = data[i];
                if (mOut != null) mOut[j] = nullMask[i];
                j++;
            }
        }
        return new LongColumn(name, d, mOut, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 LongColumn
     */
    @Override public Column take(int[] indices) {
        long[] d = new long[indices.length];
        boolean[] m = nullMask == null ? null : new boolean[indices.length];
        for (int k = 0; k < indices.length; k++) {
            d[k] = data[indices[k]];
            if (m != null) m[k] = nullMask[indices[k]];
        }
        return new LongColumn(name, d, m, true);
    }

    /** @return Column 深拷贝 */
    @Override public Column copy() { return new LongColumn(name, data, nullMask); }

    /**
     * @return Object[] 长度 == size();缺失为 null,非缺失为 Long
     */
    @Override public Object[] toObjectArray() {
        Object[] o = new Object[data.length];
        for (int i = 0; i < data.length; i++) o[i] = isNull(i) ? null : data[i];
        return o;
    }

    /** @return String "LongColumn[name, len=N]" */
    @Override public String toString() {
        return "LongColumn[" + name + ", len=" + data.length + "]";
    }
}
