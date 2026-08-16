package jian.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// ┌─ What : DataFrameConstruct —— DataFrame 的构造工厂(从 DataFrame.java 拆出,落实 §3.1 ≤600 行红线)
// │  Why  : DataFrame.java 主类 1401 非注释行(2.3× 红线);构造簇(of/buildColumn/buildFromArrays)
// │         是最大块(~190 行),自包含(只被 of/assign 调),适合独立成伴生类。
// │  Who  : 由 DataFrame.of/ofColumns/ofColumnArrays/ofColumnArraysSafe 静态委托调用;assign 调 buildColumn
// │  When : 任何 DataFrame 构造场景
// │  Where: jian-core/DataFrameConstruct.java
// │  How  : 数据走向:外部 Object[][]/Map/primitive[] → Schema 推断 + 列分发(buildColumn/buildFromArrays)
// │           → List<Column> → DataFrame.ofColumnsDirect(构造原子,留主类)→ DataFrame。
// │         关键变量:
// │           - dtypes(buildFromArrays 按 Java 数组类型映射 DType);
// │           - mask(buildColumn 对 INT/LONG/BOOL 的缺失行置 true);
// │           - zeroCopyWarned(线程安全 once 标志,§3.7.7 例外)。
// │         逻辑路线:
// │           路径 A(of)→ schema 指定 dtype → buildColumn 每 c 列 → ofColumnsDirect;
// │           路径 B(ofColumns)→ Map 转置 → Schema.infer → of;
// │           路径 C(ofColumnArrays)→ primitive 数组类型映射 dtype → wrapNoCopy 零拷贝;
// │           路径 D(ofColumnArraysSafe)→ 同 C 但先 clone 每个数组(防御)。
final class DataFrameConstruct {
    private DataFrameConstruct() {}

    /** 零拷贝安全提示标志(线程安全 once,§3.7.7 "无静态可变状态"的例外:仅提示一次)。 */
    private static volatile boolean zeroCopyWarned = false;

    /** 首次零拷贝调用时 stderr 提示一次(提醒 Web 场景改用 ofColumnArraysSafe)。 */
    private static void warnZeroCopyOnce() {
        if (!zeroCopyWarned) {
            synchronized (DataFrameConstruct.class) {
                if (!zeroCopyWarned) {
                    System.err.println("[jian] 提示: DataFrame.ofColumnArrays 是零拷贝(直接引用入参数组)。"
                        + "Web/安全场景请改用 ofColumnArraysSafe(防御性 clone)。"
                        + "此提示仅出现一次。");
                    zeroCopyWarned = true;
                }
            }
        }
    }

    /** 从 Schema + 行数据构造(列式分发:每列按 dtype 建 Column)。 */
    static DataFrame of(Schema schema, Object[][] rows) {
        Objects.requireNonNull(schema, "schema 不能为 null");
        int cols = schema.columnCount();
        int n = rows == null ? 0 : rows.length;
        List<Column> columns = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            columns.add(buildColumn(schema.nameAt(c), schema.dtypeAt(c), rows, c, n));
        }
        return DataFrame.ofColumnsDirect(columns);
    }

    /** 从列式 Map<String,Object[]> 构造(类型推断)。 */
    static DataFrame ofColumns(Map<String, Object[]> columnsByName) {
        Objects.requireNonNull(columnsByName, "columnsByName 不能为 null");
        List<String> names = new ArrayList<>(columnsByName.keySet());
        List<Object[]> cols = new ArrayList<>(columnsByName.values());
        int n = cols.isEmpty() ? 0 : cols.get(0).length;
        Object[][] rows = new Object[n][names.size()];
        for (int r = 0; r < n; r++)
            for (int c = 0; c < names.size(); c++)
                rows[r][c] = cols.get(c)[r];
        return of(Schema.infer(names, rows), rows);
    }

    /** 直接用 primitive 数组构造(零拷贝,高性能路径)。 */
    static DataFrame ofColumnArrays(List<String> columnNames, Object[] columnArrays) {
        warnZeroCopyOnce();
        return buildFromArrays(columnNames, columnArrays);
    }

    /** 安全版本——防御性 clone 所有入参数组(给 Web/安全敏感场景用)。 */
    static DataFrame ofColumnArraysSafe(List<String> columnNames, Object[] columnArrays) {
        Objects.requireNonNull(columnNames, "columnNames 不能为 null");
        if (columnNames.size() != columnArrays.length) {
            throw new IllegalArgumentException(
                "列名数 " + columnNames.size() + " ≠ 数组数 " + columnArrays.length);
        }
        Object[] cloned = new Object[columnArrays.length];
        for (int c = 0; c < columnArrays.length; c++) {
            Object arr = columnArrays[c];
            Objects.requireNonNull(arr, "第 " + c + " 列数组为 null");
            int len = java.lang.reflect.Array.getLength(arr);
            cloned[c] = java.lang.reflect.Array.newInstance(arr.getClass().getComponentType(), len);
            System.arraycopy(arr, 0, cloned[c], 0, len);
        }
        return buildFromArrays(columnNames, cloned);
    }

    /**
     * 从列数组构建 DataFrame 的核心逻辑(被 ofColumnArrays 和 ofColumnArraysSafe 共用)。
     * 调用方负责决定是否 clone(零拷贝 vs 安全)。
     */
    private static DataFrame buildFromArrays(List<String> columnNames, Object[] columnArrays) {
        Objects.requireNonNull(columnNames, "columnNames 不能为 null");
        if (columnNames.size() != columnArrays.length) {
            throw new IllegalArgumentException(
                "列名数 " + columnNames.size() + " ≠ 数组数 " + columnArrays.length);
        }
        int n = -1;
        for (int c = 0; c < columnArrays.length; c++) {
            Object arr = columnArrays[c];
            Objects.requireNonNull(arr, "第 " + c + " 列数组为 null");
            int len = java.lang.reflect.Array.getLength(arr);
            if (n == -1) n = len;
            else if (len != n) {
                throw new IllegalArgumentException(
                    "列长度不一致:第 0 列=" + n + " 行,第 " + c + " 列=" + len + " 行");
            }
        }
        if (n == -1) n = 0;

        List<DType> dtypes = new ArrayList<>(columnNames.size());
        for (Object arr : columnArrays) {
            if (arr instanceof long[])         dtypes.add(DType.LONG);
            else if (arr instanceof double[])  dtypes.add(DType.DOUBLE);
            else if (arr instanceof int[])     dtypes.add(DType.INT);
            else if (arr instanceof boolean[]) dtypes.add(DType.BOOL);
            else if (arr instanceof String[])  dtypes.add(DType.STRING);
            else if (arr instanceof java.time.LocalDate[]) dtypes.add(DType.DATE);
            else if (arr instanceof java.time.LocalDateTime[]) dtypes.add(DType.DATETIME);
            else if (arr != null && arr.getClass().isArray()
                    && arr.getClass().getComponentType().isArray()) {
                throw new IllegalArgumentException(
                    "不支持多维数组 " + arr.getClass().getSimpleName()
                    + ";ofColumnArrays 仅接受一维数组(long[]/int[]/double[]/boolean[]/String[]/Object[])");
            }
            else if (arr instanceof Object[])  dtypes.add(DType.OBJECT);
            else {
                throw new IllegalArgumentException(
                    "不支持的数组类型 " + arr.getClass().getSimpleName()
                    + ";jian DType 仅支持 long[]→LONG, int[]→INT, double[]→DOUBLE, "
                    + "boolean[]→BOOL, String[]→STRING, Object[]→OBJECT;"
                    + "short/float/byte/char 请先手动转 long/int/double");
            }
        }
        Schema schema = new Schema(columnNames, dtypes);
        List<Column> columns = new ArrayList<>(columnNames.size());
        for (int c = 0; c < columnNames.size(); c++) {
            String name = columnNames.get(c);
            Object arr = columnArrays[c];
            DType dt = dtypes.get(c);
            switch (dt) {
                case LONG: columns.add(LongColumn.wrapNoCopy(name, (long[]) arr, null)); break;
                case DOUBLE: columns.add(DoubleColumn.wrapNoCopy(name, (double[]) arr)); break;
                case INT: columns.add(IntColumn.wrapNoCopy(name, (int[]) arr, null)); break;
                case BOOL: columns.add(BoolColumn.wrapNoCopy(name, (boolean[]) arr, null)); break;
                case STRING: columns.add(StringColumn.wrapNoCopy(name, (String[]) arr)); break;
                case DATE: columns.add(DateColumn.wrapNoCopy(name, (java.time.LocalDate[]) arr)); break;
                case DATETIME: columns.add(DateTimeColumn.wrapNoCopy(name, (java.time.LocalDateTime[]) arr)); break;
                default: {
                    Object[] src = (Object[]) arr;
                    Object[] copy = new Object[src.length];
                    System.arraycopy(src, 0, copy, 0, src.length);
                    columns.add(new ObjectColumn(name, copy));
                    break;
                }
            }
        }
        return DataFrame.ofColumnsDirect(columns);
    }

    /**
     * 按 dtype + 列数据建对应 Column 子类(列分发核心)。
     * 包级可见:被 of(本类)与 DataFrame.assign(主类)共用。
     */
    static Column buildColumn(String name, DType dt, Object[][] rows, int c, int n) {
        switch (dt) {
            case INT: {
                int[] data = new int[n];
                boolean[] mask = new boolean[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) { mask[r] = true; data[r] = 0; }
                    else data[r] = (int) toLongExact(toNumber(v), name, r);
                }
                return new IntColumn(name, data, mask);
            }
            case LONG: {
                long[] data = new long[n];
                boolean[] mask = new boolean[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) { mask[r] = true; data[r] = 0; }
                    else data[r] = toLongExact(toNumber(v), name, r);
                }
                return new LongColumn(name, data, mask);
            }
            case DOUBLE: {
                double[] data = new double[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) data[r] = Double.NaN;
                    else data[r] = toNumber(v).doubleValue();
                }
                return new DoubleColumn(name, data);
            }
            case BOOL: {
                boolean[] data = new boolean[n];
                boolean[] mask = new boolean[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) { mask[r] = true; }
                    else if (v instanceof Boolean) data[r] = (Boolean) v;
                    else data[r] = Boolean.parseBoolean(((String) v).trim());
                }
                return new BoolColumn(name, data, mask);
            }
            case STRING: {
                String[] data = new String[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    data[r] = v == null ? null : v.toString();
                }
                return new StringColumn(name, data);
            }
            case DATETIME: {
                java.time.LocalDateTime[] data = new java.time.LocalDateTime[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) data[r] = null;
                    else if (v instanceof java.time.LocalDateTime) data[r] = (java.time.LocalDateTime) v;
                    else data[r] = java.time.LocalDateTime.parse(((String) v).trim().replace(' ', 'T'));
                }
                return new DateTimeColumn(name, data);
            }
            case DATE: {
                java.time.LocalDate[] data = new java.time.LocalDate[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) data[r] = null;
                    else if (v instanceof java.time.LocalDate) data[r] = (java.time.LocalDate) v;
                    else data[r] = java.time.LocalDate.parse(((String) v).trim());
                }
                return new DateColumn(name, data);
            }
            case CATEGORY: {
                String[] data = new String[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    data[r] = v == null ? null : v.toString();
                }
                return CategoryColumn.fromStrings(name, data);
            }
            case OBJECT: default: {
                Object[] data = new Object[n];
                for (int r = 0; r < n; r++) {
                    data[r] = rows[r] == null ? null : rows[r][c];
                }
                return new ObjectColumn(name, data);
            }
        }
    }

    /** 把 Number 或数字 String 转为 Number(供 buildColumn 处理 Schema 推断后的混合值)。 */
    /**
     * 克隆一列并赋新名追加到表尾(SELECT c2,c2 重复列需两个独立列对象;
     * 公开入口见 {@link DataFrame#withColumnClone})。
     * @param df DataFrame 原表,非 null
     * @param colName String 被克隆的列名,必须存在
     * @param newName String 克隆列的新名
     * @return DataFrame 追加克隆列后的新表(行数不变)
     */
    static DataFrame addColumnClone(DataFrame df, String colName, String newName) {
        Column src = df.getColumn(colName);
        List<Column> cs = new java.util.ArrayList<>(df.columnCount() + 1);
        for (String n : df.columnNames()) cs.add(df.getColumn(n));
        cs.add(src.rename(newName));
        return DataFrame.ofColumnsDirect(cs);
    }

    private static Number toNumber(Object v) {
        if (v instanceof Number) return (Number) v;
        String s = v.toString().trim();
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return Double.parseDouble(s); }
    }

    /**
     * 数值 → long 精确转换:显式 LONG/INT schema 遇超范围值(BigInteger/BigDecimal 超出 long,
     * 或 double 有小数部分)**不静默截断** —— BigInteger("99999999999999999999") 进 LONG 列
     * 会被截为 7766279631452241919(数据损坏且不可见)。
     * 双规则:推断路径(Schema.infer)已把超大整数归 STRING(对齐 pandas object);
     * 显式 LONG 列属用户明确声明,fail-fast 抛 IAE 并教学。
     * @param num Number 待转数值,非 null
     * @param colName String 列名(异常消息用)
     * @param row int 行号(异常消息用)
     * @return long 精确值;超范围抛 IAE
     */
    private static long toLongExact(Number num, String colName, int row) {
        if (num instanceof java.math.BigInteger || num instanceof java.math.BigDecimal) {
            try {
                java.math.BigInteger bi = num instanceof java.math.BigDecimal bd
                        ? bd.toBigIntegerExact() : (java.math.BigInteger) num;
                return bi.longValueExact();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("列 '" + colName + "' 第 " + row + " 行值 " + num
                        + " 超出 long 范围,显式 LONG/INT schema 不做静默截断;"
                        + "请改用 STRING 列(推断路径自动归 STRING,对齐 pandas object)");
            }
        }
        return num.longValue();
    }

    /** assign:按行号函数生成新列(类型经 Schema.infer + buildColumn;主类委托,P3 拆分)。 */
    static DataFrame assign(DataFrame df, String newName, java.util.function.IntFunction<Object> fn) {
        if (df.columnIndex(newName) >= 0) {
            throw new IllegalArgumentException("assign 目标列 \"" + newName + "\" 已存在");
        }
        int nRows = df.rowCount();
        Object[] vals = new Object[nRows];
        for (int r = 0; r < nRows; r++) vals[r] = fn.apply(r);
        Schema sub = Schema.infer(java.util.List.of(newName), new Object[][]{vals});
        Object[][] wrapped = new Object[nRows][1];
        for (int r = 0; r < nRows; r++) wrapped[r][0] = vals[r];
        Column newCol = buildColumn(newName, sub.dtypeAt(0), wrapped, 0, nRows);
        List<Column> newCols = new ArrayList<>(df.columnsInternal());
        newCols.add(newCol);
        return df.rebuild(newCols, df.index());
    }
}
