package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// ┌─ What : Schema —— DataFrame 的列名 + 类型描述(对齐 pandas dtypes/info)
// │  Why  : 构造 DataFrame 需要明确每列的名字与 dtype;类型推断(从原始数据)集中在此
// │  Who  : DataFrame.of 用它建表;IO 模块用它做外部类型 ↔ dtype 映射
// │  When : 创建 DataFrame、类型推断、astype、describe
// │  Where: jian-core/Schema.java
// │  How  : 数据走向:列名列表 + dtype 列表 → Schema → DataFrame 按 Schema 创建对应 Column 子类。
// │         关键变量变化:
// │           - names:列名(有序、不重复,重复时 by 文档约定报错);
// │           - dtypes:每列类型,与 names 等长。
// │         逻辑路线:
// │           路径 A(显式给 dtype)→ 直接建 Schema;
// │           路径 B(从 Object[][] 推断)→ infer 逐列扫描判断 dtype。
/**
 * DataFrame 的列结构(列名 + 类型),对齐 pandas 的 dtypes / info 概念。
 *
 * <p>用法:
 * <pre>{@code
 * Schema s = Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE);
 * DataFrame df = DataFrame.of(s, rowData);
 * }</pre>
 */
public final class Schema {

    private final List<String> names;
    private final List<DType> dtypes;

    /**
     * 公开构造(列名列表 + 类型列表)。
     * @param names  List&lt;String&gt; 列名,非 null,元素非 null,**不允许重复**(重复抛异常)
     * @param dtypes List&lt;DType&gt; 每列类型,非 null,长度必须 == names.size()
     * @throws IllegalArgumentException names 与 dtypes 长度不一致,或 names 含重复元素
     */
    public Schema(List<String> names, List<DType> dtypes) {
        if (names.size() != dtypes.size()) {
            throw new IllegalArgumentException("names 与 dtypes 长度须一致:" + names.size() + " vs " + dtypes.size());
        }
        validateUniqueNames(names);
        this.names = new ArrayList<>(names);
        this.dtypes = new ArrayList<>(dtypes);
    }

    /**
     * 交替传 name/dtype 构造(便捷工厂):<code>Schema.of("id", LONG, "name", STRING)</code>。
     * @param nameAndDtype Object... 交替排列的列名(String)与类型(DType),长度必须为偶数;非 null
     * @return Schema 新实例
     * @throws IllegalArgumentException 长度为奇数,或某元素类型不是 String/DType,或列名重复
     */
    public static Schema of(Object... nameAndDtype) {
        if (nameAndDtype.length % 2 != 0) {
            throw new IllegalArgumentException("nameAndDtype 必须成对,实际长度 " + nameAndDtype.length);
        }
        List<String> names = new ArrayList<>();
        List<DType> dtypes = new ArrayList<>();
        for (int i = 0; i < nameAndDtype.length; i += 2) {
            names.add((String) nameAndDtype[i]);
            dtypes.add((DType) nameAndDtype[i + 1]);
        }
        return new Schema(names, dtypes);
    }

    /**
     * 列名列表(副本,防外部修改)。
     * @return List&lt;String&gt; 列名副本,顺序与构造时一致;非 null
     */
    public List<String> names() { return new ArrayList<>(names); }

    /**
     * 类型列表(副本)。
     * @return List&lt;DType&gt; 类型副本;非 null
     */
    public List<DType> dtypes() { return new ArrayList<>(dtypes); }

    /**
     * 列数。
     * @return int 列数,≥ 0
     */
    public int columnCount() { return names.size(); }

    /**
     * 列名 → 列下标。
     * @param name String 列名,非 null
     * @return int 该列下标 ∈ [0, columnCount());不存在返回 -1
     */
    public int indexOf(String name) {
        for (int i = 0; i < names.size(); i++) if (names.get(i).equals(name)) return i;
        return -1;
    }

    /**
     * 取某列类型(按列名)。
     * @param name String 列名,非 null
     * @return DType 该列类型
     * @throws IllegalArgumentException 列名不存在(消息含现有列清单)
     */
    public DType dtypeOf(String name) {
        int i = indexOf(name);
        if (i < 0) throw new IllegalArgumentException("列不存在:" + name + ",现有列:" + names);
        return dtypes.get(i);
    }

    /**
     * 取某列类型(按下标)。
     * @param i int 列下标,范围 [0, columnCount());越界抛 IndexOutOfBoundsException
     * @return DType 该列类型
     */
    public DType dtypeAt(int i) { return dtypes.get(i); }

    /**
     * 取某列列名(按下标)。
     * @param i int 列下标,范围 [0, columnCount());越界抛 IndexOutOfBoundsException
     * @return String 列名,非 null
     */
    public String nameAt(int i) { return names.get(i); }

    /**
     * 从二维 Object 数组推断 Schema(对齐 pandas read_csv 后的类型推断)。
     *
     * <p>推断规则(从严到宽):全 int → INT;含 long → LONG;含 double/科学计数 → DOUBLE;
     * 全 true/false → BOOL;全 yyyy-MM-dd[ HH:mm:ss] → DATE/DATETIME;否则 → STRING;
     * 空列默认 STRING。
     *
     * @param columnNames List&lt;String&gt; 列名,非 null;长度 = data 的列数(列数推断以此为据)
     * @param data        Object[][] 行优先二维数组(data[row][col]),允许 null(空数据)或 length==0;
     *                    每行允许 null 或长度不足(该行缺列按 null 处理);元素类型任意
     * @return Schema 推断出的 schema:空数据时所有列默认 STRING
     */
    public static Schema infer(List<String> columnNames, Object[][] data) {
        Objects.requireNonNull(columnNames, "columnNames 不能为 null");
        if (data == null || data.length == 0) {
            // 空数据:所有列默认 STRING
            List<DType> dtypes = new ArrayList<>();
            for (int i = 0; i < columnNames.size(); i++) dtypes.add(DType.STRING);
            return new Schema(columnNames, dtypes);
        }
        int cols = columnNames.size();
        List<DType> dtypes = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            dtypes.add(inferColumn(data, c));
        }
        return new Schema(columnNames, dtypes);
    }

    /**
     * 单列类型推断(私有,被 infer 调用)。
     * @param data Object[][] 行优先数据
     * @param c    int 待推断的列下标
     * @return DType 推断结果(空列返回 STRING 兜底)
     */
    private static DType inferColumn(Object[][] data, int c) {
        // 修复(assign 空表 IOOBE):当 data 是 [[],[],...] 形式(外层非空但每行空)时,
        // row[c] 会 IOOBE。加守卫——所有行长度都 ≤ c 时,该列无数据,返回 STRING 兜底。
        boolean hasData = false;
        for (Object[] row : data) {
            if (row != null && row.length > c) { hasData = true; break; }
        }
        if (!hasData) return DType.STRING;   // 全空列,STRING 兜底(与 pandas object 一致)

        boolean hasInt = false, hasLong = false, hasDouble = false;
        boolean hasBool = false, hasString = false, hasDate = false, hasDateTime = false;
        boolean hasNullOnly = true;
        for (Object[] row : data) {
            if (row == null || row.length <= c) continue;
            Object v = row[c];
            if (v == null) continue;
            hasNullOnly = false;
            if (v instanceof Integer) hasInt = true;
            else if (v instanceof Long) hasLong = true;
            else if (v instanceof Double || v instanceof Float) hasDouble = true;
            else if (v instanceof Boolean) hasBool = true;
            else if (v instanceof java.time.LocalDate) hasDate = true;
            else if (v instanceof java.time.LocalDateTime) hasDateTime = true;
            else if (v instanceof String) {
                String s = (String) v;
                // 尝试数值/日期推断
                if (s.matches("-?\\d+")) {
                    long lv = Long.parseLong(s);
                    if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) hasInt = true;
                    else hasLong = true;
                } else if (s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?") || s.matches("-?\\d+[eE][+-]?\\d+")) {
                    hasDouble = true;
                } else if (s.equals("true") || s.equals("false")) {
                    hasBool = true;
                } else if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    hasDate = true;
                } else if (s.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}")) {
                    hasDateTime = true;
                } else {
                    hasString = true;
                }
            } else {
                hasString = true;  // 其它类型兜底
            }
        }
        if (hasNullOnly) return DType.STRING;
        // 优先级:STRING(显式非数字字符串) > DATETIME > DATE > DOUBLE > LONG > INT > BOOL
        if (hasString) return DType.STRING;
        if (hasDateTime) return DType.DATETIME;
        if (hasDate) return DType.DATE;
        if (hasDouble) return DType.DOUBLE;
        if (hasLong) return DType.LONG;
        if (hasInt) return DType.INT;
        if (hasBool) return DType.BOOL;
        return DType.STRING;
    }

    /**
     * 校验列名不重复(规范 01 §9:重复名 + allows_duplicate_labels=false 抛异常)。
     * @param names List&lt;String&gt; 列名列表
     * @throws IllegalArgumentException 含重复列名(消息含重复名与位置)
     */
    private static void validateUniqueNames(List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (names.get(i).equals(names.get(j))) {
                    throw new IllegalArgumentException(
                            "列名重复:\"" + names.get(i) + "\"(位置 " + i + " 与 " + j + ")");
                }
            }
        }
    }

    /**
     * 字符串描述(每行列名:类型)。
     * @return String 多行格式,形如 "Schema{\n  id : LONG\n  ...\n}"
     */
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("Schema{\n");
        for (int i = 0; i < names.size(); i++) {
            sb.append("  ").append(names.get(i)).append(" : ").append(dtypes.get(i)).append('\n');
        }
        return sb.append('}').toString();
    }
}
