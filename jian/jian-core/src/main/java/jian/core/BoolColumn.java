package jian.core;

import java.util.Arrays;

// ┌─ What : BoolColumn —— 布尔列实现(Column 子类,DType.BOOL)
// │  Why  : 规范 01 §2.1 列式存储;原生 boolean 不能表 null,故额外用 nullMask 位图记缺失
// │  Who  : DataFrame.columns 持有;IO 模块从 boolean[] 构造;DataFrameMerge/Stats 读取
// │  When : DataFrame 构造/查询/布尔筛选
// │  Where: jian-core/BoolColumn.java
// │  How  : 数据走向:外部 boolean[] + 可选 nullMask → 构造(默认 clone 防外部修改)→ 变换返回新 BoolColumn。
// │         关键变量变化:
// │           - data:boolean[] 主体(永不 null);
// │           - nullMask:缺失位图,null 表示"全非空"(省内存),非 null 时长度 == data.length。
// │         逻辑路线(三条构造路径):
// │           路径 A(public 构造)→ clone data + clone nullMask(默认安全);
// │           路径 B(private + noCopy)→ 直接引用(hot path,内部保证不修改);
// │           路径 C(wrapNoCopy 静态工厂)→ 同 B,暴露给性能敏感的内部调用方。
// │         变换(slice/filter/take)恒返回 noCopy 路径构造的新实例(数组是新分配的,直接引用即可)。
/**
 * 布尔列,存 boolean[] + 可选 nullMask 位图(规范 01 §2.1)。
 *
 * <p><b>不可变</b>:变换返回新 BoolColumn,自身不变(规范 §4.3)。
 */
public final class BoolColumn implements Column {

    private final String name;
    final boolean[] data;
    final boolean[] nullMask;

    /**
     * 公开构造(默认拷贝,保护不可变契约)。
     * @param name     String 列名,非 null(允许空串)
     * @param data     boolean[] 主体数据,非 null;会被 clone
     * @param nullMask boolean[] 缺失位图;null 表示全非空;非 null 时长度需 == data.length,会被 clone
     */
    public BoolColumn(String name, boolean[] data, boolean[] nullMask) {
        this.name = name;
        this.data = data.clone();
        this.nullMask = nullMask == null ? null : nullMask.clone();
    }

    /**
     * 内部构造(可控拷贝,仅供 hot path)。
     * @param name     String 列名
     * @param data     boolean[] 主体
     * @param nullMask boolean[] 缺失位图
     * @param noCopy   boolean true=直接引用(调用方保证此后不修改入参);false=clone
     */
    private BoolColumn(String name, boolean[] data, boolean[] nullMask, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
        this.nullMask = nullMask;
    }

    /**
     * 零拷贝构造(高性能内部 API)。直接引用传入数组,不 clone。
     * @param name     String 列名,非 null
     * @param data     boolean[] 主体,**调用方此后不得修改**(破坏不可变契约);非 null
     * @param nullMask boolean[] 缺失位图;允许 null(全非空)
     * @return BoolColumn 直接引用入参数组的新实例
     */
    public static BoolColumn wrapNoCopy(String name, boolean[] data, boolean[] nullMask) {
        return new BoolColumn(name, data, nullMask, true);
    }

    /**
     * 拿到底层 boolean[](返回拷贝,保护不可变契约)。
     * @return boolean[] data 的克隆副本,长度 == size();修改副本不影响本列
     */
    public boolean[] data() { return data.clone(); }

    /**
     * 拿到底层 boolean[](零拷贝,高性能 hot path 专用)。
     * @return boolean[] 内部 data 的直接引用,**不得修改**;仅供 DataFrameMerge/GroupBy/Stats 等已知只读的热路径
     */
    public boolean[] dataInPlace() { return data; }

    /**
     * 缺失位图副本。
     * @return boolean[] nullMask 的克隆副本;null 表示全非空(无缺失)
     */
    public boolean[] nullMask() { return nullMask == null ? null : nullMask.clone(); }

    /** @return DType.BOOL(恒定) */
    @Override public DType dtype() { return DType.BOOL; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /**
     * @param newName String 新列名
     * @return Column 同数据、改名后的新实例(noCopy,data/nullMask 共享引用,不可变契约保证安全)
     */
    @Override public Column rename(String newName) { return new BoolColumn(newName, data, nullMask, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }

    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object Boolean(缺失返回 null;非缺失返回 Boolean.TRUE/FALSE)
     */
    @Override public Object get(int i) { return isNull(i) ? null : data[i]; }
    /**
     * @param i int 行下标
     * @return double 缺失返回 NaN;true→1.0,false→0.0
     */
    @Override public double getDouble(int i) { return isNull(i) ? Double.NaN : (data[i] ? 1.0 : 0.0); }
    /**
     * @param i int 行下标
     * @return long true→1L,false→0L
     * @throws IllegalStateException 该行为缺失
     */
    /**
     * @param i int 行下标
     * @return long true→1L,false→0L。**缺失行返回 Long.MIN_VALUE**(缺失标记,不抛异常)。
     */
    @Override public long getLong(int i) {
        if (isNull(i)) return Long.MIN_VALUE;
        return data[i] ? 1L : 0L;
    }
    /**
     * @param i int 行下标
     * @return boolean true=该行缺失;false=有值(nullMask==null 时恒 false)
     */
    @Override public boolean isNull(int i) { return nullMask != null && nullMask[i]; }

    /**
     * 直接取布尔值(不查缺失位图,无缺失场景的高速路径)。
     * @param i int 行下标 ∈ [0, size())
     * @return boolean data[i] 原值(不区分缺失)
     */
    public boolean getBool(int i) { return data[i]; }

    /**
     * 缺失值个数。
     * @return int 缺失数 ∈ [0, size()];nullMask==null 时为 0
     */
    @Override public int nullCount() {
        if (nullMask == null) return 0;
        int c = 0;
        for (boolean m : nullMask) if (m) c++;
        return c;
    }

    /**
     * @param start int 起始(含),范围 [0, size()]
     * @param end   int 结束(不含),范围 [start, size()]
     * @return Column 新 BoolColumn,长度 = end-start;nullMask 同步切片(为 null 时保持 null)
     */
    @Override public Column slice(int start, int end) {
        boolean[] d = Arrays.copyOfRange(data, start, end);
        boolean[] m = nullMask == null ? null : Arrays.copyOfRange(nullMask, start, end);
        return new BoolColumn(name, d, m, true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 新 BoolColumn,仅含 mask==true 的行;保留 nullMask 对应位
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        boolean[] d = new boolean[n];
        boolean[] mOut = nullMask == null ? null : new boolean[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (mask[i]) {
                d[j] = data[i];
                if (mOut != null) mOut[j] = nullMask[i];
                j++;
            }
        }
        return new BoolColumn(name, d, mOut, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 新 BoolColumn,长度 == indices.length;nullMask 同步选取
     */
    @Override public Column take(int[] indices) {
        boolean[] d = new boolean[indices.length];
        boolean[] m = nullMask == null ? null : new boolean[indices.length];
        for (int k = 0; k < indices.length; k++) {
            d[k] = data[indices[k]];
            if (m != null) m[k] = nullMask[indices[k]];
        }
        return new BoolColumn(name, d, m, true);
    }

    /** @return Column 深拷贝(clone data + nullMask) */
    @Override public Column copy() { return new BoolColumn(name, data, nullMask); }

    /**
     * @return Object[] 长度 == size();缺失元素为 null,非缺失为 Boolean
     */
    @Override public Object[] toObjectArray() {
        Object[] o = new Object[data.length];
        for (int i = 0; i < data.length; i++) o[i] = isNull(i) ? null : data[i];
        return o;
    }

    /** @return String 调试描述,形如 "BoolColumn[name, len=N]" */
    @Override public String toString() {
        return "BoolColumn[" + name + ", len=" + data.length + "]";
    }
}
