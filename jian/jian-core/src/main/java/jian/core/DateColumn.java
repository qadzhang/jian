package jian.core;

import java.time.LocalDate;
import java.util.Arrays;

// ┌─ What : DateColumn —— 日期(无时间)列实现(Column 子类,DType.DATE)
// │  Why  : 规范 01 §2.1;对齐 pandas datetime64 中的纯日期列;缺失用 null 元素(非 NaN)
// │  Who  : DataFrame.columns 持有;IO(CSV/Excel)读取写入;DataFrameMerge/Stats 读取
// │  When : DataFrame 含日期列的场景(生日、合同日期、报告期)
// │  Where: jian-core/DateColumn.java
// │  How  : 数据走向:外部 LocalDate[] → 构造(默认 clone)→ 变换返回新 DateColumn。
// │         关键变量变化:data:LocalDate[] 主体(永不 null);缺失值在数组内为 null 元素。
// │         逻辑路线:
// │           构造 A(public)→ clone;构造 B(private + noCopy)→ 直接引用;构造 C(wrapNoCopy)→ 暴露 hot path。
// │           取值:getLong = toEpochDay() (距离 1970-01-01 的天数);getDouble 同义但浮点。
/**
 * 日期列,存 LocalDate[];缺失用 null 元素(规范 01 §2.1)。
 * <p><b>不可变</b>:变换返回新 DateColumn(规范 §4.3)。
 */
public final class DateColumn implements Column {

    private final String name;
    final LocalDate[] data;

    /**
     * 公开构造(默认拷贝)。
     * @param name String 列名,非 null
     * @param data LocalDate[] 主体,非 null;会被 clone;元素允许 null(缺失)
     */
    public DateColumn(String name, LocalDate[] data) {
        this.name = name;
        this.data = data.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用;false=clone
     */
    private DateColumn(String name, LocalDate[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    /**
     * 零拷贝构造(高性能内部 API)。
     * @param name String 列名
     * @param data LocalDate[] 主体,**调用方此后不得修改**;非 null
     * @return DateColumn 直接引用入参数组的新实例
     */
    public static DateColumn wrapNoCopy(String name, LocalDate[] data) {
        return new DateColumn(name, data, true);
    }

    /**
     * @return LocalDate[] data 的克隆副本,长度 == size()
     */
    public LocalDate[] data() { return data.clone(); }

    /**
     * @return LocalDate[] 内部 data 直接引用,**不得修改**;仅供 hot path
     */
    public LocalDate[] dataInPlace() { return data; }

    /** @return DType.DATE(恒定) */
    @Override public DType dtype() { return DType.DATE; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /**
     * @return Column 改名后的新实例(noCopy)
     * @param newName String 新列名;非 null
     */
    @Override public Column rename(String newName) { return new DateColumn(newName, data, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }
    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object LocalDate(可能为 null)
     */
    @Override public Object get(int i) { return data[i]; }
    /**
     * @param i int 行下标
     * @return double 距 1970-01-01 的天数(toEpochDay);缺失返回 NaN
     */
    @Override public double getDouble(int i) {
        return data[i] == null ? Double.NaN : (double) data[i].toEpochDay();
    }
    /**
     * @param i int 行下标
     * @return long 距 1970-01-01 的天数。**缺失行返回 Long.MIN_VALUE**(缺失标记,不抛异常)。
     */
    @Override public long getLong(int i) {
        if (data[i] == null) return Long.MIN_VALUE;
        return data[i].toEpochDay();
    }
    /**
     * @param i int 行下标
     * @return boolean true=该行 null(缺失);false=有值
     */
    @Override public boolean isNull(int i) { return data[i] == null; }

    /**
     * @return int null 元素个数 ∈ [0, size()]
     */
    @Override public int nullCount() {
        int c = 0;
        for (LocalDate d : data) if (d == null) c++;
        return c;
    }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 DateColumn,长度 = end-start
     */
    @Override public Column slice(int start, int end) {
        return new DateColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 DateColumn
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        LocalDate[] out = new LocalDate[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new DateColumn(name, out, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 DateColumn
     */
    @Override public Column take(int[] indices) {
        LocalDate[] out = new LocalDate[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new DateColumn(name, out, true);
    }

    /** @return Column 深拷贝 */
    @Override public Column copy() { return new DateColumn(name, data); }
    /**
     * @return Object[] data.clone()(元素即 LocalDate 或 null)
     */
    @Override public Object[] toObjectArray() { return data.clone(); }

    /** @return String "DateColumn[name, len=N]" */
    @Override public String toString() {
        return "DateColumn[" + name + ", len=" + data.length + "]";
    }
}
