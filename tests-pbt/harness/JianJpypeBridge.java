import jian.core.*;
import java.util.*;

// ┌─ What : JianJpypeBridge —— Python 端 JPype 直调 jian-core 的 Java 适配层(替代废弃的 subprocess+JSON 方案)
// │  Why  : 旧 JianPbtBridge 用 subprocess 起 java 进程 + 手写 JSON 行协议通信(874 行 Parser/writeJson)。
// │         skill(ai-code-testing)标准做法是 JPype:JVM 嵌入 Python 进程,直接调 jar 里的类,
// │         被测 jar 只需在 classpath,不需要"支持 JSON"。新方案删掉全部 JSON 序列化/解析,
// │         数据经 java.util.List/Map 原生传递,更快更干净,且对任意 jar 通用。
// │  Who  : 由 tests-pbt/harness/jian_client.py 经 JPype 调用(jpype.startJVM → JClass("JianJpypeBridge"))
// │  When : Python 测试(PBT / pandas 对照)启动时加载一次,进程内反复调用
// │  Where: tests-pbt/harness/JianJpypeBridge.java(单文件,不进 jian-core jar;javac 编译后进 classpath)
// │  Note : LONG-null 协议沿用旧约定:jian-core 的 LONG dtype 不存 null,Column.getLong 用
// │         Long.MIN_VALUE 作 missing 哨兵;需要区分 null 的 PBT 场景用 DOUBLE 列(null → NaN)。
// │  Note2: Float 推断说明 —— Python 侧 float 统一按 DOUBLE 列读回
// │         (np.float32 与 float64 在桥层不可区分);涉及 Float 精度断言请用 DOUBLE 语义,勿假设 float32。
// │  How  : 数据走向:
// │           Python 构造 java.util.ArrayList(List<String> columns, List<List<Object>> rows)
// │              → JPype 调本类静态方法 → 内部 DataFrame.ofColumnArrays 装列 → 调 jian 算子
// │              → 结果 dfToMap 转 LinkedHashMap → JPype 转回 Python dict
// │         关键变量变化:
// │           - rows(Python list)→ toDf 按列转置为 long[]/double[]/Object[] 三选一
// │           - result(Java DataFrame)→ dfToMap 逐行 get(r,c) 转 List<List<Object>>
// │         逻辑路线:
// │           路径 A(正常)→ 返回 {"columns":..., "rows":...} 或 {"value":...} / {"mask":[...]};
// │           路径 B(异常)→ RuntimeException 上抛,由 jian_client.py 转 AssertionError。
public class JianJpypeBridge {

    // ======================== DataFrame 构造(类型推断) ========================

    /** Python rows(List<List<Object>>)→ DataFrame。null 不参与类型推断;全 null 列 → DOUBLE(对齐 pandas)。 */
    static DataFrame toDf(List<String> columns, List<List<Object>> rows) {
        int nCols = columns.size();
        if (rows == null || rows.isEmpty()) {
            Object[] empty = new Object[nCols];
            for (int c = 0; c < nCols; c++) empty[c] = emptyArrayOfType(inferType(rows, c));
            return DataFrame.ofColumnArrays(columns, empty);
        }
        int n = rows.size();
        List<List<Object>> norm = new ArrayList<>(n);
        for (int r = 0; r < n; r++) {
            List<Object> row = rows.get(r);
            List<Object> nr = new ArrayList<>(nCols);
            for (int c = 0; c < nCols; c++) nr.add(c < row.size() ? row.get(c) : null);
            norm.add(nr);
        }
        Object[] colArrays = new Object[nCols];
        for (int c = 0; c < nCols; c++) {
            String kind = inferType(norm, c);
            switch (kind) {
                case "LONG": {
                    long[] arr = new long[n];
                    for (int r = 0; r < n; r++) {
                        Object v = norm.get(r).get(c);
                        arr[r] = v == null ? 0L : ((Number) v).longValue();
                    }
                    colArrays[c] = arr;
                    break;
                }
                case "DOUBLE": {
                    double[] arr = new double[n];
                    for (int r = 0; r < n; r++) {
                        Object v = norm.get(r).get(c);
                        arr[r] = v == null ? Double.NaN : ((Number) v).doubleValue();
                    }
                    colArrays[c] = arr;
                    break;
                }
                default: {
                    Object[] arr = new Object[n];
                    for (int r = 0; r < n; r++) arr[r] = norm.get(r).get(c);
                    colArrays[c] = arr;
                }
            }
        }
        return DataFrame.ofColumnArrays(columns, colArrays);
    }

    /** 全行扫描列 c 的最宽类型:String/Boolean→OBJECT;Double/Float→DOUBLE;否则 LONG;全 null→DOUBLE。 */
    private static String inferType(List<List<Object>> rows, int c) {
        boolean sawAny = false, sawDouble = false;
        for (List<Object> row : rows) {
            if (row == null || c >= row.size()) continue;
            Object v = row.get(c);
            if (v == null) continue;
            sawAny = true;
            if (v instanceof String || v instanceof Boolean) return "OBJECT";
            if (v instanceof Double || v instanceof Float) sawDouble = true;
        }
        if (!sawAny) return "DOUBLE";
        return sawDouble ? "DOUBLE" : "LONG";
    }

    /** 按 dtype 名构造空数组。 */
    private static Object emptyArrayOfType(String dtype) {
        switch (dtype) {
            case "LONG": case "INT": return new long[0];
            case "DOUBLE": case "FLOAT": return new double[0];
            case "BOOL": case "BOOLEAN": return new boolean[0];
            default: return new Object[0];
        }
    }

    /** DataFrame → LinkedHashMap。{"columns":[...],"rows":[[...],...],"dtypes":{col:dtype}} */
    private static Map<String, Object> dfToMap(DataFrame df) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", df.columnNames());
        List<List<Object>> rows = new ArrayList<>();
        int n = df.rowCount(), nCols = df.columnCount();
        for (int r = 0; r < n; r++) {
            List<Object> row = new ArrayList<>(nCols);
            for (int c = 0; c < nCols; c++) row.add(df.get(r, c));
            rows.add(row);
        }
        m.put("rows", rows);
        m.put("rowCount", n);
        // 因为 Python 侧 assert_df_equal 要比对 dtype(防"值对但类型降级"静默漏检),
        // 所以每列 dtype 一并回传;旧桥无此键时 Python 侧跳过比对
        Map<String, String> dtypes = new LinkedHashMap<>();
        for (String c : df.columnNames()) dtypes.put(c, df.getColumn(c).dtype().name());
        m.put("dtypes", dtypes);
        return m;
    }

    /** 新列追加到 df 末尾(阶段 B 统计 op 用)。 */
    private static DataFrame addColumn(DataFrame df, Column newCol) {
        List<Column> cols = new ArrayList<>(df.columnCount() + 1);
        for (String c : df.columnNames()) cols.add(df.getColumn(c));
        cols.add(newCol);
        return DataFrame.ofColumnsDirect(cols);
    }

    // ======================== 算子(静态方法,Python 直接调) ========================

    public static Map<String, Object> sort(List<String> cols, List<List<Object>> rows, String col, boolean asc) {
        return dfToMap(toDf(cols, rows).sortBy(col, asc));
    }

    public static Map<String, Object> filter(List<String> cols, List<List<Object>> rows, String expr) {
        return dfToMap(toDf(cols, rows).query(expr));
    }

    public static Map<String, Object> head(List<String> cols, List<List<Object>> rows, int n) {
        return dfToMap(toDf(cols, rows).head(n));
    }

    public static Map<String, Object> tail(List<String> cols, List<List<Object>> rows, int n) {
        return dfToMap(toDf(cols, rows).tail(n));
    }

    public static Map<String, Object> merge(List<String> lc, List<List<Object>> lr,
                                            List<String> rc, List<List<Object>> rr,
                                            String how, String on) {
        DataFrame left = toDf(lc, lr), right = toDf(rc, rr);
        return dfToMap(left.merge(right, how, on));
    }

    /** 异名键 merge(leftOn≠rightOn),pandas 对照测试(d64)用。 */
    public static Map<String, Object> mergeOn(List<String> lc, List<List<Object>> lr,
                                              List<String> rc, List<List<Object>> rr,
                                              String how, String leftOn, String rightOn) {
        DataFrame left = toDf(lc, lr), right = toDf(rc, rr);
        return dfToMap(left.merge(right, how, new String[]{leftOn}, new String[]{rightOn}, null));
    }

    public static Map<String, Object> groupBy(List<String> cols, List<List<Object>> rows,
                                              String by, String aggCol, String fn) {
        Map<String, String> spec = new HashMap<>();
        spec.put(aggCol, fn);
        return dfToMap(toDf(cols, rows).groupBy(by).agg(spec));
    }

    public static Map<String, Object> concat(List<String> c1, List<List<Object>> r1,
                                             List<String> c2, List<List<Object>> r2, int axis) {
        List<DataFrame> dfs = new ArrayList<>();
        dfs.add(toDf(c1, r1));
        dfs.add(toDf(c2, r2));
        return dfToMap(DataFrame.concat(dfs, axis));
    }

    public static Map<String, Object> dropDuplicates(List<String> cols, List<List<Object>> rows, List<String> subset) {
        return dfToMap(toDf(cols, rows).dropDuplicates(subset.toArray(new String[0]), "first"));
    }

    public static Map<String, Object> fillna(List<String> cols, List<List<Object>> rows, Object value) {
        return dfToMap(toDf(cols, rows).fillna(value));
    }

    /** 按列填充:keys/vals 平行列表 → Map → df.fillna(Map)(对齐 pandas fillna(dict))。 */
    public static Map<String, Object> fillnaDict(List<String> cols, List<List<Object>> rows,
                                                 List<String> keys, List<Object> vals) {
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), vals.get(i));
        return dfToMap(toDf(cols, rows).fillna(m));
    }

    public static Map<String, Object> dropna(List<String> cols, List<List<Object>> rows) {
        return dfToMap(toDf(cols, rows).dropna());
    }

    public static Map<String, Object> ffill(List<String> cols, List<List<Object>> rows) {
        return dfToMap(toDf(cols, rows).ffill());
    }

    public static Map<String, Object> astype(List<String> cols, List<List<Object>> rows, String col, String target) {
        return dfToMap(toDf(cols, rows).astype(col, DType.valueOf(target)));
    }

    public static Map<String, Object> select(List<String> cols, List<List<Object>> rows, List<String> keep) {
        return dfToMap(toDf(cols, rows).select(keep.toArray(new String[0])));
    }

    public static Map<String, Object> drop(List<String> cols, List<List<Object>> rows, List<String> dropCols) {
        return dfToMap(toDf(cols, rows).drop(dropCols.toArray(new String[0])));
    }

    public static Map<String, Object> slice(List<String> cols, List<List<Object>> rows, int a, int b) {
        DataFrame df = toDf(cols, rows);
        // 支持负索引(对齐 Python slice 语义:负数从尾数起算,不得截成 0/空)
        int n = df.rowCount();
        int lo = Math.min(a, b), hi = Math.max(a, b);
        if (lo < 0) lo = Math.max(0, n + lo);
        if (hi < 0) hi = Math.max(0, n + hi);
        hi = Math.min(hi, n);
        if (lo >= hi) return dfToMap(df.slice(0, 0));
        return dfToMap(df.slice(lo, hi));
    }

    public static Map<String, Object> nlargest(List<String> cols, List<List<Object>> rows, int n, String col) {
        return dfToMap(toDf(cols, rows).nlargest(n, col));
    }

    public static Map<String, Object> nsmallest(List<String> cols, List<List<Object>> rows, int n, String col) {
        return dfToMap(toDf(cols, rows).nsmallest(n, col));
    }

    public static Map<String, Object> colAdd(List<String> cols, List<List<Object>> rows,
                                             String newName, String a, String b) {
        return dfToMap(toDf(cols, rows).colAdd(newName, a, b));
    }

    public static Map<String, Object> colSub(List<String> cols, List<List<Object>> rows,
                                             String newName, String a, String b) {
        return dfToMap(toDf(cols, rows).colSub(newName, a, b));
    }

    public static Map<String, Object> colDiv(List<String> cols, List<List<Object>> rows,
                                             String newName, String a, String b) {
        return dfToMap(toDf(cols, rows).colDiv(newName, a, b));
    }

    public static Map<String, Object> colMulScalar(List<String> cols, List<List<Object>> rows,
                                                   String newName, String src, double k) {
        return dfToMap(toDf(cols, rows).colMul(newName, src, k));
    }

    public static Map<String, Object> assign(List<String> cols, List<List<Object>> rows,
                                             String newName, String value) {
        return dfToMap(toDf(cols, rows).assign(newName, i -> value));
    }

    public static Map<String, Object> idxmax(List<String> cols, List<List<Object>> rows, String col) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idx", toDf(cols, rows).idxmax(col));
        return r;
    }

    public static Map<String, Object> idxmin(List<String> cols, List<List<Object>> rows, String col) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idx", toDf(cols, rows).idxmin(col));
        return r;
    }

    public static Map<String, Object> duplicated(List<String> cols, List<List<Object>> rows,
                                                 List<String> subset, String keep) {
        String[] ss = subset == null ? null : subset.toArray(new String[0]);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mask", toDf(cols, rows).duplicated(ss, keep));
        return r;
    }

    public static Map<String, Object> sample(List<String> cols, List<List<Object>> rows,
                                             int n, boolean replace, long seed) {
        return dfToMap(toDf(cols, rows).sample(n, replace, seed));
    }

    public static Map<String, Object> isin(List<String> cols, List<List<Object>> rows, List<Object> values) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mask", toDf(cols, rows).isin(values.toArray()));
        return r;
    }

    public static Map<String, Object> where(List<String> cols, List<List<Object>> rows,
                                            List<Boolean> cond, Object other) {
        boolean[] c = toBoolArr(cond);
        return dfToMap(toDf(cols, rows).where(c, other));
    }

    public static Map<String, Object> mask(List<String> cols, List<List<Object>> rows,
                                           List<Boolean> cond, Object other) {
        boolean[] c = toBoolArr(cond);
        return dfToMap(toDf(cols, rows).mask(c, other));
    }

    public static Map<String, Object> colCmp(List<String> cols, List<List<Object>> rows,
                                             String col, String op, Object value) {
        // value 原样传给 compare(不预转 double);
        // DataFrame.cmp 的 Number 分支是"双整数走 long 精确比较",Long 大值不丢精度
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mask", toDf(cols, rows).compare(col, op, value).data());
        return r;
    }

    // ===== 阶段 B 统计扩展 =====

    public static Map<String, Object> cumsum(List<String> cols, List<List<Object>> rows, String col, String newCol) {
        return dfToMap(addColumn(toDf(cols, rows), toDf(cols, rows).colCumsum(col, newCol)));
    }

    public static Map<String, Object> diff(List<String> cols, List<List<Object>> rows, String col, int periods, String newCol) {
        return dfToMap(addColumn(toDf(cols, rows), toDf(cols, rows).colDiff(col, periods, newCol)));
    }

    public static Map<String, Object> pctChange(List<String> cols, List<List<Object>> rows, String col, int periods, String newCol) {
        return dfToMap(addColumn(toDf(cols, rows), toDf(cols, rows).colPctChange(col, periods, newCol)));
    }

    public static Map<String, Object> clip(List<String> cols, List<List<Object>> rows, String col, double lower, double upper, String newCol) {
        return dfToMap(addColumn(toDf(cols, rows), toDf(cols, rows).colClip(col, lower, upper, newCol)));
    }

    public static Map<String, Object> quantile(List<String> cols, List<List<Object>> rows, String col, double q) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value", toDf(cols, rows).colQuantile(col, q));
        return r;
    }

    public static Map<String, Object> rank(List<String> cols, List<List<Object>> rows, String col, String method, String newCol) {
        return dfToMap(addColumn(toDf(cols, rows), toDf(cols, rows).colRank(col, method, newCol)));
    }

    public static Map<String, Object> round(List<String> cols, List<List<Object>> rows, String col, int decimals, String newCol) {
        return dfToMap(addColumn(toDf(cols, rows), toDf(cols, rows).colRound(col, decimals, newCol)));
    }

    public static Map<String, Object> prod(List<String> cols, List<List<Object>> rows, String col) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value", toDf(cols, rows).colProd(col));
        return r;
    }

    // ===== 阶段 C 重塑合并扩展 =====

    public static Map<String, Object> pivot(List<String> cols, List<List<Object>> rows,
                                            String index, String columns, String values) {
        return dfToMap(toDf(cols, rows).pivot(index, columns, values));
    }

    public static Map<String, Object> pivotTable(List<String> cols, List<List<Object>> rows,
                                                 String index, String columns, String values, String aggFn) {
        return dfToMap(toDf(cols, rows).pivotTable(index, columns, values, aggFn));
    }

    public static Map<String, Object> explode(List<String> cols, List<List<Object>> rows, String col) {
        // 异常包装带算子名(裸 RuntimeException 在 Python 端难定位)
        try {
            return dfToMap(toDf(cols, rows).explode(col));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("explode 执行失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> mergeAsof(List<String> c1, List<List<Object>> r1,
                                                List<String> c2, List<List<Object>> r2, String on) {
        try {
            return dfToMap(toDf(c1, r1).mergeAsof(toDf(c2, r2), on));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("mergeAsof 执行失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> resample(List<String> cols, List<List<Object>> rows,
                                               String tsCol, String rule, String col, String fn) {
        DataFrame df = toDf(cols, rows);
        if ("sum".equals(fn)) return dfToMap(df.resample(tsCol, rule).sum(col));
        if ("mean".equals(fn)) return dfToMap(df.resample(tsCol, rule).mean(col));
        if ("count".equals(fn)) return dfToMap(df.resample(tsCol, rule).count(col));
        if ("first".equals(fn)) return dfToMap(df.resample(tsCol, rule).first());
        if ("last".equals(fn)) return dfToMap(df.resample(tsCol, rule).last());
        if ("min".equals(fn)) return dfToMap(df.resample(tsCol, rule).min(col));
        if ("max".equals(fn)) return dfToMap(df.resample(tsCol, rule).max(col));
        if ("std".equals(fn)) return dfToMap(df.resample(tsCol, rule).std());
        if ("median".equals(fn)) return dfToMap(df.resample(tsCol, rule).median());
        if ("var".equals(fn)) return dfToMap(df.resample(tsCol, rule).var());
        throw new IllegalArgumentException("未知 resample 聚合: " + fn);
    }

    // ======================== 统计/Window 扩展 ========================
    // ┌─ What : 把 jian 的统计 / Series.rolling/ewm/expanding / value_counts 暴露给 Python 桥
    // │  Why  : Python 桥根本目的不是"跑通即可",而是引入 jar 后用 pandas/numpy 当 oracle 做
    // │         详细逐值功能测试(AGENTS.md §0.5)。Window 21 方法、统计 corr/cov/skew/kurt
    // │         都必须经桥暴露,否则无从对照。
    // │  Who  : 由 tests-pbt/jian_client.py 经 JPype 调用
    // │  When : Python pandas 对照测试(test_pandas_diff.py d49-d54)启动时
    // │  Where: tests-pbt/harness/JianJpypeBridge.java
    // │  How  : 数据走向:Python (cols,rows) → toDf → df.getColumn(col) → Series.of(col)
    // │           → 按算子调 Series.rolling(w).<fn>() / ewm(a).<fn>() / DataFrameStats.corr(...)
    // │           → double[] 或 double 标量 → 包 LinkedHashMap({"values":double[]}/{"value":double})
    // │           → JPype 转回 Python list/float → 与 pandas 逐值比对。
    // │         关键变量:rolling/ewm 返 double[](窗口未满位为 NaN,经桥保持 nan);
    // │           valueCounts 返 Map<Object,Integer>(经桥转 Python dict)。
    // │         逻辑路线:每个入口按 fn 参数 switch 分发到对应聚合,未知 fn 抛 IllegalArgumentException。

    /** 单列统计标量:skewness/kurtosis/mad/sem/median/quantile。返回 {"value": double}。 */
    public static Map<String, Object> stat(List<String> cols, List<List<Object>> rows, String col, String fn) {
        Column c = toDf(cols, rows).getColumn(col);
        double v;
        switch (fn) {
            case "skewness": v = DataFrameStats.skewness(c); break;
            case "kurtosis": v = DataFrameStats.kurtosis(c); break;
            case "mad": v = DataFrameStats.mad(c); break;
            case "sem": v = DataFrameStats.sem(c); break;
            case "median": v = DataFrameStats.median(c); break;
            case "q25": v = DataFrameStats.quantile(c, 0.25); break;
            case "q75": v = DataFrameStats.quantile(c, 0.75); break;
            // 基础统计转发(sum/mean/min/max/std 等):
            // 白名单必须覆盖 jian 公共 API 全集,否则 pandas 对照测试无法经桥覆盖 colSum 等。
            case "sum": v = DataFrameStats.sum(c); break;
            case "mean": v = DataFrameStats.mean(c); break;
            case "min": v = DataFrameStats.min(c); break;
            case "max": v = DataFrameStats.max(c); break;
            case "std": v = DataFrameStats.std(c); break;
            case "count": v = DataFrameStats.count(c); break;
            case "prod": v = DataFrameStats.prod(c); break;
            // nunique 也入白名单(int 返 double),供 pandas 对照 d68
            case "nunique": v = DataFrameStats.nunique(c); break;
            default: throw new IllegalArgumentException("未知 stat fn: " + fn
                + "(支持:skewness/kurtosis/mad/sem/median/q25/q75/sum/mean/min/max/std/count/prod)");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v);
        return m;
    }

    /** 双列相关/协方差:corr(method)/cov。返回 {"value": double}。 */
    public static Map<String, Object> corr(List<String> cols, List<List<Object>> rows,
                                           String x, String y, String method) {
        DataFrame df = toDf(cols, rows);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", DataFrameStats.corr(df.getColumn(x), df.getColumn(y), method));
        return m;
    }
    public static Map<String, Object> cov(List<String> cols, List<List<Object>> rows, String x, String y) {
        DataFrame df = toDf(cols, rows);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", DataFrameStats.cov(df.getColumn(x), df.getColumn(y)));
        return m;
    }

    /** value_counts:返回 {"counts": Map<Object,Integer>}。 */
    public static Map<String, Object> valueCounts(List<String> cols, List<List<Object>> rows, String col) {
        Map<Object, Integer> vc = DataFrameArith.valueCounts(toDf(cols, rows).getColumn(col));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", vc);
        return m;
    }

    /** rolling 窗口聚合:mean/sum/std/min/max/count。返回 {"values": double[]}(窗口未满位为 NaN)。 */
    public static Map<String, Object> rolling(List<String> cols, List<List<Object>> rows,
                                              String col, int window, String fn) {
        // 伪代码:1. toDf + getColumn + Series.of 取 Series;2. s.rolling(window) 建滚动窗口;
        //         3. 按 fn switch 分发到 mean/sum/std/min/max/count;4. double[] 包 {"values":...}。
        //   窗口未满的前 (window-1) 位为 NaN(对齐 pandas rolling 默认 min_periods=window)。
        Series s = Series.of(toDf(cols, rows).getColumn(col));
        double[] r;
        switch (fn) {
            case "mean": r = s.rolling(window).mean(); break;
            case "sum": r = s.rolling(window).sum(); break;
            case "std": r = s.rolling(window).std(); break;
            case "min": r = s.rolling(window).min(); break;
            case "max": r = s.rolling(window).max(); break;
            case "count": r = s.rolling(window).count(); break;
            default: throw new IllegalArgumentException("未知 rolling fn: " + fn);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("values", r);
        return m;
    }

    /** EWM 指数加权:mean/var/std。返回 {"values": double[]}。 */
    public static Map<String, Object> ewm(List<String> cols, List<List<Object>> rows,
                                          String col, double alpha, String fn) {
        // 伪代码:1. Series.of 取 Series;2. s.ewm(alpha) 建指数加权(adjust=False,§10.16 #8 声明);
        //         3. 按 fn switch 分发到 mean/var/std;4. double[] 包 {"values":...}。
        //   注:jian EWM 固定 adjust=False(pandas 默认 adjust=True,是有意差异,§10.16 #8)。
        Series s = Series.of(toDf(cols, rows).getColumn(col));
        double[] r;
        switch (fn) {
            case "mean": r = s.ewm(alpha).mean(); break;
            case "var": r = s.ewm(alpha).var(); break;
            case "std": r = s.ewm(alpha).std(); break;
            default: throw new IllegalArgumentException("未知 ewm fn: " + fn);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("values", r);
        return m;
    }

    /** expanding 窗口:mean/sum/min/max。返回 {"values": double[]}。 */
    public static Map<String, Object> expanding(List<String> cols, List<List<Object>> rows, String col, String fn) {
        Series s = Series.of(toDf(cols, rows).getColumn(col));
        double[] r;
        switch (fn) {
            case "mean": r = s.expanding().mean(); break;
            case "sum": r = s.expanding().sum(); break;
            case "min": r = s.expanding().min(); break;
            case "max": r = s.expanding().max(); break;
            default: throw new IllegalArgumentException("未知 expanding fn: " + fn);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("values", r);
        return m;
    }

    private static boolean[] toBoolArr(List<Boolean> cond) {
        boolean[] c = new boolean[cond.size()];
        for (int i = 0; i < c.length; i++) c[i] = Boolean.TRUE.equals(cond.get(i));
        return c;
    }
}