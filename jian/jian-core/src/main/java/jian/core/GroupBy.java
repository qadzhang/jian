package jian.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ┌─ What : GroupBy —— DataFrame 分组对象(对齐 pandas.core.groupby)
// │  Why  : 规范 01 §5 要求 agg/transform/filter;GroupBy 是 group-apply 模式的核心抽象
// │  Who  : 由 df.groupBy 创建;用户链式调 agg/transform/filter
// │  When : 分组聚合(按部门算平均薪资等)
// │  Where: jian-core/GroupBy.java
// │  How  : 数据走向:DataFrame → 取 by 列 → 行索引按 groupKey 分桶(LinkedHashMap 保序)→
// │         agg 时每桶每列算统计 → 拼 DataFrame。
// │         关键变量变化:
// │           - groups:LinkedHashMap<组键 List<Object>, List<Integer> 行下标>;LinkedHashMap 保出现顺序;
// │           - groupKey 含 null 时归 "<NA>" 组(规范 01 §9)。
// │         逻辑路线:
// │           路径 A(agg)→ 每组每列算统计量 → 行=组,列=byCols+aggCol_统计名;
// │           路径 B(transform)→ 每组算统计后广播回原行序 → 长度 = 原行数;
// │           路径 C(filter)→ 组级谓词,保留整组或丢整组。
/**
 * 分组对象,对齐 pandas groupby。由 {@link DataFrame#groupBy(String...)} 创建。
 *
 * <p>用法:
 * <pre>{@code
 * DataFrame r = df.groupBy("dept")
 *     .agg(Map.of("salary", "mean", "id", "count"));
 * }</pre>
 */
public final class GroupBy {

    private final DataFrame parent;
    private final String[] byCols;
    private final Map<List<Object>, int[]> groups;  // 组键 → 组内行下标

    GroupBy(DataFrame parent, String[] byCols) {
        this.parent = parent;
        this.byCols = byCols;
        this.groups = buildGroups(parent, byCols);
    }

    /** 构建分组:扫一遍行,按 groupKey(各 byCol 值的 List)分桶,LinkedHashMap 保序。 */
    private static Map<List<Object>, int[]> buildGroups(DataFrame df, String[] byCols) {
        // 先收集到 List,最后转 int[](避免动态扩容)
        Map<List<Object>, List<Integer>> tmp = new LinkedHashMap<>();
        int n = df.rowCount();
        for (int r = 0; r < n; r++) {
            List<Object> key = new ArrayList<>(byCols.length);
            for (String col : byCols) {
                Object v = df.get(r, col);
                key.add(v == null ? "<NA>" : v);  // 规范 §9:null 归 <NA> 组
            }
            tmp.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Map<List<Object>, int[]> out = new LinkedHashMap<>();
        for (Map.Entry<List<Object>, List<Integer>> e : tmp.entrySet()) {
            List<Integer> idx = e.getValue();
            int[] arr = new int[idx.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = idx.get(i);
            out.put(e.getKey(), arr);
        }
        return out;
    }

    /** 组数。 */
    public int groupCount() { return groups.size(); }

    /** 遍历每组(对齐 pandas for name, sub_df in gb)。 */
    public Iterable<GroupEntry> iterGroups() {
        List<GroupEntry> out = new ArrayList<>(groups.size());
        for (Map.Entry<List<Object>, int[]> e : groups.entrySet()) {
            out.add(new GroupEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 单条组记录。 */
    public record GroupEntry(List<Object> key, int[] rowIndices) {}

    /**
     * 多列聚合(对齐 pandas df.groupby(by).agg(map))。
     *
     * @param aggSpec Map<列名, 聚合函数名>;聚合函数名:sum/mean/count/min/max/median/std/var/first/last/nunique
     * @return 行=组(列=byCols + agg 各列),组键拆为普通列
     */
    public DataFrame agg(Map<String, String> aggSpec) {
        // 列名:byCols + aggSpec 的每列加 _aggFn 后缀(避免重名)
        List<String> outCols = new ArrayList<>();
        for (String c : byCols) outCols.add(c);
        for (Map.Entry<String, String> e : aggSpec.entrySet()) {
            outCols.add(e.getKey() + "_" + e.getValue());
        }
        // 构造行:每组一行
        Object[][] rows = new Object[groups.size()][outCols.size()];
        int rowIdx = 0;
        for (Map.Entry<List<Object>, int[]> g : groups.entrySet()) {
            List<Object> key = g.getKey();
            int[] idx = g.getValue();
            // 写 byCols
            for (int c = 0; c < byCols.length; c++) {
                Object v = key.get(c);
                rows[rowIdx][c] = "<NA>".equals(v) ? null : v;
            }
            // 写每列 agg
            int colCursor = byCols.length;
            for (Map.Entry<String, String> e : aggSpec.entrySet()) {
                String colName = e.getKey();
                String fn = e.getValue();
                rows[rowIdx][colCursor++] = aggregate(parent.getColumn(colName), idx, fn);
            }
            rowIdx++;
        }
        // 推断 schema(数值 agg → DOUBLE,count/nunique → LONG,byCols 用原 dtype)
        Object[] nameType = new Object[outCols.size() * 2];
        for (int i = 0; i < outCols.size(); i++) {
            nameType[i * 2] = outCols.get(i);
            if (i < byCols.length) {
                // byCols 用原 dtype
                nameType[i * 2 + 1] = parent.getColumn(byCols[i]).dtype();
            } else {
                // agg 列:count/nunique → LONG,其余 → DOUBLE
                String aggFn = outCols.get(i).substring(outCols.get(i).lastIndexOf('_') + 1);
                nameType[i * 2 + 1] = (aggFn.equals("count") || aggFn.equals("nunique")) ? DType.LONG : DType.DOUBLE;
            }
        }
        return DataFrame.of(Schema.of(nameType), rows);
    }

    /** 单列单聚合(快捷)。 */
    public DataFrame agg(String col, String fn) {
        return agg(Map.of(col, fn));
    }

    /** 在某列上算指定聚合,返回标量(供单值场景)。 */
    private Object aggregate(Column c, int[] idx, String fn) {
        switch (fn) {
            case "count":
                int cnt = 0; for (int i : idx) if (!c.isNull(i)) cnt++; return (long) cnt;
            case "nunique":
                java.util.Set<Object> seen = new java.util.HashSet<>();
                for (int i : idx) { Object v = c.get(i); if (v != null) seen.add(v); }
                return (long) seen.size();
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
            case "median": case "std": case "var": {
                // 提取组内值
                List<Double> vals = new ArrayList<>();
                for (int i : idx) if (!c.isNull(i)) vals.add(c.getDouble(i));
                if (vals.isEmpty()) return Double.NaN;
                if (fn.equals("median")) {
                    vals.sort(Double::compare);
                    int sz = vals.size();
                    return sz % 2 == 0 ? (vals.get(sz/2-1)+vals.get(sz/2))/2 : vals.get(sz/2);
                }
                double s = 0; for (double v : vals) s += v; double mean = s / vals.size();
                double ss = 0; for (double v : vals) { double d = v - mean; ss += d * d; }
                double var = ss / (vals.size() - 1);  // ddof=1
                return fn.equals("std") ? Math.sqrt(var) : var;
            }
            default:
                throw new IllegalArgumentException("未知聚合函数:" + fn
                        + "(支持:count/nunique/sum/mean/min/max/first/last/median/std/var)");
        }
    }

    /**
     * 组级过滤(对齐 pandas gb.filter):保留谓词为真的组(整组保留或丢弃)。
     *
     * @param col 要判断的列
     * @param fn 聚合函数(如 "count")
     * @param keepIf 谓词,接收聚合值,返回是否保留该组
     */
    public DataFrame filter(String col, String fn, java.util.function.Predicate<Double> keepIf) {
        Column c = parent.getColumn(col);
        java.util.List<boolean[]> keepMasks = new ArrayList<>();
        // 整体 keep mask
        boolean[] keep = new boolean[parent.rowCount()];
        for (Map.Entry<List<Object>, int[]> g : groups.entrySet()) {
            Object aggVal = aggregate(c, g.getValue(), fn);
            double v = aggVal instanceof Number ? ((Number) aggVal).doubleValue() : 0;
            if (keepIf.test(v)) for (int r : g.getValue()) keep[r] = true;
        }
        return parent.filter(keep);
    }

    /** 组数(快捷)。 */
    public DataFrame size() {
        Object[][] rows = new Object[groups.size()][2];
        int i = 0;
        for (Map.Entry<List<Object>, int[]> g : groups.entrySet()) {
            rows[i][0] = g.getKey().size() == 1 ? g.getKey().get(0) : g.getKey().toString();
            rows[i][1] = (long) g.getValue().length;
            i++;
        }
        return DataFrame.of(Schema.of("key", DType.STRING, "size", DType.LONG), rows);
    }

    /**
     * 组级变换广播回原行序(对齐 pandas gb.transform)。
     *
     * <p>对每组的指定列算聚合值,然后把聚合值广播回该组的每一行 → 返回与原 DataFrame 等长的列。
     * 用途:如计算每人与所在部门平均工资的差。
     *
     * @param col 要聚合的数值列
     * @param fn 聚合函数(sum/mean/min/max/median/std/var/count)
     * @return double[]（长度=原 DataFrame 行数;缺失行为 NaN）
     */
    public double[] transform(String col, String fn) {
        Column c = parent.getColumn(col);
        int n = parent.rowCount();
        double[] result = new double[n];
        // 对每组算聚合值 → 广播回所有组内行
        for (Map.Entry<List<Object>, int[]> g : groups.entrySet()) {
            Object aggVal = aggregate(c, g.getValue(), fn);
            double v = aggVal instanceof Number ? ((Number) aggVal).doubleValue() : Double.NaN;
            for (int rowIdx : g.getValue()) result[rowIdx] = v;
        }
        return result;
    }

    /**
     * 组级变换,返回新列(对齐 pandas gb.transform('mean') 作为新列)。
     *
     * @param newColName 新列名
     * @param col 要聚合的列
     * @param fn 聚合函数
     * @return 新 DataFrame(原列 + 广播后的新列)
     */
    public DataFrame transformAsColumn(String newColName, String col, String fn) {
        double[] vals = transform(col, fn);
        return parent.assign(newColName, r -> Double.isNaN(vals[r]) ? null : vals[r]);
    }
}
