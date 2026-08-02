package jian.num;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;

// ┌─ What : Ndarray —— 多 dtype 一维数组引擎(对齐 numpy.ndarray 的统计实用子集)
// │  Why  : 规范升级后 Ndarray 是 jian-num 的核心,也是 jian-core 各 Column 的底层存储引擎;
// │        支持 INT64/FLOAT64/BOOL/DATETIME64/OBJECT 五类,整数独立保留精度,字符串/长文本走 OBJECT
// │  Who  : 用户直接构造;jian-core 的 IntColumn/DoubleColumn/StringColumn 等内部各包一个 Ndarray
// │  When : 任何一维数值/逻辑/日期/字符串数组场景
// │  Where: jian-num/Ndarray.java
// │  How  : 数据走向:各种入参 → 按 dtype 选定内部存储(long[]/double[]/boolean[]/Object[])→
// │         拷贝防外部修改 → 算术按 promote 规则升级 → 返回新 Ndarray(不可变优先)。
// │         关键变量变化:
// │           - dtype:创建时确定,算术运算后可能升级(INT64+FLOAT64→FLOAT64);
// │           - storage:根据 dtype 切换 5 种内部数组之一;
// │           - len:元素个数,所有运算前校验长度一致。
// │         逻辑路线(五条主路径):
// │           路径 A(数值运算 add/sub/mul/div)→ 仅 INT64/FLOAT64;混合 → FLOAT64;非数值抛异常;
// │           路径 B(逻辑运算 and/or/not)→ 仅 BOOL;
// │           路径 C(比较 eq/ne/lt/gt/le/ge)→ 数值/日期/字符串都支持,返回 BOOL;
// │           路径 D(字符串操作 strXxx)→ 仅 OBJECT 且元素是 String;
// │           路径 E(形状不匹配/类型不匹配)→ 抛 IllegalArgumentException 带期望/实际。
/**
 * 多 dtype 一维数组引擎,对标 numpy.ndarray 的统计实用子集。
 *
 * <p><b>5 种 dtype</b>(详见 {@link DType}):
 * <ul>
 *   <li>{@link DType#INT64} long[] —— 整数独立,大数不丢精度;</li>
 *   <li>{@link DType#FLOAT64} double[] —— 浮点,缺失用 NaN;</li>
 *   <li>{@link DType#BOOL} boolean[] —— 布尔掩码;</li>
 *   <li>{@link DType#DATETIME64} long[](epoch 秒)—— 日期时间;</li>
 *   <li>{@link DType#OBJECT} Object[] —— 字符串/长文本/二进制/嵌套(字符串最高频)。</li>
 * </ul>
 *
 * <p><b>缺失值约定</b>(对齐 pandas):
 * <table>
 *   <tr><th>dtype</th><th>缺失值</th></tr>
 *   <tr><td>FLOAT64</td><td>Double.NaN</td></tr>
 *   <tr><td>INT64</td><td>null(内部用 Long[] 装箱,缺失位 null)</td></tr>
 *   <tr><td>BOOL</td><td>null(内部用 Boolean[] 装箱)</td></tr>
 *   <tr><td>DATETIME64</td><td>Long.MIN_VALUE 标记</td></tr>
 *   <tr><td>OBJECT</td><td>Java null</td></tr>
 * </table>
 *
 * <p><b>不可变优先</b>:所有变换返回新 Ndarray(规范 §4.3)。
 */
public final class Ndarray {

    private final DType dtype;
    private final int len;
    // 5 种内部存储,只其一非 null(按 dtype 选)
    private final long[] longData;       // INT64 / DATETIME64 共用(DATETIME64 存 epoch 秒)
    private final double[] doubleData;   // FLOAT64
    private final Boolean[] boolData;    // BOOL(装箱,允许 null 表示缺失)
    private final Object[] objData;      // OBJECT(String/byte[]/嵌套)

    private Ndarray(DType dtype, long[] ld, double[] dd, Boolean[] bd, Object[] od) {
        this.dtype = dtype;
        this.longData = ld; this.doubleData = dd; this.boolData = bd; this.objData = od;
        this.len = (ld != null) ? ld.length : (dd != null) ? dd.length
                : (bd != null) ? bd.length : od.length;
    }

    // ======================== 工厂:从原始数组创建 ========================

    /** 整数数组 → INT64(拷贝)。 */
    public static Ndarray of(long[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        return new Ndarray(DType.INT64, data.clone(), null, null, null);
    }

    /** 从 int[] 创建 INT64。 */
    public static Ndarray of(int[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        long[] dst = new long[data.length];
        for (int i = 0; i < data.length; i++) dst[i] = data[i];
        return new Ndarray(DType.INT64, dst, null, null, null);
    }

    /** 浮点数组 → FLOAT64(拷贝)。 */
    public static Ndarray of(double[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        return new Ndarray(DType.FLOAT64, null, data.clone(), null, null);
    }

    /** 布尔数组 → BOOL(拷贝,允许 null 表示缺失)。 */
    public static Ndarray of(Boolean[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        return new Ndarray(DType.BOOL, null, null, data.clone(), null);
    }

    /** 对象数组 → OBJECT(拷贝;String/byte[]/嵌套都用它)。 */
    public static Ndarray of(Object[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        return new Ndarray(DType.OBJECT, null, null, null, data.clone());
    }

    /** 字符串数组便捷工厂(最高频)。等价 of((Object[]) strs)。 */
    public static Ndarray ofStrings(String... strs) {
        Objects.requireNonNull(strs, "strs 不能为 null");
        return new Ndarray(DType.OBJECT, null, null, null, strs.clone());
    }

    /** LocalDateTime 数组 → DATETIME64(内部转 epoch 秒)。 */
    public static Ndarray ofDateTimes(LocalDateTime[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        long[] secs = new long[data.length];
        for (int i = 0; i < data.length; i++) {
            secs[i] = (data[i] == null) ? Long.MIN_VALUE
                    : data[i].toEpochSecond(ZoneOffset.UTC);
        }
        return new Ndarray(DType.DATETIME64, secs, null, null, null);
    }

    // ======================== 工厂:全零 / 类型转换 ========================

    public static Ndarray zerosInt(int n) { return new Ndarray(DType.INT64, new long[n], null, null, null); }
    public static Ndarray zerosFloat(int n) { return new Ndarray(DType.FLOAT64, null, new double[n], null, null); }
    public static Ndarray zerosBool(int n) { return new Ndarray(DType.BOOL, null, null, new Boolean[n], null); }

    /**
     * 类型转换(对齐 numpy astype):
     * <ul>
     *   <li>→ FLOAT64:整数转浮点,null → NaN;</li>
     *   <li>→ INT64:浮点截断,NaN → null;</li>
     *   <li>→ OBJECT:每个元素装箱;</li>
     *   <li>其它方向见实现。</li>
     * </ul>
     */
    public Ndarray astype(DType target) {
        if (target == dtype) return this;
        switch (target) {
            case FLOAT64: return toFloat64();
            case INT64: return toInt64();
            case OBJECT: return toObject();
            default: throw new IllegalArgumentException(
                    "astype 暂不支持 " + dtype + " → " + target);
        }
    }

    private Ndarray toFloat64() {
        double[] d = new double[len];
        switch (dtype) {
            case INT64: for (int i = 0; i < len; i++) d[i] = longData[i]; break;
            case BOOL: for (int i = 0; i < len; i++) d[i] = boolData[i] == null ? Double.NaN : (boolData[i] ? 1.0 : 0.0); break;
            case DATETIME64: for (int i = 0; i < len; i++) d[i] = longData[i] == Long.MIN_VALUE ? Double.NaN : longData[i]; break;
            case OBJECT: for (int i = 0; i < len; i++) d[i] = objDouble(objData[i]); break;
            default: throw new IllegalStateException("toFloat64 from " + dtype);
        }
        return new Ndarray(DType.FLOAT64, null, d, null, null);
    }

    private Ndarray toInt64() {
        long[] l = new long[len];
        switch (dtype) {
            case FLOAT64: for (int i = 0; i < len; i++) l[i] = Double.isNaN(doubleData[i]) ? 0L : (long) doubleData[i]; break;
            case BOOL: for (int i = 0; i < len; i++) l[i] = (boolData[i] != null && boolData[i]) ? 1L : 0L; break;
            default: throw new IllegalArgumentException("toInt64 from " + dtype + " 暂不支持");
        }
        return new Ndarray(DType.INT64, l, null, null, null);
    }

    private Ndarray toObject() {
        Object[] o = new Object[len];
        switch (dtype) {
            case INT64: for (int i = 0; i < len; i++) o[i] = longData[i]; break;
            case FLOAT64: for (int i = 0; i < len; i++) o[i] = Double.isNaN(doubleData[i]) ? null : doubleData[i]; break;
            case BOOL: for (int i = 0; i < len; i++) o[i] = boolData[i]; break;
            case DATETIME64: for (int i = 0; i < len; i++) o[i] = longData[i] == Long.MIN_VALUE ? null : Instant.ofEpochSecond(longData[i]).atOffset(ZoneOffset.UTC).toLocalDateTime(); break;
            default: break;
        }
        return new Ndarray(DType.OBJECT, null, null, null, o);
    }

    // ======================== 属性 ========================

    public DType dtype() { return dtype; }
    public int len() { return len; }
    public int size() { return len; }

    // ======================== 取值 ========================

    public long getInt(int i) {
        requireDType(DType.INT64, "getInt");
        return longData[i];
    }

    public double getFloat(int i) {
        requireDType(DType.FLOAT64, "getFloat");
        return doubleData[i];
    }

    public Boolean getBool(int i) {
        requireDType(DType.BOOL, "getBool");
        return boolData[i];
    }

    public Object get(int i) {
        // 通用取值(任意 dtype 都能取)
        switch (dtype) {
            case INT64: return longData[i];
            case FLOAT64: return doubleData[i];
            case BOOL: return boolData[i];
            case DATETIME64: return longData[i] == Long.MIN_VALUE ? null
                    : Instant.ofEpochSecond(longData[i]).atOffset(ZoneOffset.UTC).toLocalDateTime();
            case OBJECT: default: return objData[i];
        }
    }

    /** 返回内部存储的拷贝(防外部修改;类型须匹配 dtype)。 */
    public long[] toLongArray() {
        if (dtype != DType.INT64 && dtype != DType.DATETIME64)
            throw new IllegalStateException("toLongArray 要求 INT64/DATETIME64,实际 " + dtype);
        return longData.clone();
    }

    public double[] toDoubleArray() {
        requireDType(DType.FLOAT64, "toDoubleArray");
        return doubleData.clone();
    }

    public Object[] toObjArray() {
        requireDType(DType.OBJECT, "toObjArray");
        return objData.clone();
    }

    // ======================== 数值算术(INT64 / FLOAT64)========================

    /** 加(对齐 numpy +)。整数+浮点 → 浮点;非数值抛异常。 */
    public Ndarray add(Ndarray other) { return arith(other, '+', "add"); }
    public Ndarray sub(Ndarray other) { return arith(other, '-', "sub"); }
    public Ndarray mul(Ndarray other) { return arith(other, '*', "mul"); }
    public Ndarray div(Ndarray other) { return arith(other, '/', "div"); }

    // ======================== 标量算术 ========================

    public Ndarray add(double s) { return scalarFloat(s, '+'); }
    public Ndarray sub(double s) { return scalarFloat(s, '-'); }
    public Ndarray mul(double s) { return scalarFloat(s, '*'); }
    public Ndarray div(double s) { return scalarFloat(s, '/'); }

    // ======================== 逻辑运算(BOOL)========================

    public Ndarray and(Ndarray other) { return logic(other, '&', "and"); }
    public Ndarray or(Ndarray other) { return logic(other, '|', "or"); }
    public Ndarray not() {
        requireDType(DType.BOOL, "not");
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) r[i] = boolData[i] == null ? null : !boolData[i];
        return new Ndarray(DType.BOOL, null, null, r, null);
    }

    // ======================== 比较(返回 BOOL)========================

    /** 相等比较(任意 dtype 都支持,null/NaN 视为不等)。 */
    public Ndarray eq(Ndarray other) { return compare(other, '=', "eq"); }
    public Ndarray ne(Ndarray other) { return compare(other, '!', "ne"); }
    public Ndarray lt(Ndarray other) { return compare(other, '<', "lt"); }
    public Ndarray gt(Ndarray other) { return compare(other, '>', "gt"); }
    public Ndarray le(Ndarray other) { return compare(other, 'L', "le"); }
    public Ndarray ge(Ndarray other) { return compare(other, 'G', "ge"); }

    // ======================== 切片 ========================

    /** 切片 [start, end)(支持负索引)。 */
    public Ndarray slice(int start, int end) {
        start = norm(start); end = norm(end);
        if (start >= end) return emptyLike();
        int n = end - start;
        switch (dtype) {
            case INT64:
            case DATETIME64: { long[] r = new long[n]; System.arraycopy(longData, start, r, 0, n);
                return new Ndarray(dtype, r, null, null, null); }
            case FLOAT64: { double[] r = new double[n]; System.arraycopy(doubleData, start, r, 0, n);
                return new Ndarray(DType.FLOAT64, null, r, null, null); }
            case BOOL: { Boolean[] r = new Boolean[n]; System.arraycopy(boolData, start, r, 0, n);
                return new Ndarray(DType.BOOL, null, null, r, null); }
            case OBJECT: default: { Object[] r = new Object[n]; System.arraycopy(objData, start, r, 0, n);
                return new Ndarray(DType.OBJECT, null, null, null, r); }
        }
    }

    // ======================== 字符串专属操作(委托 StrOps,OBJECT 且为 String)========================

    /** 字符串操作入口(对齐 pandas .str accessor)。要求 OBJECT 且元素为 String/null。 */
    public StrOps str() {
        if (dtype != DType.OBJECT) throw new IllegalStateException(
                "str() 仅 OBJECT dtype 可用(元素为 String),当前 " + dtype);
        return new StrOps(this);
    }

    // ======================== 缺失值统计(全 dtype 通用)========================

    /** 是否每个元素为缺失(对齐 pandas isna)。 */
    public Ndarray isna() {
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) r[i] = isMissingAt(i);
        return new Ndarray(DType.BOOL, null, null, r, null);
    }

    /** 非缺失元素个数。 */
    public int countNonMissing() {
        int c = 0;
        for (int i = 0; i < len; i++) if (!isMissingAt(i)) c++;
        return c;
    }

    // ======================== 实例统计(对齐 numpy a.sum() / a.mean(),规范 06 §2.1)========================

    /** 求和(仅数值 dtype;FLOAT64 跳过 NaN;INT64 全量求和;非数值抛异常)。 */
    public double sum() {
        switch (dtype) {
            case INT64: {
                double s = 0;
                for (long v : longData) s += v;
                return s;
            }
            case FLOAT64: {
                double s = 0;
                for (double v : doubleData) if (!Double.isNaN(v)) s += v;
                return s;
            }
            default:
                throw new IllegalStateException("sum 仅支持数值 dtype,实际 " + dtype);
        }
    }

    /** 均值(同上,跳过缺失);空或全缺失 → NaN。 */
    public double mean() {
        switch (dtype) {
            case INT64:
                return longData.length == 0 ? Double.NaN : sum() / longData.length;
            case FLOAT64: {
                double s = 0; int cnt = 0;
                for (double v : doubleData) if (!Double.isNaN(v)) { s += v; cnt++; }
                return cnt == 0 ? Double.NaN : s / cnt;
            }
            default:
                throw new IllegalStateException("mean 仅支持数值 dtype,实际 " + dtype);
        }
    }

    private boolean isMissingAt(int i) {
        switch (dtype) {
            case FLOAT64: return Double.isNaN(doubleData[i]);
            case INT64: return false;  // INT64 数组(long[])原生不支持缺失;需缺失请用 FLOAT64(NaN)或 OBJECT(null);DataFrame 层的 IntColumn/LongColumn 有 nullMask 完整支持缺失
            case BOOL: return boolData[i] == null;
            case DATETIME64: return longData[i] == Long.MIN_VALUE;
            case OBJECT: default: return objData[i] == null;
        }
    }

    // ======================== 内部实现 ========================

    private Ndarray arith(Ndarray other, char op, String name) {
        requireSameLen(other, name);
        // 算术运算只接受数值 dtype;promote 之前先校验,避免 OBJECT/BOOL/DATETIME64 误入
        if (!dtype.isNumeric()) throw new IllegalArgumentException(
                name + " 仅数值 dtype(INT64/FLOAT64)可用,this.dtype=" + dtype);
        if (!other.dtype.isNumeric()) throw new IllegalArgumentException(
                name + " 仅数值 dtype(INT64/FLOAT64)可用,other.dtype=" + other.dtype);
        DType rt = DType.promote(dtype, other.dtype);  // 数值向上转型
        if (rt == DType.FLOAT64) {
            double[] a = this.toDoubleArrayInternal();
            double[] b = other.toDoubleArrayInternal();
            double[] r = new double[len];
            for (int i = 0; i < len; i++) {
                if (Double.isNaN(a[i]) || Double.isNaN(b[i])) { r[i] = Double.NaN; continue; }
                r[i] = applyOp(op, a[i], b[i]);
            }
            return new Ndarray(DType.FLOAT64, null, r, null, null);
        }
        // INT64 + INT64 → INT64
        long[] r = new long[len];
        for (int i = 0; i < len; i++) r[i] = (long) applyOp(op, longData[i], other.longData[i]);
        return new Ndarray(DType.INT64, r, null, null, null);
    }

    private Ndarray scalarFloat(double s, char op) {
        if (!dtype.isNumeric()) throw new IllegalStateException(
                "标量算术仅数值 dtype 可用,当前 " + dtype);
        if (dtype == DType.FLOAT64) {
            double[] r = new double[len];
            for (int i = 0; i < len; i++) r[i] = Double.isNaN(doubleData[i]) ? Double.NaN : applyOp(op, doubleData[i], s);
            return new Ndarray(DType.FLOAT64, null, r, null, null);
        }
        long[] r = new long[len];
        for (int i = 0; i < len; i++) r[i] = (long) applyOp(op, longData[i], s);
        return new Ndarray(DType.INT64, r, null, null, null);
    }

    private Ndarray logic(Ndarray other, char op, String name) {
        requireDType(DType.BOOL, name);
        requireSameLen(other, name);
        if (other.dtype != DType.BOOL) throw new IllegalArgumentException(
                name + " 要求两侧 BOOL,this=" + dtype + ", other=" + other.dtype);
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) {
            if (boolData[i] == null || other.boolData[i] == null) { r[i] = null; continue; }
            r[i] = op == '&' ? (boolData[i] && other.boolData[i])
                             : (boolData[i] || other.boolData[i]);
        }
        return new Ndarray(DType.BOOL, null, null, r, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Ndarray compare(Ndarray other, char op, String name) {
        requireSameLen(other, name);
        Boolean[] r = new Boolean[len];
        for (int i = 0; i < len; i++) {
            Object a = this.get(i), b = other.get(i);
            if (a == null || b == null) { r[i] = (op == '!'); continue; }
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                double da = ((Number) a).doubleValue(), db = ((Number) b).doubleValue();
                if (Double.isNaN(da) || Double.isNaN(db)) { r[i] = (op == '!'); continue; }
                r[i] = cmp(op, da, db);
            } else if (a instanceof Number && b instanceof Number) {
                long la = ((Number) a).longValue(), lb = ((Number) b).longValue();
                r[i] = cmp(op, la, lb);
            } else if (a instanceof String || b instanceof String) {
                // 字符串按字典序(对齐 pandas 字符串比较)
                int c = String.valueOf(a).compareTo(String.valueOf(b));
                r[i] = cmpStr(op, c);
            } else {
                r[i] = (op == '=') ? a.equals(b) : !a.equals(b);
                if (op == '<' || op == '>' || op == 'L' || op == 'G') {
                    if (a instanceof Comparable && b.getClass().isInstance(a)) {
                        int c = ((Comparable) a).compareTo(b);
                        r[i] = cmpStr(op, c);
                    } else {
                        throw new IllegalArgumentException(name + " 不支持 " + a.getClass() + " 与 " + b.getClass());
                    }
                }
            }
        }
        return new Ndarray(DType.BOOL, null, null, r, null);
    }

    private static boolean cmp(char op, double a, double b) {
        return switch (op) { case '=' -> a == b; case '!' -> a != b; case '<' -> a < b;
            case '>' -> a > b; case 'L' -> a <= b; case 'G' -> a >= b;
            default -> throw new IllegalStateException(); };
    }

    private static boolean cmp(char op, long a, long b) {
        return switch (op) { case '=' -> a == b; case '!' -> a != b; case '<' -> a < b;
            case '>' -> a > b; case 'L' -> a <= b; case 'G' -> a >= b;
            default -> throw new IllegalStateException(); };
    }

    /** op on compareTo result c(<0/=0/>0)。 */
    private static boolean cmpStr(char op, int c) {
        return switch (op) { case '=' -> c == 0; case '!' -> c != 0; case '<' -> c < 0;
            case '>' -> c > 0; case 'L' -> c <= 0; case 'G' -> c >= 0;
            default -> throw new IllegalStateException(); };
    }

    private static double applyOp(char op, double a, double b) {
        return switch (op) { case '+' -> a + b; case '-' -> a - b; case '*' -> a * b;
            case '/' -> a / b; default -> throw new IllegalStateException("未知 op " + op); };
    }

    private double[] toDoubleArrayInternal() {
        if (dtype == DType.FLOAT64) return doubleData;
        if (dtype == DType.INT64) {
            double[] r = new double[len];
            for (int i = 0; i < len; i++) r[i] = longData[i];
            return r;
        }
        throw new IllegalStateException("toDoubleArrayInternal 仅数值,实际 " + dtype);
    }

    private int norm(int idx) {
        if (idx < 0) idx += len;
        if (idx < 0 || idx > len) throw new IndexOutOfBoundsException("索引 " + idx + " 越界,len=" + len);
        return idx;
    }

    private Ndarray emptyLike() {
        switch (dtype) {
            case INT64:
            case DATETIME64: return new Ndarray(dtype, new long[0], null, null, null);
            case FLOAT64: return new Ndarray(DType.FLOAT64, null, new double[0], null, null);
            case BOOL: return new Ndarray(DType.BOOL, null, null, new Boolean[0], null);
            case OBJECT: default: return new Ndarray(DType.OBJECT, null, null, null, new Object[0]);
        }
    }

    private void requireDType(DType expected, String op) {
        if (dtype != expected) throw new IllegalStateException(
                op + " 要求 " + expected + ",实际 " + dtype);
    }

    private void requireSameLen(Ndarray other, String op) {
        if (this.len != other.len) throw new IllegalArgumentException(
                op + " 要求等长:this.len=" + len + ", other.len=" + other.len);
    }

    private static double objDouble(Object o) {
        if (o == null) return Double.NaN;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    @Override
    public String toString() {
        String head = "Ndarray[" + dtype + ", len=" + len + "]";
        if (len == 0) return head + " {}";
        int cap = Math.min(len, 8);
        StringBuilder sb = new StringBuilder(head).append(" {");
        for (int i = 0; i < cap; i++) {
            if (i > 0) sb.append(", ");
            sb.append(get(i));
        }
        if (len > cap) sb.append(", ...");
        return sb.append("}").toString();
    }
}
