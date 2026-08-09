package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// ┌─ What : DataFrame —— jian-core 的核心数据结构(对齐 pandas DataFrame)
// │  Why  : 规范 01 全分册;DataFrame 是 jian 所有子模块的基石,列式存储 + 不可变优先 + 链式调用
// │  Who  : 用户代码直接 new;io/viz/export/dsl 全部围绕它
// │  When : 任何表格数据操作场景
// │  Where: jian-core/DataFrame.java(主体,变换方法分散在 M1.2/M1.3/M2 各 companion 类)
// │  How  : 数据走向:外部 Object[][]/Map/List → Schema 推断 + 列分发到对应 Column 子类 →
// │         DataFrame(持有 Index + List<Column>)→ 变换产生新 DataFrame → 输出/统计。
// │         关键变量变化:
// │           - columns:List<Column>,长度=列数,每个 Column 长度=行数;
// │           - index:行标签;
// │           - nRows/nCols:缓存行列数,避免反复遍历。
// │         逻辑路线:
// │           路径 A(构造)→ 校验各列等长 + 列名不重复 → 建 DataFrame;
// │           路径 B(选择 select/drop)→ 子集列 → 新 DataFrame;
// │           路径 C(过滤 filter)→ 逐列按 mask 筛 → 新 DataFrame;
// │           路径 D(行切片 head/tail/iloc)→ 逐列切片 + Index 同步 → 新 DataFrame。
/**
 * DataFrame,对齐 pandas.DataFrame。
 *
 * <p><b>三要素</b>:{@link Index}(行标签)+ 有序且不重复的列名 + 各列 {@link Column} 数据。
 * <p><b>不可变优先</b>(规范 §4.3):变换返回新 DataFrame。
 * <p><b>链式</b>:{@code df.filter(...).select(...).sortBy(...).head(10)}。
 *
 * <h2>构造示例</h2>
 * <pre>{@code
 * DataFrame df = DataFrame.of(
 *     Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
 *     new Object[][]{
 *         {1L, "alice", 90.5},
 *         {2L, "bob",   85.0},
 *         {3L, "carol", null}
 *     });
 * }</pre>
 */
public final class DataFrame {

    private final List<Column> columns;
    private final Index index;
    private final int nRows;
    private final boolean allowsDuplicateLabels;  // 规范 01 §3.12,默认 false

    private DataFrame(List<Column> columns, Index index, boolean allowsDup) {
        this.columns = columns;
        this.index = index;
        this.nRows = columns.isEmpty() ? 0 : columns.get(0).size();
        this.allowsDuplicateLabels = allowsDup;
    }

    /**
     * 直接用已构造好的 Column 列表建 DataFrame(高性能 hot path)。
     *
     * <p><b>注意</b>:零拷贝——直接引用传入的 columns 列表与各 Column,调用方此后不应再修改。
     * 仅供 merge/groupby 等已知不修改的热路径使用(如 merge fast path 用 toColumn 构造带 nullMask 的列)。
     *
     * @param columns 各列(类型可以是 LongColumn/DoubleColumn/...;行数由首列决定)
     */
    public static DataFrame ofColumnsDirect(java.util.List<Column> columns) {
        Objects.requireNonNull(columns, "columns 不能为 null");
        int n = columns.isEmpty() ? 0 : columns.get(0).size();
        // 校验各列等长
        for (int c = 0; c < columns.size(); c++) {
            if (columns.get(c).size() != n) {
                throw new IllegalArgumentException(
                    "列长度不一致:第 0 列=" + n + " 行,第 " + c + " 列=" + columns.get(c).size() + " 行");
            }
        }
        return new DataFrame(new ArrayList<>(columns), Index.range(n), false);
    }

    // ======================== 构造工厂 ========================

    /**
     * 从 Schema + 行数据构造(列式分发:每列按 dtype 建 Column)。
     *
     * @param schema 列名 + 类型
     * @param rows 行优先二维数组(rows[row][col]),null 表示缺失
     */
    public static DataFrame of(Schema schema, Object[][] rows) {
        Objects.requireNonNull(schema, "schema 不能为 null");
        int cols = schema.columnCount();
        int n = rows == null ? 0 : rows.length;
        List<Column> columns = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            String name = schema.nameAt(c);
            DType dt = schema.dtypeAt(c);
            columns.add(buildColumn(name, dt, rows, c, n));
        }
        return new DataFrame(columns, Index.range(n), false);
    }

    /**
     * 从列式 Map&lt;列名, Object[]&gt; 构造(类型推断)。
     * @param columnsByName Map&lt;String,Object[]&gt; 列名→列数据;非 null;每列长度须一致
     * @return DataFrame 类型推断后构造的 DataFrame
     */
    public static DataFrame ofColumns(Map<String, Object[]> columnsByName) {
        Objects.requireNonNull(columnsByName, "columnsByName 不能为 null");
        List<String> names = new ArrayList<>(columnsByName.keySet());
        List<Object[]> cols = new ArrayList<>(columnsByName.values());
        int n = cols.isEmpty() ? 0 : cols.get(0).length;
        // 转 Object[][] 推断
        Object[][] rows = new Object[n][names.size()];
        for (int r = 0; r < n; r++)
            for (int c = 0; c < names.size(); c++)
                rows[r][c] = cols.get(c)[r];
        Schema schema = Schema.infer(names, rows);
        return of(schema, rows);
    }

    /**
     * 直接用 primitive 数组构造 DataFrame(零拷贝,高性能路径)。
     *
     * <p>每个数组按 Java 类型映射到 DType:
     * <ul>
     *   <li>{@code long[]} → LONG</li>
     *   <li>{@code double[]} → DOUBLE(DNaN 视为缺失)</li>
     *   <li>{@code int[]} → INT</li>
     *   <li>{@code boolean[]} → BOOL</li>
     *   <li>{@code String[]} → STRING</li>
     *   <li>其它 {@code Object[]} → OBJECT</li>
     * </ul>
     *
     * <p><b>⚠️ 安全警告</b>:零拷贝——直接引用传入的数组。
     * 调用方此后<b>不应</b>再修改这些数组,否则"不可变"的 DataFrame 内容会被外部修改。
     * <ul>
     *   <li><b>内部热路径</b>(merge/groupby/benchmark):jian 自己的代码,构造后不再改数组,安全</li>
     *   <li><b>Web/安全场景</b>:用户可能传入外部可控数组后继续修改 → 用 {@link #ofColumnArraysSafe} 替代(clone 防御)</li>
     * </ul>
     * 首次调用时会输出 stderr 安全提示(仅一次,提醒开发者检查是否需要用 Safe 版本)。
     *
     * @param columnNames 列名(顺序与 columnArrays 对应);非 null
     * @param columnArrays primitive 数组({@code long[]}/{@code double[]}/{@code Object[]} 等);非 null
     * @return DataFrame 零拷贝构造(直接引用入参数组)
     * @see #ofColumnArraysSafe 安全版本(防御性 clone,给 Web 场景用)
     */
    public static DataFrame ofColumnArrays(List<String> columnNames, Object[] columnArrays) {
        // 安全提示:首次调用时输出(静态标志保证只打一次,不影响性能)
        warnZeroCopyOnce();
        return buildFromArrays(columnNames, columnArrays);
    }

    /**
     * 内部:从列数组构建 DataFrame 的核心逻辑(被 ofColumnArrays 和 ofColumnArraysSafe 共用)。
     * 调用方负责决定是否 clone(零拷贝 vs 安全)。
     */
    private static DataFrame buildFromArrays(List<String> columnNames, Object[] columnArrays) {
        Objects.requireNonNull(columnNames, "columnNames 不能为 null");
        if (columnNames.size() != columnArrays.length) {
            throw new IllegalArgumentException(
                "列名数 " + columnNames.size() + " ≠ 数组数 " + columnArrays.length);
        }
        // BUG #5 修复:校验各列数组长度一致(否则后续 filter/slice/get 行为未定义)
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
        if (n == -1) n = 0;  // 空数组列表

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
                // 第 3 轮 BUG #3 修复:多维数组(long[][]/int[][]/Object[][])明确报错
                // 注意:必须放在 instanceof Object[] 之前 —— 因为 Java 数组协变,
                // long[][] instanceof Object[] == true,会先误判 OBJECT 然后强转 CCE
                throw new IllegalArgumentException(
                    "不支持多维数组 " + arr.getClass().getSimpleName()
                    + ";ofColumnArrays 仅接受一维数组(long[]/int[]/double[]/boolean[]/String[]/Object[])");
            }
            else if (arr instanceof Object[])  dtypes.add(DType.OBJECT);
            else {
                // short[]/float[]/byte[]/char[] 等 jian DType 不支持的 primitive 数组
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
                case INT:
                    // BUG #4 修复:int[] 保留 INT 类型(不再升位为 LONG),用 IntColumn.wrapNoCopy
                    columns.add(IntColumn.wrapNoCopy(name, (int[]) arr, null));
                    break;
                case BOOL: {
                    boolean[] src = (boolean[]) arr;
                    columns.add(BoolColumn.wrapNoCopy(name, src, null));
                    break;
                }
                case STRING:
                    // BUG #3 修复:String[] 用 StringColumn(不再误判为 OBJECT)
                    columns.add(StringColumn.wrapNoCopy(name, (String[]) arr));
                    break;
                case DATE:
                    // AI agent2 BUG A 配套:LocalDate[] 用 DateColumn(不再误判为 OBJECT)
                    columns.add(DateColumn.wrapNoCopy(name, (java.time.LocalDate[]) arr));
                    break;
                case DATETIME:
                    columns.add(DateTimeColumn.wrapNoCopy(name, (java.time.LocalDateTime[]) arr));
                    break;
                default: {
                    Object[] src = (Object[]) arr;
                    Object[] copy = new Object[src.length];
                    System.arraycopy(src, 0, copy, 0, src.length);
                    columns.add(new ObjectColumn(name, copy));
                    break;
                }
            }
        }
        return new DataFrame(columns, Index.range(n), false);
    }

    /**
     * 安全版本——防御性 clone 所有入参数组(给 Web/安全敏感场景用)。
     *
     * <p>与 {@link #ofColumnArrays} 参数/返回值完全相同,区别是:
     * <ul>
     *   <li>{@link #ofColumnArrays}:零拷贝(快,但调用方修改原数组会改变 DataFrame 内容)</li>
     *   <li>{@code ofColumnArraysSafe}:clone 所有数组(慢,但 DataFrame 完全独立,外部修改不影响)</li>
     * </ul>
     *
     * <p><b>使用建议</b>:
     * <ul>
     *   <li>Web 服务(Tomcat/Spring Boot):用 <b>Safe 版本</b>(用户可控数据)</li>
     *   <li>内部热路径(merge/groupby/benchmark):用 {@link #ofColumnArrays}(jian 自己的代码)</li>
     *   <li>不确定:用 Safe 版本(安全优先)</li>
     * </ul>
     *
     * @param columnNames 列名(顺序与 columnArrays 对应);非 null
     * @param columnArrays primitive 数组;非 null。方法内部会 clone 每个数组
     * @return DataFrame 防御性拷贝构造(与入参数组完全独立)
     */
    public static DataFrame ofColumnArraysSafe(List<String> columnNames, Object[] columnArrays) {
        Objects.requireNonNull(columnNames, "columnNames 不能为 null");
        if (columnNames.size() != columnArrays.length) {
            throw new IllegalArgumentException(
                "列名数 " + columnNames.size() + " ≠ 数组数 " + columnArrays.length);
        }
        // 防御性 clone 每个数组
        Object[] cloned = new Object[columnArrays.length];
        for (int c = 0; c < columnArrays.length; c++) {
            Object arr = columnArrays[c];
            Objects.requireNonNull(arr, "第 " + c + " 列数组为 null");
            int len = java.lang.reflect.Array.getLength(arr);
            cloned[c] = java.lang.reflect.Array.newInstance(arr.getClass().getComponentType(), len);
            System.arraycopy(arr, 0, cloned[c], 0, len);
        }
        // clone 后的数组是独立的,直接调 buildFromArrays(不需要安全提示)
        return buildFromArrays(columnNames, cloned);
    }

    /**
     * 零拷贝安全提示(仅首次调用,stderr 输出一次)。
     * 提醒开发者 ofColumnArrays 是零拷贝,Web 场景应改用 ofColumnArraysSafe。
     */
    private static volatile boolean zeroCopyWarned = false;
    private static void warnZeroCopyOnce() {
        if (!zeroCopyWarned) {
            synchronized (DataFrame.class) {
                if (!zeroCopyWarned) {
                    System.err.println("[jian] 提示: DataFrame.ofColumnArrays 是零拷贝(直接引用入参数组)。"
                        + "Web/安全场景请改用 ofColumnArraysSafe(防御性 clone)。"
                        + "此提示仅出现一次。");
                    zeroCopyWarned = true;
                }
            }
        }
    }

    /**
     * 从列数组构建 DataFrame 的核心逻辑(被 ofColumnArrays 和 ofColumnArraysSafe 共用)。
     */
    /** 按 dtype + 列数据建对应 Column 子类(列分发核心)。 */
    private static Column buildColumn(String name, DType dt, Object[][] rows, int c, int n) {
        switch (dt) {
            case INT: {
                int[] data = new int[n];
                boolean[] mask = new boolean[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) { mask[r] = true; data[r] = 0; }
                    else data[r] = toNumber(v).intValue();
                }
                return new IntColumn(name, data, mask);
            }
            case LONG: {
                long[] data = new long[n];
                boolean[] mask = new boolean[n];
                for (int r = 0; r < n; r++) {
                    Object v = rows[r] == null ? null : rows[r][c];
                    if (v == null) { mask[r] = true; data[r] = 0; }
                    else data[r] = toNumber(v).longValue();
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

    // ======================== 属性(简单 getter,不强制 5W1H,但补返回类型)========================

    /** @return int 行数,≥ 0 */
    public int rowCount() { return nRows; }
    /** @return int 列数,≥ 0 */
    public int columnCount() { return columns.size(); }
    /** @return int[] {行数, 列数} */
    public int[] shape() { return new int[]{nRows, columns.size()}; }
    /**
     * @return int 元素总数 = 行数 × 列数。
     *         <b>溢出保护</b>:用 {@link Math#multiplyExact(int, int)} 检测溢出
     *         (行数 × 列数 > Integer.MAX_VALUE 时抛 ArithmeticException,
     *         不静默返回负数);jian 定位「单机千万行」(< 2.1 亿行 × 1 列)实际不会触发,
     *         但 API 行为应明确而非静默错。
     */
    public int size() { return Math.multiplyExact(nRows, columns.size()); }
    /** @return boolean true=空表(行数 0) */
    public boolean isEmpty() { return nRows == 0; }
    /** @return Index 行索引(直接引用,非拷贝) */
    public Index index() { return index; }
    /** @return boolean 是否允许重复列名 */
    public boolean allowsDuplicateLabels() { return allowsDuplicateLabels; }

    /** 列名列表(有序)。 */
    public List<String> columnNames() {
        List<String> r = new ArrayList<>(columns.size());
        for (Column c : columns) r.add(c.name());
        return r;
    }

    /** 各列 dtype(有序)。 */
    public List<DType> dtypes() {
        List<DType> r = new ArrayList<>(columns.size());
        for (Column c : columns) r.add(c.dtype());
        return r;
    }

    /**
     * 列名 → 列下标。
     * @param name String 列名,非 null
     * @return int 该列下标 ∈ [0, columnCount());不存在返回 -1
     */
    public int columnIndex(String name) {
        for (int i = 0; i < columns.size(); i++)
            if (columns.get(i).name().equals(name)) return i;
        return -1;
    }

    // ======================== 取列 ========================

    /**
     * 按列名取 Column(类型不安全,需调用方转型)。
     * @param name String 列名,必须存在;非 null
     * @return Column 该列直接引用(非拷贝)
     * @throws IllegalArgumentException 列不存在
     */
    public Column getColumn(String name) {
        int i = requireColumn(name);
        return columns.get(i);
    }

    /**
     * 按列名取 Series(对齐 pandas,单列操作入口)。
     * @param name String 列名,必须存在;非 null
     * @return Series 包装该列
     */
    public Series getSeries(String name) {
        return Series.of(getColumn(name));
    }

    /**
     * 按列名取 StringColumn(类型安全)。
     * @param name String 列名,必须存在且 dtype==STRING;非 null
     * @return StringColumn 该列
     * @throws IllegalStateException 列非 STRING 类型
     */
    public StringColumn getStringColumn(String name) {
        Column c = getColumn(name);
        if (!(c instanceof StringColumn)) {
            throw new IllegalStateException("列 \"" + name + "\" 不是 STRING,实际 " + c.dtype());
        }
        return (StringColumn) c;
    }

    /**
     * 取 DoubleColumn(类型化访问器)。
     * @param name String 列名,必须存在;非 null
     * @return DoubleColumn;INT/LONG 列会转 DOUBLE 返回新实例
     * @throws IllegalStateException 列非数值类型
     */
    public DoubleColumn getDoubleColumn(String name) {
        Column c = getColumn(name);
        if (c instanceof DoubleColumn) return (DoubleColumn) c;
        // 数值列兼容:INT/LONG 转 DOUBLE(返回新的 DoubleColumn)
        if (c.dtype().isNumeric()) return (DoubleColumn) convertColumn(c, DType.DOUBLE);
        throw new IllegalStateException("列 \"" + name + "\" 不是数值(DOUBLE/INT/LONG),实际 " + c.dtype());
    }

    /**
     * 取 LongColumn(类型化访问器)。
     * @param name String 列名,必须存在;非 null
     * @return LongColumn;INT 列会转 LONG 返回新实例
     * @throws IllegalStateException 列非整数类型
     */
    public LongColumn getLongColumn(String name) {
        Column c = getColumn(name);
        if (c instanceof LongColumn) return (LongColumn) c;
        // INT 列可当 LONG 用(转 LONG)
        if (c.dtype() == DType.INT) return (LongColumn) convertColumn(c, DType.LONG);
        if (c.dtype() == DType.LONG) return (LongColumn) c;
        throw new IllegalStateException("列 \"" + name + "\" 不是整数(INT/LONG),实际 " + c.dtype());
    }

    /**
     * 取 IntColumn(类型化访问器)。
     * @param name String 列名,必须存在;非 null
     * @return IntColumn;LONG 列会转 INT 返回新实例(可能丢精度)
     * @throws IllegalStateException 列非整数类型
     */
    public IntColumn getIntColumn(String name) {
        Column c = getColumn(name);
        if (c.dtype() == DType.INT) return (IntColumn) c;
        if (c.dtype() == DType.LONG) return (IntColumn) convertColumn(c, DType.INT);  // LONG 转 INT
        throw new IllegalStateException("列 \"" + name + "\" 不是整数(INT/LONG),实际 " + c.dtype());
    }

    /**
     * 按下标取第 row 行第 col 列的值(对齐 pandas at)。
     * @param row int 行下标 ∈ [0, rowCount())
     * @param col int 列下标 ∈ [0, columnCount())
     * @return Object 该单元格值(可能为 null)
     */
    public Object get(int row, int col) {
        return columns.get(col).get(row);
    }

    /**
     * 按列名取值。
     * @param row    int 行下标 ∈ [0, rowCount())
     * @param colName String 列名,必须存在;非 null
     * @return Object 该单元格值
     */
    public Object get(int row, String colName) {
        int c = requireColumn(colName);
        return columns.get(c).get(row);
    }

    /**
     * 取第 i 行的所有值(对齐 pandas itertuples)。
     * @param i int 行下标 ∈ [0, rowCount())
     * @return Object[] 长度 == columnCount() 的行数据;缺失为 null
     */
    public Object[] getRow(int i) {
        Object[] r = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            Column col = columns.get(c);
            // 修复:缺失行放 null(IO 层依赖 getRow 返回 null 表示缺失,而非 NaN 对象)
            r[c] = col.isNull(i) ? null : col.get(i);
        }
        return r;
    }

    /** 行迭代器(对齐 pandas iterrows)。 */
    public Iterable<Object[]> iterRows() {
        return () -> new java.util.Iterator<>() {
            int i = 0;
            @Override public boolean hasNext() { return i < nRows; }
            @Override public Object[] next() {
                if (i >= nRows) throw new java.util.NoSuchElementException();
                return getRow(i++);
            }
        };
    }

    // ======================== 选择 / 过滤 / 切片(返回新 DataFrame)========================

    /**
     * 选列子集(对齐 pandas df[["a","b"]] / select)。
     * @param names String... 列名数组;每个必须存在;非 null
     * @return DataFrame 仅含指定列的新表(行数不变);列顺序按 names 顺序
     */
    public DataFrame select(String... names) {
        List<Column> sub = new ArrayList<>(names.length);
        for (String name : names) {
            int i = requireColumn(name);
            sub.add(columns.get(i));
        }
        return new DataFrame(sub, index, allowsDuplicateLabels);
    }

    /**
     * 丢弃指定列(对齐 pandas drop)。
     * @param names String... 待丢弃列名;每个必须存在;非 null
     * @return DataFrame 不含 names 中列的新表
     */
    public DataFrame drop(String... names) {
        java.util.Set<String> toDrop = new java.util.HashSet<>(Arrays.asList(names));
        List<Column> sub = new ArrayList<>();
        for (Column c : columns) if (!toDrop.contains(c.name())) sub.add(c);
        return new DataFrame(sub, index, allowsDuplicateLabels);
    }

    /**
     * 按布尔掩码过滤行(对齐 pandas df[mask])。
     * @param mask boolean[] 掩码,长度必须 == rowCount();非 null
     * @return DataFrame 仅含 mask==true 行的新表
     * @throws IllegalArgumentException mask 长度与行数不一致
     */
    public DataFrame filter(boolean[] mask) {
        if (mask.length != nRows) {
            throw new IllegalArgumentException("mask 长度 " + mask.length + " != 行数 " + nRows);
        }
        int newSize = 0;
        for (boolean m : mask) if (m) newSize++;
        List<Column> sub = new ArrayList<>(columns.size());
        for (Column c : columns) sub.add(c.filter(mask));
        return new DataFrame(sub, index.filter(mask, newSize), allowsDuplicateLabels);
    }

    /**
     * 替换 Index(对齐 pandas set_index 后的视图)。2026-08-09 阶段 A 新增,被 DataFrameIndex.setIndex 调用。
     * <p>不可变:返回新 DataFrame,列不变,Index 替换为参数;新 Index 长度必须 == rowCount()。
     * @param newIndex Index 新行索引,非 null;长度必须 == rowCount()
     * @return DataFrame 新实例,Index 替换;列不变
     * @throws IllegalArgumentException newIndex.size() != rowCount()
     */
    public DataFrame withIndex(Index newIndex) {
        Objects.requireNonNull(newIndex, "newIndex 不能为 null");
        if (newIndex.size() != nRows) {
            throw new IllegalArgumentException(
                "newIndex.size()=" + newIndex.size() + " ≠ rowCount()=" + nRows);
        }
        return new DataFrame(columns, newIndex, allowsDuplicateLabels);
    }

    /**
     * 行切片 [start, end)(对齐 pandas df[start:end],支持负索引)。
     * @param start int 起始(含);负数表示从末尾算
     * @param end   int 结束(不含);负数表示从末尾算
     * @return DataFrame 长度 = max(0, end-start) 的新表
     */
    public DataFrame slice(int start, int end) {
        start = norm(start, nRows); end = norm(end, nRows);
        if (start >= end) {
            return new DataFrame(emptyColumns(), Index.range(0), allowsDuplicateLabels);
        }
        List<Column> sub = new ArrayList<>(columns.size());
        for (Column c : columns) sub.add(c.slice(start, end));
        return new DataFrame(sub, index.slice(start, end), allowsDuplicateLabels);
    }

    /**
     * 前 n 行(对齐 pandas head)。
     * @param n int 行数;负数视为 0;n &gt; rowCount() 时取全部
     * @return DataFrame 前 n 行
     */
    public DataFrame head(int n) {
        return slice(0, Math.min(Math.max(n, 0), nRows));
    }

    /**
     * 后 n 行(对齐 pandas tail)。
     * @param n int 行数;负数视为 0
     * @return DataFrame 末尾 n 行
     */
    public DataFrame tail(int n) {
        n = Math.min(Math.max(n, 0), nRows);
        return n == 0 ? slice(0, 0) : slice(nRows - n, nRows);
    }

    /** @return DataFrame 默认 head(5) 的快捷 */
    public DataFrame head() { return head(5); }
    /** @return DataFrame 默认 tail(5) 的快捷 */
    public DataFrame tail() { return tail(5); }

    /**
     * 按行下标选取(对齐 pandas take / iloc)。
     * @param indices int[] 行下标数组,每个 ∈ [0, rowCount());允许重复/乱序;非 null
     * @return DataFrame 长度 == indices.length 的新表
     */
    public DataFrame takeRows(int[] indices) {
        List<Column> sub = new ArrayList<>(columns.size());
        for (Column c : columns) sub.add(c.take(indices));
        return new DataFrame(sub, index.take(indices), allowsDuplicateLabels);
    }

    // ======================== merge / concat(对齐 pandas §3.10)========================

    /**
     * 关系 join(对齐 pandas.merge)。
     *
     * @param right    DataFrame 右表,非 null
     * @param how      String join 类型:"inner"/"left"/"right"/"outer";非 null
     * @param on       String 单列 join 键名(左右同名);非 null
     * @param suffixes String[] 重名列后缀,null 用默认 ["_x","_y"]
     * @return DataFrame JOIN 结果(语义见 DataFrameMerge.merge)
     */
    public DataFrame merge(DataFrame right, String how, String on, String[] suffixes) {
        return DataFrameMerge.merge(this, right, how, on, suffixes);
    }

    /**
     * inner join on 单列,默认后缀。
     * @param right DataFrame 右表,非 null
     * @param on    String join 键名;非 null
     * @return DataFrame inner join 结果
     */
    public DataFrame merge(DataFrame right, String on) {
        return DataFrameMerge.merge(this, right, "inner", on, null);
    }

    /**
     * 指定 how + on 单列,默认后缀。
     * @param right DataFrame 右表,非 null
     * @param how   String join 类型;非 null
     * @param on    String join 键名;非 null
     * @return DataFrame join 结果
     */
    public DataFrame merge(DataFrame right, String how, String on) {
        return DataFrameMerge.merge(this, right, how, on, null);
    }

    /**
     * 多列键 join(左右不同名)。
     * @param right    DataFrame 右表,非 null
     * @param how      String join 类型;非 null
     * @param leftOn   String[] 左表键列名数组;非 null
     * @param rightOn  String[] 右表键列名数组;长度需 == leftOn.length
     * @param suffixes String[] 重名列后缀;null 用默认
     * @return DataFrame JOIN 结果
     */
    public DataFrame merge(DataFrame right, String how, String[] leftOn, String[] rightOn, String[] suffixes) {
        return DataFrameMerge.merge(this, right, how, leftOn, rightOn, suffixes);
    }

    // ======================== 重塑(委托 DataFrameReshape,对齐 pandas §3.9)========================

    /**
     * 透视表(对齐 pandas.pivot_table)。
     *
     * @param index   String 行分组列名;非 null
     * @param columns String 散开成列的分组列名;非 null
     * @param values  String 被聚合的值列名;非 null
     * @param aggFn   String 聚合函数(mean/sum/count/min/max/first/last/nunique);非 null
     * @return DataFrame 行 = index 不同值,列 = index + 各 columns 值
     */
    public DataFrame pivotTable(String index, String columns, String values, String aggFn) {
        return DataFrameReshape.pivotTable(this, index, columns, values, aggFn);
    }

    /** pivotTable 默认 mean。 */
    public DataFrame pivotTable(String index, String columns, String values) {
        return DataFrameReshape.pivotTable(this, index, columns, values, "mean");
    }

    /**
     * 宽转长(对齐 pandas.melt)。
     *
     * @param idVars 标识列(保留)
     * @param valueVars 被展平的值列
     */
    public DataFrame melt(String[] idVars, String[] valueVars) {
        return DataFrameReshape.melt(this, idVars, valueVars);
    }

    /** 转置(对齐 pandas df.T):行列互换。 */
    public DataFrame transpose() { return DataFrameReshape.transpose(this); }

    /** transpose 别名(对齐 pandas .T)。 */
    public DataFrame T() { return transpose(); }

    /**
     * 去重(对齐 pandas drop_duplicates)。
     *
     * @param subset 判断列(null = 全部列)
     * @param keep "first"(首次出现保留)/"last"/"false"(只保留全唯一行)
     */
    public DataFrame dropDuplicates(String[] subset, String keep) {
        return DataFrameReshape.dropDuplicates(this, subset == null ? columnNames().toArray(new String[0]) : subset, keep);
    }

    /** dropDuplicates 全列 + keep first。 */
    public DataFrame dropDuplicates() {
        return dropDuplicates(columnNames().toArray(new String[0]), "first");
    }

    // ======================== GroupBy(对齐 pandas §3.5/§5)========================

    /**
     * 按一列或多列分组(对齐 pandas df.groupby)。返回 {@link GroupBy} 对象支持后续 agg/transform/filter。
     *
     * @param byCols 分组列名
     */
    public GroupBy groupBy(String... byCols) {
        if (byCols.length == 0) throw new IllegalArgumentException("byCols 不能为空");
        for (String c : byCols) requireColumn(c);
        return new GroupBy(this, byCols);
    }

    // ======================== 排序(委托 DataFrameSort,对齐 pandas §3.9)========================

    /**
     * 按多列排序(对齐 pandas sort_values)。
     *
     * @param byCols 排序键列名
     * @param ascending 每列是否升序(长度须一致)
     */
    public DataFrame sortBy(String[] byCols, boolean[] ascending) {
        return DataFrameSort.sortValues(this, byCols, ascending, "last");
    }

    /**
     * 单列排序便捷方法。
     * @param col       String 排序列名,必须存在;非 null
     * @param ascending boolean true=升序;false=降序
     * @return DataFrame 排序后的新表
     */
    public DataFrame sortBy(String col, boolean ascending) {
        return sortBy(new String[]{col}, new boolean[]{ascending});
    }

    /**
     * 按行索引排序(对齐 pandas sort_index)。
     * @param ascending boolean true=升序;false=降序
     * @return DataFrame 按行索引排序后的新表
     */
    public DataFrame sortIndex(boolean ascending) {
        return DataFrameSort.sortIndex(this, ascending);
    }

    /**
     * TopN 最大(对齐 pandas nlargest)。
     * @param n     int 取前 n 行,≥ 0
     * @param byCol String 排序列名;非 null
     * @return DataFrame byCol 降序的前 n 行
     */
    public DataFrame nlargest(int n, String byCol) { return DataFrameSort.nlargest(this, n, byCol); }

    /**
     * TopN 最小(对齐 pandas nsmallest)。
     * @param n     int 取前 n 行,≥ 0
     * @param byCol String 排序列名;非 null
     * @return DataFrame byCol 升序的前 n 行
     */
    public DataFrame nsmallest(int n, String byCol) { return DataFrameSort.nsmallest(this, n, byCol); }

    // ======================== 列级算术(委托 DataFrameArith,对齐 pandas §3.4)========================

    /**
     * 列间加,结果作新列加到 DataFrame(对齐 df[新列] = a + b)。
     *
     * @param newCol  String 新列名,非 null;若已存在则覆盖
     * @param leftCol String 左列名,必须存在且数值类型;非 null
     * @param rightCol String 右列名,必须存在且数值类型;非 null
     * @return DataFrame 含新列的新表(原列保留);任一缺失行结果为 NaN
     */
    public DataFrame colAdd(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.add(this, leftCol, rightCol));
    }
    /**
     * 列间减(参数语义同 {@link #colAdd(String,String,String)})。
     * @param newCol  String 新列名,非 null;已存在则覆盖
     * @param leftCol String 左列名,数值类型;非 null
     * @param rightCol String 右列名,数值类型;非 null
     * @return DataFrame leftCol - rightCol 作新列
     */
    public DataFrame colSub(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.sub(this, leftCol, rightCol));
    }
    /**
     * 列间乘(参数语义同 {@link #colAdd})。
     * @param newCol  String 新列名,非 null
     * @param leftCol String 左列名,数值类型;非 null
     * @param rightCol String 右列名,数值类型;非 null
     * @return DataFrame leftCol * rightCol 作新列
     */
    public DataFrame colMul(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.mul(this, leftCol, rightCol));
    }
    /**
     * 列间除(参数语义同 {@link #colAdd})。
     * @param newCol  String 新列名,非 null
     * @param leftCol String 左列名,数值类型;非 null
     * @param rightCol String 右列名,数值类型;非 null
     * @return DataFrame leftCol / rightCol 作新列;除以 0 得 ±Infinity
     */
    public DataFrame colDiv(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.div(this, leftCol, rightCol));
    }

    /**
     * 标量乘(对齐 Series * scalar),结果作新列。
     * @param newCol String 新列名,非 null
     * @param srcCol String 源列名,必须存在且数值类型;非 null
     * @param scalar double 标量
     * @return DataFrame srcCol * scalar 作新列
     */
    public DataFrame colMul(String newCol, String srcCol, double scalar) {
        return withColumn(newCol, DataFrameArith.mulScalar(this, srcCol, scalar));
    }
    /**
     * 标量加(参数语义同 {@link #colMul(String,String,double)})。
     * @param newCol String 新列名,非 null
     * @param srcCol String 源列名,数值类型;非 null
     * @param scalar double 标量
     * @return DataFrame srcCol + scalar 作新列
     */
    public DataFrame colAdd(String newCol, String srcCol, double scalar) {
        return withColumn(newCol, DataFrameArith.addScalar(this, srcCol, scalar));
    }

    /** 内部:把一个 DoubleColumn 加为新列(若已存在则替换)。 */
    private DataFrame withColumn(String newCol, DoubleColumn c) {
        Column named = c.rename(newCol);
        List<Column> newCols = new ArrayList<>(columns);
        int idx = columnIndex(newCol);
        if (idx >= 0) newCols.set(idx, named);
        else newCols.add(named);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    // ======================== 描述统计(委托 DataFrameStats,对齐 pandas §3.6)========================

    /** 所有数值列的均值(跳过非数值列)。 */
    public Map<String, Double> mean() { return DataFrameStats.numericStat(this, "mean"); }
    public Map<String, Double> sum() { return DataFrameStats.numericStat(this, "sum"); }
    public Map<String, Double> min() { return DataFrameStats.numericStat(this, "min"); }
    public Map<String, Double> max() { return DataFrameStats.numericStat(this, "max"); }
    public Map<String, Double> median() { return DataFrameStats.numericStat(this, "median"); }
    public Map<String, Double> std() { return DataFrameStats.numericStat(this, "std"); }

    /**
     * 单列均值(快捷)。
     * @param colName String 数值列名,必须存在;非 null
     * @return double 该列均值;全空返回 NaN
     */
    public double colMean(String colName) { return DataFrameStats.mean(getColumn(colName)); }
    /** @return double 该列求和(参数同 {@link #colMean}) */
    public double colSum(String colName) { return DataFrameStats.sum(getColumn(colName)); }
    /** @return double 该列最小(参数同 {@link #colMean}) */
    public double colMin(String colName) { return DataFrameStats.min(getColumn(colName)); }
    /** @return double 该列最大(参数同 {@link #colMean}) */
    public double colMax(String colName) { return DataFrameStats.max(getColumn(colName)); }
    /**
     * @param colName String 数值列名,必须存在;非 null
     * @return double 该列中位数
     */
    public double colMedian(String colName) { return DataFrameStats.median(getColumn(colName)); }
    /**
     * @param colName String 数值列名,必须存在;非 null
     * @return double 该列样本标准差(ddof=1)
     */
    public double colStd(String colName) { return DataFrameStats.std(getColumn(colName)); }

    /**
     * describe(对齐 pandas df.describe):返回 DataFrame,行=统计量,列=数值列。
     * @return DataFrame 8 行(count/mean/std/min/25%/50%/75%/max)× (1 + 数值列数)
     */
    public DataFrame describe() { return DataFrameStats.describe(this); }

    /**
     * 某列分位数(R-7 linear,对齐 numpy 默认)。
     * @param colName String 数值列名,必须存在;非 null
     * @param q       double 分位点 ∈ [0.0, 1.0]
     * @return double 分位数值
     */
    public double colPercentile(String colName, double q) {
        return DataFrameStats.percentile(getColumn(colName), q);
    }

    // ======================== 阶段 B 统计扩展(2026-08-09;委托 DataFrameStats)========================

    /** 列偏度(对齐 pandas Series.skew);经 StatsProvider SPI。 */
    public double colSkew(String colName) { return DataFrameStats.skewness(getColumn(colName)); }
    /** 列峰度(超额,对齐 pandas Series.kurt);经 SPI。 */
    public double colKurt(String colName) { return DataFrameStats.kurtosis(getColumn(colName)); }
    /** 列平均绝对偏差(对齐 pandas Series.mad);经 SPI。 */
    public double colMad(String colName) { return DataFrameStats.mad(getColumn(colName)); }
    /** 列标准误差(对齐 pandas Series.sem);经 SPI。 */
    public double colSem(String colName) { return DataFrameStats.sem(getColumn(colName)); }
    /** 列精确分位数(对齐 pandas Series.quantile;经 SPI)。 */
    public double colQuantile(String colName, double q) { return DataFrameStats.quantile(getColumn(colName), q); }
    /** 列方差(对齐 pandas Series.var;直接 ddof=1 计算)。 */
    public double colVar(String colName) {
        double std = DataFrameStats.std(getColumn(colName));
        return std * std;
    }
    /** 列积(对齐 pandas Series.prod)。 */
    public double colProd(String colName) { return DataFrameStats.prod(getColumn(colName)); }
    /** 列唯一值数(对齐 pandas Series.nunique;skip 缺失)。 */
    public int colNunique(String colName) { return DataFrameStats.nunique(getColumn(colName)); }
    /** 列 all(对齐 pandas Series.all;所有非缺失值为真)。 */
    public boolean colAll(String colName) { return DataFrameStats.all(getColumn(colName)); }
    /** 列 any(对齐 pandas Series.any;任一非缺失值为真)。 */
    public boolean colAny(String colName) { return DataFrameStats.any(getColumn(colName)); }

    /** 两列相关(对齐 pandas Series.corr;method=pearson/spearman,默认 pearson);经 SPI。 */
    public double colCorr(String colA, String colB, String method) {
        return DataFrameStats.corr(getColumn(colA), getColumn(colB), method);
    }
    /** colCorr 便捷重载:method=pearson。 */
    public double colCorr(String colA, String colB) { return colCorr(colA, colB, "pearson"); }
    /** 两列协方差(对齐 pandas Series.cov);经 SPI。 */
    public double colCov(String colA, String colB) {
        return DataFrameStats.cov(getColumn(colA), getColumn(colB));
    }

    /** 列内秩为新列(对齐 pandas Series.rank;method=average/min/max/first/dense);经 SPI。 */
    public DoubleColumn colRank(String colName, String method, String newColName) {
        return DataFrameStats.rank(getColumn(colName), method, newColName);
    }
    /** colRank 便捷重载:method=average,newColName={col}_rank。 */
    public DoubleColumn colRank(String colName) {
        return colRank(colName, "average", colName + "_rank");
    }

    /** 列累积和为新列(对齐 pandas Series.cumsum)。 */
    public DoubleColumn colCumsum(String colName, String newColName) {
        return DataFrameStats.cumsum(getColumn(colName), newColName);
    }
    /** 列累积最大为新列(对齐 pandas Series.cummax)。 */
    public DoubleColumn colCummax(String colName, String newColName) {
        return DataFrameStats.cummax(getColumn(colName), newColName);
    }
    /** 列累积最小为新列(对齐 pandas Series.cummin)。 */
    public DoubleColumn colCummin(String colName, String newColName) {
        return DataFrameStats.cummin(getColumn(colName), newColName);
    }
    /** 列累积积为新列(对齐 pandas Series.cumprod)。 */
    public DoubleColumn colCumprod(String colName, String newColName) {
        return DataFrameStats.cumprod(getColumn(colName), newColName);
    }
    /** 列差分为新列(对齐 pandas Series.diff(periods))。 */
    public DoubleColumn colDiff(String colName, int periods, String newColName) {
        return DataFrameStats.diff(getColumn(colName), periods, newColName);
    }
    /** 列百分比变化为新列(对齐 pandas Series.pct_change)。 */
    public DoubleColumn colPctChange(String colName, int periods, String newColName) {
        return DataFrameStats.pctChange(getColumn(colName), periods, newColName);
    }
    /** 列裁剪为新列(对齐 pandas Series.clip)。 */
    public DoubleColumn colClip(String colName, double lower, double upper, String newColName) {
        return DataFrameStats.clip(getColumn(colName), lower, upper, newColName);
    }
    /** 列四舍五入为新列(对齐 pandas Series.round)。 */
    public DoubleColumn colRound(String colName, int decimals, String newColName) {
        return DataFrameStats.round(getColumn(colName), decimals, newColName);
    }

    /** 全数值列相关矩阵(对齐 pandas DataFrame.corr;method=pearson/spearman)。 */
    public DataFrame corrMatrix(String method) { return buildMatrix("corr", method); }
    /** corrMatrix 便捷重载:method=pearson。 */
    public DataFrame corrMatrix() { return corrMatrix("pearson"); }
    /** 全数值列协方差矩阵(对齐 pandas DataFrame.cov)。 */
    public DataFrame covMatrix() { return buildMatrix("cov", null); }

    // ======================== 阶段 C 重塑/合并/二元扩展(2026-08-09)========================

    /** 简单透视(无聚合,对齐 pandas df.pivot);委托 {@link DataFrameReshape}。 */
    public DataFrame pivot(String index, String columns, String values) {
        return DataFrameReshape.pivot(this, index, columns, values);
    }
    /** 列展平(对齐 pandas df.explode);委托 {@link DataFrameReshape}。 */
    public DataFrame explode(String col) { return DataFrameReshape.explode(this, col); }
    /** 堆叠:列→行(对齐 pandas df.stack;委托 {@link DataFrameReshape})。 */
    public DataFrame stack(String[] idCols, String[] valueCols) {
        return DataFrameReshape.stack(this, idCols, valueCols);
    }
    /** 展开:行→列(对齐 pandas df.unstack;委托 {@link DataFrameReshape})。 */
    public DataFrame unstack(String idCol, String keyCol, String valCol) {
        return DataFrameReshape.unstack(this, idCol, keyCol, valCol);
    }

    /** 索引 join(对齐 pandas df.join);委托 {@link DataFrameMerge}。 */
    public DataFrame join(DataFrame right, String on, String how) {
        return DataFrameMerge.join(this, right, on, how);
    }
    /** join 便捷重载:how=left。 */
    public DataFrame join(DataFrame right, String on) { return join(right, on, "left"); }
    /** 按最近键对齐(对齐 pandas merge_asof,方向 backward);委托 {@link DataFrameMerge}。 */
    public DataFrame mergeAsof(DataFrame right, String on) {
        return DataFrameMerge.mergeAsof(this, right, on);
    }

    /** DataFrame 与标量逐列加(对齐 pandas df.add(scalar));委托 {@link DataFrameArith}。 */
    public DataFrame addScalarAllColumns(double scalar) { return DataFrameArith.addScalarAllColumns(this, scalar); }
    /** DataFrame 与标量逐列减。 */
    public DataFrame subScalarAllColumns(double scalar) { return DataFrameArith.subScalarAllColumns(this, scalar); }
    /** DataFrame 与标量逐列乘。 */
    public DataFrame mulScalarAllColumns(double scalar) { return DataFrameArith.mulScalarAllColumns(this, scalar); }
    /** DataFrame 与标量逐列除。 */
    public DataFrame divScalarAllColumns(double scalar) { return DataFrameArith.divScalarAllColumns(this, scalar); }

    // ======================== 阶段 D 时序扩展(2026-08-09)========================

    /**
     * 时间序列重采样(对齐 pandas DataFrame.resample(rule))。
     * <p>链式调聚合:df.resample("ts", "1D").sum() / .mean() / .count() / .ohlc("price") / .agg({...})
     * @param tsCol String 时间列名(LocalDateTime 元素);非 null
     * @param rule  String 频率字符串,如 "1D"/"2H"/"1W";非 null
     * @return Resampler 重采样器对象(链式调聚合)
     */
    public Resampler resample(String tsCol, String rule) {
        return new Resampler(this, tsCol, rule);
    }

    /**
     * 行位移(对齐 pandas DataFrame.shift(periods))。
     * <p>periods>0 向下位移(首 periods 行变缺失);periods<0 向上位移。
     * @param colName String 待位移列名;非 null
     * @param periods int 位移步数;0 抛异常
     * @param newColName String 新列名
     * @return DoubleColumn 同长度新列;位移产生的空缺为 NaN
     */
    public DoubleColumn shift(String colName, int periods, String newColName) {
        Column c = getColumn(colName);
        if (periods == 0) throw new IllegalArgumentException("shift periods 不能为 0");
        int n = c.size();
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = 0; i < n; i++) {
            int src = i - periods;
            if (src >= 0 && src < n && !c.isNull(src) && !Double.isNaN(c.getDouble(src))) {
                out[i] = c.getDouble(src);
            }
        }
        return new DoubleColumn(newColName, out);
    }

    /** shift 便捷重载:newColName = {col}_shifted。 */
    public DoubleColumn shift(String colName, int periods) {
        return shift(colName, periods, colName + "_shifted");
    }

    /**
     * 时间筛选(对齐 pandas DataFrame.at_time):返回行时间 == time 的所有行。
     * <p>语义:要求 tsCol 元素类型为 LocalDateTime(<b>不强制 DType.DATETIME</b>;
     * 实际值是 LocalDateTime 即可)。非 LocalDateTime 的行被静默跳过 —— String/Long
     * 等其它类型列会返回<b>空结果(非异常)</b>,这与 pandas at_time 不校验 dtype 一致。
     * @param tsCol String 时间列名;非 null
     * @param time  java.time.LocalTime 目标时刻;非 null
     * @return DataFrame 选中的行(行序保留)
     */
    public DataFrame atTime(String tsCol, java.time.LocalTime time) {
        java.util.List<Integer> picked = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount(); i++) {
            Object v = get(i, tsCol);
            if (v instanceof java.time.LocalDateTime lt && lt.toLocalTime().equals(time)) {
                picked.add(i);
            }
        }
        int[] idx = picked.stream().mapToInt(Integer::intValue).toArray();
        return takeRows(idx);
    }

    /**
     * 时间段筛选(对齐 pandas DataFrame.between_time):返回行时间 ∈ [start, end] 的行。
     * <p>语义同 {@link #atTime}:要求 tsCol 元素是 LocalDateTime(不强制 DType.DATETIME);
     * 非 LocalDateTime 行被静默跳过。
     * @param tsCol String 时间列名;非 null
     * @param start java.time.LocalTime 起始(含)
     * @param end   java.time.LocalTime 结束(含);start > end 时跨午夜
     */
    public DataFrame betweenTime(String tsCol, java.time.LocalTime start, java.time.LocalTime end) {
        boolean crossMidnight = start.isAfter(end);
        java.util.List<Integer> picked = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount(); i++) {
            Object v = get(i, tsCol);
            if (!(v instanceof java.time.LocalDateTime lt)) continue;
            java.time.LocalTime t = lt.toLocalTime();
            boolean inRange = crossMidnight
                ? (t.compareTo(start) >= 0 || t.compareTo(end) <= 0)
                : (t.compareTo(start) >= 0 && t.compareTo(end) <= 0);
            if (inRange) picked.add(i);
        }
        int[] idx = picked.stream().mapToInt(Integer::intValue).toArray();
        return takeRows(idx);
    }

    /**
     * asof 查询(对齐 pandas DataFrame.asof):返回 ≤ label 的最后一个非空观测所在行。
     * @param tsCol String 时间列名(LocalDateTime 元素);非 null
     * @param label LocalDateTime 目标时间;非 null
     * @return DataFrame 含一行(若没找到返回空表)
     */
    public DataFrame asof(String tsCol, java.time.LocalDateTime label) {
        int found = -1;
        for (int i = 0; i < rowCount(); i++) {
            Object v = get(i, tsCol);
            if (v instanceof java.time.LocalDateTime lt && !lt.isAfter(label)) {
                found = i;
            } else if (v instanceof java.time.LocalDateTime lt && lt.isAfter(label)) {
                break;  // 升序时提前退出
            }
        }
        if (found < 0) return takeRows(new int[0]);
        return takeRows(new int[]{found});
    }

    /**
     * 构建全数值列的相关/协方差矩阵。
     * @param kind "corr" 或 "cov"
     * @param method 用于 corr:pearson/spearman
     */
    private DataFrame buildMatrix(String kind, String method) {
        List<String> numCols = new ArrayList<>();
        for (String c : columnNames()) {
            DType dt = getColumn(c).dtype();
            if (dt == DType.DOUBLE || dt == DType.LONG || dt == DType.INT) numCols.add(c);
        }
        int k = numCols.size();
        if (k == 0) return DataFrame.ofColumnsDirect(new java.util.ArrayList<>());
        // 输出 schema:第一列 "_index_"(STRING) + 各数值列(DOUBLE)
        String labelCol = "_index_";
        // Schema.of 参数:[name1, dtype1, name2, dtype2, ...];labelCol 占 2 项 + k 个数值列各 2 项 = 2 + k*2
        Object[] schParts = new Object[2 + k * 2];
        schParts[0] = labelCol; schParts[1] = DType.STRING;
        for (int j = 0; j < k; j++) { schParts[2 + j * 2] = numCols.get(j); schParts[3 + j * 2] = DType.DOUBLE; }
        Schema sch = Schema.of(schParts);
        Object[][] rows = new Object[k][];
        for (int i = 0; i < k; i++) {
            Object[] row = new Object[k + 1];
            row[0] = numCols.get(i);
            for (int j = 0; j < k; j++) {
                double v = "corr".equals(kind)
                    ? DataFrameStats.corr(getColumn(numCols.get(i)), getColumn(numCols.get(j)), method)
                    : DataFrameStats.cov(getColumn(numCols.get(i)), getColumn(numCols.get(j)));
                row[j + 1] = v;
            }
            rows[i] = row;
        }
        return DataFrame.of(sch, rows);
    }

    // ======================== 函数应用(对齐 pandas apply/map)========================

    /**
     * 对数值列每个元素应用函数,返回新 DoubleColumn(对齐 Series.apply)。
     * @param colName String 数值列名,必须存在且数值类型;非 null
     * @param fn      java.util.function.DoubleUnaryOperator 一元函数;非 null
     * @return DoubleColumn 同长度新列;缺失行 NaN
     */
    public DoubleColumn applyNumeric(String colName, java.util.function.DoubleUnaryOperator fn) {
        return DataFrameStats.applyNumeric(this, colName, fn);
    }

    /**
     * 对任意列元素应用函数转 String 列。
     * @param colName String 列名,必须存在;非 null
     * @param fn      java.util.function.Function&lt;Object,String&gt; 转字符串函数;非 null;入参可能为 null
     * @return StringColumn 同长度新列
     */
    public StringColumn applyStr(String colName, java.util.function.Function<Object, String> fn) {
        return DataFrameStats.applyToString(this, colName, fn);
    }

    /**
     * 派生新列(对齐 pandas assign):用 fn 对每行求值,返回新 DataFrame(原列 + 新列)。
     *
     * @param newName String 新列名,必须**不存在**(已存在抛异常);非 null
     * @param fn      java.util.function.IntFunction&lt;Object&gt; 接收行号 r ∈ [0, rowCount()),返回新列该行的值;非 null
     * @return DataFrame 含原列 + 新列的新表
     * @throws IllegalArgumentException newName 已存在
     */
    public DataFrame assign(String newName, java.util.function.IntFunction<Object> fn) {
        if (columnIndex(newName) >= 0) {
            throw new IllegalArgumentException("assign 目标列 \"" + newName + "\" 已存在");
        }
        Object[] vals = new Object[nRows];
        for (int r = 0; r < nRows; r++) vals[r] = fn.apply(r);
        // 推断类型:扫一遍
        Schema sub = Schema.infer(java.util.List.of(newName), new Object[][]{vals});
        DType dt = sub.dtypeAt(0);
        Column newCol;
        Object[][] wrapped = new Object[nRows][1];
        for (int r = 0; r < nRows; r++) wrapped[r][0] = vals[r];
        newCol = buildColumn(newName, dt, wrapped, 0, nRows);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.add(newCol);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    // ======================== 缺失值处理(委托 DataFrameMissing,对齐 pandas §3.8)========================

    /** 是否每单元格缺失(返回 mask DataFrame,对齐 df.isna)。 */
    public DataFrame isna() { return DataFrameMissing.isna(this); }

    /**
     * dropna:how=any/all,subset 指定列。
     * @param how    String "any"=任一列缺失即丢;"all"=全部缺失才丢;非 null
     * @param subset String[] 仅考虑的列名;null=全部列
     * @return DataFrame 删除缺失行后的新表
     */
    public DataFrame dropna(String how, String[] subset) {
        return DataFrameMissing.dropna(this, how, subset);
    }

    /** dropna 任一列缺失即丢(全部列)。 */
    public DataFrame dropna() { return dropna("any", null); }

    /**
     * fillna 用常量填缺失。
     * @param value Object 填充值;数值列期望 Number,字符串列期望 String/任意;非 null
     * @return DataFrame 缺失单元格替换为 value 的新表
     */
    public DataFrame fillna(Object value) { return DataFrameMissing.fillna(this, value); }

    /**
     * 线性插值填充缺失(对齐 pandas DataFrame.interpolate;2026-08-09 阶段 F 新增)。
     * <p>策略:数值列缺失位置按前后非缺失值线性插值;首尾连续缺失保持;非数值列原样保留。
     * @return DataFrame 同结构,数值列缺失被线性插值填充
     */
    public DataFrame interpolate() { return DataFrameMissing.interpolate(this); }

    /** notna:isna 的反转(返回非缺失掩码 DataFrame,对齐 pandas df.notna)。 */
    public DataFrame notna() {
        DataFrame na = isna();
        java.util.List<Column> out = new java.util.ArrayList<>();
        for (String c : na.columnNames()) {
            BoolColumn bc = (BoolColumn) na.getColumn(c);
            boolean[] inv = new boolean[bc.size()];
            boolean[] mask = new boolean[bc.size()];
            for (int i = 0; i < bc.size(); i++) {
                if (bc.isNull(i)) { mask[i] = true; }
                else inv[i] = !Boolean.TRUE.equals(bc.get(i));
            }
            out.add(new BoolColumn(c, inv, mask));
        }
        return new DataFrame(out, index, allowsDuplicateLabels);
    }
    /** notnull:notna 的别名(对齐 pandas)。 */
    public DataFrame notnull() { return notna(); }
    /** pad:ffill 的别名(对齐 pandas pad = forward fill)。 */
    public DataFrame pad() { return ffill(); }
    /** backfill:bfill 的别名(对齐 pandas)。 */
    public DataFrame backfill() { return bfill(); }

    /** ffill 前向填充。 */
    public DataFrame ffill() { return DataFrameMissing.ffill(this); }

    /** bfill 后向填充。 */
    public DataFrame bfill() { return DataFrameMissing.bfill(this); }

    // ======================== query(对齐 pandas df.query,L1 子集)========================

    /**
     * 用表达式过滤行(对齐 pandas df.query)。
     *
     * <p>支持:L1 布尔子集({@code > < >= <= == !=}、{@code && || !}、{@code and/or/not}、
     * {@code between X and Y}、{@code like '%pat%'}、{@code is null}、括号)。
     *
     * <p>完整 L1+L2+L3(Pratt parser + SQL 子集 + 多方言)在 jian-dsl 模块,经 SPI 自动升级。
     *
     * @param expr 表达式,如 {@code "age > 18 && city == 'SH'"}
     */
    public DataFrame query(String expr) {
        // 经 SPI 加载 DslEngine(若引了 jian-dsl 自动升级到完整 L1+L2+L3;否则用 core 内置 L1 子集兜底)
        return DslEngine.current().query(this, expr);
    }

    /**
     * L2 派生新列(对齐 pandas df.eval,规范 07 §2.2)。
     * @param expr String 派生表达式,如 {@code "total = price * qty"};非 null
     * @return DataFrame 含派生列的新表
     * @throws ModuleNotLoadedException 未引 jian-dsl 模块
     */
    public DataFrame eval(String expr) {
        return DslEngine.current().eval(this, expr);
    }

    /**
     * L3 SQL 子集(规范 07 §2.2/§2.3)。接收者 df 为 SQL 中的主表(this/DUAL),
     * ${name} 占位按出现顺序绑定到 binds 参数。
     * @param sql   String SQL 语句,含 ${name} 占位;非 null
     * @param binds DataFrame... 占位符绑定的辅表,按 ${name} 出现顺序对应;允许空
     * @return DataFrame SQL 执行结果
     * @throws ModuleNotLoadedException 未引 jian-dsl 模块
     */
    public DataFrame sql(String sql, DataFrame... binds) {
        return DslEngine.current().sql(this, sql, binds);
    }

    // ======================== 列级比较(返回 BoolColumn,便于 mask 链式)========================

    /**
     * 比较某列与常数(对齐 pandas Series > 调用),返回 BoolColumn 作 mask。
     *
     * @param colName 列名(须数值或字符串)
     * @param op 运算符 {@code > < >= <= == !=}
     * @param value 常数(Number 或 String)
     */
    public BoolColumn compare(String colName, String op, Object value) {
        int i = requireColumn(colName);
        Column c = columns.get(i);
        boolean[] m = new boolean[nRows];
        for (int r = 0; r < nRows; r++) {
            // 修复:用 isNull 判断缺失(get() 对 NaN 现在返回 Double.NaN 不是 null)
            if (c.isNull(r)) { m[r] = false; continue; }
            Object v = c.get(r);
            m[r] = cmp(v, op, value);
        }
        return new BoolColumn(colName, m, null);
    }

    /**
     * 大于(数值列常用):每行 colName &gt; value。
     * @param colName String 数值列名,必须存在;非 null
     * @param value   double 比较阈值
     * @return BoolColumn 同长度掩码;该行缺失时为 false
     */
    public BoolColumn colGt(String colName, double value) {
        return compare(colName, ">", value);
    }
    /** 小于(参数语义同 {@link #colGt})。 */
    public BoolColumn colLt(String colName, double value) { return compare(colName, "<", value); }
    /** 大于等于(参数语义同 {@link #colGt})。 */
    public BoolColumn colGe(String colName, double value) { return compare(colName, ">=", value); }
    /** 小于等于(参数语义同 {@link #colGt})。 */
    public BoolColumn colLe(String colName, double value) { return compare(colName, "<=", value); }
    /**
     * 等于(支持任意类型,对齐 pandas ==)。
     * @param colName String 列名,必须存在;非 null
     * @param value   Object 比较值;类型应与列元素兼容(Number/String/Boolean 等)
     * @return BoolColumn 同长度掩码;缺失行 false
     */
    public BoolColumn colEq(String colName, Object value) { return compare(colName, "==", value); }
    /** 不等于(参数语义同 {@link #colEq})。 */
    public BoolColumn colNe(String colName, Object value) { return compare(colName, "!=", value); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean cmp(Object a, String op, Object b) {
        // ┌─ What : 通用比较器,处理 > < >= <= == != 六种运算符,支持 Number / 同型 Comparable / 混型
        // │  Why  : L8 修复(2026-08-09,与 AI agent2 第二轮审查共识,基于本机 pandas 1.5.3 实测):
        // │         原 == 用 a.equals(b)(类型敏感、+0.0 ≠ -0.0),而 > 用 String 字典序,语义分裂;
        // │         但若把混型 == 改成 String 字典序 c==0,会让 "1"==1 → true,**偏离 pandas**。
        // │         pandas 1.5.3 实测:混型 == 恒 False、混型 > 抛 TypeError。
        // │         故采用 pandas 对齐版:
        // │           ① 同型 Number → 数值比(±0.0 等价,NaN≠NaN,与 IEEE/pandas 一致)
        // │           ② 严格同型 Comparable → compareTo(String==String/LocalDateTime==LocalDateTime 等)
        // │           ③ 混型 → == 恒 false / != 恒 true(对齐 pandas);> < 维持既有 String 字典序
        // │              (jian 既有"宽厚"行为,避免 String 列与数值比直接崩;但绝不把 == 也抬成字典序相等)
        // │  Who  : 由 query / compare 等过滤算子调用
        // │  When : 任何比较运算
        // │  How  : 三段式分支,混型分支里 == / != 直接定值,顺序比较走 String.valueOf 字典序
        if (a instanceof Number na && b instanceof Number nb) {
            double x = na.doubleValue(), y = nb.doubleValue();
            return switch (op) { case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y;
                case "<=" -> x <= y; case "==" -> x == y; case "!=" -> x != y;
                default -> throw new IllegalArgumentException("未知 op " + op); };
        }
        // 同型且同为 Comparable → 用 T 的 compareTo(此时 b 必同型,不会 CCE)
        // 覆盖 String==String、LocalDateTime==LocalDateTime、BigDecimal==BigDecimal 等主要场景
        if (a != null && b != null && a.getClass() == b.getClass() && a instanceof Comparable ca) {
            int c = ((Comparable<Object>) ca).compareTo(b);
            return switch (op) { case "==" -> c == 0; case "!=" -> c != 0;
                case ">" -> c > 0; case "<" -> c < 0; case ">=" -> c >= 0; case "<=" -> c <= 0;
                default -> throw new IllegalArgumentException("未知 op " + op); };
        }
        // 混型 / 不可比:
        // ≈ pandas 混型语义:== 恒 false、!= 恒 true(字符串 "1" 与数字 1 永不相等);
        // > < >= <= 维持既有 String 字典序(jian 宽厚行为,兼容存量,不抛 TypeError)
        return switch (op) {
            case "==" -> false;
            case "!=" -> true;
            default -> {
                int c = String.valueOf(a).compareTo(String.valueOf(b));
                yield switch (op) { case ">" -> c > 0; case "<" -> c < 0;
                    case ">=" -> c >= 0; case "<=" -> c <= 0;
                    default -> throw new IllegalArgumentException("未知 op " + op); };
            }
        };
    }

    // ======================== loc / iloc(对齐 pandas)========================

    /**
     * iloc:按位置选行(返回新 DataFrame)。
     * @param rowIndices int... 位置下标,每个 ∈ [0, rowCount());允许重复/乱序;非 null
     * @return DataFrame 长度 == rowIndices.length 的新表
     */
    public DataFrame iloc(int... rowIndices) {
        return takeRows(rowIndices);
    }

    /**
     * loc:按行标签选行。当前 Index 是 RangeIndex 时,标签 == 位置。
     * @param labels Object... 行标签值;RangeIndex 时需为 Number;非 null
     * @return DataFrame 选中的行组成的新表(找不到的标签被跳过)
     */
    public DataFrame loc(Object... labels) {
        if (index.isRange()) {
            // ┌─ What : RangeIndex 的 loc —— 标签 == 位置下标,需 Number 且为整数
            // │  Why  : L8 修复(2026-08-09,与 AI agent2 第二轮审查共识):
            // │         原 ((Number) labels[k]).intValue() 有两类静默错误:
            // │           ① 传 String 标签 → ClassCastException(无业务语义提示)
            // │           ② 传 2.5 这种非整数 → intValue() 静默截断为 2(取错行)
            // │         现加两道检查:非 Number 抛 IAE(带类型提示);非整数抛 IAE(带实际值)
            // │  Who  : loc(RangeIndex 分支)
            // │  When : 用户对 RangeIndex DataFrame 调 loc(label...)
            // │  How  : instanceof Number → 检查 dv == Math.rint(dv) → 越界检查 → takeRows
            int[] idx = new int[labels.length];
            int n = rowCount();
            for (int k = 0; k < labels.length; k++) {
                Object label = labels[k];
                if (!(label instanceof Number)) {
                    throw new IllegalArgumentException(
                        "RangeIndex 的标签须为数字,实际第 " + k + " 个标签: "
                        + (label == null ? "null" : label.getClass().getSimpleName() + "「" + label + "」"));
                }
                double dv = ((Number) label).doubleValue();
                if (dv != Math.rint(dv)) {
                    throw new IllegalArgumentException(
                        "RangeIndex 标签须为整数,实际第 " + k + " 个标签为 " + dv);
                }
                int rowIdx = ((Number) label).intValue();
                if (rowIdx < 0 || rowIdx >= n) {
                    throw new IndexOutOfBoundsException(
                        "标签 " + rowIdx + " 越界(RangeIndex 行数 " + n + ")");
                }
                idx[k] = rowIdx;
            }
            return takeRows(idx);
        }
        // 显式标签:扫一遍匹配
        Object[] all = index.labels();
        java.util.List<Integer> hit = new java.util.ArrayList<>();
        for (Object label : labels) {
            for (int i = 0; i < all.length; i++) if (all[i] != null && all[i].equals(label)) hit.add(i);
        }
        int[] idx = new int[hit.size()];
        for (int k = 0; k < idx.length; k++) idx[k] = hit.get(k);
        return takeRows(idx);
    }

    // ======================== 阶段 A 高频实用扩展(2026-08-09,内聚到既有伴生类)========================

    /** 极值位置:列最大值所在首行下标(对齐 pandas df.idxmax);并入 {@link DataFrameSort}(与 nlargest 同类)。 */
    public int idxmax(String col) { return DataFrameSort.idxmax(this, col); }
    /** 极值位置:列最小值所在首行下标(对齐 pandas df.idxmin);并入 {@link DataFrameSort}。 */
    public int idxmin(String col) { return DataFrameSort.idxmin(this, col); }
    /** 重复行掩码(对齐 pandas df.duplicated,keep=first/last/none);并入 {@link DataFrameReshape}(与 dropDuplicates 同类)。 */
    public boolean[] duplicated(String[] subset, String keep) { return DataFrameReshape.duplicated(this, subset, keep); }
    /** duplicated 便捷重载:subset=全列,keep=first。 */
    public boolean[] duplicated() { return DataFrameReshape.duplicated(this, null, "first"); }

    /** reset_index(对齐 pandas df.reset_index);主类承载(与 Index 操作同源)。 */
    public DataFrame resetIndex(String indexCol) { return resetIndexImpl(this, indexCol); }
    /** reset_index 便捷重载:丢弃原 Index(无新列名)。 */
    public DataFrame resetIndex() { return resetIndexImpl(this, null); }
    /** set_index(对齐 pandas df.set_index);主类承载。 */
    public DataFrame setIndex(String[] cols, boolean drop) { return setIndexImpl(this, cols, drop); }
    /** set_index 便捷重载:drop=true(默认)。 */
    public DataFrame setIndex(String... cols) { return setIndexImpl(this, cols, true); }

    /**
     * tz_localize:给 DATETIME 列附加时区(对齐 pandas tz_localize;L10 2026-08-09)。
     * <p>把 LocalDateTime → ZonedDateTime(用 java.time.ZoneId);jian v1 用 OBJECT 列存储 ZonedDateTime。
     * @param colName String DATETIME 列名;非 null
     * @param zoneId String 时区 ID,如 "Asia/Shanghai"/"UTC"/"+08:00";非 null
     * @return DataFrame 该列转为 OBJECT(ZonedDateTime 元素)的新表
     */
    public DataFrame tzLocalize(String colName, String zoneId) {
        java.time.ZoneId zid = java.time.ZoneId.of(zoneId);
        Column src = getColumn(colName);
        Object[] arr = new Object[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (src.isNull(i)) { arr[i] = null; continue; }
            Object v = src.get(i);
            if (v instanceof java.time.LocalDateTime lt) arr[i] = lt.atZone(zid);
            else arr[i] = v;
        }
        Column newCol = new ObjectColumn(colName + "_tz", arr);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.set(columnIndex(colName), newCol.rename(colName));
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /**
     * tz_convert:转换 DATETIME 列的时区(对齐 pandas tz_convert;L10 2026-08-09)。
     * <p>要求该列已是 ZonedDateTime(经 tz_localize 转换);否则抛 IAE。
     * @param colName String 列名(需含 ZonedDateTime 元素);非 null
     * @param zoneId String 目标时区 ID;非 null
     * @return DataFrame 该列时区转换后的新表
     */
    public DataFrame tzConvert(String colName, String zoneId) {
        java.time.ZoneId target = java.time.ZoneId.of(zoneId);
        Column src = getColumn(colName);
        Object[] arr = new Object[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (src.isNull(i)) { arr[i] = null; continue; }
            Object v = src.get(i);
            if (v instanceof java.time.ZonedDateTime zdt) {
                arr[i] = zdt.withZoneSameInstant(target);
            } else {
                throw new IllegalArgumentException("tz_convert 要求列 " + colName
                    + " 含 ZonedDateTime(先 tz_localize);实际第 " + i + " 行:" + (v == null ? "null" : v.getClass().getSimpleName()));
            }
        }
        Column newCol = new ObjectColumn(colName, arr);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.set(columnIndex(colName), newCol);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /** 采样(对齐 pandas df.sample,可复现种子);主类承载(与 takeRows/iloc 同类行选择入口)。 */
    public DataFrame sample(int n, boolean replace, long seed) { return sampleImpl(this, n, replace, seed); }
    /** pipe 链式管道(对齐 pandas df.pipe);主类承载(与 eval/sql 等链式入口同类)。 */
    public <R> R pipe(java.util.function.Function<DataFrame, R> fn) { return fn.apply(this); }
    /** 按行应用,生成新列(对齐 pandas df.apply(axis=1) 单列输出);主类承载(链式入口)。 */
    public DataFrame applyRow(String newCol, java.util.function.Function<Object[], Object> fn) {
        return applyRowImpl(this, newCol, fn);
    }

    /** 行级成员判断(对齐 pandas df.isin,任一列命中);并入 {@link DataFrameMissing}(与 isna 同类掩码语义)。 */
    public boolean[] isin(Object... values) { return DataFrameMissing.isin(this, values); }
    /** 列级成员判断(对齐 Series.isin);并入 {@link DataFrameMissing}。 */
    public boolean[] colIsin(String col, Object... values) { return DataFrameMissing.colIsin(this, col, values); }
    /** 条件保留(对齐 pandas df.where,cond==false 替换);并入 {@link DataFrameMissing}(与 fillna 同类值替换)。 */
    public DataFrame where(boolean[] cond, Object other) { return DataFrameMissing.where(this, cond, other); }
    /** 条件替换(对齐 pandas df.mask,cond==true 替换);并入 {@link DataFrameMissing}。 */
    public DataFrame mask(boolean[] cond, Object other) { return DataFrameMissing.mask(this, cond, other); }
    /** 元信息表格(对齐 pandas df.info);主类承载(与 dtypes/describe 同类元信息)。 */
    public String info() { return infoImpl(this); }
    /** 按 dtype 筛列(对齐 pandas df.select_dtypes);主类承载(元信息查询)。 */
    public DataFrame selectDtypes(DType[] include, DType[] exclude) { return selectDtypesImpl(this, include, exclude); }

    // ======================== 元信息补全(对齐 pandas axes/ndim/memory_usage/attrs)========================

    /** 行列轴(对齐 pandas df.axes);返回 [行标签 list, 列名 list]。 */
    public java.util.List<java.util.List<?>> axes() {
        java.util.List<Object> rowLabels = new ArrayList<>();
        for (int i = 0; i < nRows; i++) rowLabels.add(index.get(i));
        return java.util.List.of(rowLabels, new ArrayList<>(columnNames()));
    }

    /** 维度数(对齐 pandas df.ndim);DataFrame 固定 2。 */
    public int ndim() { return 2; }

    /** 内存估算(对齐 pandas df.memory_usage);每列 size × 8 字节,返回总字节数。 */
    public long memoryUsage() {
        long total = 0;
        for (String c : columnNames()) total += (long) getColumn(c).size() * 8;
        return total;
    }

    /** 元数据字典(对齐 pandas df.attrs);返回可变 Map(用户可自由读写;线程安全 ConcurrentHashMap)。 */
    public java.util.Map<String, Object> attrs() { return attrsMap; }
    private final java.util.Map<String, Object> attrsMap = new java.util.concurrent.ConcurrentHashMap<>();

    /** 类型推断升级(对齐 pandas df.infer_objects);扫每列,OBJECT → 推断为 LONG/DOUBLE/STRING/BOOL。 */
    public DataFrame inferObjects() {
        List<Column> newCols = new ArrayList<>();
        for (String c : columnNames()) {
            Column col = getColumn(c);
            newCols.add(col.dtype() == DType.OBJECT ? inferColumnDtype(col) : col);
        }
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /** convert_dtypes 别名(等价 inferObjects)。 */
    public DataFrame convertDtypes() { return inferObjects(); }

    /** 转二维数组(对齐 pandas df.to_numpy);返回 Object[][],每行一个 Object[]。 */
    public Object[][] toNumpy() {
        Object[][] out = new Object[nRows][];
        for (int r = 0; r < nRows; r++) out[r] = getRow(r);
        return out;
    }

    // ======================== 单单元格读写(对齐 pandas at/iat/isetitem)========================

    /** 按标签读单单元格(对齐 pandas df.at[rowLabel, colName])。 */
    public Object at(Object rowLabel, String colName) {
        int rowIdx = index.isRange() ? ((Number) rowLabel).intValue() : findLabel(rowLabel);
        return get(rowIdx, colName);
    }

    /** 按位置读单单元格(对齐 pandas df.iat[i, j])。 */
    public Object iat(int rowIdx, int colIdx) { return get(rowIdx, colIdx); }

    /** 按位置写单单元格(对齐 pandas df.isetitem);返回新 DataFrame(不可变)。 */
    public DataFrame isetitem(int rowIdx, int colIdx, Object value) {
        List<Column> newCols = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            if (c == colIdx) {
                Column src = columns.get(c);
                Object[] arr = src.toObjectArray();
                arr[rowIdx] = value;
                // 用原 dtype 重建列;若类型不兼容则退化到 OBJECT
                try {
                    newCols.add(convertColumn(new ObjectColumn(src.name(), arr), src.dtype()));
                } catch (Exception e) {
                    newCols.add(new ObjectColumn(src.name(), arr));
                }
            } else {
                newCols.add(columns.get(c));
            }
        }
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /** 找标签在 Index 中的位置(-1 = 未找到)。 */
    private int findLabel(Object label) {
        Object[] labels = index.labels();
        if (labels == null) return -1;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != null && labels[i].equals(label)) return i;
        }
        return -1;
    }

    // ======================== 增删列(对齐 pandas insert/pop)========================

    /** 在 loc 位置插入新列(对齐 pandas df.insert);返回新 DataFrame。 */
    public DataFrame insert(int loc, String name, Object[] values) {
        requireColumnAbsent(name);
        if (values.length != nRows) throw new IllegalArgumentException(
            "insert 值数组长度 " + values.length + " ≠ rowCount " + nRows);
        Column newCol = inferColumnFromArray(name, values);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.add(loc, newCol);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /** 弹出某列(对齐 pandas df.pop);返回弹出的 Column(同时新 DataFrame 不含该列)。 */
    public jian.core.Column pop(String name) {
        int idx = requireColumn(name);
        Column popped = columns.get(idx);
        // 返回 popped 列;调用方应同时使用 "新 df = df.drop(name)" 获取不含该列的表
        return popped;
    }

    // ======================== 迭代器(对齐 pandas iterrows/itertuples/items/keys)========================

    /** 行迭代:每行 (rowLabel, Object[])(对齐 pandas df.iterrows)。 */
    public Iterable<Object[]> iterrows() {
        return () -> new java.util.Iterator<>() {
            int r = 0;
            @Override public boolean hasNext() { return r < nRows; }
            @Override public Object[] next() {
                Object[] row = new Object[nRows > 0 ? columns.size() + 1 : 1];
                row[0] = index.get(r);
                Object[] data = getRow(r);
                System.arraycopy(data, 0, row, 1, data.length);
                r++;
                return row;
            }
        };
    }

    /** 行迭代(命名元组风格,返回 Object[])(对齐 pandas df.itertuples)。 */
    public Iterable<Object[]> itertuples() {
        return iterRows();  // 复用既有 iterRows(index 不含,只返回数据行)
    }

    /** 列迭代:每列 (colName, Series)(对齐 pandas df.items)。 */
    public Iterable<Object[]> items() {
        return () -> new java.util.Iterator<>() {
            int c = 0;
            @Override public boolean hasNext() { return c < columns.size(); }
            @Override public Object[] next() {
                return new Object[]{columnNames().get(c), columns.get(c++)};
            }
        };
    }

    /** 列名迭代(对齐 pandas df.keys)。 */
    public Iterable<String> keys() { return columnNames(); }

    // ======================== 列名前后缀(对齐 pandas add_prefix/add_suffix)========================

    /** 列名加前缀(对齐 pandas df.add_prefix);返回列名变化的新 DataFrame。 */
    public DataFrame addPrefix(String prefix) {
        List<Column> newCols = new ArrayList<>();
        for (String c : columnNames()) {
            Column col = getColumn(c);
            newCols.add(col.rename(prefix + c));
        }
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /** 列名加后缀(对齐 pandas df.add_suffix)。 */
    public DataFrame addSuffix(String suffix) {
        List<Column> newCols = new ArrayList<>();
        for (String c : columnNames()) {
            Column col = getColumn(c);
            newCols.add(col.rename(c + suffix));
        }
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    // ======================== 补全入口:dot/abs/combine_first/mode/value_counts/nunique/reindex/squeeze ========================

    /** 矩阵点积(对齐 pandas df.dot);委托 {@link DataFrameArith}。 */
    public double dot(DataFrame other) { return DataFrameArith.dot(this, other); }
    /** 逐列绝对值(对齐 pandas df.abs);委托 {@link DataFrameArith}。 */
    public DataFrame abs() { return DataFrameArith.abs(this); }
    /** combine_first(对齐 pandas df.combine_first);委托 {@link DataFrameArith}。 */
    public DataFrame combineFirst(DataFrame other) { return DataFrameArith.combineFirst(this, other); }
    /** 众数(对齐 pandas Series.mode);委托 {@link DataFrameArith}。 */
    public Object colMode(String colName) { return DataFrameArith.mode(getColumn(colName)); }
    /** 值计数(对齐 pandas Series.value_counts);委托 {@link DataFrameArith}。 */
    public java.util.Map<Object, Integer> colValueCounts(String colName) {
        return DataFrameArith.valueCounts(getColumn(colName));
    }
    /** 唯一值数(DataFrame 级,对齐 pandas Series.nunique);委托 {@link DataFrameArith}。 */
    public int colNuniqueDf(String colName) { return DataFrameArith.nunique(getColumn(colName)); }

    /** 重索引(对齐 pandas df.reindex);委托 {@link DataFrameReshape}。 */
    public DataFrame reindex(Object[] labels) { return DataFrameReshape.reindex(this, labels); }
    /** reindex_like(对齐 pandas df.reindex_like)。 */
    public DataFrame reindexLike(DataFrame other) { return DataFrameReshape.reindexLike(this, other); }
    /** 降维(对齐 pandas df.squeeze)。 */
    public Object squeeze() { return DataFrameReshape.squeeze(this); }
    /** rename_axis(API 兼容占位,jian v1 Index 无 name)。 */
    public DataFrame renameAxis(String name) { return DataFrameReshape.renameAxis(this, name); }
    /** 替换列名(对齐 pandas df.set_axis)。 */
    public DataFrame setAxis(Object[] labels) { return DataFrameReshape.setAxis(this, labels); }

    /** 首个非缺失行号(对齐 pandas df.first_valid_index)。 */
    public int firstValidIndex() {
        for (int i = 0; i < nRows; i++) {
            for (String c : columnNames()) {
                if (!getColumn(c).isNull(i)) return i;
            }
        }
        return -1;
    }
    /** 末个非缺失行号(对齐 pandas df.last_valid_index)。 */
    public int lastValidIndex() {
        for (int i = nRows - 1; i >= 0; i--) {
            for (String c : columnNames()) {
                if (!getColumn(c).isNull(i)) return i;
            }
        }
        return -1;
    }

    /** 要求列不存在(用于 insert/assign 新列)。 */
    private void requireColumnAbsent(String name) {
        if (columnIndex(name) >= 0) {
            throw new IllegalArgumentException("列 \"" + name + "\" 已存在");
        }
    }

    /** 推断 OBJECT 列真实 dtype。 */
    private Column inferColumnDtype(Column src) {
        Object[] vals = src.toObjectArray();
        boolean allLong = true, allDouble = true, allString = true, allBool = true;
        for (Object v : vals) {
            if (v == null) continue;
            if (!(v instanceof Integer) && !(v instanceof Long)) allLong = false;
            if (!(v instanceof Number)) allDouble = false;
            if (!(v instanceof String)) allString = false;
            if (!(v instanceof Boolean)) allBool = false;
        }
        if (allBool) return convertColumn(src, DType.BOOL);
        if (allLong) return convertColumn(src, DType.LONG);
        if (allDouble) return convertColumn(src, DType.DOUBLE);
        if (allString) return convertColumn(src, DType.STRING);
        return src;
    }


    // ======================== astype 类型转换(对齐 pandas)========================

    /**
     * 把某列转为指定 dtype(对齐 pandas astype)。
     * <p><b>2026-08-09 阶段 F 扩展支持</b>:支持 7 种 dtype——
     * {@link DType#DOUBLE}/{@link DType#LONG}/{@link DType#INT}/{@link DType#STRING}/
     * {@link DType#BOOL}/{@link DType#DATETIME}/{@link DType#DATE}/{@link DType#OBJECT}。
     * 仅 {@link DType#CATEGORY} 暂不支持(jian v1 未实现完整 CATEGORY dtype 语义)。
     * <p>类型转换规则:
     * <ul>
     *   <li>BOOL:接受 Boolean / 数值(非 0 为 true)/ "true"-"false" / "1"-"0" 字符串</li>
     *   <li>DATETIME:接受 LocalDateTime / LocalDate(atStartOfDay)/ ISO 字符串</li>
     *   <li>DATE:接受 LocalDate / LocalDateTime(toLocalDate)/ ISO 字符串</li>
     * </ul>
     * @param colName String 列名,必须存在;非 null
     * @param target  DType 目标类型;非 null
     * @return DataFrame 该列被转换后的新 DataFrame(同 dtype 时返回 this;不可变,不修改原 DataFrame)
     */
    public DataFrame astype(String colName, DType target) {
        int i = requireColumn(colName);
        Column c = columns.get(i);
        if (c.dtype() == target) return this;
        Column converted = convertColumn(c, target);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.set(i, converted);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /** 把整列转目标 dtype(基于 Object[] 中转,实现简单;性能不是 M1 关注点)。 */
    private static Column convertColumn(Column src, DType target) {
        String name = src.name();
        Object[] vals = src.toObjectArray();
        int n = vals.length;
        switch (target) {
            case DOUBLE: {
                double[] d = new double[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) d[i] = Double.NaN;
                    else if (vals[i] instanceof Number num) d[i] = num.doubleValue();
                    else d[i] = Double.parseDouble(String.valueOf(vals[i]));
                }
                return new DoubleColumn(name, d);
            }
            case LONG: {
                long[] d = new long[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; d[i] = 0; }
                    else if (vals[i] instanceof Number num) d[i] = num.longValue();
                    else d[i] = Long.parseLong(String.valueOf(vals[i]));
                }
                return new LongColumn(name, d, mask);
            }
            case STRING: {
                String[] d = new String[n];
                for (int i = 0; i < n; i++) d[i] = vals[i] == null ? null : String.valueOf(vals[i]);
                return new StringColumn(name, d);
            }
            case INT: {
                int[] d = new int[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; d[i] = 0; }
                    else if (vals[i] instanceof Number num) d[i] = num.intValue();
                    else d[i] = Integer.parseInt(String.valueOf(vals[i]));
                }
                return new IntColumn(name, d, mask);
            }
            case BOOL: {
                // 2026-08-09 阶段 F:扩到 BOOL
                // 接受:Boolean / "true"/"false" / 0/1 数值
                boolean[] d = new boolean[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; continue; }
                    if (vals[i] instanceof Boolean b) d[i] = b;
                    else if (vals[i] instanceof Number num) d[i] = num.doubleValue() != 0;
                    else {
                        String s = String.valueOf(vals[i]).toLowerCase();
                        d[i] = "true".equals(s) || "1".equals(s);
                    }
                }
                return new BoolColumn(name, d, mask);
            }
            case DATETIME: {
                // 2026-08-09 阶段 F:扩到 DATETIME
                // 接受:LocalDateTime / LocalDate(转 atStartOfDay)/ String(ISO 格式解析)
                java.time.LocalDateTime[] d = new java.time.LocalDateTime[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) continue;
                    if (vals[i] instanceof java.time.LocalDateTime lt) d[i] = lt;
                    else if (vals[i] instanceof java.time.LocalDate ld) d[i] = ld.atStartOfDay();
                    else {
                        try {
                            // 兼容 ISO 格式(可能含 T 或空格分隔)
                            String s = vals[i].toString().replace(' ', 'T');
                            d[i] = java.time.LocalDateTime.parse(s);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("astype DATETIME 无法解析第 " + i
                                + " 行值:" + vals[i] + "(期望 ISO 格式 2026-01-01T12:00:00)");
                        }
                    }
                }
                return new DateTimeColumn(name, d);
            }
            case DATE: {
                // 2026-08-09 阶段 F:扩到 DATE
                java.time.LocalDate[] d = new java.time.LocalDate[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) continue;
                    if (vals[i] instanceof java.time.LocalDate ld) d[i] = ld;
                    else if (vals[i] instanceof java.time.LocalDateTime lt) d[i] = lt.toLocalDate();
                    else {
                        try {
                            d[i] = java.time.LocalDate.parse(String.valueOf(vals[i]));
                        } catch (Exception e) {
                            throw new IllegalArgumentException("astype DATE 无法解析第 " + i
                                + " 行值:" + vals[i] + "(期望 ISO 格式 2026-01-01)");
                        }
                    }
                }
                return new DateColumn(name, d);
            }
            case OBJECT: {
                return new ObjectColumn(name, vals);
            }
            default:
                // CATEGORY 列暂不支持(jian v1 未实现完整 CATEGORY dtype 语义)
                throw new IllegalArgumentException("astype 暂不支持转换到 " + target
                    + "(支持:DOUBLE/LONG/INT/STRING/BOOL/DATETIME/DATE/OBJECT)");
        }
    }

    // ======================== toString(对齐 pandas repr)========================

    @Override public String toString() {
        return toString(10, 20);
    }

    /**
     * 格式化表格输出(对齐 pandas __repr__:截断 + head/tail + 维度摘要)。
     * @param maxRows    int 最大显示行数;超出按 head/tail 截断并显示 "..."
     * @param maxColWidth int 每列最大字符宽;超出截断
     * @return String 多行表格字符串;空表返回 "Empty DataFrame"
     */
    public String toString(int maxRows, int maxColWidth) {
        if (nRows == 0) {
            return "Empty DataFrame\ncolumns: " + columnNames();
        }
        StringBuilder sb = new StringBuilder();
        List<String> names = columnNames();

        boolean truncate = nRows > maxRows;
        int headN = truncate ? maxRows / 2 + 1 : nRows;
        int tailN = truncate ? maxRows / 2 : 0;

        // 列宽:取表头长 + 将要显示的行中该列最长值(字符串),封顶 maxColWidth
        int[] widths = new int[names.size() + 1];
        widths[0] = 4;  // 行索引列宽
        for (int c = 0; c < names.size(); c++) {
            int w = Math.max(names.get(c).length(), 4);
            // 扫描将要显示的行
            for (int r = 0; r < headN; r++) w = Math.max(w, valWidth(r, c));
            if (truncate) for (int r = nRows - tailN; r < nRows; r++) w = Math.max(w, valWidth(r, c));
            widths[c + 1] = Math.min(w, maxColWidth);
        }

        // 表头
        sb.append(String.format("%-" + widths[0] + "s", "")).append(' ');
        for (int c = 0; c < names.size(); c++)
            sb.append(String.format("%-" + widths[c + 1] + "s", trunc(names.get(c), widths[c + 1]))).append(' ');
        sb.append('\n');

        // head 行
        for (int r = 0; r < headN; r++) appendRow(sb, r, widths);
        if (truncate) {
            sb.append(String.format("%-" + widths[0] + "s", "...")).append(' ');
            for (int c = 0; c < names.size(); c++)
                sb.append(String.format("%-" + widths[c + 1] + "s", "...")).append(' ');
            sb.append('\n');
            for (int r = nRows - tailN; r < nRows; r++) appendRow(sb, r, widths);
        }
        sb.append("\n[").append(nRows).append(" rows × ").append(columns.size()).append(" columns]");
        return sb.toString();
    }

    /** 计算 (r, c) 单元格值的显示宽度。 */
    private int valWidth(int r, int c) {
        Object v = columns.get(c).get(r);
        if (v == null) return 4;  // <NA>
        return formatVal(v, columns.get(c).dtype()).length();
    }

    private void appendRow(StringBuilder sb, int r, int[] widths) {
        sb.append(String.format("%-" + widths[0] + "s", String.valueOf(index.get(r)))).append(' ');
        for (int c = 0; c < columns.size(); c++) {
            Object v = columns.get(c).get(r);
            String s = v == null ? "<NA>" : formatVal(v, columns.get(c).dtype());
            sb.append(String.format("%-" + widths[c + 1] + "s", trunc(s, widths[c + 1]))).append(' ');
        }
        sb.append('\n');
    }

    private static String formatVal(Object v, DType dt) {
        if (dt == DType.DOUBLE) return String.format("%.6g", ((Number) v).doubleValue());
        return String.valueOf(v);
    }

    private static String trunc(String s, int w) {
        if (s.length() <= w) return s;
        if (w <= 3) return s.substring(0, w);
        return s.substring(0, w - 3) + "...";
    }

    private static int norm(int idx, int len) {
        if (idx < 0) idx += len;
        if (idx < 0 || idx > len) throw new IndexOutOfBoundsException("索引 " + idx + " 越界,len=" + len);
        return idx;
    }

    private int requireColumn(String name) {
        int i = columnIndex(name);
        if (i < 0) {
            throw new IllegalArgumentException(
                    "列 \"" + name + "\" 不存在,现有列:" + columnNames());
        }
        return i;
    }

    /**
     * 把 Number 或数字 String 转为 Number(供 buildColumn 处理 Schema 推断后的混合值)。
     * Schema.infer 可能把纯数字字符串列判为 INT/LONG/DOUBLE,buildColumn 需兼容 String 形式。
     */
    private static Number toNumber(Object v) {
        if (v instanceof Number) return (Number) v;
        String s = v.toString().trim();
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return Double.parseDouble(s); }
    }

    private List<Column> emptyColumns() {
        List<Column> r = new ArrayList<>();
        for (Column c : columns) r.add(c.slice(0, 0));
        return r;
    }

    // ======================== concat(静态,对齐 pandas.concat)========================

    /**
     * 纵向/横向拼接(对齐 pandas.concat)。
     *
     * @param dfs DataFrame 列表
     * @param axis 0=纵向(行堆叠,列对齐);1=横向(列拼接,行对齐)
     */
    public static DataFrame concat(java.util.List<DataFrame> dfs, int axis) {
        return DataFrameMerge.concat(dfs, axis);
    }

    /** 纵向拼接便捷方法(行堆叠)。 */
    public static DataFrame concat(DataFrame... dfs) {
        return concat(java.util.Arrays.asList(dfs), 0);
    }

    /** 内部:变换后构造(复用列,不拷贝)。 */
    DataFrame rebuild(List<Column> newColumns, Index newIndex) {
        return new DataFrame(newColumns, newIndex, allowsDuplicateLabels);
    }

    /**
     * 内部:取所有列(同包用)。
     *
     * <p><b>不可变契约</b>:返回 {@link Collections#unmodifiableList(List)} 视图,
     * 防止同包工具类意外 add/remove 破坏 DataFrame 的「值不可变」承诺
     * (见 AGENTS.md §3.7.7「无静态可变状态 + 不可变数据」红线)。
     * 所有现有调用方(DataFrameStats/DataFrameMissing 等)都是只读迭代,
     * 返回 unmodifiableList 不破坏任何调用方,且能挡住未来的误修改。
     */
    List<Column> columnsInternal() { return Collections.unmodifiableList(columns); }

    // ======================== 阶段 A 主类承载的 *Impl(私有静态,被主类入口委托)========================

    // ┌─ What : resetIndex 实现 —— Index 转普通列,新表回 RangeIndex
    // │  Why  : 对齐 pandas reset_index;主类承载因与 Index getter 同源
    // │  How  : ① RangeIndex 或 indexCol=null → 直接返回同表 ② 否则 labels 转新列
    private static DataFrame resetIndexImpl(DataFrame df, String indexCol) {
        if (df.index().isRange() || indexCol == null || indexCol.isEmpty()) {
            return DataFrame.ofColumnsDirect(copyColumns(df));
        }
        Object[] labels = df.index().labels();
        if (labels == null) return DataFrame.ofColumnsDirect(copyColumns(df));
        Column idxCol = inferColumnFromArray(indexCol, labels);
        List<Column> cols = copyColumns(df);
        cols.add(idxCol);
        return DataFrame.ofColumnsDirect(cols);
    }

    // ┌─ What : setIndex 实现 —— 普通列提升为 Index
    // │  Why  : 对齐 pandas set_index
    private static DataFrame setIndexImpl(DataFrame df, String[] cols, boolean drop) {
        if (cols == null || cols.length == 0) {
            throw new IllegalArgumentException("set_index cols 至少 1 列");
        }
        for (String c : cols) df.getColumn(c);  // 校验列存在
        Set<String> promoted = new HashSet<>(Arrays.asList(cols));
        List<Column> remaining = new ArrayList<>();
        for (String c : df.columnNames()) if (!drop || !promoted.contains(c)) remaining.add(df.getColumn(c));
        DataFrame result = DataFrame.ofColumnsDirect(remaining);
        Column c0 = df.getColumn(cols[0]);
        Object[] labels0 = new Object[df.rowCount()];
        for (int i = 0; i < labels0.length; i++) labels0[i] = c0.get(i);
        return result.withIndex(Index.of(labels0));
    }

    // ┌─ What : sample 实现 —— 随机采样(可复现种子)
    // │  Why  : 对齐 pandas df.sample;主类承载因与 takeRows 同源(行选择)
    // │  How  : replace=true 直接 nextInt;replace=false Fisher-Yates 部分洗牌
    private static DataFrame sampleImpl(DataFrame df, int n, boolean replace, long seed) {
        int total = df.rowCount();
        if (n < 0) throw new IllegalArgumentException("sample n 不能为负:" + n);
        if (total == 0 && n > 0) throw new IllegalArgumentException("sample n=" + n + " 但 rowCount=0");
        if (!replace && n > total) {
            throw new IllegalArgumentException("sample n=" + n + " > rowCount=" + total + "(需 replace=true)");
        }
        java.util.Random rng = new java.util.Random(seed);
        int[] picked = new int[n];
        if (replace) {
            for (int k = 0; k < n; k++) picked[k] = rng.nextInt(total);
        } else {
            int[] pool = new int[total];
            for (int i = 0; i < total; i++) pool[i] = i;
            for (int k = 0; k < n; k++) {
                int j = k + rng.nextInt(total - k);
                int tmp = pool[k]; pool[k] = pool[j]; pool[j] = tmp;
                picked[k] = pool[k];
            }
        }
        return df.takeRows(picked);
    }

    // ┌─ What : applyRow 实现 —— 按行应用函数生成新列
    // │  Why  : 对齐 pandas df.apply(axis=1) 单列输出;主类承载(链式入口与 eval/sql 同类)
    private static DataFrame applyRowImpl(DataFrame df, String newCol,
                                          java.util.function.Function<Object[], Object> fn) {
        int n = df.rowCount();
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = fn.apply(df.getRow(i));
        Column newColObj = inferColumnFromArray(newCol, out);
        List<Column> cols = new ArrayList<>(df.columnCount() + 1);
        for (String c : df.columnNames()) cols.add(df.getColumn(c));
        cols.add(newColObj);
        return DataFrame.ofColumnsDirect(cols);
    }

    // ┌─ What : info 实现 —— 可读性表格
    // │  Why  : 对齐 pandas df.info;主类承载因与 dtypes/describe 同类元信息
    private static String infoImpl(DataFrame df) {
        StringBuilder sb = new StringBuilder();
        sb.append("<DataFrame: ").append(df.rowCount()).append(" 行 × ").append(df.columnCount()).append(" 列>\n");
        sb.append(String.format(" %-3s %-12s %-11s %5s   %s%n", "#", "列名", "dtype", "非空", "内存"));
        long totalMem = 0;
        List<String> cols = df.columnNames();
        for (int i = 0; i < cols.size(); i++) {
            Column c = df.getColumn(cols.get(i));
            int nonNull = c.size() - c.nullCount();
            long mem = (long) c.size() * 8;
            totalMem += mem;
            String nm = cols.get(i).length() <= 12 ? cols.get(i) : cols.get(i).substring(0, 11) + "…";
            sb.append(String.format(" %-3d %-12s %-11s %5d   %6d B%n", i, nm, c.dtype(), nonNull, mem));
        }
        sb.append("总内存估算: ").append(totalMem).append(" B");
        return sb.toString();
    }

    // ┌─ What : selectDtypes 实现 —— 按 dtype 筛列
    // │  Why  : 对齐 pandas df.select_dtypes;主类承载因与 select 同类列选择
    private static DataFrame selectDtypesImpl(DataFrame df, DType[] include, DType[] exclude) {
        Set<DType> inc = include == null ? null : EnumSet.noneOf(DType.class);
        if (inc != null) inc.addAll(Arrays.asList(include));
        Set<DType> exc = exclude == null ? null : EnumSet.noneOf(DType.class);
        if (exc != null) exc.addAll(Arrays.asList(exclude));
        List<String> kept = new ArrayList<>();
        for (String c : df.columnNames()) {
            DType dt = df.getColumn(c).dtype();
            if (inc != null && !inc.contains(dt)) continue;
            if (exc != null && exc.contains(dt)) continue;
            kept.add(c);
        }
        return kept.isEmpty() ? df.drop(df.columnNames().toArray(new String[0]))
                              : df.select(kept.toArray(new String[0]));
    }

    /** 内部:复制 df 的所有列(Column 自身不可变,引用复制即可)。 */
    private static List<Column> copyColumns(DataFrame df) {
        List<Column> cols = new ArrayList<>(df.columnCount());
        for (String c : df.columnNames()) cols.add(df.getColumn(c));
        return cols;
    }

    /** 内部:从 Object[] 推断 Column 类型(全 Long/Integer → LONG;全 Number → DOUBLE;全 String → STRING;否则 OBJECT)。 */
    private static Column inferColumnFromArray(String name, Object[] arr) {
        boolean allLong = true, allDouble = true, allString = true;
        for (Object v : arr) {
            if (v == null) continue;
            if (!(v instanceof Integer) && !(v instanceof Long)) allLong = false;
            if (!(v instanceof Number)) allDouble = false;
            if (!(v instanceof String)) allString = false;
        }
        if (allLong) {
            long[] l = new long[arr.length];
            boolean[] mask = new boolean[arr.length];
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) mask[i] = true;
                else l[i] = ((Number) arr[i]).longValue();
            }
            return new LongColumn(name, l, mask);
        }
        if (allDouble) {
            double[] d = new double[arr.length];
            for (int i = 0; i < arr.length; i++) d[i] = arr[i] == null ? Double.NaN : ((Number) arr[i]).doubleValue();
            return new DoubleColumn(name, d);
        }
        if (allString) {
            String[] s = new String[arr.length];
            for (int i = 0; i < arr.length; i++) s[i] = arr[i] == null ? null : String.valueOf(arr[i]);
            return new StringColumn(name, s);
        }
        return new ObjectColumn(name, arr);
    }
}
