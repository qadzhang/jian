import jian.core.*;
import java.io.*;
import java.util.*;

// ┌─ What : JianPbtBridge —— Python Hypothesis 跨语言 PBT 的 Java 端桥梁
// │  Why  : 让 Python Hypothesis 能驱动 jian-core 跑性质测试,无需 JNI/桥接库
// │         Hypothesis 是 Python PBT 事实标准(NumPy/pandas/PyTorch 同款),shrinking 能力强
// │         与 jian-core 的 jqwik 测试形成"双语言交叉 PBT"
// │  Who  : 由 tests-pbt/harness/jian_client.py 通过 subprocess 启动
// │  When : Python 测试启动时开一个 java 进程,反复 stdin/stdout 一来一回
// │  Where: tests-pbt/harness/JianPbtBridge.java(单文件,不进 jian-core jar)
// │  Note : LONG-null→0L 是协议约定 —— jian-core 的 LONG dtype 不存 null,
// │         Column.getLong(i) 用 Long.MIN_VALUE 作 missing 哨兵;bridge 沿用此约定
// │         (LONG 列的 null 元素在反序列化时转 0L,与 Long.MIN_VALUE 同为"非数字"哨兵)。
// │         需要在 PBT 中区分 null 的场景,请用 DOUBLE 列(null → NaN,NaN 是 IEEE 754
// │         原生缺失值,jian 的 DOUBLE dtype 全程保留 NaN 不丢失)。
// │         这是契约层约定,不是 bug;改 NaN 会把 LONG 列升格 DOUBLE,破坏 200+ 既有 PBT。
// │  How  : 数据走向:
// │           Python 发一行 JSON {"op":"sort","df":[...],"args":{...}}
// │              → stdin → Java 解析 JSON → 调 jian 算子 → 结果序列化为 JSON → stdout
// │         关键:每行一个 JSON 请求,一个 JSON 响应,行分隔(简单可靠,易调试)
// │         逻辑路线:
// │           路径 A(构造 df)→ 用 DataFrame.ofColumnArrays 直接装 long[]/double[];
// │           路径 B(算子)→ sort/filter/head/tail/merge/groupBy.agg/concat/dropDuplicates;
// │           路径 C(出错)→ {"ok":false,"error":"..."} 不让进程退出。
public class JianPbtBridge {

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"));
        String line;
        while ((line = in.readLine()) != null) {
            String resp;
            try {
                resp = handle(line);
            } catch (Throwable t) {
                resp = "{\"ok\":false,\"error\":" + jsonStr(t.getClass().getSimpleName() + ": " + t.getMessage()) + "}";
            }
            out.write(resp);
            out.write("\n");
            out.flush();
        }
    }

    private static String handle(String json) throws Exception {
        Map<String, Object> req = parseJson(json);
        String op = (String) req.get("op");
        Object result;
        switch (op) {
            case "ping": result = "pong"; break;
            case "sort": result = opSort(req); break;
            case "filter": result = opFilter(req); break;
            case "head": result = opHead(req); break;
            case "tail": result = opTail(req); break;
            case "merge": result = opMerge(req); break;
            case "groupBy": result = opGroupBy(req); break;
            case "concat": result = opConcat(req); break;
            case "dropDuplicates": result = opDropDuplicates(req); break;
            case "fillna": result = opFillna(req); break;
            case "dropna": result = opDropna(req); break;
            case "ffill": result = opFfill(req); break;
            case "astype": result = opAstype(req); break;
            case "select": result = opSelect(req); break;
            case "drop": result = opDrop(req); break;
            case "slice": result = opSlice(req); break;
            case "nlargest": result = opNlargest(req); break;
            case "nsmallest": result = opNsmallest(req); break;
            case "colAdd": result = opColAdd(req); break;
            case "colSub": result = opColSub(req); break;
            case "colDiv": result = opColDiv(req); break;
            case "colMulScalar": result = opColMulScalar(req); break;
            case "assign": result = opAssign(req); break;
            case "idxmax": result = opIdxmax(req); break;
            case "idxmin": result = opIdxmin(req); break;
            case "duplicated": result = opDuplicated(req); break;
            case "sample": result = opSample(req); break;
            case "isin": result = opIsin(req); break;
            case "where": result = opWhere(req); break;
            case "mask": result = opMask(req); break;
            // 列比较(对齐 DataFrame.compare):返回 boolean mask,用于差分测试对照 pandas (df[col] op k)
            case "colCmp": result = opColCmp(req); break;
            // 阶段 B 统计扩展
            case "cumsum": result = opCumsum(req); break;
            case "diff": result = opDiff(req); break;
            case "pct_change": result = opPctChange(req); break;
            case "clip": result = opClip(req); break;
            case "quantile": result = opQuantile(req); break;
            case "rank": result = opRank(req); break;
            case "round": result = opRound(req); break;
            case "prod": result = opProd(req); break;
            // 阶段 C 重塑合并扩展
            case "pivot": result = opPivot(req); break;
            case "explode": result = opExplode(req); break;
            case "mergeAsof": result = opMergeAsof(req); break;
            default: throw new IllegalArgumentException("未知 op: " + op);
        }
        return "{\"ok\":true,\"result\":" + toJson(result) + "}";
    }

    private static Map<String, Object> opSort(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        boolean asc = Boolean.TRUE.equals(args.get("asc"));
        return dfToMap(df.sortBy(col, asc));
    }

    private static Map<String, Object> opFilter(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String expr = (String) args.get("expr");
        return dfToMap(df.query(expr));
    }

    private static Map<String, Object> opHead(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        int n = ((Number) args.get("n")).intValue();
        return dfToMap(df.head(n));
    }

    private static Map<String, Object> opTail(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        int n = ((Number) args.get("n")).intValue();
        return dfToMap(df.tail(n));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> opMerge(Map<String, Object> req) throws Exception {
        List<Object> dfs = (List<Object>) req.get("dfs");
        Map<String, Object> args = asMap(req.get("args"));
        String how = (String) args.get("how");
        String on = (String) args.get("on");
        DataFrame left = toDf(dfs.get(0));
        DataFrame right = toDf(dfs.get(1));
        return dfToMap(left.merge(right, how, on));
    }

    private static Map<String, Object> opGroupBy(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String byCol = (String) args.get("by");
        String aggCol = (String) args.get("col");
        String fn = (String) args.get("fn");
        Map<String, String> spec = new HashMap<>();
        spec.put(aggCol, fn);
        return dfToMap(df.groupBy(byCol).agg(spec));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> opConcat(Map<String, Object> req) throws Exception {
        List<Object> dfsRaw = (List<Object>) req.get("dfs");
        Map<String, Object> args = asMap(req.get("args"));
        int axis = ((Number) args.get("axis")).intValue();
        List<DataFrame> dfs = new ArrayList<>();
        for (Object d : dfsRaw) dfs.add(toDf(d));
        return dfToMap(DataFrame.concat(dfs, axis));
    }

    private static Map<String, Object> opDropDuplicates(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        @SuppressWarnings("unchecked")
        List<String> subset = (List<String>) args.get("subset");
        return dfToMap(df.dropDuplicates(subset.toArray(new String[0]), "first"));
    }

    private static Map<String, Object> opFillna(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        Object value = args.get("value");
        return dfToMap(df.fillna(value));
    }

    private static Map<String, Object> opDropna(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        return dfToMap(df.dropna());
    }

    private static Map<String, Object> opFfill(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        return dfToMap(df.ffill());
    }

    private static Map<String, Object> opAstype(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        String targetStr = (String) args.get("target");
        jian.core.DType target = jian.core.DType.valueOf(targetStr);
        return dfToMap(df.astype(col, target));
    }

    private static Map<String, Object> opSelect(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) args.get("cols");
        return dfToMap(df.select(cols.toArray(new String[0])));
    }

    private static Map<String, Object> opDrop(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) args.get("cols");
        return dfToMap(df.drop(cols.toArray(new String[0])));
    }

    private static Map<String, Object> opSlice(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        int a = ((Number) args.get("a")).intValue();
        int b = ((Number) args.get("b")).intValue();
        int lo = Math.min(a, b);
        int hi = Math.min(Math.max(a, b), df.rowCount());
        if (lo >= hi) return dfToMap(df.slice(0, 0));   // 空区间返回空表
        return dfToMap(df.slice(lo, hi));
    }

    private static Map<String, Object> opNlargest(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        int n = ((Number) args.get("n")).intValue();
        String col = (String) args.get("col");
        return dfToMap(df.nlargest(n, col));
    }

    private static Map<String, Object> opNsmallest(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        int n = ((Number) args.get("n")).intValue();
        String col = (String) args.get("col");
        return dfToMap(df.nsmallest(n, col));
    }

    private static Map<String, Object> opColAdd(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String newName = (String) args.get("newName");
        String a = (String) args.get("a");
        String b = (String) args.get("b");
        return dfToMap(df.colAdd(newName, a, b));
    }

    /** 列间减(对齐 colAdd 参数结构):df.colSub(newName, a, b)。 */
    private static Map<String, Object> opColSub(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String newName = (String) args.get("newName");
        String a = (String) args.get("a");
        String b = (String) args.get("b");
        return dfToMap(df.colSub(newName, a, b));
    }

    /** 列间除(对齐 colAdd 参数结构):df.colDiv(newName, a, b)。 */
    private static Map<String, Object> opColDiv(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String newName = (String) args.get("newName");
        String a = (String) args.get("a");
        String b = (String) args.get("b");
        return dfToMap(df.colDiv(newName, a, b));
    }

    private static Map<String, Object> opColMulScalar(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String newName = (String) args.get("newName");
        String src = (String) args.get("src");
        double k = ((Number) args.get("k")).doubleValue();
        return dfToMap(df.colMul(newName, src, k));
    }

    private static Map<String, Object> opAssign(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String newName = (String) args.get("newName");
        String constantValue = (String) args.get("value");   // 简化:用常量值
        return dfToMap(df.assign(newName, i -> constantValue));
    }

    // ===== 阶段 A 高频实用扩展(2026-08-09):idxmax/idxmin/duplicated/sample/isin/where/mask =====

    private static Map<String, Object> opIdxmax(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        int idx = df.idxmax(col);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idx", idx);
        return r;
    }

    private static Map<String, Object> opIdxmin(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        int idx = df.idxmin(col);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idx", idx);
        return r;
    }

    private static Map<String, Object> opDuplicated(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        Object subsetObj = args.get("subset");
        String[] subset = subsetObj == null ? null
            : ((java.util.List<?>) subsetObj).stream().map(Object::toString).toArray(String[]::new);
        String keep = (String) args.getOrDefault("keep", "first");
        boolean[] mask = df.duplicated(subset, keep);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mask", mask);
        return r;
    }

    private static Map<String, Object> opSample(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        int n = ((Number) args.get("n")).intValue();
        boolean replace = Boolean.parseBoolean(args.getOrDefault("replace", "false").toString());
        long seed = ((Number) args.get("seed")).longValue();
        return dfToMap(df.sample(n, replace, seed));
    }

    private static Map<String, Object> opIsin(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        Object[] values = ((java.util.List<?>) args.get("values")).toArray();
        boolean[] mask = df.isin(values);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mask", mask);
        return r;
    }

    private static Map<String, Object> opWhere(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        // cond 用 List<Boolean> 传
        java.util.List<?> condList = (java.util.List<?>) args.get("cond");
        boolean[] cond = new boolean[condList.size()];
        for (int i = 0; i < cond.length; i++) cond[i] = Boolean.parseBoolean(condList.get(i).toString());
        Object other = args.get("other");
        return dfToMap(df.where(cond, other));
    }

    private static Map<String, Object> opMask(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        java.util.List<?> condList = (java.util.List<?>) args.get("cond");
        boolean[] cond = new boolean[condList.size()];
        for (int i = 0; i < cond.length; i++) cond[i] = Boolean.parseBoolean(condList.get(i).toString());
        Object other = args.get("other");
        return dfToMap(df.mask(cond, other));
    }

    /** 列比较 op(对齐 DataFrame.compare(colName, op, value))。
     *  专门用于差分测试:直接调 jian 的 compare 拿 mask,与 pandas (df[col] op k) 对照。
     *  比"用 where(mask) 间接验证 lt 语义"更直接 —— compare() 的代码路径被真覆盖。
     *  参数:args={"col":"v","op":"<","value":k};返回 {"mask":[...]} */
    private static Map<String, Object> opColCmp(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        String op = (String) args.get("op");
        Object value = args.get("value");
        // Double 经 JSON 反序列化可能仍是 Double;long 类显式转 double 让 compare 数值比较一致
        if (value instanceof Number) value = ((Number) value).doubleValue();
        jian.core.BoolColumn bc = df.compare(col, op, value);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mask", bc.data());
        return r;
    }

    // ===== 阶段 B 统计扩展 op(2026-08-09)=====

    private static Map<String, Object> opCumsum(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        String newCol = (String) args.getOrDefault("newCol", col + "_cumsum");
        // cumsum 返回新列,组装成新 df:原列 + 新列
        jian.core.DoubleColumn newColObj = df.colCumsum(col, newCol);
        return dfToMap(addColumn(df, newColObj));
    }

    private static Map<String, Object> opDiff(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        int periods = ((Number) args.get("periods")).intValue();
        String newCol = (String) args.getOrDefault("newCol", col + "_diff");
        jian.core.DoubleColumn newColObj = df.colDiff(col, periods, newCol);
        return dfToMap(addColumn(df, newColObj));
    }

    private static Map<String, Object> opPctChange(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        int periods = ((Number) args.get("periods")).intValue();
        String newCol = (String) args.getOrDefault("newCol", col + "_pct");
        jian.core.DoubleColumn newColObj = df.colPctChange(col, periods, newCol);
        return dfToMap(addColumn(df, newColObj));
    }

    private static Map<String, Object> opClip(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        double lower = ((Number) args.get("lower")).doubleValue();
        double upper = ((Number) args.get("upper")).doubleValue();
        String newCol = (String) args.getOrDefault("newCol", col + "_clip");
        jian.core.DoubleColumn newColObj = df.colClip(col, lower, upper, newCol);
        return dfToMap(addColumn(df, newColObj));
    }

    private static Map<String, Object> opQuantile(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        double q = ((Number) args.get("q")).doubleValue();
        double v = df.colQuantile(col, q);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value", v);
        return r;
    }

    private static Map<String, Object> opRank(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        String method = (String) args.getOrDefault("method", "average");
        String newCol = (String) args.getOrDefault("newCol", col + "_rank");
        jian.core.DoubleColumn newColObj = df.colRank(col, method, newCol);
        return dfToMap(addColumn(df, newColObj));
    }

    private static Map<String, Object> opRound(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        int decimals = ((Number) args.get("decimals")).intValue();
        String newCol = (String) args.getOrDefault("newCol", col + "_round");
        jian.core.DoubleColumn newColObj = df.colRound(col, decimals, newCol);
        return dfToMap(addColumn(df, newColObj));
    }

    private static Map<String, Object> opProd(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        double v = df.colProd(col);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value", v);
        return r;
    }

    /** 把新列追加到 df 末尾,返回新 df(用于返回 colXxx 新列给 Python)。 */
    private static DataFrame addColumn(DataFrame df, Column newCol) {
        java.util.List<Column> cols = new java.util.ArrayList<>(df.columnCount() + 1);
        for (String c : df.columnNames()) cols.add(df.getColumn(c));
        cols.add(newCol);
        return DataFrame.ofColumnsDirect(cols);
    }

    // ===== 阶段 C 重塑合并 op(2026-08-09)=====

    private static Map<String, Object> opPivot(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String index = (String) args.get("index");
        String columns = (String) args.get("columns");
        String values = (String) args.get("values");
        return dfToMap(df.pivot(index, columns, values));
    }

    private static Map<String, Object> opExplode(Map<String, Object> req) throws Exception {
        DataFrame df = toDf(req.get("df"));
        Map<String, Object> args = asMap(req.get("args"));
        String col = (String) args.get("col");
        return dfToMap(df.explode(col));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> opMergeAsof(Map<String, Object> req) throws Exception {
        java.util.List<Object> dfs = (java.util.List<Object>) req.get("dfs");
        Map<String, Object> args = asMap(req.get("args"));
        String on = (String) args.get("on");
        DataFrame left = toDf(dfs.get(0));
        DataFrame right = toDf(dfs.get(1));
        return dfToMap(left.mergeAsof(right, on));
    }

    // ======================== DataFrame ↔ JSON ========================

    /** 把 Map(json) 转成 DataFrame。约定 schema:{"columns":["id","v"],"rows":[[1,10.0],...]}。
     *
     * <p>类型推断规则(2026-08 与 AI agent1 共识):
     * <ul>
     *   <li><b>显式 schema 优先</b>:df["dtypes"](如 ["LONG","DOUBLE"])可信度最高,有则按它构造列。</li>
     *   <li><b>非空 frame 无 dtypes</b>:全行扫描取最宽类型(修复历史 bug:之前只看首行 →
     *       首行 null 时全列误降级 Object[])。null 不参与推断。</li>
     *   <li><b>空 frame 无 dtypes</b>:为兼容既有协议(200+ PBT 测试依赖),回退到旧版约定 ——
     *       第 0 列(id 类)默认 LONG,其余列默认 DOUBLE。新测试若需其他 schema,显式传 dtypes 即可。</li>
     *   <li><b>Long 列的 null→0L</b>:这是 jian-core LONG dtype 的协议(LONG 不存 null,
     *       Column.getLong 用 Long.MIN_VALUE 作 missing 哨兵)。需要区分 null 的场景请用 DOUBLE 列
     *       (null → NaN,IEEE 754 原生缺失值,jian DOUBLE dtype 全程保留 NaN)。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static DataFrame toDf(Object obj) {
        Map<String, Object> m = asMap(obj);
        List<String> cols = (List<String>) m.get("columns");
        List<Object> rows = (List<Object>) m.get("rows");
        int nCols = cols.size();

        // 显式 schema 优先:dtypes 字段(["LONG","DOUBLE",...] 或 ["long","double",...])
        List<String> dtypes = (List<String>) m.get("dtypes");
        if (dtypes != null) {
            if (dtypes.size() != nCols) {
                throw new IllegalArgumentException(
                    "dtypes 长度(" + dtypes.size() + ")必须 == columns 长度(" + nCols + ")");
            }
        }

        if (rows == null || rows.isEmpty()) {
            // 空 frame:有 dtypes 用 dtypes;无 dtypes 回退旧版兼容约定(第0列 LONG,其余 DOUBLE)
            Object[] emptyArrays = new Object[nCols];
            for (int c = 0; c < nCols; c++) {
                String kind = dtypes != null ? dtypes.get(c).toUpperCase() : (c == 0 ? "LONG" : "DOUBLE");
                emptyArrays[c] = emptyArrayOfType(kind);
            }
            return DataFrame.ofColumnArrays(cols, emptyArrays);
        }

        int n = rows.size();
        Object[] colArrays = new Object[nCols];
        for (int c = 0; c < nCols; c++) {
            String kind = dtypes != null ? dtypes.get(c).toUpperCase() : inferColumnType(rows, c);
            switch (kind) {
                case "LONG": {
                    long[] arr = new long[n];
                    for (int r = 0; r < n; r++) {
                        Object v = ((List<Object>) rows.get(r)).get(c);
                        arr[r] = v == null ? 0L : ((Number) v).longValue();
                    }
                    colArrays[c] = arr;
                    break;
                }
                case "DOUBLE": {
                    double[] arr = new double[n];
                    for (int r = 0; r < n; r++) {
                        Object v = ((List<Object>) rows.get(r)).get(c);
                        arr[r] = v == null ? Double.NaN : ((Number) v).doubleValue();
                    }
                    colArrays[c] = arr;
                    break;
                }
                default: {
                    Object[] arr = new Object[n];
                    for (int r = 0; r < n; r++) arr[r] = ((List<Object>) rows.get(r)).get(c);
                    colArrays[c] = arr;
                    break;
                }
            }
        }
        return DataFrame.ofColumnArrays(cols, colArrays);
    }

    /** 全行扫描列 c 的最宽类型(修复"只看首行"bug)。
     *  优先级:出现 String/Boolean→OBJECT;否则出现 Double/Float→DOUBLE;否则 LONG。
     *  全 null 列 → DOUBLE(与 pandas 一致:pd.DataFrame({'v':[None]}) 推断为 float64,
     *  因为 float 能原生表达缺失 NaN,而 LONG 不存 null)。 */
    @SuppressWarnings("unchecked")
    private static String inferColumnType(List<Object> rows, int c) {
        boolean sawAnyValue = false;
        boolean sawDouble = false;
        for (Object row : rows) {
            Object v = ((List<Object>) row).get(c);
            if (v == null) continue;            // null 不参与推断(历史 bug:首行 null 即误降级 OBJECT)
            sawAnyValue = true;
            if (v instanceof String) return "OBJECT";
            if (v instanceof Double || v instanceof Float) sawDouble = true;
            else if (v instanceof Boolean) return "OBJECT";
        }
        // 全 null 列 → DOUBLE(语义:含缺失的列该用能存缺失的 dtype;LONG 不存 null,DOUBLE 存 NaN)
        if (!sawAnyValue) return "DOUBLE";
        return sawDouble ? "DOUBLE" : "LONG";
    }

    /** 按 dtype 名构造空数组(空 frame 用)。 */
    private static Object emptyArrayOfType(String dtype) {
        switch (dtype.toUpperCase()) {
            case "LONG": case "INT": return new long[0];
            case "DOUBLE": case "FLOAT": return new double[0];
            case "BOOL": case "BOOLEAN": return new boolean[0];
            default: return new Object[0];
        }
    }

    /** DataFrame → Map(json)。{"columns":[...],"rows":[[...],...]} */
    private static Map<String, Object> dfToMap(DataFrame df) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", df.columnNames());
        List<List<Object>> rows = new ArrayList<>();
        int n = df.rowCount();
        int nCols = df.columnCount();
        for (int r = 0; r < n; r++) {
            List<Object> row = new ArrayList<>(nCols);
            for (int c = 0; c < nCols; c++) row.add(df.get(r, c));
            rows.add(row);
        }
        m.put("rows", rows);
        m.put("rowCount", n);
        return m;
    }

    // ======================== 极简 JSON 解析/序列化 ========================
    // 协议字段固定,只含 Map/List/String/Number/Boolean/null,
    // 手写极简解析器避免引入外部依赖(如 Jackson)。

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> parseJson(String s) {
        Parser p = new Parser(s);
        return (Map<String, Object>) p.parseValue();
    }

    static class Parser {
        final String s; int i = 0;
        Parser(String s) {
            this.s = s;
            // 跳过 UTF-8 BOM(若有)——AI agent 1 审查发现没处理,可能导致首字符乱码
            if (s.length() >= 1 && s.charAt(0) == '\uFEFF') i = 1;
        }
        Object parseValue() {
            skipWs();
            char c = s.charAt(i);
            if (c == '{') return parseObj();
            if (c == '[') return parseArr();
            if (c == '"') return parseStr();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') { i += 4; return null; }
            return parseNum();
        }
        Map<String, Object> parseObj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWs();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                String k = parseStr();
                skipWs(); i++;
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                char c = s.charAt(i++);
                if (c == ',') continue;
                if (c == '}') break;
            }
            return m;
        }
        List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWs();
            if (s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ',') continue;
                if (c == ']') break;
            }
            return list;
        }
        String parseStr() {
            StringBuilder sb = new StringBuilder();
            i++;  // skip 起始 "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();   // 正常闭合
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("JSON 字符串结尾的反斜杠无跟随字符");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;   // 补:\r
                        case 'b': sb.append('\b'); break;   // 补:\b
                        case 'f': sb.append('\f'); break;   // 补:\f
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            // 补:反斜杠+u+4 位十六进制(RFC 8259 标准 unicode 转义)
                            // 关键陷阱:Java 词法分析前处理 backslash-u 转义,即使在本注释里也会被解析,
                            // 故此处绝不能写出 "反斜杠 u X X X X" 这种字面字符序列
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("unicode 转义不完整");
                            }
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            // 非法转义:RFC 8259 规定只允许上述字符。宽松处理:原样保留
                            sb.append(e);
                    }
                } else sb.append(c);
            }
            // 循环退出但没找到收尾 " —— 未闭合字符串
            throw new IllegalArgumentException("JSON 字符串未闭合(缺少收尾 \")");
        }
        Boolean parseBool() {
            if (s.charAt(i) == 't') { i += 4; return true; }
            i += 5; return false;
        }
        Number parseNum() {
            skipWs();
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            String num = s.substring(start, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }
        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    private static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { sb.append(jsonStr((String) o)); return; }
        // 修复:Double/Float 的 NaN/Infinity 用 null 输出(标准 JSON 兼容,RFC 8259 不允许 NaN/Infinity)
        if (o instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d);
            return;
        }
        if (o instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) sb.append("null");
            else sb.append(f);
            return;
        }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        // 2026-08-09 阶段 A 修复:裸 primitive/Object 数组(如 boolean[] mask)单独处理,
        // 否则 fallback 到 o.toString() 会输出 "[Z@154e4367" 内部引用,Python 解析成乱码字符。
        if (o instanceof boolean[] arr) {
            sb.append("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i]);
            }
            sb.append("]");
            return;
        }
        if (o instanceof int[] arr) {
            sb.append("[");
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(","); sb.append(arr[i]); }
            sb.append("]");
            return;
        }
        if (o instanceof long[] arr) {
            sb.append("[");
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(","); sb.append(arr[i]); }
            sb.append("]");
            return;
        }
        if (o instanceof double[] arr) {
            sb.append("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                // NaN/Infinity → null(标准 JSON 不允许)
                if (Double.isNaN(arr[i]) || Double.isInfinite(arr[i])) sb.append("null");
                else sb.append(arr[i]);
            }
            sb.append("]");
            return;
        }
        if (o instanceof Object[] arr) {
            sb.append("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                writeJson(arr[i], sb);
            }
            sb.append("]");
            return;
        }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(jsonStr(e.getKey())).append(":");
                writeJson(e.getValue(), sb);
            }
            sb.append("}");
            return;
        }
        if (o instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object x : (List<Object>) o) {
                if (!first) sb.append(",");
                first = false;
                writeJson(x, sb);
            }
            sb.append("]");
            return;
        }
        sb.append(jsonStr(o.toString()));
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
