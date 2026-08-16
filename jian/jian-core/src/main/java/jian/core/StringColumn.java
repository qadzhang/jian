package jian.core;

import java.util.Arrays;

// ┌─ What : StringColumn —— 字符串列(对齐 pandas object dtype 中 String 元素的高频列)
// │  Why  : 字符串是 DataFrame 中使用频率最高的列类型,独立实现便于高频路径优化与 .str accessor 接入
// │  Who  : DataFrame 内部持有;DSL 的 like / jian-num 的 StrOps 经 DataFrame 接入
// │  When : 任何文本列(姓名/地址/描述/长文本)
// │  Where: jian-core/StringColumn.java
// │  How  : 数据走向:String[] 存引用,缺失为 null(与 pandas object dtype 一致)。
// │         关键变量变化:
// │           - data:原始 String 数组,变换产生新数组;
// │           - 长文本(10M 字符)无上限,JVM String 即 UTF-16。
// │         逻辑路线:
// │           路径 A(变换 upper/trim)→ 逐元素 map,null 透传;
// │           路径 B(谓词 contains/starts)→ 返回 BoolColumn;
// │           路径 C(切片/过滤)→ 复制数组子集。
/**
 * 字符串列,对齐 pandas object dtype 中的 String 元素。
 *
 * <p>内部 {@code String[]} 存引用,缺失为 Java {@code null}(对齐 pandas 默认)。
 * <p>支持长文本(JVM String 无长度上限,10M+ 字符无压力)。
 */
public final class StringColumn implements Column {

    private final String name;
    final String[] data;

    /**
     * 公开构造(默认拷贝)。
     * @param name String 列名,非 null
     * @param data String[] 主体,非 null;会被 clone;元素允许 null(表示缺失)
     */
    public StringColumn(String name, String[] data) {
        // 列名不允许 null(null 名下游 toString/导出全 NPE)
        java.util.Objects.requireNonNull(name, "列名不能为 null");
        this.name = name;
        this.data = data.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用(调用方保证此后不修改入参);false=clone
     */
    private StringColumn(String name, String[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    /**
     * 零拷贝构造(高性能内部 API)。
     * @param name String 列名
     * @param data String[] 主体,**调用方此后不得修改**;非 null
     * @return StringColumn 直接引用入参数组的新实例
     */
    public static StringColumn wrapNoCopy(String name, String[] data) {
        return new StringColumn(name, data, true);
    }

    /**
     * @return String[] data 的克隆副本,长度 == size();修改副本不影响本列
     */
    public String[] data() { return data.clone(); }

    /**
     * @return String[] 内部 data 的直接引用,**不得修改**;仅供 hot path
     */
    public String[] dataInPlace() { return data; }

    /** @return DType.STRING(恒定) */
    @Override public DType dtype() { return DType.STRING; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /**
     * @return Column 改名后的新实例(noCopy)
     * @param newName String 新列名;非 null
     */
    @Override public Column rename(String newName) { return new StringColumn(newName, data, true); }
    /** @return int 行数 == data.length */
    @Override public int size() { return data.length; }

    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object String 原值(可能为 null)
     */
    @Override public Object get(int i) { return data[i]; }
    /**
     * @param i int 行下标
     * @return double 解析后的 double;缺失返回 NaN
     * @throws IllegalStateException 该行字符串不能解析为 double
     */
    @Override public double getDouble(int i) {
        if (data[i] == null) return Double.NaN;
        try { return Double.parseDouble(data[i].trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("String 列第 " + i + " 行 \"" + data[i] + "\" 不能转 double");
        }
    }
    /**
     * @param i int 行下标
     * @return long 解析后的 long。**缺失行返回 Long.MIN_VALUE**(缺失标记,不抛异常)。
     * @throws IllegalStateException 该行字符串不能解析为 long
     */
    @Override public long getLong(int i) {
        if (data[i] == null) return Long.MIN_VALUE;
        try { return Long.parseLong(data[i].trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("String 列第 " + i + " 行 \"" + data[i] + "\" 不能转 long");
        }
    }
    /**
     * @param i int 行下标
     * @return boolean true=该行为 null(缺失);false=非空
     */
    @Override public boolean isNull(int i) { return data[i] == null; }

    /**
     * @return int null 元素个数 ∈ [0, size()]
     */
    @Override public int nullCount() {
        int c = 0;
        for (String s : data) if (s == null) c++;
        return c;
    }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 StringColumn,长度 = end-start
     */
    @Override public Column slice(int start, int end) {
        return new StringColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 StringColumn
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        String[] out = new String[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new StringColumn(name, out, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 StringColumn
     */
    @Override public Column take(int[] indices) {
        String[] out = new String[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new StringColumn(name, out, true);
    }

    /** @return Column 深拷贝 */
    @Override public Column copy() { return new StringColumn(name, data); }

    /**
     * @return Object[] data.clone()(字符串列元素的装箱形式与原数组一致)
     */
    @Override public Object[] toObjectArray() { return data.clone(); }

    // ======================== 字符串专属批量操作(对齐 pandas .str accessor)========================

    /**
     * 全转大写(null 透传)。
     * @return StringColumn 新实例,每元素 toUpperCase();null 保持 null
     */
    public StringColumn upper() { return map(s -> s == null ? null : s.toUpperCase()); }
    /**
     * 全转小写(null 透传)。
     * @return StringColumn 新实例,每元素 toLowerCase()
     */
    public StringColumn lower() { return map(s -> s == null ? null : s.toLowerCase()); }
    /**
     * 去首尾空白(null 透传)。
     * @return StringColumn 新实例,每元素 trim()
     */
    public StringColumn trim() { return map(s -> s == null ? null : s.trim()); }

    /**
     * 包含子串判断(字面量子串,非正则)。
     * @param substr String 待查找子串,非 null
     * @return BoolColumn 同名新实例;null 元素或不含 substr → false;含 → true
     */
    public BoolColumn contains(String substr) {
        boolean[] r = new boolean[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] != null && data[i].contains(substr);
        return new BoolColumn(name, r, null);
    }

    /**
     * 前缀匹配。
     * @param prefix String 前缀,非 null
     * @return BoolColumn;null → false;以 prefix 开头 → true
     */
    public BoolColumn startsWith(String prefix) {
        boolean[] r = new boolean[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] != null && data[i].startsWith(prefix);
        return new BoolColumn(name, r, null);
    }

    /**
     * 后缀匹配。
     * @param suffix String 后缀,非 null
     * @return BoolColumn;null → false;以 suffix 结尾 → true
     */
    public BoolColumn endsWith(String suffix) {
        boolean[] r = new boolean[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] != null && data[i].endsWith(suffix);
        return new BoolColumn(name, r, null);
    }

    /**
     * 字面量替换(非正则,对齐 String.replace)。
     * @param target String 待替换子串,非 null
     * @param repl   String 替换为,非 null
     * @return StringColumn 新实例;null 元素透传
     */
    public StringColumn replace(String target, String repl) {
        return map(s -> s == null ? null : s.replace(target, repl));
    }

    /**
     * 每个元素长度。
     * @return IntColumn 同名新实例;非 null 元素为其字符串长度(≥0);**null 元素标记为缺失**(nullMask 对应位 true,length 值为 0)
     */
    public IntColumn length() {
        int[] r = new int[data.length];
        boolean[] mask = new boolean[data.length];
        for (int i = 0; i < data.length; i++) {
            if (data[i] == null) { r[i] = 0; mask[i] = true; }
            else r[i] = data[i].length();
        }
        return new IntColumn(name, r, mask);
    }

    /** 字符串变换函数接口(供 map 用)。 */
    @FunctionalInterface
    private interface StrFn { String apply(String s); }

    /**
     * 逐元素 map(私有)。
     * @param fn StrFn 变换函数,非 null;入参可能为 null,函数需自行处理
     * @return StringColumn 新实例(noCopy 构造)
     */
    private StringColumn map(StrFn fn) {
        String[] r = new String[data.length];
        for (int i = 0; i < data.length; i++) r[i] = fn.apply(data[i]);
        return new StringColumn(name, r, true);
    }

    /** @return String "StringColumn[name, len=N]" */
    @Override public String toString() {
        return "StringColumn[" + name + ", len=" + data.length + "]";
    }
}
