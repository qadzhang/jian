package jian.core;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

// ┌─ What : DateTimeColumn —— 日期时间列实现(Column 子类,DType.DATETIME)
// │  Why  : 规范 01 §2.1;对齐 pandas datetime64;缺失用 null 元素;与 DateColumn(纯日期)区分
// │  Who  : DataFrame.columns 持有;IO(CSV/Excel)读取;DataFrameMerge/Stats 读取
// │  When : DataFrame 含日期时间列的场景(订单时间、日志戳、传感器时刻)
// │  Where: jian-core/DateTimeColumn.java
// │  How  : 数据走向:外部 LocalDateTime[] → 构造(默认 clone)→ 变换返回新 DateTimeColumn。
// │         关键变量变化:data:LocalDateTime[] 主体(永不 null);缺失值在数组内为 null 元素。
// │         逻辑路线:
// │           构造 A(public)→ clone;B(private + noCopy)→ 直接引用;C(wrapNoCopy)→ 暴露 hot path。
// │           取值:getLong/getDouble = toEpochSecond(UTC) (距 1970-01-01T00:00:00Z 的秒数)。
/**
 * 日期时间列,存 LocalDateTime[];缺失用 null 元素(规范 01 §2.1)。
 * <p><b>不可变</b>:变换返回新 DateTimeColumn(规范 §4.3)。
 */
public final class DateTimeColumn implements Column {

    private final String name;
    final LocalDateTime[] data;

    /**
     * 公开构造(默认拷贝)。
     * @param name String 列名,非 null
     * @param data LocalDateTime[] 主体,非 null;会被 clone;元素允许 null(缺失)
     */
    public DateTimeColumn(String name, LocalDateTime[] data) {
        this.name = name;
        this.data = data.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用;false=clone
     */
    private DateTimeColumn(String name, LocalDateTime[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    /**
     * 零拷贝构造(高性能内部 API)。
     * @param name String 列名
     * @param data LocalDateTime[] 主体,**调用方此后不得修改**;非 null
     * @return DateTimeColumn 直接引用入参数组的新实例
     */
    public static DateTimeColumn wrapNoCopy(String name, LocalDateTime[] data) {
        return new DateTimeColumn(name, data, true);
    }

    /**
     * @return LocalDateTime[] data 的克隆副本,长度 == size()
     */
    public LocalDateTime[] data() { return data.clone(); }

    /**
     * @return LocalDateTime[] 内部 data 直接引用,**不得修改**;仅供 hot path
     */
    public LocalDateTime[] dataInPlace() { return data; }

    /** @return DType.DATETIME(恒定) */
    @Override public DType dtype() { return DType.DATETIME; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /**
     * @return Column 改名后的新实例(noCopy)
     * @param newName String 新列名;非 null
     */
    @Override public Column rename(String newName) { return new DateTimeColumn(newName, data, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }
    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object LocalDateTime(可能为 null)
     */
    @Override public Object get(int i) { return data[i]; }
    /**
     * @param i int 行下标
     * @return double 距 1970-01-01T00:00:00Z 的秒数(UTC);缺失返回 NaN
     */
    @Override public double getDouble(int i) {
        return data[i] == null ? Double.NaN : (double) data[i].toEpochSecond(ZoneOffset.UTC);
    }
    /**
     * @param i int 行下标
     * @return long 距 1970-01-01T00:00:00Z 的秒数(UTC)。**缺失行返回 Long.MIN_VALUE**(缺失标记,不抛异常)。
     */
    @Override public long getLong(int i) {
        if (data[i] == null) return Long.MIN_VALUE;
        return data[i].toEpochSecond(ZoneOffset.UTC);
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
        for (LocalDateTime d : data) if (d == null) c++;
        return c;
    }

    /**
     * 直接取 LocalDateTime(类型化访问器,无 boxing)。
     * @param i int 行下标 ∈ [0, size())
     * @return LocalDateTime data[i](可能为 null;不查缺失)
     */
    public LocalDateTime getDateTime(int i) { return data[i]; }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 DateTimeColumn,长度 = end-start
     */
    @Override public Column slice(int start, int end) {
        return new DateTimeColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 DateTimeColumn
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        LocalDateTime[] out = new LocalDateTime[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new DateTimeColumn(name, out, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 DateTimeColumn
     */
    @Override public Column take(int[] indices) {
        LocalDateTime[] out = new LocalDateTime[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new DateTimeColumn(name, out, true);
    }

    /** @return Column 深拷贝 */
    @Override public Column copy() { return new DateTimeColumn(name, data); }
    /**
     * @return Object[] data.clone()(元素即 LocalDateTime 或 null)
     */
    @Override public Object[] toObjectArray() { return data.clone(); }

    /** @return String "DateTimeColumn[name, len=N]" */
    @Override public String toString() {
        return "DateTimeColumn[" + name + ", len=" + data.length + "]";
    }
}
