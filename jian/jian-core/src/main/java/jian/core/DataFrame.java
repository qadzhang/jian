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
import java.util.OptionalInt;
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
// 行数留档(§3.1 破例):本文件不含注释 609 行(红线 600)。toRecords/fromRecords/
// selectBy 三个公共入口均已单行委托 RecordBridge/DataFrameMeta(主类零实现体);select/drop/
// filter/slice/iterrows 等小实现按 §3.1.1.1 归属表属主类承载,继续拆分将破坏"链式入口聚合"。
public final class DataFrame {

    private final List<Column> columns;
    private final Index index;
    private final int nRows;
    private final boolean allowsDuplicateLabels;  // 规范 01 §3.12,默认 false

    private DataFrame(List<Column> columns, Index index, int rowsOverride, boolean allowsDup) {
        this.columns = columns;
        this.index = index;
        this.allowsDuplicateLabels = allowsDup;
        this.nRows = rowsOverride;
    }

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
        return DataFrameConstruct.of(schema, rows);
    }

    /**
     * 从 Java record 列表构造(组件名 → 列名,组件声明类型精确映射 DType,不做推断;
     * 借鉴 Kotlin DataFrame convertTo,Java 17 record 的强类型入口)。
     * <pre>{@code
     * record Order(String 类别, long 金额) {}
     * DataFrame df = DataFrame.fromRecords(List.of(new Order("食品", 10L), new Order("文具", 5L)));
     * }</pre>
     * @param records List&lt;?&gt; record 实例列表,非 null 且非空;元素须为同一 record 类型
     * @return DataFrame 列 = record 组件,行数 = records.size()
     * @throws IllegalArgumentException 空列表 / 元素非 record / 元素类型不齐
     */
    public static DataFrame fromRecords(List<?> records) {
        return RecordBridge.fromRecords(records);
    }

    /**
     * 从列式 Map&lt;列名, Object[]&gt; 构造(类型推断)。
     * @param columnsByName Map&lt;String,Object[]&gt; 列名→列数据;非 null;每列长度须一致
     * @return DataFrame 类型推断后构造的 DataFrame
     */
    public static DataFrame ofColumns(Map<String, Object[]> columnsByName) {
        return DataFrameConstruct.ofColumns(columnsByName);
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
        return DataFrameConstruct.ofColumnArrays(columnNames, columnArrays);
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
        return DataFrameConstruct.ofColumnArraysSafe(columnNames, columnArrays);
    }

    /**
     * 零拷贝安全提示(仅首次调用,stderr 输出一次)。
     * 提醒开发者 ofColumnArrays 是零拷贝,Web 场景应改用 ofColumnArraysSafe。
     */
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
        return DataFrameConvert.getDoubleColumn(this, name);
    }

    /**
     * 取 LongColumn(类型化访问器)。
     * @param name String 列名,必须存在;非 null
     * @return LongColumn;INT 列会转 LONG 返回新实例
     * @throws IllegalStateException 列非整数类型
     */
    public LongColumn getLongColumn(String name) {
        return DataFrameConvert.getLongColumn(this, name);
    }

    /**
     * 取 IntColumn(类型化访问器)。
     * @param name String 列名,必须存在;非 null
     * @return IntColumn;LONG 列会转 INT 返回新实例(可能丢精度)
     * @throws IllegalStateException 列非整数类型
     */
    public IntColumn getIntColumn(String name) {
        return DataFrameConvert.getIntColumn(this, name);
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
            // 因为 IO 层依赖 getRow 用 null 表示缺失,所以缺失行放 null(而非 NaN 对象)
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
     * 按谓词选列(列选择器,对齐 pandas df.filter(regex=) / Kotlin DataFrame cols(startsWith(..));
     * 谓词作用于列名,命中列按原列序保留)。
     * <pre>{@code
     * df.selectBy(col -> col.startsWith("q") || df.getColumn(col).dtype() == DType.DOUBLE);
     * }</pre>
     * @param columnPredicate Predicate&lt;String&gt; 列名谓词,非 null
     * @return DataFrame 仅含命中列的新表;无命中列时返回 0 列表(与 select() 空参行为一致)
     */
    public DataFrame selectBy(java.util.function.Predicate<String> columnPredicate) {
        return DataFrameMeta.selectByImpl(this, columnPredicate);
    }

    /**
     * 每行转成一个 Java record 实例(强类型出口;record 组件名 ↔ 列名精确匹配;
     * DataFrame 多余列忽略 = 投影语义,组件缺列则报错;类型跨族不隐式转换,请先 astype)。
     * <p>缺失值语义(AGENTS.md §3.5):非 DOUBLE 列缺失 → null(组件须为包装类型,原始类型报错);
     * DOUBLE 列缺失/NaN 以 {@code Double.NaN} 不失真传递(API 出口不做 null 转换)。
     * <pre>{@code
     * record Order(String 类别, long 金额) {}
     * List<Order> orders = df.toRecords(Order.class);
     * }</pre>
     * @param type Class&lt;T&gt; 目标 record 类型,非 null 且必须为 record
     * @param <T> record 类型
     * @return List&lt;T&gt; 行数 == rowCount()
     * @throws IllegalArgumentException type 非 record / 组件无对应列 / 某行类型不匹配、越界或 null 进原始类型组件
     */
    public <T> List<T> toRecords(Class<T> type) {
        return RecordBridge.toRecords(this, type);
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
     * 替换 Index(对齐 pandas set_index 后的视图)。被 DataFrameIndex.setIndex 调用。
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
        // 因为对齐 pandas iloc 切片(负数倒数 + 越界 clamp,pandas iloc[-5:2]/iloc[0:99]
        // 均容忍并夹取),所以这里越界不抛异常。
        // start<0 → 倒数后再夹 0;end<0 → 倒数;end>n → n;start≥end → 空表(不抛)。
        if (start < 0) start += nRows;
        if (end < 0) end += nRows;
        start = Math.max(Math.min(start, nRows), 0);
        end = Math.max(Math.min(end, nRows), 0);
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
        // 因为对齐 pandas —— n<0 表示"去掉末 |n| 行"(pandas head(-1) 返回除最后一行外的全部),
        // 所以负参不返回空表,而是截掉末尾 |n| 行
        if (n < 0) return slice(0, nRows + n);
        return slice(0, Math.min(n, nRows));
    }

    /**
     * 后 n 行(对齐 pandas tail)。
     * @param n int 行数;负数视为 0
     * @return DataFrame 末尾 n 行
     */
    public DataFrame tail(int n) {
        // 因为对齐 pandas —— n<0 表示"去掉首 |n| 行",所以负参不返回空表,而是截掉头部 |n| 行
        if (n < 0) return slice(-n, nRows);
        n = Math.min(n, nRows);
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

    /**
     * pivotTable 默认 mean。
     * @param index String 索引列名
     * @param columns String 透视列名
     * @param values String 值列名
     */
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
    /**
     * @return double 该列求和(参数同 {@link #colMean})
     * @param colName String 列名,必须存在;非 null
     */
    public double colSum(String colName) { return DataFrameStats.sum(getColumn(colName)); }
    /**
     * @return double 该列最小(参数同 {@link #colMean})
     * @param colName String 列名,必须存在;非 null
     */
    public double colMin(String colName) { return DataFrameStats.min(getColumn(colName)); }
    /**
     * @return double 该列最大(参数同 {@link #colMean})
     * @param colName String 列名,必须存在;非 null
     */
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

    // ======================== 统计扩展(委托 DataFrameStats)========================

    /**
     * 列偏度(对齐 pandas Series.skew);经 StatsProvider SPI。
     * @param colName String 列名,必须存在;非 null
     */
    public double colSkew(String colName) { return DataFrameStats.skewness(getColumn(colName)); }
    /**
     * 列峰度(超额,对齐 pandas Series.kurt);经 SPI。
     * @param colName String 列名,必须存在;非 null
     */
    public double colKurt(String colName) { return DataFrameStats.kurtosis(getColumn(colName)); }
    /**
     * 列平均绝对偏差(对齐 pandas Series.mad);经 SPI。
     * @param colName String 列名,必须存在;非 null
     */
    public double colMad(String colName) { return DataFrameStats.mad(getColumn(colName)); }
    /**
     * 列标准误差(对齐 pandas Series.sem);经 SPI。
     * @param colName String 列名,必须存在;非 null
     */
    public double colSem(String colName) { return DataFrameStats.sem(getColumn(colName)); }
    /**
     * 列精确分位数(对齐 pandas Series.quantile;经 SPI)。
     * @param colName String 列名,必须存在;非 null
     * @param q double 分位数[0,1]
     */
    public double colQuantile(String colName, double q) { return DataFrameStats.quantile(getColumn(colName), q); }
    /**
     * 列方差(对齐 pandas Series.var;直接 ddof=1 计算)。
     * @param colName String 列名,必须存在;非 null
     */
    public double colVar(String colName) {
        double std = DataFrameStats.std(getColumn(colName));
        return std * std;
    }
    /**
     * 列积(对齐 pandas Series.prod)。
     * @param colName String 列名,必须存在;非 null
     */
    public double colProd(String colName) { return DataFrameStats.prod(getColumn(colName)); }
    /**
     * 列唯一值数(对齐 pandas Series.nunique;skip 缺失)。
     * @param colName String 列名,必须存在;非 null
     */
    public int colNunique(String colName) { return DataFrameStats.nunique(getColumn(colName)); }
    /**
     * 列 all(对齐 pandas Series.all;所有非缺失值为真)。
     * @param colName String 列名,必须存在;非 null
     */
    public boolean colAll(String colName) { return DataFrameStats.all(getColumn(colName)); }
    /**
     * 列 any(对齐 pandas Series.any;任一非缺失值为真)。
     * @param colName String 列名,必须存在;非 null
     */
    public boolean colAny(String colName) { return DataFrameStats.any(getColumn(colName)); }

    /**
     * 两列相关(对齐 pandas Series.corr;method=pearson/spearman,默认 pearson);经 SPI。
     * @param colA 参数;非 null
     * @param colB 参数;非 null
     * @param method String 方法(pearson/spearman)
     */
    public double colCorr(String colA, String colB, String method) {
        return DataFrameStats.corr(getColumn(colA), getColumn(colB), method);
    }
    /**
     * colCorr 便捷重载:method=pearson。
     * @param colA 参数;非 null
     * @param colB 参数;非 null
     */
    public double colCorr(String colA, String colB) { return colCorr(colA, colB, "pearson"); }
    /**
     * 两列协方差(对齐 pandas Series.cov);经 SPI。
     * @param colA 参数;非 null
     * @param colB 参数;非 null
     */
    public double colCov(String colA, String colB) {
        return DataFrameStats.cov(getColumn(colA), getColumn(colB));
    }

    /**
     * 列内秩为新列(对齐 pandas Series.rank;method=average/min/max/first/dense);经 SPI。
     * @param colName String 列名,必须存在;非 null
     * @param method String 方法(pearson/spearman)
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colRank(String colName, String method, String newColName) {
        return DataFrameStats.rank(getColumn(colName), method, newColName);
    }
    /**
     * colRank 便捷重载:method=average,newColName={col}_rank。
     * @param colName String 列名,必须存在;非 null
     */
    public DoubleColumn colRank(String colName) {
        return colRank(colName, "average", colName + "_rank");
    }

    /**
     * 列累积和为新列(对齐 pandas Series.cumsum)。
     * @param colName String 列名,必须存在;非 null
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colCumsum(String colName, String newColName) {
        return DataFrameStats.cumsum(getColumn(colName), newColName);
    }
    /**
     * 列累积最大为新列(对齐 pandas Series.cummax)。
     * @param colName String 列名,必须存在;非 null
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colCummax(String colName, String newColName) {
        return DataFrameStats.cummax(getColumn(colName), newColName);
    }
    /**
     * 列累积最小为新列(对齐 pandas Series.cummin)。
     * @param colName String 列名,必须存在;非 null
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colCummin(String colName, String newColName) {
        return DataFrameStats.cummin(getColumn(colName), newColName);
    }
    /**
     * 列累积积为新列(对齐 pandas Series.cumprod)。
     * @param colName String 列名,必须存在;非 null
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colCumprod(String colName, String newColName) {
        return DataFrameStats.cumprod(getColumn(colName), newColName);
    }
    /**
     * 列差分为新列(对齐 pandas Series.diff(periods))。
     * @param colName String 列名,必须存在;非 null
     * @param periods int 位移步数
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colDiff(String colName, int periods, String newColName) {
        return DataFrameStats.diff(getColumn(colName), periods, newColName);
    }
    /**
     * 列百分比变化为新列(对齐 pandas Series.pct_change)。
     * @param colName String 列名,必须存在;非 null
     * @param periods int 位移步数
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colPctChange(String colName, int periods, String newColName) {
        return DataFrameStats.pctChange(getColumn(colName), periods, newColName);
    }
    /**
     * 列裁剪为新列(对齐 pandas Series.clip)。
     * @param colName String 列名,必须存在;非 null
     * @param lower double 下界
     * @param upper double 上界
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colClip(String colName, double lower, double upper, String newColName) {
        return DataFrameStats.clip(getColumn(colName), lower, upper, newColName);
    }
    /**
     * 列四舍五入为新列(对齐 pandas Series.round)。
     * @param colName String 列名,必须存在;非 null
     * @param decimals int 小数位
     * @param newColName String 新列名;非 null
     */
    public DoubleColumn colRound(String colName, int decimals, String newColName) {
        return DataFrameStats.round(getColumn(colName), decimals, newColName);
    }

    /**
     * 全数值列相关矩阵(对齐 pandas DataFrame.corr;method=pearson/spearman)。
     * @param method String 方法(pearson/spearman)
     */
    public DataFrame corrMatrix(String method) { return DataFrameMeta.buildMatrix(this, "corr", method); }
    /** corrMatrix 便捷重载:method=pearson。 */
    public DataFrame corrMatrix() { return corrMatrix("pearson"); }
    /** 全数值列协方差矩阵(对齐 pandas DataFrame.cov)。 */
    public DataFrame covMatrix() { return DataFrameMeta.buildMatrix(this, "cov", null); }

    // ======================== 重塑/合并/二元扩展 ========================

    /**
     * 简单透视(无聚合,对齐 pandas df.pivot);委托 {@link DataFrameReshape}。
     * @param index String 索引列名
     * @param columns String 透视列名
     * @param values String 值列名
     */
    public DataFrame pivot(String index, String columns, String values) {
        return DataFrameReshape.pivot(this, index, columns, values);
    }
    /**
     * 列展平(对齐 pandas df.explode);委托 {@link DataFrameReshape}。
     * @param col String 列名;非 null
     */
    public DataFrame explode(String col) { return DataFrameReshape.explode(this, col); }
    /**
     * 堆叠:列→行(对齐 pandas df.stack;委托 {@link DataFrameReshape})。
     * @param idCols 参数;非 null
     * @param valueCols 参数;非 null
     */
    public DataFrame stack(String[] idCols, String[] valueCols) {
        return DataFrameReshape.stack(this, idCols, valueCols);
    }
    /**
     * 展开:行→列(对齐 pandas df.unstack;委托 {@link DataFrameReshape})。
     * @param idCol 参数;非 null
     * @param keyCol 参数;非 null
     * @param valCol 参数;非 null
     */
    public DataFrame unstack(String idCol, String keyCol, String valCol) {
        return DataFrameReshape.unstack(this, idCol, keyCol, valCol);
    }

    /**
     * 索引 join(对齐 pandas df.join);委托 {@link DataFrameMerge}。
     * @param right DataFrame 右表;非 null
     * @param on String 连接键列名;非 null
     * @param how String 连接类型(inner/left/right/outer)
     */
    public DataFrame join(DataFrame right, String on, String how) {
        return DataFrameMerge.join(this, right, on, how);
    }
    /**
     * join 便捷重载:how=left。
     * @param right DataFrame 右表;非 null
     * @param on String 连接键列名;非 null
     */
    public DataFrame join(DataFrame right, String on) { return join(right, on, "left"); }
    /**
     * 按最近键对齐(对齐 pandas merge_asof,方向 backward);委托 {@link DataFrameMerge}。
     * @param right DataFrame 右表;非 null
     * @param on String 连接键列名;非 null
     */
    public DataFrame mergeAsof(DataFrame right, String on) {
        return DataFrameMerge.mergeAsof(this, right, on);
    }

    /**
     * DataFrame 与标量逐列加(对齐 pandas df.add(scalar));委托 {@link DataFrameArith}。
     * @param scalar double 标量
     */
    public DataFrame addScalarAllColumns(double scalar) { return DataFrameArith.addScalarAllColumns(this, scalar); }
    /**
     * DataFrame 与标量逐列减。
     * @param scalar double 标量
     */
    public DataFrame subScalarAllColumns(double scalar) { return DataFrameArith.subScalarAllColumns(this, scalar); }
    /**
     * DataFrame 与标量逐列乘。
     * @param scalar double 标量
     */
    public DataFrame mulScalarAllColumns(double scalar) { return DataFrameArith.mulScalarAllColumns(this, scalar); }
    /**
     * DataFrame 与标量逐列除。
     * @param scalar double 标量
     */
    public DataFrame divScalarAllColumns(double scalar) { return DataFrameArith.divScalarAllColumns(this, scalar); }

    // ======================== 时序扩展 ========================

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
        return DataFrameTimeseries.shift(this, colName, periods, newColName);
    }

    /**
     * shift 便捷重载:newColName = {col}_shifted。
     * @param colName String 列名,必须存在;非 null
     * @param periods int 位移步数
     */
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
        return DataFrameTimeseries.atTime(this, tsCol, time);
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
        return DataFrameTimeseries.betweenTime(this, tsCol, start, end);
    }

    /**
     * asof 查询(对齐 pandas DataFrame.asof):返回 ≤ label 的最后一个非空观测所在行。
     * 因为遇 > label 即 break 依赖输入升序(乱序输入会提前退出返回错误行),
     * 所以这里全扫描取"最后一个 ≤ label"的行,对乱序输入行为确定;null 行跳过不中断。
     * @param tsCol String 时间列名(LocalDateTime 元素);非 null
     * @param label LocalDateTime 目标时间;非 null
     * @return DataFrame 含一行(若没找到返回空表)
     */
    public DataFrame asof(String tsCol, java.time.LocalDateTime label) {
        return DataFrameTimeseries.asof(this, tsCol, label);
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
        return DataFrameConstruct.assign(this, newName, fn);
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
     * 按列填充缺失(对齐 pandas df.fillna(dict))。
     * @param byCol Map&lt;String,Object&gt; 列名 → 填充值;非 null
     * @return DataFrame 命中列的缺失被填充,其余列不动
     * @throws IllegalArgumentException 列名不存在或值类型与列 dtype 不匹配
     */
    public DataFrame fillna(java.util.Map<String, Object> byCol) { return DataFrameMissing.fillnaByColumn(this, byCol); }

    /**
     * 线性插值填充缺失(对齐 pandas DataFrame.interpolate)。
     * <p>策略:数值列缺失位置按前后非缺失值线性插值;首尾连续缺失保持;非数值列原样保留。
     * @return DataFrame 同结构,数值列缺失被线性插值填充
     */
    public DataFrame interpolate() { return DataFrameMissing.interpolate(this); }

    /** notna:isna 的反转(返回非缺失掩码 DataFrame,对齐 pandas df.notna)。 */
    public DataFrame notna() {
        return DataFrameMeta.notna(this);
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
        return DataFrameCompare.compare(this, colName, op, value);
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
    /**
     * 小于(参数语义同 {@link #colGt})。
     * @param colName String 列名,必须存在;非 null
     * @param value Object 比较值
     */
    public BoolColumn colLt(String colName, double value) { return compare(colName, "<", value); }
    /**
     * 大于等于(参数语义同 {@link #colGt})。
     * @param colName String 列名,必须存在;非 null
     * @param value Object 比较值
     */
    public BoolColumn colGe(String colName, double value) { return compare(colName, ">=", value); }
    /**
     * 小于等于(参数语义同 {@link #colGt})。
     * @param colName String 列名,必须存在;非 null
     * @param value Object 比较值
     */
    public BoolColumn colLe(String colName, double value) { return compare(colName, "<=", value); }
    /**
     * 等于(支持任意类型,对齐 pandas ==)。
     * @param colName String 列名,必须存在;非 null
     * @param value   Object 比较值;类型应与列元素兼容(Number/String/Boolean 等)
     * @return BoolColumn 同长度掩码;缺失行 false
     */
    public BoolColumn colEq(String colName, Object value) { return compare(colName, "==", value); }
    /**
     * 不等于(参数语义同 {@link #colEq})。
     * @param colName String 列名,必须存在;非 null
     * @param value Object 比较值
     * @return BoolColumn 同长度掩码;缺失行 **true**(因为 NaN != x 对齐 pandas 与 query 双引擎)
     */
    public BoolColumn colNe(String colName, Object value) { return compare(colName, "!=", value); }

    // ======================== loc / iloc(对齐 pandas)========================

    /**
     * iloc:按位置选行(返回新 DataFrame)。
     * @param rowIndices int... 位置下标,每个 ∈ [0, rowCount());允许重复/乱序;非 null
     * @return DataFrame 长度 == rowIndices.length 的新表
     */
    public DataFrame iloc(int... rowIndices) {
        // 因为对齐 pandas iloc —— 负下标从尾部倒数(iloc(-1) = 最后一行),
        // 所以先归一再透传;takeRows 保持严格非负契约不变。
        int n = rowCount();
        int[] normed = rowIndices.clone();
        for (int i = 0; i < normed.length; i++) {
            if (normed[i] < 0) normed[i] += n;
        }
        return takeRows(normed);
    }

    /**
     * loc:按行标签选行。当前 Index 是 RangeIndex 时,标签 == 位置。
     * @param labels Object... 行标签值;RangeIndex 时需为 Number;非 null
     * @return DataFrame 选中的行组成的新表(找不到的标签被跳过)
     */
    public DataFrame loc(Object... labels) {
        return DataFrameIndex.loc(this, labels);
    }

    // ======================== 高频实用扩展(内聚到既有伴生类)========================

    /**
     * 极值位置:列最大值所在首行下标(对齐 pandas df.idxmax);并入 {@link DataFrameSort}(与 nlargest 同类)。
     * @param col String 列名;非 null
     */
    public int idxmax(String col) { return DataFrameSort.idxmax(this, col); }
    /**
     * 极值位置:列最小值所在首行下标(对齐 pandas df.idxmin);并入 {@link DataFrameSort}。
     * @param col String 列名;非 null
     */
    public int idxmin(String col) { return DataFrameSort.idxmin(this, col); }
    /**
     * 重复行掩码(对齐 pandas df.duplicated,keep=first/last/none);并入 {@link DataFrameReshape}(与 dropDuplicates 同类)。
     * @param subset String[] 子集列
     * @param keep String 保留策略
     */
    public boolean[] duplicated(String[] subset, String keep) { return DataFrameReshape.duplicated(this, subset, keep); }
    /** duplicated 便捷重载:subset=全列,keep=first。 */
    public boolean[] duplicated() { return DataFrameReshape.duplicated(this, null, "first"); }

    /**
     * reset_index(对齐 pandas df.reset_index);主类承载(与 Index 操作同源)。
     * @param indexCol String 索引列名
     */
    public DataFrame resetIndex(String indexCol) { return DataFrameIndex.resetIndexImpl(this, indexCol); }
    /** reset_index 便捷重载:丢弃原 Index(无新列名)。 */
    public DataFrame resetIndex() { return DataFrameIndex.resetIndexImpl(this, null); }
    /**
     * set_index(对齐 pandas df.set_index);主类承载。
     * @param cols String[] 列名
     * @param drop boolean 是否删除原列
     */
    public DataFrame setIndex(String[] cols, boolean drop) { return DataFrameIndex.setIndexImpl(this, cols, drop); }
    /**
     * set_index 便捷重载:drop=true(默认)。
     * @param cols String[] 列名
     */
    public DataFrame setIndex(String... cols) { return DataFrameIndex.setIndexImpl(this, cols, true); }

    /**
     * tz_localize:给 DATETIME 列附加时区(对齐 pandas tz_localize)。
     * <p>把 LocalDateTime → ZonedDateTime(用 java.time.ZoneId);jian v1 用 OBJECT 列存储 ZonedDateTime。
     * @param colName String DATETIME 列名;非 null
     * @param zoneId String 时区 ID,如 "Asia/Shanghai"/"UTC"/"+08:00";非 null
     * @return DataFrame 该列转为 OBJECT(ZonedDateTime 元素)的新表
     */
    public DataFrame tzLocalize(String colName, String zoneId) {
        return DataFrameTimeseries.tzLocalize(this, colName, zoneId);
    }

    /**
     * tz_convert:转换 DATETIME 列的时区(对齐 pandas tz_convert)。
     * <p>要求该列已是 ZonedDateTime(经 tz_localize 转换);否则抛 IAE。
     * @param colName String 列名(需含 ZonedDateTime 元素);非 null
     * @param zoneId String 目标时区 ID;非 null
     * @return DataFrame 该列时区转换后的新表
     */
    public DataFrame tzConvert(String colName, String zoneId) {
        return DataFrameTimeseries.tzConvert(this, colName, zoneId);
    }

    /**
     * 采样(对齐 pandas df.sample,可复现种子);主类承载(与 takeRows/iloc 同类行选择入口)。
     * @param n int 数量
     * @param replace boolean 是否有放回
     * @param seed long 随机种子
     */
    public DataFrame sample(int n, boolean replace, long seed) { return DataFrameIndex.sampleImpl(this, n, replace, seed); }
    /**
     * pipe 链式管道(对齐 pandas df.pipe);主类承载(与 eval/sql 等链式入口同类)。
     * @param fn String 聚合函数名
     */
    public <R> R pipe(java.util.function.Function<DataFrame, R> fn) { return fn.apply(this); }
    /**
     * 按行应用,生成新列(对齐 pandas df.apply(axis=1) 单列输出);主类承载(链式入口)。
     * @param newCol String 新列名;非 null
     * @param fn String 聚合函数名
     */
    public DataFrame applyRow(String newCol, java.util.function.Function<Object[], Object> fn) {
        return DataFrameIndex.applyRowImpl(this, newCol, fn);
    }

    /**
     * 行级成员判断(对齐 pandas df.isin,任一列命中);并入 {@link DataFrameMissing}(与 isna 同类掩码语义)。
     * @param values String 值列名
     */
    public boolean[] isin(Object... values) { return DataFrameMissing.isin(this, values); }
    /**
     * 列级成员判断(对齐 Series.isin);并入 {@link DataFrameMissing}。
     * @param col String 列名;非 null
     * @param values String 值列名
     */
    public boolean[] colIsin(String col, Object... values) { return DataFrameMissing.colIsin(this, col, values); }
    /**
     * 条件保留(对齐 pandas df.where,cond==false 替换);并入 {@link DataFrameMissing}(与 fillna 同类值替换)。
     * @param cond boolean[] 条件掩码
     * @param other Object 替换值
     */
    public DataFrame where(boolean[] cond, Object other) { return DataFrameMissing.where(this, cond, other); }
    /**
     * 条件替换(对齐 pandas df.mask,cond==true 替换);并入 {@link DataFrameMissing}。
     * @param cond boolean[] 条件掩码
     * @param other Object 替换值
     */
    public DataFrame mask(boolean[] cond, Object other) { return DataFrameMissing.mask(this, cond, other); }
    /** 元信息表格(对齐 pandas df.info);主类承载(与 dtypes/describe 同类元信息)。 */
    public String info() { return DataFrameMeta.infoImpl(this); }
    /**
     * 按 dtype 筛列(对齐 pandas df.select_dtypes);主类承载(元信息查询)。
     * @param include DType[] 包含
     * @param exclude DType[] 排除
     */
    public DataFrame selectDtypes(DType[] include, DType[] exclude) { return DataFrameMeta.selectDtypesImpl(this, include, exclude); }

    // ======================== 元信息补全(对齐 pandas axes/ndim/memory_usage/attrs)========================

    /** 行列轴(对齐 pandas df.axes);返回 [行标签 list, 列名 list]。 */
    public java.util.List<java.util.List<?>> axes() {
        java.util.List<Object> rowLabels = new ArrayList<>();
        for (int i = 0; i < nRows; i++) rowLabels.add(index.get(i));
        return java.util.List.of(rowLabels, new ArrayList<>(columnNames()));
    }

    /** 维度数(对齐 pandas df.ndim);DataFrame 固定 2。 */
    public int ndim() { return 2; }

    /**
     * 内存估算(对齐 pandas df.memory_usage,单位字节)。
     * 因为一律按 size×8 估算会严重低估 STRING 列(100 字符 × 1M 行实际约 200MB),
     * 所以按 dtype 估算:DOUBLE/LONG/DATETIME=8,INT/DATE=4/8,BOOL=1,
     * STRING=40 头 + 平均长度×2(UTF-16)。
     * @return long 估算总字节数
     */
    public long memoryUsage() {
        return DataFrameMeta.memoryUsage(this);
    }

    /** 元数据字典(对齐 pandas df.attrs);返回可变 Map(用户可自由读写;线程安全 ConcurrentHashMap)。 */
    public java.util.Map<String, Object> attrs() { return attrsMap; }
    private final java.util.Map<String, Object> attrsMap = new java.util.concurrent.ConcurrentHashMap<>();

    /** 类型推断升级(对齐 pandas df.infer_objects);扫每列,OBJECT → 推断为 LONG/DOUBLE/STRING/BOOL。 */
    public DataFrame inferObjects() {
        return DataFrameTypes.inferObjects(this);
    }

    /** convert_dtypes 别名(等价 inferObjects)。 */
    public DataFrame convertDtypes() { return inferObjects(); }

    /** 转二维数组(对齐 pandas df.to_numpy);返回 Object[][],每行一个 Object[]。 */
    public Object[][] toNumpy() {
        return DataFrameMeta.toNumpy(this);
    }

    // ======================== 单单元格读写(对齐 pandas at/iat/isetitem)========================

    /**
     * 按标签读单单元格(对齐 pandas df.at[rowLabel, colName])。
     * @param rowLabel 参数;非 null
     * @param colName String 列名,必须存在;非 null
     */
    public Object at(Object rowLabel, String colName) {
        int rowIdx = index.isRange() ? ((Number) rowLabel).intValue() : findLabel(rowLabel);
        return get(rowIdx, colName);
    }

    /**
     * 按位置读单单元格(对齐 pandas df.iat[i, j])。
     * @param rowIdx 参数;非 null
     * @param colIdx 参数;非 null
     */
    public Object iat(int rowIdx, int colIdx) { return get(rowIdx, colIdx); }

    /**
     * 按位置写单单元格(对齐 pandas df.isetitem);返回新 DataFrame(不可变)。
     * @param rowIdx 参数;非 null
     * @param colIdx 参数;非 null
     * @param value Object 比较值
     */
    public DataFrame isetitem(int rowIdx, int colIdx, Object value) {
        // 实现体在 DataFrameChain(主类超 §3.1 红线拆分)
        return DataFrameChain.isetitemImpl(this, rowIdx, colIdx, value);
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

    /**
     * 在 loc 位置插入新列(对齐 pandas df.insert);返回新 DataFrame。
     * @param loc 参数;非 null
     * @param name String 名称;非 null
     * @param values String 值列名
     */
    public DataFrame insert(int loc, String name, Object[] values) {
        requireColumnAbsent(name);
        if (values.length != nRows) throw new IllegalArgumentException(
            "insert 值数组长度 " + values.length + " ≠ rowCount " + nRows);
        Column newCol = DataFrameTypes.inferColumnFromArray(name, values);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.add(loc, newCol);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /**
     * 弹出某列(对齐 pandas df.pop);返回弹出的 Column。
     * <p>因为 jian 的 DataFrame 不可变(AGENTS.md §4.3),无法像 pandas 一样原地移除列,
     * 所以本方法返回列本身,**不含该列的新表请用
     * {@code df.drop(name)}**(两行代码等效 pandas 的单行 pop):
     * <pre>{@code
     * Column popped = df.pop("a");
     * DataFrame rest = df.drop("a");
     * }</pre>
     * @param name String 列名,必须存在;非 null
     * @return jian.core.Column 被弹出的列(原 DataFrame 不变)
     */
    public jian.core.Column pop(String name) {
        int idx = requireColumn(name);
        return columns.get(idx);
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

    /** 行迭代(命名元组风格,对齐 pandas df.itertuples;默认含行标签)。 */
    public Iterable<Object[]> itertuples() {
        // 因为要与 iterrows 形状统一(pandas itertuples 默认含 index),
        // 所以返回 iterrows()(label + 纯数据行),两个迭代器形状一致。
        return iterrows();
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

    /** 列名加前缀(对齐 pandas df.add_prefix);返回列名变化的新 DataFrame。
     * @param prefix 参数;非 null
     *  因为 null+"c" 会静默得 "nullc" 破坏列名,所以 prefix 为 null 时抛 IAE。 */
    public DataFrame addPrefix(String prefix) {
        if (prefix == null) throw new IllegalArgumentException("add_prefix prefix 不能为 null");
        List<Column> newCols = new ArrayList<>();
        for (String c : columnNames()) {
            Column col = getColumn(c);
            newCols.add(col.rename(prefix + c));
        }
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    /**
     * 列名加后缀(对齐 pandas df.add_suffix);因为 null+"c" 会静默得 "nullc" 破坏列名,
     * 所以 suffix 为 null 时抛 IAE。
     * @param suffix 参数;非 null
     */
    public DataFrame addSuffix(String suffix) {
        if (suffix == null) throw new IllegalArgumentException("add_suffix suffix 不能为 null");
        List<Column> newCols = new ArrayList<>();
        for (String c : columnNames()) {
            Column col = getColumn(c);
            newCols.add(col.rename(c + suffix));
        }
        return new DataFrame(newCols, index, allowsDuplicateLabels);
    }

    // ======================== 补全入口:dot/abs/combine_first/mode/value_counts/nunique/reindex/squeeze ========================

    /**
     * 矩阵点积(对齐 pandas df.dot);委托 {@link DataFrameArith}。
     * @param other Object 替换值
     */
    public double dot(DataFrame other) { return DataFrameArith.dot(this, other); }
    /** 逐列绝对值(对齐 pandas df.abs);委托 {@link DataFrameArith}。 */
    public DataFrame abs() { return DataFrameArith.abs(this); }
    /**
     * combine_first(对齐 pandas df.combine_first);委托 {@link DataFrameArith}。
     * @param other Object 替换值
     */
    public DataFrame combineFirst(DataFrame other) { return DataFrameArith.combineFirst(this, other); }
    /**
     * 众数(对齐 pandas Series.mode);委托 {@link DataFrameArith}。
     * @param colName String 列名,必须存在;非 null
     */
    public Object colMode(String colName) { return DataFrameArith.mode(getColumn(colName)); }
    /**
     * 值计数(对齐 pandas Series.value_counts);委托 {@link DataFrameArith}。
     * @param colName String 列名,必须存在;非 null
     */
    public java.util.Map<Object, Integer> colValueCounts(String colName) {
        return DataFrameArith.valueCounts(getColumn(colName));
    }
    /**
     * 唯一值数(DataFrame 级,对齐 pandas Series.nunique);委托 {@link DataFrameArith}。
     * @param colName String 列名,必须存在;非 null
     */
    public int colNuniqueDf(String colName) { return DataFrameArith.nunique(getColumn(colName)); }

    /**
     * 重索引(对齐 pandas df.reindex);委托 {@link DataFrameReshape}。
     * @param labels Object... 行标签
     */
    public DataFrame reindex(Object[] labels) { return DataFrameReshape.reindex(this, labels); }
    /**
     * reindex_like(对齐 pandas df.reindex_like)。
     * @param other Object 替换值
     */
    public DataFrame reindexLike(DataFrame other) { return DataFrameReshape.reindexLike(this, other); }
    /** 降维(对齐 pandas df.squeeze)。 */
    public Object squeeze() { return DataFrameReshape.squeeze(this); }
    /**
     * rename_axis(API 兼容占位,jian v1 Index 无 name)。
     * @param name String 名称;非 null
     */
    public DataFrame renameAxis(String name) { return DataFrameReshape.renameAxis(this, name); }

    /**
     * 重命名列(对齐 pandas df.rename(columns=dict))。
     * <p>数据走向:映射表逐列查名 → 命中的列经 {@link Column#rename} 产新实例(不可变优先),
     * 未命中的列原实例复用 → rebuild 重建(列数据零拷贝,仅换名)。
     * <p>设计说明:jian-io-sql 的列名白名单(ASCII)拒绝中文列名时,报错指引调用本方法
     * 改成 ASCII 名后再写库。
     * @param mapping Map&lt;String,String&gt; 旧列名 → 新列名;非 null;新名须非 null;
     *                只改映射中出现的列,其余保持原名
     * @return DataFrame 全部列按映射改名后的新实例
     * @throws IllegalArgumentException 旧列名不存在,或改名后出现重复列名
     */
    public DataFrame renameColumns(java.util.Map<String, String> mapping) {
        // 实现体在 DataFrameChain
        return DataFrameChain.renameColumnsImpl(this, mapping);
    }
    /**
     * 替换列名(对齐 pandas df.set_axis)。
     * @param labels Object... 行标签
     */
    public DataFrame setAxis(Object[] labels) { return DataFrameReshape.setAxis(this, labels); }

    /**
     * 首个非缺失行号(对齐 pandas df.first_valid_index)。
     * 返回类型与 DatetimeIndex 版本一致,统一为 OptionalInt(全缺失返回 empty,不用 -1 哨兵)。
     * @return OptionalInt 首个非缺失行号;全缺失返回 empty
     */
    public OptionalInt firstValidIndex() {
        return DataFrameMeta.firstValidIndex(this);
    }

    /**
     * 末个非缺失行号(对齐 pandas df.last_valid_index)。
     * 返回类型与 DatetimeIndex 版本一致,统一为 OptionalInt(全缺失返回 empty,不用 -1 哨兵)。
     * @return OptionalInt 末个非缺失行号;全缺失返回 empty
     */
    public OptionalInt lastValidIndex() {
        return DataFrameMeta.lastValidIndex(this);
    }

    /** 要求列不存在(用于 insert/assign 新列)。 */
    private void requireColumnAbsent(String name) {
        if (columnIndex(name) >= 0) {
            throw new IllegalArgumentException("列 \"" + name + "\" 已存在");
        }
    }

    // ======================== astype 类型转换(对齐 pandas)========================

    /**
     * 把某列转为指定 dtype(对齐 pandas astype)。
     * <p>支持 7 种 dtype——
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
        Column converted = DataFrameConvert.convertColumn(c, target);
        List<Column> newCols = new ArrayList<>(columns);
        newCols.set(i, converted);
        return new DataFrame(newCols, index, allowsDuplicateLabels);
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
        return DataFrameDisplay.toString(this, maxRows, maxColWidth);
    }

    private static int norm(int idx, int len) {
        if (idx < 0) idx += len;
        if (idx < 0 || idx > len) throw new IndexOutOfBoundsException("索引 " + idx + " 越界,len=" + len);
        return idx;
    }

    int requireColumn(String name) {
        int i = columnIndex(name);
        if (i < 0) {
            throw new IllegalArgumentException(
                    "列 \"" + name + "\" 不存在,现有列:" + columnNames());
        }
        return i;
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

    /**
     * 纵向拼接便捷方法(行堆叠);因为 concat 内部可能往列表加列头行,Arrays.asList
     * 固定大小会抛 UnsupportedOperationException,所以用可变 List 包裹。
     * @param dfs 参数;非 null
     */
    public static DataFrame concat(DataFrame... dfs) {
        return concat(new java.util.ArrayList<>(java.util.Arrays.asList(dfs)), 0);
    }

    /** 内部:变换后构造(复用列,不拷贝)。 */
    DataFrame rebuild(List<Column> newColumns, Index newIndex) {
        return new DataFrame(newColumns, newIndex, allowsDuplicateLabels);
    }

        /**
     * 包级工厂:0 列 N 行的 DataFrame。
     * 因为 setIndex 把唯一列提升为 Index 后剩余 0 列,但行数须保留 N
     * (pandas set_index 同场景返回 N rows × 0 cols),所以需要此工厂。
     * @param rows int 行数,&ge; 0
     * @param idx Index 索引,size == rows;非 null
     * @return DataFrame 0 列 N 行
     */
    static DataFrame ofZeroColumnsWithIndex(int rows, Index idx) {
        return new DataFrame(new ArrayList<>(), idx, rows, false);
    }
    /**
     * 克隆一列并赋新名追加到表尾(因为 SELECT c2,c2 重复列引用需两个独立列,
     * jian-dsl SqlEngine 经此公开入口物化重复列,DataFrame 列名唯一契约不变)。
     * @param colName String 被克隆的列名,必须存在;非 null
     * @param newName String 克隆列的新名;不得与现有列名重复;非 null
     * @return DataFrame 追加克隆列后的新表(行数不变)
     */
    public DataFrame withColumnClone(String colName, String newName) {
        return DataFrameConstruct.addColumnClone(this, colName, newName);
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

    // ======================== 主类承载的 *Impl(私有静态,被主类入口委托)========================

}
