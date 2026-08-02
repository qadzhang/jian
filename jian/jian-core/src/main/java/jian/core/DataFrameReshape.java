package jian.core;

import java.util.ArrayList;
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
     * @param index 行分组列(可以是 1 列;多列 v2 支持)
     * @param columns 散开成列的分组列
     * @param values 被聚合的值列
     * @param aggFn 聚合函数(mean/sum/count/min/max/first/last...)
     */
    public static DataFrame pivotTable(DataFrame df, String index, String columns,
                                       String values, String aggFn) {
        // 1. 收集 columns 列的不同值(保序)
        List<Object> colValues = new ArrayList<>();
        java.util.Set<Object> colSeen = new java.util.HashSet<>();
        for (int r = 0; r < df.rowCount(); r++) {
            Object v = df.get(r, columns);
            if (colSeen.add(v)) colValues.add(v);
        }
        // 2. 收集 index 列的不同值(保序)
        List<Object> indexValues = new ArrayList<>();
        java.util.Set<Object> indexSeen = new java.util.HashSet<>();
        for (int r = 0; r < df.rowCount(); r++) {
            Object v = df.get(r, index);
            if (indexSeen.add(v)) indexValues.add(v);
        }
        // 3. 用 groupBy (index, columns) 聚合 values,缓存结果
        // map: (indexVal, colVal) → agg 值
        Map<String, Object> cellMap = new LinkedHashMap<>();
        // 先按 (index, columns) 分组
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        for (int r = 0; r < df.rowCount(); r++) {
            Object iv = df.get(r, index);
            Object cv = df.get(r, columns);
            String key = keyOf(iv, cv);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Column valCol = df.getColumn(values);
        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
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

    private static String keyOf(Object i, Object c) {
        return String.valueOf(i) + "\0" + String.valueOf(c);
    }

    /**
     * 宽转长(对齐 pandas.melt):每行的指定 value 列各产生一行,id_vars 重复。
     *
     * @param idVars 标识列(保留)
     * @param valueVars 被展平的值列(列名 → value 列,值 → value 列内容)
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

    /** 转置(对齐 pandas df.T):行列互换。 */
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
     */
    public static DataFrame dropDuplicates(DataFrame df, String[] subset, String keep) {
        int n = df.rowCount();
        boolean[] keepMask = new boolean[n];
        java.util.Set<String> seen = new java.util.HashSet<>();
        // keep=first 从前往后;keep=last 从后往前;false 只保留全唯一行
        if ("false".equalsIgnoreCase(keep)) {
            // 全唯一:统计每个 key 出现次数,只保留出现 1 次的
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int r = 0; r < n; r++) {
                String k = keyOf(df, r, subset);
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
                        String k = keyOf(df, r, subset);
                        keepMask[r] = seen.add(k);
                    }
                } else {
                    for (int r = n - 1; r >= 0; r--) {
                        String k = keyOf(df, r, subset);
                        keepMask[r] = seen.add(k);
                    }
                }
            }
        }
        return df.filter(keepMask);
    }

    private static String keyOf(DataFrame df, int r, String[] subset) {
        StringBuilder sb = new StringBuilder();
        for (String c : subset) sb.append(df.get(r, c)).append('\0');
        return sb.toString();
    }

    // 复用 GroupBy 的聚合逻辑(轻量复制避免跨类依赖)
    private static Object aggregate(Column c, int[] idx, String fn) {
        switch (fn) {
            case "count":
                int cnt = 0; for (int i : idx) if (!c.isNull(i)) cnt++; return (long) cnt;
            case "sum": { double s = 0; for (int i : idx) if (!c.isNull(i)) s += c.getDouble(i); return s; }
            case "mean": { double s = 0; int n = 0; for (int i : idx) if (!c.isNull(i)) { s += c.getDouble(i); n++; } return n == 0 ? Double.NaN : s / n; }
            case "min": { double m = Double.POSITIVE_INFINITY; boolean any = false;
                for (int i : idx) if (!c.isNull(i)) { any = true; if (c.getDouble(i) < m) m = c.getDouble(i); }
                return any ? m : Double.NaN; }
            case "max": { double m = Double.NEGATIVE_INFINITY; boolean any = false;
                for (int i : idx) if (!c.isNull(i)) { any = true; if (c.getDouble(i) > m) m = c.getDouble(i); }
                return any ? m : Double.NaN; }
            case "first": return c.get(idx[0]);
            case "last": return c.get(idx[idx.length - 1]);
            default: throw new IllegalArgumentException("pivotTable/dropDuplicates 不支持的聚合:" + fn);
        }
    }
}
