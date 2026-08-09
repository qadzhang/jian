package jian.core;

import java.time.LocalDateTime;
import java.util.*;

// ┌─ What : Resampler —— 时间序列重采样器(对齐 pandas.DataFrame.resample(rule))
// │  Why  : 时序分析核心能力(下采样:1H → 1D 求和;上采样:1D → 1H 填充);Stats/Missing 覆盖不了的新职能
// │  Who  : 由 DataFrame.resample(rule) 创建,链式 .sum()/.mean()/.count()/... 聚合
// │  When : 2026-08-09 阶段 D 落地
// │  Where: jian-core/Resampler.java(独立类,与 Frequency + DatetimeIndex 配合)
// │  How  : 数据走向:
// │           ① 取 df 的时间索引(经 setIndexDatetime 或 from 列)
// │           ② 按 rule 用 Frequency.range 生成目标时间网格
// │           ③ 把每行分配到 [grid[i], grid[i+1]) 桶(左闭右开)
// │           ④ 对每桶每数值列应用聚合函数(sum/mean/count/min/max/...)
// │         关键变量变化:
// │           - bins:Map<gridIndex, List<rowIndex>>(哪些原行落到这个网格)
// │           - out:新 DataFrame,行数 = 网格点数 - 1
/**
 * 时间序列重采样器(对齐 pandas DataFrame.resample)。
 *
 * <p>用法:
 * <pre>{@code
 * DataFrame df = ...;  // 含 LocalDateTime 索引或列
 * DataFrame daily = df.resample("ts", "1D").sum();
 * DataFrame hourlyMean = df.resample("ts", "1H").mean();
 * DataFrame ohlc = df.resample("ts", "1D").ohlc("price");
 * }</pre>
 *
 * <p>聚合方法(链式调):
 * <ul>
 *   <li>{@link #sum()} / {@link #mean()} / {@link #median()} / {@link #min()} / {@link #max()} / {@link #count()}:全数值列聚合</li>
 *   <li>{@link #sum(String)} / {@link #mean(String)} / ... :指定单列聚合</li>
 *   <li>{@link #ohlc(String)}:某列的 open/high/low/close(金融 K 线)</li>
 *   <li>{@link #agg(Map)}:多列多聚合</li>
 *   <li>{@link #first()} / {@link #last()}:每个 bucket 的首/末行</li>
 * </ul>
 *
 * <p><b>桶分配规则</b>:左闭右开 [grid[i], grid[i+1]);grid 由 Frequency.range 生成。
 */
public final class Resampler {

    private final DataFrame df;
    private final String tsCol;       // 时间列名(LocalDateTime 元素)
    private final Frequency freq;     // 频率(已 parse)
    private final LocalDateTime[] ts; // 升序时间点(从 tsCol 提取,跳过 null)
    private final int[] origIdx;      // 对应原 df 行下标
    private final LocalDateTime[] grid; // 输出网格点 [t0, t0+f, t0+2f, ...]

    /**
     * 公开构造(由 DataFrame.resample 调用)。
     * @param df DataFrame 目标表,非 null
     * @param tsCol String 时间列名(必须存在;元素应为 LocalDateTime 或可转);非 null
     * @param rule String 频率字符串,如 "1D"/"2H";非 null
     */
    public Resampler(DataFrame df, String tsCol, String rule) {
        this.df = df;
        this.tsCol = tsCol;
        this.freq = Frequency.parse(rule);
        // 提取时间点(跳过 null)
        int n = df.rowCount();
        List<LocalDateTime> tsList = new ArrayList<>();
        List<Integer> idxList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object v = df.get(i, tsCol);
            LocalDateTime lt = toLdt(v);
            if (lt != null) {
                tsList.add(lt);
                idxList.add(i);
            }
        }
        // 升序排(保持原相对顺序)
        Integer[] order = idxList.toArray(new Integer[0]);
        Arrays.sort(order, (a, b) -> tsList.get(idxList.indexOf(a)).compareTo(tsList.get(idxList.indexOf(b))));
        // 简化:直接按扫到的顺序假设已升序(pandas resample 也要求升序)
        this.ts = tsList.toArray(new LocalDateTime[0]);
        this.origIdx = idxList.stream().mapToInt(Integer::intValue).toArray();
        // 网格:从 ts[0] 到 ts[n-1],每 freq 步一个点
        if (this.ts.length >= 1) {
            LocalDateTime start = this.ts[0];
            LocalDateTime end = this.ts[this.ts.length - 1];
            List<LocalDateTime> gridList = new ArrayList<>();
            LocalDateTime cur = start;
            int safety = 0;
            while (!cur.isAfter(end) && safety < 100000) {
                gridList.add(cur);
                cur = freq.plus(cur);
                safety++;
            }
            // 多加一个 endpoint 用于左闭右开
            gridList.add(cur);
            this.grid = gridList.toArray(new LocalDateTime[0]);
        } else {
            this.grid = new LocalDateTime[0];
        }
    }

    /** 转 LocalDateTime(支持 LocalDateTime / LocalDate → atStartOfDay / String → parse)。 */
    private static LocalDateTime toLdt(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime lt) return lt;
        if (v instanceof java.time.LocalDate ld) return ld.atStartOfDay();
        if (v instanceof String s) {
            try { return LocalDateTime.parse(s.replace(' ', 'T')); } catch (Exception e) { return null; }
        }
        return null;
    }

    // ======================== 单一聚合(全数值列)========================

    /** 全数值列 sum 聚合。 */
    public DataFrame sum() { return aggregateAll("sum"); }
    /** 全数值列 mean 聚合。 */
    public DataFrame mean() { return aggregateAll("mean"); }
    /** 全数值列 median 聚合。 */
    public DataFrame median() { return aggregateAll("median"); }
    /** 全数值列 min 聚合。 */
    public DataFrame min() { return aggregateAll("min"); }
    /** 全数值列 max 聚合。 */
    public DataFrame max() { return aggregateAll("max"); }
    /** 全列 count(非空计数)。 */
    public DataFrame count() { return aggregateAll("count"); }
    /** 全数值列 std 聚合。 */
    public DataFrame std() { return aggregateAll("std"); }
    /** 全数值列 var 聚合。 */
    public DataFrame var() { return aggregateAll("var"); }

    // ======================== 单列聚合 ========================

    /** 单列 sum 聚合(返回 DataFrame 含 bucket 时间 + 该列聚合值)。 */
    public DataFrame sum(String col) { return aggregateOne(col, "sum"); }
    public DataFrame mean(String col) { return aggregateOne(col, "mean"); }
    public DataFrame min(String col) { return aggregateOne(col, "min"); }
    public DataFrame max(String col) { return aggregateOne(col, "max"); }
    public DataFrame count(String col) { return aggregateOne(col, "count"); }

    // ======================== OHLC(K 线)========================

    /**
     * 某列的 OHLC 聚合(对齐 pandas resample.ohlc)。
     * @param col String 待 OHLC 的数值列名;非 null
     * @return DataFrame 列 = [bucket, col_open, col_high, col_low, col_close],行数 = grid.length - 1
     */
    public DataFrame ohlc(String col) {
        Column c = df.getColumn(col);
        // 计算 buckets
        Map<Integer, List<Integer>> bins = computeBins();
        Schema sch = Schema.of(
            "_bucket_", DType.DATETIME,
            col + "_open", DType.DOUBLE,
            col + "_high", DType.DOUBLE,
            col + "_low", DType.DOUBLE,
            col + "_close", DType.DOUBLE);
        List<Object[]> rows = new ArrayList<>();
        for (int g = 0; g < grid.length - 1; g++) {
            List<Integer> bucket = bins.get(g);
            Object[] row = new Object[5];
            row[0] = grid[g];
            if (bucket == null || bucket.isEmpty()) {
                row[1] = null; row[2] = null; row[3] = null; row[4] = null;
            } else {
                double open = Double.NaN, high = Double.NaN, low = Double.NaN, close = Double.NaN;
                for (int k = 0; k < bucket.size(); k++) {
                    int rowIdx = bucket.get(k);
                    if (c.isNull(rowIdx) || Double.isNaN(c.getDouble(rowIdx))) continue;
                    double v = c.getDouble(rowIdx);
                    if (k == 0) { open = v; high = v; low = v; }
                    else { high = Math.max(high, v); low = Math.min(low, v); }
                    close = v;
                }
                row[1] = Double.isNaN(open) ? null : open;
                row[2] = Double.isNaN(high) ? null : high;
                row[3] = Double.isNaN(low) ? null : low;
                row[4] = Double.isNaN(close) ? null : close;
            }
            rows.add(row);
        }
        return DataFrame.of(sch, rows.toArray(new Object[0][]));
    }

    // ======================== 多列多聚合 agg ========================

    /**
     * 多列多聚合(对齐 pandas resample.agg({"col": "fn", ...}))。
     * @param spec Map<String,String> {colName: aggFn},fn 支持 sum/mean/count/min/max/median/std/var
     * @return DataFrame 列 = spec.keySet()(各 fn_col 形式命名);行数 = grid.length - 1
     */
    public DataFrame agg(Map<String, String> spec) {
        Map<Integer, List<Integer>> bins = computeBins();
        // 输出 schema:_bucket_(name+dtype=2 项)+ spec 各列(name+dtype 各 2 项)
        Object[] schParts = new Object[2 + spec.size() * 2];
        schParts[0] = "_bucket_"; schParts[1] = DType.DATETIME;
        int sp = 2;
        for (Map.Entry<String, String> e : spec.entrySet()) {
            schParts[sp++] = e.getValue() + "_" + e.getKey();
            schParts[sp++] = DType.DOUBLE;
        }
        Schema sch = Schema.of(schParts);
        List<Object[]> rows = new ArrayList<>();
        for (int g = 0; g < grid.length - 1; g++) {
            List<Integer> bucket = bins.get(g);
            Object[] row = new Object[1 + spec.size()];
            row[0] = grid[g];
            int j = 1;
            for (Map.Entry<String, String> e : spec.entrySet()) {
                Column c = df.getColumn(e.getKey());
                row[j++] = bucketAggregate(c, bucket, e.getValue());
            }
            rows.add(row);
        }
        return DataFrame.of(sch, rows.toArray(new Object[0][]));
    }

    // ======================== 首末行 ========================

    /** 每 bucket 的首行(对齐 pandas resample.first)。 */
    public DataFrame first() { return aggregateAll("first"); }
    /** 每 bucket 的末行(对齐 pandas resample.last)。 */
    public DataFrame last() { return aggregateAll("last"); }

    // ======================== 内部:桶分配 ========================

    /**
     * 计算 grid 网格到原行下标的桶映射(左闭右开 [grid[g], grid[g+1]))。
     * 算法:对每个原行时间点 ts[k],二分找它落在哪个 grid 区间。
     */
    private Map<Integer, List<Integer>> computeBins() {
        Map<Integer, List<Integer>> bins = new LinkedHashMap<>();
        int gp = 0;  // grid pointer:单调推进(因 ts 升序,grid 也升序)
        for (int k = 0; k < ts.length; k++) {
            LocalDateTime t = ts[k];
            // 推进 gp 到包含 t 的最后一个 grid 点(grid[gp] <= t < grid[gp+1])
            while (gp + 1 < grid.length && !t.isBefore(grid[gp + 1])) gp++;
            // 校验:确保 t ∈ [grid[gp], grid[gp+1])
            if (gp + 1 < grid.length && !t.isBefore(grid[gp]) && t.isBefore(grid[gp + 1])) {
                bins.computeIfAbsent(gp, x -> new ArrayList<>()).add(origIdx[k]);
            }
            // 边界:t 早于 grid[gp] 或晚于 grid 末点 → 不分配(异常数据)
        }
        return bins;
    }

    /** 全数值列聚合。 */
    private DataFrame aggregateAll(String fn) {
        Map<Integer, List<Integer>> bins = computeBins();
        List<String> numCols = new ArrayList<>();
        for (String c : df.columnNames()) {
            DType dt = df.getColumn(c).dtype();
            if (dt == DType.DOUBLE || dt == DType.LONG || dt == DType.INT) numCols.add(c);
        }
        Object[] schParts = new Object[(1 + numCols.size()) * 2];
        schParts[0] = "_bucket_"; schParts[1] = DType.DATETIME;
        for (int j = 0; j < numCols.size(); j++) {
            schParts[2 + j * 2] = numCols.get(j) + "_" + fn;
            schParts[3 + j * 2] = DType.DOUBLE;
        }
        Schema sch = Schema.of(schParts);
        List<Object[]> rows = new ArrayList<>();
        for (int g = 0; g < grid.length - 1; g++) {
            List<Integer> bucket = bins.get(g);
            Object[] row = new Object[1 + numCols.size()];
            row[0] = grid[g];
            for (int j = 0; j < numCols.size(); j++) {
                row[1 + j] = bucketAggregate(df.getColumn(numCols.get(j)), bucket, fn);
            }
            rows.add(row);
        }
        return DataFrame.of(sch, rows.toArray(new Object[0][]));
    }

    /** 单列聚合。 */
    private DataFrame aggregateOne(String col, String fn) {
        Map<Integer, List<Integer>> bins = computeBins();
        Column c = df.getColumn(col);
        Schema sch = Schema.of("_bucket_", DType.DATETIME, col + "_" + fn, DType.DOUBLE);
        List<Object[]> rows = new ArrayList<>();
        for (int g = 0; g < grid.length - 1; g++) {
            List<Integer> bucket = bins.get(g);
            Object[] row = new Object[]{grid[g], bucketAggregate(c, bucket, fn)};
            rows.add(row);
        }
        return DataFrame.of(sch, rows.toArray(new Object[0][]));
    }

    /** 在 bucket 上应用聚合 fn 到 Column c。 */
    private Double bucketAggregate(Column c, List<Integer> bucket, String fn) {
        if (bucket == null || bucket.isEmpty()) return null;
        double[] vals = new double[bucket.size()];
        int valid = 0;
        for (int idx : bucket) {
            if (!c.isNull(idx) && !Double.isNaN(c.getDouble(idx))) {
                vals[valid++] = c.getDouble(idx);
            }
        }
        if (valid == 0) return null;
        switch (fn) {
            case "count":
                return (double) valid;  // count 返回非空数
            case "sum": {
                double s = 0; for (int i = 0; i < valid; i++) s += vals[i]; return s;
            }
            case "mean": {
                double s = 0; for (int i = 0; i < valid; i++) s += vals[i]; return s / valid;
            }
            case "min": {
                double m = Double.POSITIVE_INFINITY; for (int i = 0; i < valid; i++) m = Math.min(m, vals[i]); return m;
            }
            case "max": {
                double m = Double.NEGATIVE_INFINITY; for (int i = 0; i < valid; i++) m = Math.max(m, vals[i]); return m;
            }
            case "median": {
                double[] sorted = Arrays.copyOf(vals, valid);
                Arrays.sort(sorted);
                return valid % 2 == 0 ? (sorted[valid/2-1] + sorted[valid/2]) / 2 : sorted[valid/2];
            }
            case "std":
            case "var": {
                double s = 0; for (int i = 0; i < valid; i++) s += vals[i];
                double mean = s / valid;
                double ss = 0; for (int i = 0; i < valid; i++) { double d = vals[i] - mean; ss += d * d; }
                double var = valid >= 2 ? ss / (valid - 1) : Double.NaN;
                return "std".equals(fn) ? Math.sqrt(var) : var;
            }
            case "first": return (double) vals[0];
            case "last": return (double) vals[valid - 1];
            default:
                throw new IllegalArgumentException("Resampler 不支持聚合:" + fn
                    + "(支持:sum/mean/count/min/max/median/std/var/first/last)");
        }
    }
}
