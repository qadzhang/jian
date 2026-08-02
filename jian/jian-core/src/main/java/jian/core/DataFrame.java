package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * 从列式 Map<列名, Object[]> 构造(类型推断)。
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

    // ======================== 属性 ========================

    public int rowCount() { return nRows; }
    public int columnCount() { return columns.size(); }
    public int[] shape() { return new int[]{nRows, columns.size()}; }
    public int size() { return nRows * columns.size(); }
    public boolean isEmpty() { return nRows == 0; }
    public Index index() { return index; }
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

    /** 列名 → 列下标;不存在返回 -1。 */
    public int columnIndex(String name) {
        for (int i = 0; i < columns.size(); i++)
            if (columns.get(i).name().equals(name)) return i;
        return -1;
    }

    // ======================== 取列 ========================

    /** 按列名取 Column(类型不安全,需调用方转型)。不存在抛异常。 */
    public Column getColumn(String name) {
        int i = requireColumn(name);
        return columns.get(i);
    }

    /** 按列名取 Series(对齐 pandas,单列操作入口)。 */
    public Series getSeries(String name) {
        return Series.of(getColumn(name));
    }

    /** 按列名取 StringColumn(类型安全;类型不符抛异常)。 */
    public StringColumn getStringColumn(String name) {
        Column c = getColumn(name);
        if (!(c instanceof StringColumn)) {
            throw new IllegalStateException("列 \"" + name + "\" 不是 STRING,实际 " + c.dtype());
        }
        return (StringColumn) c;
    }

    public DoubleColumn getDoubleColumn(String name) {
        Column c = getColumn(name);
        if (c instanceof DoubleColumn) return (DoubleColumn) c;
        // 数值列兼容:INT/LONG 转 DOUBLE(返回新的 DoubleColumn)
        if (c.dtype().isNumeric()) return (DoubleColumn) convertColumn(c, DType.DOUBLE);
        throw new IllegalStateException("列 \"" + name + "\" 不是数值(DOUBLE/INT/LONG),实际 " + c.dtype());
    }

    public LongColumn getLongColumn(String name) {
        Column c = getColumn(name);
        if (c instanceof LongColumn) return (LongColumn) c;
        // INT 列可当 LONG 用(转 LONG)
        if (c.dtype() == DType.INT) return (LongColumn) convertColumn(c, DType.LONG);
        if (c.dtype() == DType.LONG) return (LongColumn) c;
        throw new IllegalStateException("列 \"" + name + "\" 不是整数(INT/LONG),实际 " + c.dtype());
    }

    public IntColumn getIntColumn(String name) {
        Column c = getColumn(name);
        if (c.dtype() == DType.INT) return (IntColumn) c;
        if (c.dtype() == DType.LONG) return (IntColumn) convertColumn(c, DType.INT);  // LONG 转 INT
        throw new IllegalStateException("列 \"" + name + "\" 不是整数(INT/LONG),实际 " + c.dtype());
    }

    /** 按下标取第 i 行第 c 列的值(对齐 pandas at)。 */
    public Object get(int row, int col) {
        return columns.get(col).get(row);
    }

    /** 按列名取值。 */
    public Object get(int row, String colName) {
        int c = requireColumn(colName);
        return columns.get(c).get(row);
    }

    /** 取第 i 行的所有值(对齐 pandas itertuples)。 */
    public Object[] getRow(int i) {
        Object[] r = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) r[c] = columns.get(c).get(i);
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

    /** 选列子集(对齐 pandas df[["a","b"]] / select)。 */
    public DataFrame select(String... names) {
        List<Column> sub = new ArrayList<>(names.length);
        for (String name : names) {
            int i = requireColumn(name);
            sub.add(columns.get(i));
        }
        return new DataFrame(sub, index, allowsDuplicateLabels);
    }

    /** 丢弃指定列(对齐 pandas drop)。 */
    public DataFrame drop(String... names) {
        java.util.Set<String> toDrop = new java.util.HashSet<>(Arrays.asList(names));
        List<Column> sub = new ArrayList<>();
        for (Column c : columns) if (!toDrop.contains(c.name())) sub.add(c);
        return new DataFrame(sub, index, allowsDuplicateLabels);
    }

    /** 按布尔掩码过滤行(对齐 pandas df[mask])。 */
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

    /** 行切片 [start, end)(对齐 pandas df[start:end])。 */
    public DataFrame slice(int start, int end) {
        start = norm(start, nRows); end = norm(end, nRows);
        if (start >= end) {
            return new DataFrame(emptyColumns(), Index.range(0), allowsDuplicateLabels);
        }
        List<Column> sub = new ArrayList<>(columns.size());
        for (Column c : columns) sub.add(c.slice(start, end));
        return new DataFrame(sub, index.slice(start, end), allowsDuplicateLabels);
    }

    /** 前 n 行(对齐 pandas head)。 */
    public DataFrame head(int n) {
        return slice(0, Math.min(Math.max(n, 0), nRows));
    }

    /** 后 n 行(对齐 pandas tail)。 */
    public DataFrame tail(int n) {
        n = Math.min(Math.max(n, 0), nRows);
        return n == 0 ? slice(0, 0) : slice(nRows - n, nRows);
    }

    /** 默认 head(5)。 */
    public DataFrame head() { return head(5); }
    public DataFrame tail() { return tail(5); }

    /** 按行下标选取(对齐 pandas take / iloc)。 */
    public DataFrame takeRows(int[] indices) {
        List<Column> sub = new ArrayList<>(columns.size());
        for (Column c : columns) sub.add(c.take(indices));
        return new DataFrame(sub, index.take(indices), allowsDuplicateLabels);
    }

    // ======================== merge / concat(对齐 pandas §3.10)========================

    /**
     * 关系 join(对齐 pandas.merge)。
     *
     * @param right 右表
     * @param how "inner"/"left"/"right"/"outer"
     * @param on join 键列(左右同名)
     * @param suffixes 重名列后缀(null 用默认 ["_x","_y"])
     */
    public DataFrame merge(DataFrame right, String how, String on, String[] suffixes) {
        return DataFrameMerge.merge(this, right, how, on, suffixes);
    }

    /** inner join on 单列,默认后缀。 */
    public DataFrame merge(DataFrame right, String on) {
        return DataFrameMerge.merge(this, right, "inner", on, null);
    }

    /** 指定 how + on 单列,默认后缀。 */
    public DataFrame merge(DataFrame right, String how, String on) {
        return DataFrameMerge.merge(this, right, how, on, null);
    }

    /**
     * 多列键 join(左右不同名)。
     */
    public DataFrame merge(DataFrame right, String how, String[] leftOn, String[] rightOn, String[] suffixes) {
        return DataFrameMerge.merge(this, right, how, leftOn, rightOn, suffixes);
    }

    // ======================== 重塑(委托 DataFrameReshape,对齐 pandas §3.9)========================

    /**
     * 透视表(对齐 pandas.pivot_table)。
     *
     * @param index 行分组列
     * @param columns 散开成列的分组列
     * @param values 被聚合的值列
     * @param aggFn 聚合函数(mean/sum/count/min/max/first/last)
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

    /** 单列排序便捷方法。 */
    public DataFrame sortBy(String col, boolean ascending) {
        return sortBy(new String[]{col}, new boolean[]{ascending});
    }

    /** 按行索引排序(对齐 pandas sort_index)。 */
    public DataFrame sortIndex(boolean ascending) {
        return DataFrameSort.sortIndex(this, ascending);
    }

    /** TopN 最大(对齐 pandas nlargest)。 */
    public DataFrame nlargest(int n, String byCol) { return DataFrameSort.nlargest(this, n, byCol); }

    /** TopN 最小(对齐 pandas nsmallest)。 */
    public DataFrame nsmallest(int n, String byCol) { return DataFrameSort.nsmallest(this, n, byCol); }

    // ======================== 列级算术(委托 DataFrameArith,对齐 pandas §3.4)========================

    /**
     * 列间加,结果作新列加到 DataFrame(对齐 df[新列] = a + b)。
     *
     * @param newCol 新列名
     * @param leftCol + 左列
     * @param rightCol + 右列
     */
    public DataFrame colAdd(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.add(this, leftCol, rightCol));
    }
    public DataFrame colSub(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.sub(this, leftCol, rightCol));
    }
    public DataFrame colMul(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.mul(this, leftCol, rightCol));
    }
    public DataFrame colDiv(String newCol, String leftCol, String rightCol) {
        return withColumn(newCol, DataFrameArith.div(this, leftCol, rightCol));
    }

    /** 标量乘(对齐 Series * scalar),结果作新列。 */
    public DataFrame colMul(String newCol, String srcCol, double scalar) {
        return withColumn(newCol, DataFrameArith.mulScalar(this, srcCol, scalar));
    }
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

    /** 单列均值(快捷)。 */
    public double colMean(String colName) { return DataFrameStats.mean(getColumn(colName)); }
    public double colSum(String colName) { return DataFrameStats.sum(getColumn(colName)); }
    public double colMin(String colName) { return DataFrameStats.min(getColumn(colName)); }
    public double colMax(String colName) { return DataFrameStats.max(getColumn(colName)); }
    public double colMedian(String colName) { return DataFrameStats.median(getColumn(colName)); }
    public double colStd(String colName) { return DataFrameStats.std(getColumn(colName)); }

    /** describe(对齐 pandas df.describe):返回 DataFrame,行=统计量,列=数值列。 */
    public DataFrame describe() { return DataFrameStats.describe(this); }

    /** 某列分位数(R-7 linear,对齐 numpy 默认)。 */
    public double colPercentile(String colName, double q) {
        return DataFrameStats.percentile(getColumn(colName), q);
    }

    // ======================== 函数应用(对齐 pandas apply/map)========================

    /** 对数值列每个元素应用函数,返回新 DoubleColumn(对齐 Series.apply)。 */
    public DoubleColumn applyNumeric(String colName, java.util.function.DoubleUnaryOperator fn) {
        return DataFrameStats.applyNumeric(this, colName, fn);
    }

    /** 对任意列元素应用函数转 String 列。 */
    public StringColumn applyStr(String colName, java.util.function.Function<Object, String> fn) {
        return DataFrameStats.applyToString(this, colName, fn);
    }

    /**
     * 派生新列(对齐 pandas assign):用 fn 对每行求值,返回新 DataFrame(原列 + 新列)。
     *
     * @param newName 新列名
     * @param fn 接收行号 r,返回新列的值
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

    /** dropna:how=any/all,subset 指定列(null=全部)。 */
    public DataFrame dropna(String how, String[] subset) {
        return DataFrameMissing.dropna(this, how, subset);
    }

    /** dropna 任一列缺失即丢(全部列)。 */
    public DataFrame dropna() { return dropna("any", null); }

    /** fillna 用常量填缺失。 */
    public DataFrame fillna(Object value) { return DataFrameMissing.fillna(this, value); }

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
     * <pre>{@code
     * DataFrame df2 = df.eval("total = price * qty; grade = score >= 90 ? 'A' : 'B'");
     * }</pre>
     * <p>需要 jian-dsl 模块;未引时抛 {@link ModuleNotLoadedException}。
     */
    public DataFrame eval(String expr) {
        return DslEngine.current().eval(this, expr);
    }

    /**
     * L3 SQL 子集(规范 07 §2.2/§2.3)。接收者 df 为 SQL 中的主表(this/DUAL),
     * ${name} 占位按出现顺序绑定到 binds 参数。
     * <pre>{@code
     * DataFrame r = df.sql("SELECT dept, mean(salary) FROM this GROUP BY dept");
     * DataFrame j = df.sql("SELECT * FROM this JOIN ${b} ON this.id = b.id", other);
     * }</pre>
     * <p>需要 jian-dsl 模块;未引时抛 {@link ModuleNotLoadedException}。
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
            Object v = c.get(r);
            if (v == null) { m[r] = false; continue; }
            m[r] = cmp(v, op, value);
        }
        return new BoolColumn(colName, m, null);
    }

    /** 大于(数值列常用)。 */
    public BoolColumn colGt(String colName, double value) {
        return compare(colName, ">", value);
    }
    public BoolColumn colLt(String colName, double value) { return compare(colName, "<", value); }
    public BoolColumn colGe(String colName, double value) { return compare(colName, ">=", value); }
    public BoolColumn colLe(String colName, double value) { return compare(colName, "<=", value); }
    public BoolColumn colEq(String colName, Object value) { return compare(colName, "==", value); }
    public BoolColumn colNe(String colName, Object value) { return compare(colName, "!=", value); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean cmp(Object a, String op, Object b) {
        if (a instanceof Number && b instanceof Number) {
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            return switch (op) { case ">" -> x > y; case "<" -> x < y; case ">=" -> x >= y;
                case "<=" -> x <= y; case "==" -> x == y; case "!=" -> x != y;
                default -> throw new IllegalArgumentException("未知 op " + op); };
        }
        int c = String.valueOf(a).compareTo(String.valueOf(b));
        return switch (op) { case "==" -> a.equals(b); case "!=" -> !a.equals(b);
            case ">" -> c > 0; case "<" -> c < 0; case ">=" -> c >= 0; case "<=" -> c <= 0;
            default -> throw new IllegalArgumentException("未知 op " + op); };
    }

    // ======================== loc / iloc(对齐 pandas)========================

    /** iloc:按位置选行(返回新 DataFrame)。 */
    public DataFrame iloc(int... rowIndices) {
        return takeRows(rowIndices);
    }

    /**
     * loc:按行标签选行。当前 Index 是 RangeIndex 时,标签 == 位置。
     */
    public DataFrame loc(Object... labels) {
        if (index.isRange()) {
            int[] idx = new int[labels.length];
            for (int k = 0; k < labels.length; k++) {
                idx[k] = ((Number) labels[k]).intValue();
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

    // ======================== astype 类型转换(对齐 pandas)========================

    /**
     * 把某列转为指定 dtype(对齐 pandas astype)。
     *
     * @return 新 DataFrame(原列被替换)
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
                for (int i = 0; i < n; i++) d[i] = vals[i] == null ? Double.NaN : ((Number) vals[i]).doubleValue();
                return new DoubleColumn(name, d);
            }
            case LONG: {
                long[] d = new long[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; d[i] = 0; }
                    else d[i] = ((Number) vals[i]).longValue();
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
                    else d[i] = ((Number) vals[i]).intValue();
                }
                return new IntColumn(name, d, mask);
            }
            case OBJECT: {
                return new ObjectColumn(name, vals);
            }
            default:
                throw new IllegalArgumentException("astype 暂不支持转换到 " + target + "(仅 DOUBLE/LONG/INT/STRING/OBJECT)");
        }
    }

    // ======================== toString(对齐 pandas repr)========================

    @Override public String toString() {
        return toString(10, 20);
    }

    /** 格式化表格输出(对齐 pandas __repr__:截断 + head/tail + 维度摘要)。 */
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

    /** 内部:取所有列(同包用)。 */
    List<Column> columnsInternal() { return columns; }
}
