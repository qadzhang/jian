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
// │           - groupKey 含 null 时归 NA_KEY 哨兵组(私有对象而非 "<NA>" 字符串,
// │             防与键列里真实的 "<NA>" 字面量合并;对外展示层把哨兵还原为 null)。
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

    /**
     * null 组键的<b>私有哨兵对象</b>(外界拿不到)。
     * 因为用字符串 "<NA>" 作 null 组键时,分组键列里恰有字面量 "<NA>" 会与 null 键
     * 合并成同一组(pandas 中两者是不同的键),所以用私有哨兵对象。仅作内层 map 键;
     * agg/size/iterGroups 等展示层把哨兵还原为 null 输出。
     */
    private static final Object NA_KEY = new Object();

    private final DataFrame parent;
    private final String[] byCols;
    private final Map<List<Object>, int[]> groups;  // 组键 → 组内行下标

    GroupBy(DataFrame parent, String[] byCols) {
        this.parent = parent;
        this.byCols = byCols;
        this.groups = buildGroups(parent, byCols);
    }

    /**
     * 构建分组:扫一遍行,按 groupKey(各 byCol 值的 List)分桶,LinkedHashMap 保序。
     *
     * <p><b>fast path</b>:单列 LONG/INT/DOUBLE key 走 {@link ColumnarHashMap}(零装箱开放寻址),
     * 实测 500 万行分组可从 ~3 秒降到 ~300ms(约 10 倍)。其它场景(多列 key、字符串 key、
     * 含 null)走通用 LinkedHashMap<List<Object>> 路径兜底,正确性优先。
     *
     * <p><b>NaN/缺失值分组语义(经测试固定,与 pandas 对齐)</b>:
     * <ul>
     *   <li>fast path 仅在 <code>nullCount==0</code> 时启用(见下方判定)。含 NaN/缺失值时
     *       一律 fall back 到 generic 路径,避免 NaN 的 Long.MIN_VALUE 哨兵与正常值冲突。</li>
     *   <li>generic 路径用 <code>df.get(r, col)</code> 取值:对 LONG/INT/STRING 列,缺失值返回 null
     *       → 归一为私有哨兵 {@code NA_KEY}("<NA>" 字符串会与键列真实字面量撞车,
     *       展示层还原为 null);对 DOUBLE 列,缺失值返回 Double.NaN 对象。
     *       由于 List.equals / hashCode 对 Double 元素调用 Double.equals(比较 bit pattern),
     *       Double.NaN 与 Double.NaN 的 equals 恒为 true → <b>所有 NaN 行归入同一组</b>(与
     *       pandas groupBy(dropna=True 默认行为一致)。</li>
     *   <li>注:pandas 的 groupBy 默认 dropna=True(缺失组被丢弃),需要保留用 dropna=False。
     *       jian 当前一律保留缺失组(等价 pandas dropna=False),若需 dropna 语义,链式调
     *       {@code df.filter(row -> !df.isNull(row, col)).groupBy(col)}。</li>
     * </ul>
     */
    private static Map<List<Object>, int[]> buildGroups(DataFrame df, String[] byCols) {
        // fast path 判定:单列 + 数值 dtype + 列里无 null(简化,避免处理 null key 的特殊语义;
        // 含 NaN/缺失值时 fall back generic,既正确又能复用"NaN 单独成组"语义,见方法 javadoc)
        if (byCols.length == 1) {
            Column col = df.getColumn(byCols[0]);
            DType dt = col.dtype();
            if (dt == DType.LONG || dt == DType.INT) {
                if (col.nullCount() == 0) return buildGroupsLong(df, byCols[0], DataFrameTypes.columnToLongArray(col));
            } else if (dt == DType.DOUBLE) {
                if (col.nullCount() == 0) return buildGroupsDouble(df, byCols[0], ((DoubleColumn) col).dataInPlace());
            }
        }
        // 通用路径(性能优化):
        // 因为每行 new ArrayList 作键 + computeIfAbsent 全元素 hash + Integer 装箱,
        // 1M 行多键分组会 18× 超线性(实测 1864ms vs pandas 454ms),所以三处改造:
        //  ①键改 Object[] 包装(HashCode 用 Arrays.hashCode),命中时**零分配**(复用 scratch 探针,
        //    仅新组才 clone 入 map);②行号收进紧凑 IntBuf(int[] 自增长,无 Integer 装箱);
        //  ③±0.0 归一与 null→哨兵组语义保持不变(哨兵为私有对象而非 "<NA>" 字符串)。
        int n = df.rowCount();
        Map<GroupKey, IntBuf> tmp = new LinkedHashMap<>();
        Object[] scratch = new Object[byCols.length];
        // 循环外取 Column[] 引用,消除每行每列 df.get(r, name) 的 columnIndex 字符串查找
        // (concat 行循环同类的热点;高基数 card≥1000 时收益最大,高低基数全受益)
        Column[] keyCols = new Column[byCols.length];
        for (int c = 0; c < byCols.length; c++) keyCols[c] = df.getColumn(byCols[c]);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < byCols.length; c++) {
                Object v = keyCols[c].get(r);
                // ±0.0 归一到 +0.0(numpy/pandas 中 0.0 == -0.0 同组)
                if (v instanceof Double d && d == 0.0) v = 0.0;
                // null 归私有哨兵组("<NA>" 字符串会与真实 "<NA>" 字面量撞车)
                scratch[c] = v == null ? NA_KEY : v;
            }
            GroupKey probe = new GroupKey(scratch);          // 探针键(不 clone,零拷贝)
            IntBuf buf = tmp.get(probe);
            if (buf == null) {
                tmp.put(new GroupKey(scratch.clone()), buf = new IntBuf());  // 新组才复制键
            }
            buf.add(r);
        }
        Map<List<Object>, int[]> out = new LinkedHashMap<>();
        for (Map.Entry<GroupKey, IntBuf> e : tmp.entrySet()) {
            out.put(java.util.Arrays.asList(e.getKey().vals), e.getValue().toArray());
        }
        return out;
    }

    /** 多键分组键(Object[] 值语义,Arrays.hashCode/equals;探针复用 scratch,入 map 才 clone)。 */
    private static final class GroupKey {
        final Object[] vals;
        GroupKey(Object[] vals) { this.vals = vals; }
        @Override public int hashCode() { return java.util.Arrays.hashCode(vals); }
        @Override public boolean equals(Object o) {
            return o instanceof GroupKey k && java.util.Arrays.equals(vals, k.vals);
        }
    }

    /** 行号紧凑缓冲(避免 List&lt;Integer&gt; 装箱)。 */
    private static final class IntBuf {
        private int[] a = new int[8];
        private int len;
        void add(int v) {
            if (len == a.length) a = java.util.Arrays.copyOf(a, len << 1);
            a[len++] = v;
        }
        int[] toArray() { return java.util.Arrays.copyOf(a, len); }
    }

    /**
     * long/int key 的 fast path:用 LinkedHashMap<Long, int[]>(裸 long key,不装箱不 new ArrayList)。
     * 瓶颈对比:原路径每行 new ArrayList<>(1) + 装箱 Long + List.hashCode;fast path 直接 putIfAbsent(long)。
     * 注:此处不需要 ColumnarHashMap(join 才需要双向查找,group 只需"按 key 累积下标")。
     * 因为 List.of 不可变(下游若对 key list 做 add/remove 会抛 UnsupportedOperationException),
     * 所以外包 key 用 ArrayList&lt;Object&gt;(与 generic 路径一致)。
     */
    private static Map<List<Object>, int[]> buildGroupsLong(DataFrame df, String byCol, long[] keys) {
        int n = keys.length;
        LinkedHashMap<Long, java.util.List<Integer>> tmp = new LinkedHashMap<>();
        for (int r = 0; r < n; r++) {
            tmp.computeIfAbsent(keys[r], kk -> new ArrayList<>()).add(r);
        }
        Map<List<Object>, int[]> out = new LinkedHashMap<>();
        for (java.util.Map.Entry<Long, java.util.List<Integer>> e : tmp.entrySet()) {
            java.util.List<Integer> idx = e.getValue();
            int[] arr = new int[idx.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = idx.get(i);
            // 用 ArrayList<Object> 包,与 generic 路径一致(行为统一)
            java.util.List<Object> keyList = new ArrayList<>(1);
            keyList.add(e.getKey());
            out.put(keyList, arr);
        }
        return out;
    }

    /** double key 的 fast path(逻辑同 long 版,直接用 Double 作 key)。 */
    private static Map<List<Object>, int[]> buildGroupsDouble(DataFrame df, String byCol, double[] keys) {
        int n = keys.length;
        LinkedHashMap<Double, java.util.List<Integer>> tmp = new LinkedHashMap<>();
        for (int r = 0; r < n; r++) {
            // ±0.0 归一到 +0.0(numpy/pandas 同组)
            double k = keys[r];
            if (k == 0.0) k = 0.0;
            tmp.computeIfAbsent(k, kk -> new ArrayList<>()).add(r);
        }
        Map<List<Object>, int[]> out = new LinkedHashMap<>();
        for (java.util.Map.Entry<Double, java.util.List<Integer>> e : tmp.entrySet()) {
            java.util.List<Integer> idx = e.getValue();
            int[] arr = new int[idx.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = idx.get(i);
            java.util.List<Object> keyList = new ArrayList<>(1);
            keyList.add(e.getKey());
            out.put(keyList, arr);
        }
        return out;
    }

    /** Column → long[](int 升位)。仅 LONG/INT 调用。 */

    /**
     * 组数。
     * @return int 分组数量,≥ 1(空表也会产生 1 个"空组"或 0 组,视构造而定)
     */
    public int groupCount() { return groups.size(); }

    /**
     * 遍历每组(对齐 pandas for name, sub_df in gb)。
     * <p>内层 map 键用 NA_KEY 哨兵表示缺失组,此处(展示层)把哨兵还原为 null。
     * @return Iterable&lt;GroupEntry&gt; 各组迭代器;每组含 key + 行下标数组
     */
    public Iterable<GroupEntry> iterGroups() {
        List<GroupEntry> out = new ArrayList<>(groups.size());
        for (Map.Entry<List<Object>, int[]> e : groups.entrySet()) {
            // 展示层:NA_KEY 哨兵 → null(缺失组标签对用户呈现为 null,§3.5 语义)
            List<Object> displayKey = new ArrayList<>(e.getKey());
            for (int i = 0; i < displayKey.size(); i++) {
                if (displayKey.get(i) == NA_KEY) displayKey.set(i, null);
            }
            out.add(new GroupEntry(displayKey, e.getValue()));
        }
        return out;
    }

    /**
     * 单条组记录。
     * @param key       List&lt;Object&gt; 组键(各 byCol 的取值,可能含 null)
     * @param rowIndices int[] 该组所有行的下标,每个 ∈ [0, nRows);长度 ≥ 1
     */
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
            // 写 byCols(NA_KEY 哨兵在展示层还原为 null)
            for (int c = 0; c < byCols.length; c++) {
                Object v = key.get(c);
                rows[rowIdx][c] = v == NA_KEY ? null : v;
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
                // agg 列:count/nunique → LONG;sum 对 STRING 列(拼接语义)与
                // first/last(返回原值)按源列 dtype;其余 → DOUBLE
                String aggFn = outCols.get(i).substring(outCols.get(i).lastIndexOf('_') + 1);
                String srcCol = outCols.get(i).substring(0, outCols.get(i).length() - aggFn.length() - 1);
                DType srcDt = parent.getColumn(srcCol).dtype();
                if (aggFn.equals("count") || aggFn.equals("nunique")) {
                    nameType[i * 2 + 1] = DType.LONG;
                } else if (aggFn.equals("first") || aggFn.equals("last")) {
                    // 因为 first/last 返回原值,对齐 pandas 保留 bool/datetime64
                    // (不把 BOOL/DATE/DATETIME 的 first/last toString 化成 "true"/"2026-01-01"),
                    // 所以一律保留源 dtype
                    nameType[i * 2 + 1] = srcDt;
                } else if (aggFn.equals("sum")) {
                    if (srcDt == DType.BOOL) {
                        nameType[i * 2 + 1] = DType.LONG;          // BOOL sum = true 计数
                    } else if (!srcDt.isNumeric()) {
                        nameType[i * 2 + 1] = DType.STRING;        // 字符串 sum=拼接
                    } else {
                        nameType[i * 2 + 1] = DType.DOUBLE;
                    }
                } else {
                    nameType[i * 2 + 1] = DType.DOUBLE;
                }
            }
        }
        return DataFrame.of(Schema.of(nameType), rows);
    }

    /**
     * 单列单聚合(快捷)。
     * @param col String 待聚合的列名,必须在 byCols 之外且存在;非 null
     * @param fn  String 聚合函数名:sum/mean/count/min/max/median/std/var/first/last/nunique;非 null
     * @return DataFrame 单列聚合结果(列名 col_fn)
     */
    public DataFrame agg(String col, String fn) {
        return agg(Map.of(col, fn));
    }

    /**
     * 在某列上算指定聚合,返回标量(供单值场景)。
     * @param c   Column 待聚合列
     * @param idx int[] 该组的行下标
     * @param fn  String 聚合函数名
     * @return Object 聚合结果:count/nunique 返回 Long;sum/mean/min/max/median/std/var 返回 Double;first/last 返回原值
     */
    private Object aggregate(Column c, int[] idx, String fn) {
        switch (fn) {
            case "count":
                int cnt = 0; for (int i : idx) if (!c.isNull(i)) cnt++; return (long) cnt;
            case "nunique":
                java.util.Set<Object> seen = new java.util.HashSet<>();
                // ±0.0 数值等价归一(pandas nunique([0,-0,1])=2)
                for (int i : idx) { if (!c.isNull(i)) seen.add(DataFrameStats.normUniqueKey(c.get(i))); }
                return (long) seen.size();
            // 因为对齐 pandas groupby.sum 对 str 的行为(拼接,实测 'xy'),所以字符串列 sum = 拼接;
            // 数值列照旧 double 求和。null/NaN 跳过(pandas skipna 默认)。
            // 因为 isNumeric() 不含 BOOL(落进字符串拼接分支会产出 "truefalsetrue",
            // 而 pandas 返回 True 计数),所以 BOOL 列 sum = true 计数(LONG)。
            case "sum": {
                if (c.dtype() == DType.BOOL) {
                    long trueCnt = 0;
                    for (int i : idx) if (!c.isNull(i) && (Boolean) c.get(i)) trueCnt++;
                    return trueCnt;
                }
                if (!c.dtype().isNumeric()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i : idx) if (!c.isNull(i)) sb.append(c.get(i));
                    return sb.toString();
                }
                double s = 0; for (int i : idx) if (!c.isNull(i)) s += c.getDouble(i); return s;
            }
            case "mean":
            // avg 是 SQL 标准聚合(与 mean 语义等价,pandas/SQL 一致),作为 mean 别名一并支持
            case "avg": { double s = 0; int n = 0; for (int i : idx) if (!c.isNull(i)) { s += c.getDouble(i); n++; } return n == 0 ? Double.NaN : s / n; }
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
                        + "(支持:count/nunique/sum/avg/mean/min/max/first/last/median/std/var)");
        }
    }

    /**
     * 组级过滤(对齐 pandas gb.filter):保留谓词为真的组(整组保留或丢弃)。
     * @param col    String 要判断的列名,必须存在;非 null
     * @param fn     String 聚合函数(如 "count");非 null
     * @param keepIf java.util.function.Predicate&lt;Double&gt; 谓词,接收每组的聚合值(double),返回是否保留该组;非 null
     * @return DataFrame 原表中仅保留 keepIf 为 true 的组对应行
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

    /**
     * 组大小(快捷,对齐 pandas gb.size)。
     * 单列 key 保留元素原始值与 dtype(调用方无需再按键类型分支处理);
     * 多列 key 无法保 dtype,统一 toString(STRING 列,文档声明)。
     * @return DataFrame 两列:key(组键)+ size(每组行数,LONG)
     */
    public DataFrame size() {
        Object[][] rows = new Object[groups.size()][2];
        int i = 0;
        boolean singleCol = true;
        boolean hasNullKey = false;
        for (Map.Entry<List<Object>, int[]> g : groups.entrySet()) {
            if (g.getKey().size() != 1) singleCol = false;
            Object k = g.getKey().size() == 1 ? g.getKey().get(0) : g.getKey().toString();
            // 因为 key 列混合(数值 key 与缺失组并存)时,缺失键若走字符串化会在
            // DataFrame.of 落 toNumber 时抛裸 NFE,所以存在缺失组时整体走 OBJECT +
            // 缺失组→null(对齐 pandas dropna=False 的 <NA> 组,缺失组在输出中表达为 null 行标签)。
            // null 键的内层标记为 NA_KEY 哨兵(字符串哨兵会与真实 "<NA>" 字面量撞车)
            if (k == NA_KEY) { hasNullKey = true; k = null; }
            rows[i][0] = k;
            rows[i][1] = (long) g.getValue().length;
            i++;
        }
        // 单列 key:按首个元素类型定 key 列 dtype(数值 key 不再 String 化);
        // 含缺失组(数值与 null 混合)时强制 OBJECT(承载数值+null 混合)
        DType keyDtype = DType.STRING;
        if (hasNullKey) keyDtype = DType.OBJECT;
        else if (singleCol && !groups.isEmpty()) {
            Object first = groups.keySet().iterator().next().get(0);
            if (first instanceof Integer) keyDtype = DType.INT;
            else if (first instanceof Long) keyDtype = DType.LONG;
            else if (first instanceof Double || first instanceof Float) keyDtype = DType.DOUBLE;
            else if (first instanceof Boolean) keyDtype = DType.BOOL;
        }
        return DataFrame.of(Schema.of("key", keyDtype, "size", DType.LONG), rows);
    }

    /**
     * 组级变换广播回原行序(对齐 pandas gb.transform)。
     *
     * <p>对每组的指定列算聚合值,然后把聚合值广播回该组的每一行 → 返回与原 DataFrame 等长的列。
     * 用途:如计算每人与所在部门平均工资的差。
     *
     * @param col String 要聚合的数值列名,必须存在;非 null
     * @param fn  String 聚合函数(sum/mean/min/max/median/std/var/count);非 null
     * @return double[] 长度 == 原 DataFrame 行数;每组的所有行填该组的聚合值;聚合失败/缺失行填 NaN
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
     * @param newColName String 新列名,非 null
     * @param col        String 要聚合的列名,非 null
     * @param fn         String 聚合函数,非 null
     * @return DataFrame 在原表基础上 assign 新列 newColName(聚合广播值;缺失为 null)
     */
    public DataFrame transformAsColumn(String newColName, String col, String fn) {
        double[] vals = transform(col, fn);
        return parent.assign(newColName, r -> Double.isNaN(vals[r]) ? null : vals[r]);
    }
}
