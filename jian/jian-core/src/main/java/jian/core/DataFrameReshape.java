package jian.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ┌─ What : DataFrameReshape —— 重塑操作(对齐 pandas §3.9:pivot_table/melt/transpose/duplicated/drop_duplicates)
// │  Why  : 规范要求 pivot/melt/transpose;长宽转换是数据分析核心
// │  Who  : DataFrame.pivotTable/melt/transpose/T/dropDuplicates 委托此类
// │  When : 长转宽、宽转长、转置、去重
// │  Where: jian-core/DataFrameReshape.java
// │  How  : pivotTable 数据走向:groupBy(index ∪ columns) → agg(value) → columns 维散开成多列(unstack)。
// │         melt 数据走向:每行 → 每个值列产生一行(id_vars 重复)。
/**
 * 重塑工具,对齐 pandas pivot_table/melt/transpose。
 */
public final class DataFrameReshape {

    private DataFrameReshape() {}

    /**
     * 透视表(对齐 pandas.pivot_table)。
     *
     * <p>语义:对 (index, columns) 组合聚合 value 列,把 columns 维的不同值散开成多列。
     *
     * @param df 目标
     * @param df      DataFrame 目标表,非 null
     * @param index   String 行分组列名(单列;多列 v2 支持);必须存在;非 null
     * @param columns String 散开成列的分组列名;必须存在;非 null
     * @param values  String 被聚合的值列名;必须存在;非 null
     * @param aggFn   String 聚合函数:mean/sum/count/min/max/first/last/nunique;非 null
     * @return DataFrame 行 = index 列的不同值,列 = index 列名 + 各 columns 列值;单元格 = 聚合结果
     */
    public static DataFrame pivotTable(DataFrame df, String index, String columns,
                                       String values, String aggFn) {
        // 因为 index/columns 键列含缺失的行按 pandas pivot_table 默认 dropna=True 丢弃
        //(把 null 塞进桶键会 NPE),所以这里跳过键缺失的行。
        // 判缺失一律用 getColumn(x).isNull(r)(§3.5.2:不得用 get()==null,
        // DoubleColumn.get(NaN) 返回 Double.NaN 不是 null)。
        // 伪代码:
        //   1. 扫描全表,跳过 index 或 columns 键缺失的行(与 pandas 分组前丢行一致)
        //   2. 其余行收集 columns/index 的不同值(保序)并按 (indexVal, colVal) 分桶
        //   3. 聚合 + 散开成宽表(桶内聚合后按 columns 取值散到各列)
        Column indexCol = df.getColumn(index);
        Column columnsCol = df.getColumn(columns);
        // 1. 收集 columns 列的不同值(保序;键缺失的行跳过)
        List<Object> colValues = new ArrayList<>();
        java.util.Set<Object> colSeen = new java.util.HashSet<>();
        for (int r = 0; r < df.rowCount(); r++) {
            if (indexCol.isNull(r) || columnsCol.isNull(r)) continue;  // dropna:跳过键缺失行
            Object v = df.get(r, columns);
            if (colSeen.add(v)) colValues.add(v);
        }
        // 2. 收集 index 列的不同值(保序;键缺失的行跳过)
        List<Object> indexValues = new ArrayList<>();
        java.util.Set<Object> indexSeen = new java.util.HashSet<>();
        for (int r = 0; r < df.rowCount(); r++) {
            if (indexCol.isNull(r) || columnsCol.isNull(r)) continue;  // dropna:跳过键缺失行
            Object v = df.get(r, index);
            if (indexSeen.add(v)) indexValues.add(v);
        }
        // 3. 用 groupBy (index, columns) 聚合 values,缓存结果
        // map: (indexVal, colVal) → agg 值
        Map<List<Object>, Object> cellMap = new LinkedHashMap<>();
        // 先按 (index, columns) 分组(键缺失的行跳过)
        Map<List<Object>, List<Integer>> buckets = new LinkedHashMap<>();
        for (int r = 0; r < df.rowCount(); r++) {
            if (indexCol.isNull(r) || columnsCol.isNull(r)) continue;  // dropna:跳过键缺失行
            Object iv = df.get(r, index);
            Object cv = df.get(r, columns);
            List<Object> key = keyOf(iv, cv);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Column valCol = df.getColumn(values);
        for (Map.Entry<List<Object>, List<Integer>> e : buckets.entrySet()) {
            int[] idx = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            cellMap.put(e.getKey(), aggregate(valCol, idx, aggFn));
        }
        // 4. 输出:行 = indexValues,列 = index + 每个 colValue
        Object[][] rows = new Object[indexValues.size()][colValues.size() + 1];
        for (int i = 0; i < indexValues.size(); i++) {
            rows[i][0] = indexValues.get(i);
            for (int j = 0; j < colValues.size(); j++) {
                rows[i][j + 1] = cellMap.get(keyOf(indexValues.get(i), colValues.get(j)));
            }
        }
        // 5. schema
        Object[] nameType = new Object[(colValues.size() + 1) * 2];
        nameType[0] = index;
        nameType[1] = df.getColumn(index).dtype();
        for (int j = 0; j < colValues.size(); j++) {
            nameType[(j + 1) * 2] = String.valueOf(colValues.get(j));
            nameType[(j + 1) * 2 + 1] = DType.DOUBLE;  // 聚合结果默认 DOUBLE
        }
        return DataFrame.of(Schema.of(nameType), rows);
    }

    private static List<Object> keyOf(Object i, Object c) {
        // 因为 "\0" 字符串拼接在值本身含 \0 时会被误拼成同一键
        // (不同 (i,c) 组合被识别为同一键导致行错乱),所以用 List 作键。
        // List.of 不接受 null 元素,改 Arrays.asList(允许 null,防御;
        // 正常路径已在上游按 dropna 跳过键缺失的行,null 不会到达此处)
        return java.util.Arrays.asList(i, c);
    }

    /**
     * 宽转长(对齐 pandas.melt):每行的指定 value 列各产生一行,id_vars 重复。
     * @param df        DataFrame 目标表,非 null
     * @param idVars    String[] 标识列(每行保留这些列的值);必须存在;非 null(允许空数组)
     * @param valueVars String[] 被展平的值列(列名进 "variable" 列,值进 "value" 列);必须存在;非 null
     * @return DataFrame 列 = idVars + ["variable","value"];行数 = df.rowCount() * valueVars.length
     */
    public static DataFrame melt(DataFrame df, String[] idVars, String[] valueVars) {
        List<String> outCols = new ArrayList<>();
        for (String s : idVars) outCols.add(s);
        outCols.add("variable");
        outCols.add("value");

        List<Object[]> outRows = new ArrayList<>();
        for (int r = 0; r < df.rowCount(); r++) {
            for (String vv : valueVars) {
                Object[] row = new Object[idVars.length + 2];
                for (int k = 0; k < idVars.length; k++) row[k] = df.get(r, idVars[k]);
                row[idVars.length] = vv;
                row[idVars.length + 1] = df.get(r, vv);
                outRows.add(row);
            }
        }
        Object[][] data = outRows.toArray(new Object[0][]);
        Schema schema = Schema.infer(outCols, data);
        return DataFrame.of(schema, data);
    }

    /**
     * 转置(对齐 pandas df.T):行列互换。
     * @param df DataFrame 目标表,非 null
     * @return DataFrame 行列互换后的新表:新行数 = 原 columnCount;新列数 = 原 rowCount + 1;
     *         第一列 "_index" 存原列名;后续列名 "0"/"1"/... 存原行号
     */
    public static DataFrame transpose(DataFrame df) {
        int rows = df.rowCount();
        int cols = df.columnCount();
        Object[][] t = new Object[cols][rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                t[c][r] = df.get(r, c);
            }
        }
        // 新列名:用原行号 0..n-1;新第一列用原列名
        List<String> newNames = new ArrayList<>();
        newNames.add("_index");
        for (int r = 0; r < rows; r++) newNames.add(String.valueOf(r));
        // 把原列名作为第一列
        Object[][] data = new Object[cols][rows + 1];
        for (int c = 0; c < cols; c++) {
            data[c][0] = df.columnNames().get(c);
            for (int r = 0; r < rows; r++) data[c][r + 1] = t[c][r];
        }
        return DataFrame.ofColumns(toMap(newNames, data));
    }

    /**
     * 列名+列数据 → Map 工具(私有)。
     * @param names     List&lt;String&gt; 列名
     * @param colsByRow Object[][] 按行组织的列数据
     * @return Map&lt;String,Object[]&gt; 列名→列数据
     */
    private static Map<String, Object[]> toMap(List<String> names, Object[][] colsByRow) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (int c = 0; c < names.size(); c++) {
            Object[] col = new Object[colsByRow.length];
            for (int r = 0; r < colsByRow.length; r++) col[r] = colsByRow[r][c];
            m.put(names.get(c), col);
        }
        return m;
    }

    /**
     * 去重(对齐 pandas drop_duplicates):按 subset 列去重,keep="first"/"last"/false。
     * @param df     DataFrame 目标表,非 null
     * @param subset String[] 参与判重的列名;null=全部列;数组中列名必须存在
     * @param keep   String 保留策略:"first"=首条;"last"=末条;"false"=重复全删(只保留唯一行);null 视为 "first"
     * @return DataFrame 去重后的新表(行数 ≤ df.rowCount();列不变)
     */
    public static DataFrame dropDuplicates(DataFrame df, String[] subset, String keep) {
        int n = df.rowCount();
        boolean[] keepMask = new boolean[n];
        java.util.Set<List<Object>> seen = new java.util.HashSet<>();
        // keep=first 从前往后;keep=last 从后往前;false 只保留全唯一行
        if ("false".equalsIgnoreCase(keep)) {
            // 全唯一:统计每个 key 出现次数,只保留出现 1 次的
            Map<List<Object>, Integer> counts = new LinkedHashMap<>();
            for (int r = 0; r < n; r++) {
                List<Object> k = keyOf(df, r, subset);
                counts.merge(k, 1, Integer::sum);
            }
            for (int r = 0; r < n; r++) {
                keepMask[r] = counts.get(keyOf(df, r, subset)) == 1;
            }
        } else {
            boolean first = !"last".equalsIgnoreCase(keep);
            for (int pass = 0; pass < 1; pass++) {
                if (first) {
                    for (int r = 0; r < n; r++) {
                        List<Object> k = keyOf(df, r, subset);
                        keepMask[r] = seen.add(k);
                    }
                } else {
                    for (int r = n - 1; r >= 0; r--) {
                        List<Object> k = keyOf(df, r, subset);
                        keepMask[r] = seen.add(k);
                    }
                }
            }
        }
        return df.filter(keepMask);
    }

    // ┌─ What : duplicated —— 重复行掩码(对齐 pandas DataFrame.duplicated)
    // │  Why  : 与 dropDuplicates 同算法不同产出(掩码 vs 去重后的表),按 §3.1.1.1 内聚到此
    // │  Who  : 由 DataFrame.duplicated 单行委托
    // │  When : duplicated/dropDuplicates 判重入口调用时
    // │  How  : ① keep="none":出现 ≥2 次的行全标 true
    //         ② keep="first"(默认):首次出现 false,后续 true
    //         ③ keep="last":末次出现 false,之前的重复 true
    /**
     * 重复行掩码(对齐 pandas DataFrame.duplicated)。
     * <p>策略:
     * <ul>
     *   <li>keep="first"(默认):首次出现 false,后续重复 true</li>
     *   <li>keep="last":末次出现 false,之前的重复 true</li>
     *   <li>keep="none":所有出现 ≥ 2 次的行全部标 true(都不保留)</li>
     * </ul>
     * @param df     DataFrame 目标表,非 null
     * @param subset String[] 参与判重的列名;null/空 表示全部列
     * @param keep   String "first"(默认)/ "last"/ "none"(注意:pandas 用 false,jian 用 "none" 字符串)
     * @return boolean[] 长度 == rowCount();true 表示该行是"重复行"
     */
    public static boolean[] duplicated(DataFrame df, String[] subset, String keep) {
        int n = df.rowCount();
        if (n == 0) return new boolean[0];
        String[] cols = (subset == null || subset.length == 0)
            ? df.columnNames().toArray(new String[0]) : subset;
        // subset 列存在性校验(传不存在的列名直接报错,不静默返回全 false)
        for (String c : cols) {
            if (df.columnIndex(c) < 0) {
                throw new IllegalArgumentException("duplicated subset 列不存在:" + c);
            }
        }
        String keepMode = keep == null ? "first" : keep;

        // keep="none":出现 ≥ 2 次的签名 → 所有该签名的行都判重
        if ("none".equals(keepMode) || "false".equalsIgnoreCase(keepMode)) {
            Map<List<Object>, Integer> cnt = new HashMap<>();
            for (int i = 0; i < n; i++) cnt.merge(keyOf(df, i, cols), 1, Integer::sum);
            boolean[] out = new boolean[n];
            for (int i = 0; i < n; i++) out[i] = cnt.get(keyOf(df, i, cols)) >= 2;
            return out;
        }

        // keep="first" / "last":要保留的下标集合(签名 → 唯一保留下标)
        boolean[] out = new boolean[n];
        java.util.Set<List<Object>> seen = new java.util.HashSet<>();
        if ("first".equals(keepMode)) {
            for (int i = 0; i < n; i++) out[i] = !seen.add(keyOf(df, i, cols));
        } else if ("last".equals(keepMode)) {
            for (int i = n - 1; i >= 0; i--) out[i] = !seen.add(keyOf(df, i, cols));
        } else {
            throw new IllegalArgumentException("duplicated keep 取值:first/last/none,实际:" + keepMode);
        }
        return out;
    }

    private static List<Object> keyOf(DataFrame df, int r, String[] subset) {
        // List 作键防 "\0" 拼接冲突
        List<Object> key = new ArrayList<>(subset.length);
        for (String c : subset) key.add(df.get(r, c));
        return key;
    }

    // 复用 GroupBy 的聚合逻辑(轻量复制避免跨类依赖)
    private static Object aggregate(Column c, int[] idx, String fn) {
        switch (fn) {
            case "count":
                int cnt = 0; for (int i : idx) if (!c.isNull(i)) cnt++; return (long) cnt;
            case "nunique":
                java.util.Set<Object> seen = new java.util.HashSet<>();
                // ±0.0 数值等价归一(同 GroupBy)
                for (int i : idx) if (!c.isNull(i)) seen.add(DataFrameStats.normUniqueKey(c.get(i)));
                return (long) seen.size();
            case "sum": { double s = 0; for (int i : idx) if (!c.isNull(i)) s += c.getDouble(i); return s; }
            case "mean": { double s = 0; int n = 0; for (int i : idx) if (!c.isNull(i)) { s += c.getDouble(i); n++; } return n == 0 ? Double.NaN : s / n; }
            case "min": { double m = Double.POSITIVE_INFINITY; boolean any = false;
                for (int i : idx) if (!c.isNull(i)) { any = true; if (c.getDouble(i) < m) m = c.getDouble(i); }
                return any ? m : Double.NaN; }
            case "max": { double m = Double.NEGATIVE_INFINITY; boolean any = false;
                for (int i : idx) if (!c.isNull(i)) { any = true; if (c.getDouble(i) > m) m = c.getDouble(i); }
                return any ? m : Double.NaN; }
            // first/last 对齐 pandas 默认 skipna=True:跳过组内缺失,
            // 取第一个/最后一个非空值;组内全空 → null(DoubleColumn 为 NaN,与 pandas 一致)
            case "first":
                for (int i : idx) if (!c.isNull(i)) return c.get(i);
                return idx.length == 0 ? null : c.get(idx[0]);
            case "last":
                for (int i = idx.length - 1; i >= 0; i--) if (!c.isNull(idx[i])) return c.get(idx[i]);
                return idx.length == 0 ? null : c.get(idx[idx.length - 1]);
            default: throw new IllegalArgumentException("pivotTable/dropDuplicates 不支持的聚合:" + fn);
        }
    }

    // ======================== 重塑扩展(按 §3.1.1.1 内聚到此类)========================

    // ┌─ What : pivot —— 简单透视(无聚合,严格 1:1 映射,重复键抛异常)
    // │  Why  : 与 pivotTable 同源但语义不同(pivotTable 含聚合,pivot 假设唯一)
    // │  How  : ① 用 (index, columns) 作行键扫一遍,记录每个 (idx_val, col_val) → value
    //         ② 输出列 = columns 列的唯一值(排序);输出行 = index 列唯一值(保序)
    /**
     * 简单透视(对齐 pandas DataFrame.pivot;无聚合,假设每个 (index, columns) 组合唯一)。
     * @param df       DataFrame 目标表,非 null
     * @param index    String 用作输出行标签的列名;非 null
     * @param columns  String 用作输出列标签的列名;非 null
     * @param values   String 用作输出单元值的列名;非 null
     * @return DataFrame 行数 = index 列唯一值数,列数 = columns 列唯一值数 + 1(第一列为 index 列)
     * @throws IllegalArgumentException 当 (index, columns) 有重复 → 与 pandas 一致
     */
    public static DataFrame pivot(DataFrame df, String index, String columns, String values) {
        int n = df.rowCount();
        // 收集 index 唯一值(保序)与 columns 唯一值(升序)
        java.util.List<Object> indexUniq = new java.util.ArrayList<>();
        java.util.Set<Object> indexSeen = new java.util.HashSet<>();
        java.util.Set<Object> colSeen = new java.util.TreeSet<>(DataFrameReshape::compareObj);
        java.util.List<Object> colList = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object iv = df.get(i, index);
            if (indexSeen.add(iv)) indexUniq.add(iv);
            Object cv = df.get(i, columns);
            if (colSeen.add(cv)) colList.add(cv);
        }
        // 用 TreeSet 排序的列
        java.util.List<Object> sortedCols = new java.util.ArrayList<>(colSeen);
        int nIdx = indexUniq.size(), nCol = sortedCols.size();
        // 值桶:每个 index 唯一值一行,每个 col 唯一值一列
        // 用 Map<indexVal, Map<colVal, value>> 索引(避免 n×m 全填)
        Map<Object, Map<Object, Object>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Object iv = df.get(i, index);
            Object cv = df.get(i, columns);
            Object vv = df.get(i, values);
            Map<Object, Object> row = grid.computeIfAbsent(iv, k -> new HashMap<>());
            if (row.containsKey(cv)) {
                throw new IllegalArgumentException(
                    "pivot (index=" + iv + ", columns=" + cv + ") 重复;请用 pivotTable 加聚合");
            }
            row.put(cv, vv);
        }
        // 输出 schema:[index, col1, col2, ...] 全 OBJECT(简化;具体类型由调用方 astype 转换)
        Object[] schParts = new Object[2 * (1 + nCol)];
        schParts[0] = index; schParts[1] = DType.OBJECT;
        for (int j = 0; j < nCol; j++) {
            schParts[2 + j * 2] = String.valueOf(sortedCols.get(j));
            schParts[3 + j * 2] = DType.OBJECT;
        }
        Schema sch = Schema.of(schParts);
        Object[][] rows = new Object[nIdx][];
        for (int r = 0; r < nIdx; r++) {
            Object[] row = new Object[1 + nCol];
            row[0] = indexUniq.get(r);
            Map<Object, Object> cells = grid.get(indexUniq.get(r));
            for (int c = 0; c < nCol; c++) {
                row[1 + c] = cells == null ? null : cells.get(sortedCols.get(c));
            }
            rows[r] = row;
        }
        return DataFrame.of(sch, rows);
    }

    /** 通用 Object 比较器(数值统一 doubleCompare;非数值 toString 比较)。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareObj(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && b.getClass().equals(a.getClass())) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // ┌─ What : explode —— 把 list 列展平(每元素一行,其它列复制)
    // │  Why  : 对齐 pandas DataFrame.explode(col);List 元素展平,非 List 元素当 1 元素处理
    /**
     * 把指定列展平(对齐 pandas DataFrame.explode)。
     * <p>该列元素应为 Iterable/数组/单值。Iterable/数组每个元素生成一行(其它列复制);
     * 单值/null 当 1 元素处理(null 保留为 null)。
     * @param df DataFrame 目标表,非 null
     * @param col String 列名(通常 OBJECT dtype),非 null
     * @return DataFrame 行数 = 各行展平后元素数之和
     */
    @SuppressWarnings("unchecked")
    public static DataFrame explode(DataFrame df, String col) {
        int n = df.rowCount();
        java.util.List<Object[]> exploded = new java.util.ArrayList<>();
        for (int r = 0; r < n; r++) {
            Object[] origRow = df.getRow(r);
            Object v = df.get(r, col);
            if (v == null) {
                exploded.add(origRow.clone());
            } else if (v instanceof Iterable<?> it) {
                java.util.List<Object> elems = new java.util.ArrayList<>();
                it.forEach(elems::add);
                if (elems.isEmpty()) {
                    Object[] newRow = origRow.clone();
                    exploded.add(newRow);
                } else {
                    for (Object e : elems) {
                        Object[] newRow = origRow.clone();
                        newRow[df.columnIndex(col)] = e;
                        exploded.add(newRow);
                    }
                }
            } else if (v.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(v);
                if (len == 0) {
                    exploded.add(origRow.clone());
                } else {
                    for (int k = 0; k < len; k++) {
                        Object[] newRow = origRow.clone();
                        newRow[df.columnIndex(col)] = java.lang.reflect.Array.get(v, k);
                        exploded.add(newRow);
                    }
                }
            } else {
                exploded.add(origRow.clone());
            }
        }
        Object[][] rows = exploded.toArray(new Object[0][]);
        // 重建 schema:用原 df 的 columnNames + dtypes 拼 Object[](name1, dtype1, name2, dtype2, ...)
        java.util.List<String> names = df.columnNames();
        java.util.List<DType> dtypes = df.dtypes();
        Object[] schParts = new Object[names.size() * 2];
        for (int i = 0; i < names.size(); i++) {
            schParts[i * 2] = names.get(i);
            schParts[i * 2 + 1] = dtypes.get(i);
        }
        return DataFrame.of(Schema.of(schParts), rows);
    }

    // stack / unstack:返回新 DataFrame,不破坏不可变
    // stack:列名→行(宽→长,类似 melt 但保留所有非索引列)
    // unstack:行值→列名(长→宽,类似 pivot)

    /**
     * 把指定列"堆叠"为行(对齐 pandas DataFrame.stack;宽→长)。
     * <p>策略:把 valueCols 的列名放入新 "variable" 列,值放入 "value" 列;idCols 原样保留。
     * 等价于 melt(idCols, valueCols),但语义更贴近 pandas stack。
     * @param df DataFrame 目标表,非 null
     * @param idCols String[] 保留为标识的列(不被堆叠);可空数组(全部堆叠)
     * @param valueCols String[] 被堆叠的值列;列名进 variable,值进 value;非 null
     * @return DataFrame 列 = idCols + ["variable", "value"];行数 = rowCount × valueCols.length
     */
    public static DataFrame stack(DataFrame df, String[] idCols, String[] valueCols) {
        // stack 本质是 melt 的别名(语义等价:列→行)
        String[] ids = idCols == null ? new String[0] : idCols;
        return melt(df, ids, valueCols);
    }

    /**
     * 展开:行→列(对齐 pandas DataFrame.unstack;长→宽)。
     * @param df DataFrame 目标表;非 null
     * @param idCol 参数;非 null
     * @param keyCol 参数;非 null
     * @param valCol 参数;非 null
     */
    public static DataFrame unstack(DataFrame df, String idCol, String keyCol, String valCol) {
        return pivot(df, idCol, keyCol, valCol);
    }

    // ======================== 补全:reindex/reindex_like/squeeze/rename_axis/set_axis ========================

    /**
     * 重索引(对齐 pandas df.reindex);按 labels 重排行,缺失补 null。
     * @param df DataFrame 目标表
     * @param labels Object[] 目标行标签序列;当前 Index 中存在的保留,不存在的补全 null 行
     * @return DataFrame 行数 == labels.length
     */
    public static DataFrame reindex(DataFrame df, Object[] labels) {
        java.util.List<Integer> keepIdx = new java.util.ArrayList<>();
        java.util.List<Integer> newIdx = new java.util.ArrayList<>();
        for (Object label : labels) {
            int found = -1;
            Object[] existing = df.index().labels();
            if (existing != null) {
                for (int i = 0; i < existing.length; i++) {
                    if (existing[i] != null && existing[i].equals(label)) { found = i; break; }
                }
            } else if (df.index().isRange() && label instanceof Number) {
                int li = ((Number) label).intValue();
                if (li >= 0 && li < df.rowCount()) found = li;
            }
            if (found >= 0) { keepIdx.add(found); newIdx.add(0); }
            else { keepIdx.add(-1); newIdx.add(1); }  // -1 = 新行(补 null)
        }
        // 构建新表
        Object[][] rows = new Object[labels.length][];
        for (int r = 0; r < labels.length; r++) {
            int src = keepIdx.get(r);
            if (src >= 0) {
                rows[r] = df.getRow(src);
            } else {
                rows[r] = new Object[df.columnCount()];  // 全 null
            }
        }
        Object[] schParts = new Object[df.columnCount() * 2];
        for (int i = 0; i < df.columnCount(); i++) {
            schParts[i * 2] = df.columnNames().get(i);
            schParts[i * 2 + 1] = df.dtypes().get(i);
        }
        DataFrame result = DataFrame.of(Schema.of(schParts), rows);
        // 替换 Index 为 labels
        return result.withIndex(Index.of(labels));
    }

    /**
     * reindex_like(对齐 pandas df.reindex_like);用 other 的 Index 重索引 self。
     * @param self 参数;非 null
     * @param other Object 替换值
     */
    public static DataFrame reindexLike(DataFrame self, DataFrame other) {
        Object[] labels = other.index().labels();
        if (labels == null) {
            // RangeIndex → 生成 0..n-1
            labels = new Object[other.rowCount()];
            for (int i = 0; i < labels.length; i++) labels[i] = i;
        }
        return reindex(self, labels);
    }

    /**
     * 降维(对齐 pandas df.squeeze);单行/单列 → 标量或 Series。
     * <p>单行单列 → Object(标量);单列 → 本列 Column;单行 → Object[](行数据);其它 → 原 df 不变。
     * @param df DataFrame 目标表;非 null
     */
    public static Object squeeze(DataFrame df) {
        if (df.rowCount() == 1 && df.columnCount() == 1) {
            return df.get(0, 0);
        }
        if (df.columnCount() == 1) {
            return df.getColumn(df.columnNames().get(0));
        }
        if (df.rowCount() == 1) {
            return df.getRow(0);
        }
        return df;  // 无法降维,返回原表
    }

    /**
     * 重命名 Index 名(对齐 pandas df.rename_axis);jian Index 无 name 字段,简化为返回原 df(无操作)。
     * <p>真正实现需要 Index 加 name 字段(留 v2);当前为 API 兼容占位。
     * @param df DataFrame 目标表;非 null
     * @param name String 名称;非 null
     */
    public static DataFrame renameAxis(DataFrame df, String name) {
        // jian v1 Index 无 name 字段;此方法为 API 兼容占位,返回原 df
        return df;
    }

    /**
     * 替换列名(对齐 pandas df.set_axis);用新列名数组替换现有列名。
     * @param df DataFrame 目标表
     * @param newLabels Object[] 新列名数组;长度必须 == columnCount
     */
    public static DataFrame setAxis(DataFrame df, Object[] newLabels) {
        if (newLabels.length != df.columnCount()) {
            throw new IllegalArgumentException("set_axis 标签数 " + newLabels.length + " ≠ 列数 " + df.columnCount());
        }
        java.util.List<Column> newCols = new java.util.ArrayList<>();
        for (int i = 0; i < df.columnCount(); i++) {
            newCols.add(df.getColumn(df.columnNames().get(i)).rename(String.valueOf(newLabels[i])));
        }
        return DataFrame.ofColumnsDirect(newCols);
    }
}
