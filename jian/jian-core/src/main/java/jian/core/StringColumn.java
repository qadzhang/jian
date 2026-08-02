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

    public StringColumn(String name, String[] data) {
        this.name = name;
        this.data = data.clone();
    }

    private StringColumn(String name, String[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    @Override public DType dtype() { return DType.STRING; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new StringColumn(newName, data, true); }
    @Override public int size() { return data.length; }

    @Override public Object get(int i) { return data[i]; }
    @Override public double getDouble(int i) {
        if (data[i] == null) return Double.NaN;
        try { return Double.parseDouble(data[i].trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("String 列第 " + i + " 行 \"" + data[i] + "\" 不能转 double");
        }
    }
    @Override public long getLong(int i) {
        if (data[i] == null) return 0L;
        try { return Long.parseLong(data[i].trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("String 列第 " + i + " 行 \"" + data[i] + "\" 不能转 long");
        }
    }
    @Override public boolean isNull(int i) { return data[i] == null; }

    @Override public int nullCount() {
        int c = 0;
        for (String s : data) if (s == null) c++;
        return c;
    }

    public String[] data() { return data.clone(); }

    @Override public Column slice(int start, int end) {
        return new StringColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        String[] out = new String[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new StringColumn(name, out, true);
    }

    @Override public Column take(int[] indices) {
        String[] out = new String[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new StringColumn(name, out, true);
    }

    @Override public Column copy() { return new StringColumn(name, data); }

    @Override public Object[] toObjectArray() { return data.clone(); }

    // ======================== 字符串专属批量操作(对齐 pandas .str accessor)========================

    /** 全转大写,null 透传。 */
    public StringColumn upper() { return map(s -> s == null ? null : s.toUpperCase()); }
    public StringColumn lower() { return map(s -> s == null ? null : s.toLowerCase()); }
    public StringColumn trim() { return map(s -> s == null ? null : s.trim()); }

    /** 包含子串(字面量),返回 BoolColumn。null → false(便于 mask 用)。 */
    public BoolColumn contains(String substr) {
        boolean[] r = new boolean[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] != null && data[i].contains(substr);
        return new BoolColumn(name, r, null);
    }

    /** 前缀匹配,返回 BoolColumn。 */
    public BoolColumn startsWith(String prefix) {
        boolean[] r = new boolean[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] != null && data[i].startsWith(prefix);
        return new BoolColumn(name, r, null);
    }

    /** 后缀匹配,返回 BoolColumn。 */
    public BoolColumn endsWith(String suffix) {
        boolean[] r = new boolean[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] != null && data[i].endsWith(suffix);
        return new BoolColumn(name, r, null);
    }

    /** 字面量替换。 */
    public StringColumn replace(String target, String repl) {
        return map(s -> s == null ? null : s.replace(target, repl));
    }

    /** 每个元素长度,缺失为 -1(用 IntColumn,nullMask 标记)。 */
    public IntColumn length() {
        int[] r = new int[data.length];
        boolean[] mask = new boolean[data.length];
        for (int i = 0; i < data.length; i++) {
            if (data[i] == null) { r[i] = 0; mask[i] = true; }
            else r[i] = data[i].length();
        }
        return new IntColumn(name, r, mask);
    }

    @FunctionalInterface
    private interface StrFn { String apply(String s); }

    private StringColumn map(StrFn fn) {
        String[] r = new String[data.length];
        for (int i = 0; i < data.length; i++) r[i] = fn.apply(data[i]);
        return new StringColumn(name, r, true);
    }

    @Override public String toString() {
        return "StringColumn[" + name + ", len=" + data.length + "]";
    }
}
