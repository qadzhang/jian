package jian.num;

// ┌─ What : StrOps —— Ndarray 的字符串操作入口(对齐 pandas.Series.str accessor)
// │  Why  : 字符串是 OBJECT dtype 中最高频的元素,提供上层/批量变换避免逐个手写循环;
// │        规范要求"长文本(10M 以内)"也能处理,JVM String 无长度上限,天然支持
// │  Who  : 由 Ndarray.str() 创建;被 jian-core 的 StringColumn / DSL 的 like 操作复用
// │  When : 任何字符串列的批量变换/筛选
// │  Where: jian-num/StrOps.java
// │  How  : 数据走向:OBJECT Ndarray → 逐元素 String 变换 → 新 Ndarray(同 dtype,通常 OBJECT 或 BOOL/INT64)。
// │         关键变量变化:
// │           - null 元素始终透传为 null(对齐 pandas .str 的行为);
// │           - 变换类型决定返回 dtype:upper/lower/strip/slice → OBJECT;length → INT64;
// │             contains/startsWith/equals → BOOL。
// │         逻辑路线:
// │           路径 A(逐元素变换)upper/lower/trim/strip/slice/replace/repeat → 新 OBJECT;
// │           路径 B(逐元素聚合)length → INT64;
// │           路径 C(逐元素谓词)contains/startsWith/endsWith/equals → BOOL;
// │           路径 D(元素非 String 且非 null)→ 抛 ClassCastException 提示。
/**
 * 字符串操作入口,由 {@link Ndarray#str()} 返回,对齐 pandas {@code Series.str} accessor。
 *
 * <p>所有方法均遵循:<b>null 元素透传为 null</b>(对齐 pandas .str 默认 na_action='ignore')。
 *
 * <p>覆盖字符串高频操作:
 * <ul>
 *   <li>变换(返回 OBJECT Ndarray):{@link #upper}/{@link #lower}/{@link #trim}/{@link #strip}/{@link #slice}/{@link #replace}/{@link #repeat}/{@link #padLeft}/{@link #padRight};</li>
 *   <li>聚合(返回 INT64 Ndarray):{@link #length};</li>
 *   <li>谓词(返回 BOOL Ndarray):{@link #contains}/{@link #startsWith}/{@link #endsWith}/{@link #equalsIgnoreCase};</li>
 *   <li>拼接:{@link #cat}(返回单个 String)。</li>
 * </ul>
 */
public final class StrOps {

    private final Ndarray arr;
    private final Object[] data;
    private final int len;

    StrOps(Ndarray arr) {
        this.arr = arr;
        this.data = arr.toObjArray();
        this.len = data.length;
    }

    // ======================== 变换 → OBJECT ========================

    /** 全转大写(对齐 .str.upper)。null 透传。 */
    public Ndarray upper() { return mapStrTransform(String::toUpperCase); }

    /** 全转小写(对齐 .str.lower)。 */
    public Ndarray lower() { return mapStrTransform(String::toLowerCase); }

    /** 去首尾空白(对齐 .str.strip)。 */
    public Ndarray strip() { return mapStrTransform(String::trim); }

    /** trim 别名。 */
    public Ndarray trim() { return strip(); }

    /**
     * 重复 n 次(对齐 .str.repeat)。
     *
     * @param n int 每个元素字符串重复的次数,约束:n &gt;= 0(n=0 返回空串)
     * @return Ndarray OBJECT dtype,每个元素为原字符串重复 n 次的结果,null 透传为 null
     */
    public Ndarray repeat(int n) {
        Object[] r = new Object[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).repeat(n);
        return Ndarray.of(r);
    }

    /**
     * 子串切片 [start, end)(对齐 .str.slice,支持负索引)。
     *
     * @param start int 起始索引(含),约束:支持负索引(-1 表末尾);越界自动夹到 [0, len]
     * @param end   int 结束索引(不含),约束:支持负索引;越界自动夹到 [0, len];start &gt;= end 返回空串
     * @return Ndarray OBJECT dtype,每个元素为切片结果,null 透传为 null
     */
    public Ndarray slice(int start, int end) {
        Object[] r = new Object[len];
        for (int i = 0; i < len; i++) {
            if (data[i] == null) { r[i] = null; continue; }
            String s = (String) data[i];
            int s0 = start < 0 ? s.length() + start : start;
            int s1 = end < 0 ? s.length() + end : end;
            s0 = Math.max(0, Math.min(s0, s.length()));
            s1 = Math.max(0, Math.min(s1, s.length()));
            r[i] = s0 >= s1 ? "" : s.substring(s0, s1);
        }
        return Ndarray.of(r);
    }

    /**
     * 字面量替换(对齐 .str.replace 默认 regex=False)。
     *
     * @param target      String 被替换的字面子串,约束:不能为 null;空串会在每个字符间插入 replacement
     * @param replacement String 替换为的新子串,约束:可为空串(等效删除 target)
     * @return Ndarray OBJECT dtype,每个元素为替换后的结果,null 透传为 null
     * @see #replaceRegex 正则版
     */
    public Ndarray replace(String target, String replacement) {
        Object[] r = new Object[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).replace(target, replacement);
        return Ndarray.of(r);
    }

    /**
     * 正则替换(对齐 .str.replace(regex=True))。
     *
     * @param regex        String 正则表达式,约束:Java 正则语法;非法模式会抛 PatternSyntaxException
     * @param replacement  String 替换串,约束:可含 $1/$2 反向引用;可为空串(等效删除)
     * @return Ndarray OBJECT dtype,每个元素为正则替换后的结果,null 透传为 null
     */
    public Ndarray replaceRegex(String regex, String replacement) {
        Object[] r = new Object[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).replaceAll(regex, replacement);
        return Ndarray.of(r);
    }

    /**
     * 左填充到 width(对齐 .str.pad(side='left'))。
     *
     * @param width int 目标最小宽度,约束:width &gt;= 0;原串长度 &gt;= width 时不填充
     * @return Ndarray OBJECT dtype,左侧以空格补齐到指定宽度,null 透传为 null
     */
    public Ndarray padLeft(int width) { return pad(width, true, ' '); }

    /**
     * 右填充到 width。
     *
     * @param width int 目标最小宽度,约束:width &gt;= 0;原串长度 &gt;= width 时不填充
     * @return Ndarray OBJECT dtype,右侧以空格补齐到指定宽度,null 透传为 null
     */
    public Ndarray padRight(int width) { return pad(width, false, ' '); }

    private Ndarray pad(int width, boolean left, char pad) {
        Object[] r = new Object[len];
        for (int i = 0; i < len; i++) {
            if (data[i] == null) { r[i] = null; continue; }
            String s = (String) data[i];
            if (s.length() >= width) { r[i] = s; continue; }
            StringBuilder sb = new StringBuilder(width);
            int n = width - s.length();
            if (left) { for (int k = 0; k < n; k++) sb.append(pad); sb.append(s); }
            else { sb.append(s); for (int k = 0; k < n; k++) sb.append(pad); }
            r[i] = sb.toString();
        }
        return Ndarray.of(r);
    }

    // ======================== 聚合 → INT64 ========================

    /** 每个元素字符串长度(对齐 .str.len)。null 计为缺失(用 Long.MIN_VALUE 标记,上层视需处理)。 */
    public Ndarray length() {
        long[] r = new long[len];
        for (int i = 0; i < len; i++) {
            r[i] = data[i] == null ? Long.MIN_VALUE : ((String) data[i]).length();
        }
        return Ndarray.of(r);
    }

    // ======================== 谓词 → BOOL ========================

    /**
     * 包含子串(对齐 .str.contains,字面量版)。null → null。
     *
     * @param substr String 待检测子串,约束:不能为 null;空串恒返回 true
     * @return Ndarray BOOL dtype,每个元素表示原串是否包含 substr,null 透传为 null
     */
    public Ndarray contains(String substr) {
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).contains(substr);
        return Ndarray.of(r);
    }

    /**
     * 正则包含(对齐 .str.contains(regex=True))。
     *
     * @param regex String 正则表达式,约束:Java 正则语法;非法模式抛 PatternSyntaxException
     * @return Ndarray BOOL dtype,每个元素表示原串是否匹配正则,null 透传为 null
     */
    public Ndarray containsRegex(String regex) {
        Boolean[] r = new Boolean[len];
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : p.matcher((String) data[i]).find();
        return Ndarray.of(r);
    }

    /**
     * 前缀匹配(对齐 .str.startswith)。
     *
     * @param prefix String 前缀子串,约束:不能为 null;空串恒返回 true
     * @return Ndarray BOOL dtype,每个元素表示原串是否以 prefix 开头,null 透传为 null
     */
    public Ndarray startsWith(String prefix) {
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).startsWith(prefix);
        return Ndarray.of(r);
    }

    /**
     * 后缀匹配(对齐 .str.endswith)。
     *
     * @param suffix String 后缀子串,约束:不能为 null;空串恒返回 true
     * @return Ndarray BOOL dtype,每个元素表示原串是否以 suffix 结尾,null 透传为 null
     */
    public Ndarray endsWith(String suffix) {
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).endsWith(suffix);
        return Ndarray.of(r);
    }

    /**
     * 忽略大小写相等。
     *
     * @param target String 比较目标串,约束:不能为 null
     * @return Ndarray BOOL dtype,每个元素表示原串是否与 target 大小写不敏感相等,null 透传为 null
     */
    public Ndarray equalsIgnoreCase(String target) {
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) r[i] = data[i] == null ? null : ((String) data[i]).equalsIgnoreCase(target);
        return Ndarray.of(r);
    }

    // ======================== 拼接 → 单个 String ========================

    /**
     * 全部元素拼接(对齐 .str.cat(sep)),跳过 null。
     *
     * @param sep String 元素之间的分隔符,约束:可为 null(按 null 处理)或空串(无间隔拼接)
     * @return String 所有非 null 元素按顺序拼接的结果;全 null 时返回空串
     */
    public String cat(String sep) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < len; i++) {
            if (data[i] == null) continue;
            if (!first) sb.append(sep);
            sb.append((String) data[i]);
            first = false;
        }
        return sb.toString();
    }

    // ======================== 内部 ========================

    @FunctionalInterface
    private interface StrFn { String apply(String s); }

    private Ndarray mapStrTransform(StrFn fn) {
        Object[] r = new Object[len];
        for (int i = 0; i < len; i++) {
            if (data[i] == null) { r[i] = null; continue; }
            if (!(data[i] instanceof String)) {
                throw new ClassCastException("str() 变换要求 String 元素,第 " + i
                        + " 个元素实际 " + data[i].getClass().getName());
            }
            r[i] = fn.apply((String) data[i]);
        }
        return Ndarray.of(r);
    }
}
