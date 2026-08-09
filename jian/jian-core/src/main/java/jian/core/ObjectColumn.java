package jian.core;

import java.util.Arrays;

// ┌─ What : ObjectColumn —— 任意引用类型列实现(Column 子类,DType.OBJECT,兜底类型)
// │  Why  : 规范 01 §2.1;二进制(byte[])/嵌套结构/未知类型无法归入其它 dtype 时用此兜底
// │  Who  : DataFrame.columns 持有;IO(parquet/orc 的复杂类型)读取;DataFrameMerge/Stats 读取
// │  When : 元素类型不在 INT/LONG/DOUBLE/BOOL/STRING/DATE/DATETIME/CATEGORY 之内
// │  Where: jian-core/ObjectColumn.java
// │  How  : 数据走向:外部 Object[] → 构造(默认 clone)→ 变换返回新 ObjectColumn。
// │         关键变量变化:data:Object[] 主体(永不 null);缺失值在数组内为 null 元素;元素类型任意。
// │         逻辑路线:
// │           构造 A(public)→ clone;B(private + noCopy)→ 直接引用;C(wrapNoCopy)→ 暴露 hot path。
// │           getDouble/getLong:若元素为 Number 直接取值,否则尝试 toString 后 parse;失败抛异常。
/**
 * 任意引用类型列(兜底 dtype),存 Object[];缺失用 null 元素(规范 01 §2.1)。
 * <p><b>不可变</b>:变换返回新 ObjectColumn(规范 §4.3)。
 */
public final class ObjectColumn implements Column {

    private final String name;
    final Object[] data;

    /**
     * 公开构造(默认拷贝)。
     * @param name String 列名,非 null
     * @param data Object[] 主体,非 null;会被 clone;元素类型任意,允许 null(缺失)
     */
    public ObjectColumn(String name, Object[] data) {
        this.name = name;
        this.data = data.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用;false=clone
     */
    private ObjectColumn(String name, Object[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    /**
     * 零拷贝构造(高性能内部 API)。
     * @param name String 列名
     * @param data Object[] 主体,**调用方此后不得修改**;非 null
     * @return ObjectColumn 直接引用入参数组的新实例
     */
    public static ObjectColumn wrapNoCopy(String name, Object[] data) {
        return new ObjectColumn(name, data, true);
    }

    /**
     * @return Object[] data 的克隆副本,长度 == size()
     */
    public Object[] data() { return data.clone(); }

    /**
     * @return Object[] 内部 data 直接引用,**不得修改**;仅供 hot path
     */
    public Object[] dataInPlace() { return data; }

    /** @return DType.OBJECT(恒定) */
    @Override public DType dtype() { return DType.OBJECT; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /** @return Column 改名后的新实例(noCopy) */
    @Override public Column rename(String newName) { return new ObjectColumn(newName, data, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }
    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object 原值(可能为 null)
     */
    @Override public Object get(int i) { return data[i]; }
    /**
     * @param i int 行下标
     * @return double 元素是 Number 时直接 doubleValue;否则 toString 后 parse;缺失返回 NaN
     * @throws IllegalStateException 元素非 Number 且字符串不能 parse 为 double
     */
    @Override public double getDouble(int i) {
        Object o = data[i];
        if (o == null) return Double.NaN;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Object 列第 " + i + " 行不能转 double:" + o);
        }
    }
    /**
     * @param i int 行下标
     * @return long 元素是 Number 时直接 longValue;否则 toString 后 parse
     * @throws IllegalStateException 元素为 null(缺失),或非 Number 且不能 parse 为 long
     */
    @Override public long getLong(int i) {
        Object o = data[i];
        if (o == null) throw new IllegalStateException("ObjectColumn 第 " + i + " 行为缺失,不能转 long");
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Object 列第 " + i + " 行不能转 long:" + o);
        }
    }
    /**
     * @param i int 行下标
     * @return boolean true=该行 null(缺失);false=非空
     */
    @Override public boolean isNull(int i) { return data[i] == null; }

    /**
     * @return int null 元素个数 ∈ [0, size()]
     */
    @Override public int nullCount() {
        int c = 0;
        for (Object o : data) if (o == null) c++;
        return c;
    }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 ObjectColumn,长度 = end-start
     */
    @Override public Column slice(int start, int end) {
        return new ObjectColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 ObjectColumn
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        Object[] out = new Object[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new ObjectColumn(name, out, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 ObjectColumn
     */
    @Override public Column take(int[] indices) {
        Object[] out = new Object[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new ObjectColumn(name, out, true);
    }

    /** @return Column 深拷贝 */
    @Override public Column copy() { return new ObjectColumn(name, data); }
    /**
     * @return Object[] data.clone()(元素原样,与 get() 一致)
     */
    @Override public Object[] toObjectArray() { return data.clone(); }

    /** @return String "ObjectColumn[name, len=N]" */
    @Override public String toString() {
        return "ObjectColumn[" + name + ", len=" + data.length + "]";
    }
}
