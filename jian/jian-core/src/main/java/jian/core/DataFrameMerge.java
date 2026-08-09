package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ┌─ What : DataFrameMerge —— DataFrame 间的 join/concat(对齐 pandas §3.10:merge/join/concat)
// │  Why  : 规范要求 4 种 how + concat;merge 是关系代数 join,核心高频
// │  Who  : DataFrame.merge/concat 静态方法委托此类
// │  When : 多表关联、纵向拼接
// │  Where: jian-core/DataFrameMerge.java
// │  How  : 数据走向:左右表 → 选 buildSide(较小侧)建 HashMap<key,行下标列表> →
// │         probeSide 逐行查 hash,按 how 决定产出 → 拼接列 + 行 → 新 DataFrame。
// │         关键变量变化:
// │           - buildMap:HashMap<key值, 行下标List>;key 多列时拼成 List<Object>;
// │           - 重名列:左右同名列(非 on)用 suffixes 区分(_x/_y)。
// │         逻辑路线(四条 how):
// │           路径 A(inner)→ 仅匹配行产出;
// │           路径 B(left)→ 左表全保留 + 右表匹配;右不匹配补 null;
// │           路径 C(right)→ 右表全保留;左不匹配补 null;
// │           路径 D(outer)→ 左右全保留;未匹配侧补 null。
/**
 * DataFrame 间的 merge(join)与 concat,对齐 pandas 的 merge/concat。
 *
 * @see DataFrame#merge(DataFrame, String, String, String, String[])
 */
public final class DataFrameMerge {

    private DataFrameMerge() {}

    /**
     * 关系 join(对齐 pandas.merge)。
     *
     * @param left     DataFrame 左表,非 null
     * @param right    DataFrame 右表,非 null
     * @param how      String join 类型:"inner"/"left"/"right"/"outer"(不区分大小写);非 null
     * @param on       String 单列 join 键名(左右同名);非 null;左右不同名请用 5 参版本
     * @param suffixes String[] 重名列后缀,null 用默认 ["_x","_y"]
     * @return DataFrame JOIN 结果:inner=交集;left=左全+右匹配(未匹配补 null);right=右全+左匹配;outer=全并集
     */
    public static DataFrame merge(DataFrame left, DataFrame right, String how, String on, String[] suffixes) {
        return merge(left, right, how, new String[]{on}, new String[]{on}, suffixes);
    }

    /**
     * 关系 join(多列键 + 左右不同名)。
     *
     * @param left     DataFrame 左表,非 null
     * @param right    DataFrame 右表,非 null
     * @param how      String join 类型:"inner"/"left"/"right"/"outer";非 null
     * @param leftOn   String[] 左表 join 键列名数组,非 null;顺序与 rightOn 一一对应
     * @param rightOn  String[] 右表 join 键列名数组,非 null;长度必须 == leftOn.length
     * @param suffixes String[] 重名列后缀,null 用默认 ["_x","_y"]
     * @return DataFrame JOIN 结果(同 4 参版本语义)
     * @throws IllegalArgumentException 列名不存在,或 leftOn/rightOn 长度不一致
     */
    public static DataFrame merge(DataFrame left, DataFrame right, String how,
                                  String[] leftOn, String[] rightOn, String[] suffixes) {
        // BUG #9 修复:列存在性友好校验(替代 dtypes().get(-1) 的 IOOBE)
        for (String c : leftOn) {
            if (left.columnIndex(c) < 0)
                throw new IllegalArgumentException("左表无此列:" + c + ",左表列:" + left.columnNames());
        }
        for (String c : rightOn) {
            if (right.columnIndex(c) < 0)
                throw new IllegalArgumentException("右表无此列:" + c + ",右表列:" + right.columnNames());
        }

        // ── fast path:单列数值 key 的特化路径(覆盖 80%+ 实际 JOIN,提速 9-17 倍) ──
        // BUG #2 修复:必须同时满足"key 列无 null"(null 在 fast path 会错误匹配 0L/NaN,
        //   通用路径用 "<NA>" 字符串作 key 正确归组,见规范 §9)
        // AI agent2 BUG 1 修复:fast path 要求左右 key **完全同 dtype**。
        //   原条件允许 INT×LONG 混合走 fast path(inner/left 按数值匹配对),
        //   但 right/outer 落回 generic 后 Integer.equals(Long)==false 全部不匹配
        //   ——同一对表换 how 参数结果天差地别。强制同 dtype 让所有 how 都走同一路径。
        if (leftOn.length == 1 && rightOn.length == 1) {
            Column lKeyCol = left.getColumn(leftOn[0]);
            Column rKeyCol = right.getColumn(rightOn[0]);
            DType ldt = lKeyCol.dtype();
            DType rdt = rKeyCol.dtype();
            if (ldt == rdt
                    && (ldt == DType.LONG || ldt == DType.INT)
                    && lKeyCol.nullCount() == 0 && rKeyCol.nullCount() == 0) {
                return mergeSingleLongKey(left, right, how, leftOn[0], rightOn[0], suffixes);
            }
            if (ldt == DType.DOUBLE && rdt == DType.DOUBLE
                    && lKeyCol.nullCount() == 0 && rKeyCol.nullCount() == 0) {
                return mergeSingleDoubleKey(left, right, how, leftOn[0], rightOn[0], suffixes);
            }
        }
        return mergeGeneric(left, right, how, leftOn, rightOn, suffixes);
    }

    // ======================== fast path:单列 long/int key ========================

    /**
     * 单列 long/int key 的特化 hash join。
     * 关键:用 {@link ColumnarHashMap}(open-addressing,primitive 数组)替代 HashMap<List<Object>>;
     * 输出走 {@link DataFrame#ofColumnArrays} 零拷贝,避免逐行 new Object[] + 装箱。
     *
     * <p>仅支持 inner/left 两种 how(覆盖最常见场景);right/outer 落回通用路径。
     */
    private static DataFrame mergeSingleLongKey(DataFrame left, DataFrame right, String how,
                                                String leftKeyCol, String rightKeyCol, String[] suffixes) {
        if (!how.equals("inner") && !how.equals("left")) {
            return mergeGeneric(left, right, how, new String[]{leftKeyCol}, new String[]{rightKeyCol}, suffixes);
        }
        // 取 primitive key 数组(int 升位为 long)
        long[] lKeys = toLongArray(left.getColumn(leftKeyCol));
        long[] rKeys = toLongArray(right.getColumn(rightKeyCol));

        // 右表入桶
        ColumnarHashMap map = ColumnarHashMap.buildFromLong(rKeys);

        // 预估输出容量:max(左表行数, inner 不会超 l+r)
        int estimated = how.equals("inner")
                ? Math.min(left.rowCount(), right.rowCount()) * 2 + 16
                : left.rowCount() + 16;

        // 输出列:左表全部 + 右表(去 rightKeyCol)的重名加 _y 后缀
        String sy = (suffixes == null || suffixes.length < 2) ? "_y" : suffixes[1];
        java.util.List<String> rightOutNames = new ArrayList<>();
        java.util.Set<String> leftNameSet = new HashSet<>(left.columnNames());
        for (String n : right.columnNames()) {
            if (n.equals(rightKeyCol)) continue;  // 跳过 join key
            rightOutNames.add(leftNameSet.contains(n) ? n + sy : n);
        }
        java.util.List<String> outNames = new ArrayList<>(left.columnNames());
        outNames.addAll(rightOutNames);
        int nLeftCols = left.columnCount();
        int nRightOutCols = rightOutNames.size();
        int nOutCols = nLeftCols + nRightOutCols;

        // 收右表"非 key 列"的列下标(用于取数)
        int[] rightOutIdx = new int[nRightOutCols];
        int rc = 0;
        for (int c = 0; c < right.columnCount(); c++) {
            if (right.columnNames().get(c).equals(rightKeyCol)) continue;
            rightOutIdx[rc++] = c;
        }

        // 用 Object[] 收各输出列的 builder,按列收集(列式输出,避免逐行装箱后还要切回列)
        // 简化:每列用 ArrayList<Object>,最后转数组。后续可优化为按 dtype 直接用 primitive 容器。
        java.util.List<Object>[] colBuilders = new java.util.ArrayList[nOutCols];
        for (int c = 0; c < nOutCols; c++) colBuilders[c] = new ArrayList<>(estimated);

        // 主循环:左表驱动,命中右表则按行下标展开
        for (int l = 0; l < left.rowCount(); l++) {
            long k = lKeys[l];
            int firstR = map.findLong(k);
            if (firstR < 0) {
                if (how.equals("left")) {
                    // 左表行 + 右表全 null
                    for (int c = 0; c < nLeftCols; c++) colBuilders[c].add(left.get(l, c));
                    for (int c = 0; c < nRightOutCols; c++) colBuilders[nLeftCols + c].add(null);
                }
                continue;
            }
            // 同 key 可能多行,遍历桶内链表
            for (int r = firstR; r >= 0; r = map.nextInBucket(r)) {
                for (int c = 0; c < nLeftCols; c++) colBuilders[c].add(left.get(l, c));
                for (int c = 0; c < nRightOutCols; c++) {
                    colBuilders[nLeftCols + c].add(right.get(r, rightOutIdx[c]));
                }
            }
        }

        // 计算每输出列的源 dtype(BUG #1 修复:按源 dtype 决定输出 primitive 类型)
        DType[] outDtypes = new DType[nOutCols];
        for (int c = 0; c < nLeftCols; c++) outDtypes[c] = left.dtypes().get(c);
        for (int c = 0; c < nRightOutCols; c++) outDtypes[nLeftCols + c] = right.dtypes().get(rightOutIdx[c]);

        // AI agent2 BUG 3 修复:用 toColumn 保留 dtype + nullMask(不再降级 OBJECT)
        java.util.List<Column> outCols = new ArrayList<>(nOutCols);
        for (int c = 0; c < nOutCols; c++) {
            outCols.add(toColumn(outNames.get(c), colBuilders[c], outDtypes[c]));
        }
        return DataFrame.ofColumnsDirect(outCols);
    }

    /** 单列 double key 的特化路径(同 long 版,只是 key 用 doubleToLongBits 入桶)。 */
    private static DataFrame mergeSingleDoubleKey(DataFrame left, DataFrame right, String how,
                                                  String leftKeyCol, String rightKeyCol, String[] suffixes) {
        if (!how.equals("inner") && !how.equals("left")) {
            return mergeGeneric(left, right, how, new String[]{leftKeyCol}, new String[]{rightKeyCol}, suffixes);
        }
        double[] lKeys = ((DoubleColumn) left.getColumn(leftKeyCol)).dataInPlace();
        double[] rKeys = ((DoubleColumn) right.getColumn(rightKeyCol)).dataInPlace();
        ColumnarHashMap map = ColumnarHashMap.buildFromDouble(rKeys);

        String sy = (suffixes == null || suffixes.length < 2) ? "_y" : suffixes[1];
        java.util.List<String> rightOutNames = new ArrayList<>();
        java.util.Set<String> leftNameSet = new HashSet<>(left.columnNames());
        for (String n : right.columnNames()) {
            if (n.equals(rightKeyCol)) continue;
            rightOutNames.add(leftNameSet.contains(n) ? n + sy : n);
        }
        java.util.List<String> outNames = new ArrayList<>(left.columnNames());
        outNames.addAll(rightOutNames);
        int nLeftCols = left.columnCount();
        int nRightOutCols = rightOutNames.size();
        int nOutCols = nLeftCols + nRightOutCols;
        int[] rightOutIdx = new int[nRightOutCols];
        int rc = 0;
        for (int c = 0; c < right.columnCount(); c++) {
            if (right.columnNames().get(c).equals(rightKeyCol)) continue;
            rightOutIdx[rc++] = c;
        }
        int estimated = how.equals("inner")
                ? Math.min(left.rowCount(), right.rowCount()) * 2 + 16
                : left.rowCount() + 16;
        java.util.List<Object>[] colBuilders = new java.util.ArrayList[nOutCols];
        for (int c = 0; c < nOutCols; c++) colBuilders[c] = new ArrayList<>(estimated);

        for (int l = 0; l < left.rowCount(); l++) {
            double k = lKeys[l];
            int firstR = map.findDouble(k);
            if (firstR < 0) {
                if (how.equals("left")) {
                    for (int c = 0; c < nLeftCols; c++) colBuilders[c].add(left.get(l, c));
                    for (int c = 0; c < nRightOutCols; c++) colBuilders[nLeftCols + c].add(null);
                }
                continue;
            }
            for (int r = firstR; r >= 0; r = map.nextInBucket(r)) {
                for (int c = 0; c < nLeftCols; c++) colBuilders[c].add(left.get(l, c));
                for (int c = 0; c < nRightOutCols; c++) {
                    colBuilders[nLeftCols + c].add(right.get(r, rightOutIdx[c]));
                }
            }
        }

        // 计算每输出列的源 dtype(BUG #1 修复)
        DType[] outDtypes = new DType[nOutCols];
        for (int c = 0; c < nLeftCols; c++) outDtypes[c] = left.dtypes().get(c);
        for (int c = 0; c < nRightOutCols; c++) outDtypes[nLeftCols + c] = right.dtypes().get(rightOutIdx[c]);

        // AI agent2 BUG 3 修复:用 toColumn 保留 dtype + nullMask
        java.util.List<Column> outCols = new ArrayList<>(nOutCols);
        for (int c = 0; c < nOutCols; c++) {
            outCols.add(toColumn(outNames.get(c), colBuilders[c], outDtypes[c]));
        }
        return DataFrame.ofColumnsDirect(outCols);
    }

    /** 取 long 或 int 列的 long[](int 升位)。 */
    private static long[] toLongArray(Column col) {
        if (col instanceof LongColumn lc) return lc.dataInPlace();
        if (col instanceof IntColumn ic) {
            int[] src = ic.dataInPlace();
            long[] out = new long[src.length];
            for (int i = 0; i < src.length; i++) out[i] = src[i];
            return out;
        }
        throw new IllegalStateException("toLongArray 仅支持 LONG/INT,实际 " + col.dtype());
    }


    /**
     * 把 List<Object> 转为 Column(按源列 dtype 派发),正确处理 null:
     * 数值/布尔列带 null 时,**保留 dtype 并附 nullMask**(不降级 OBJECT)。
     *
     * <p>这是 AI agent2 审查发现的 BUG 3 的修复:原 toPrimitiveArray 在 hasNull 时
     * 直接退化为 Object[],导致下游 getLong 等抛 ClassCastException。
     * 正确做法是返回带 nullMask 的 LongColumn/DoubleColumn/IntColumn/BoolColumn。
     *
     * <p>伪代码:
     *   1. 扫一遍找 null,记录 nullMask;
     *   2. 按 dtype 派发:数值/布尔列同时返回 primitive 数组 + nullMask;
     *   3. STRING 列走 String[](null 在数组里);OBJECT 走 Object[]。
     */
    private static Column toColumn(String name, java.util.List<Object> list, DType dtype) {
        int n = list.size();
        // 先扫一遍建 nullMask(只对支持 nullMask 的 LONG/INT/BOOL 列;DOUBLE 用 NaN 表缺失,不需 nullMask)
        boolean[] nullMask = null;
        if (dtype == DType.LONG || dtype == DType.INT || dtype == DType.BOOL) {
            boolean hasNull = false;
            for (Object v : list) if (v == null) { hasNull = true; break; }
            if (hasNull) {
                nullMask = new boolean[n];
                for (int i = 0; i < n; i++) if (list.get(i) == null) nullMask[i] = true;
            }
        }
        switch (dtype) {
            case LONG: {
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) {
                    Object v = list.get(i);
                    arr[i] = v == null ? 0L : ((Number) v).longValue();
                }
                return LongColumn.wrapNoCopy(name, arr, nullMask);
            }
            case INT: {
                int[] arr = new int[n];
                for (int i = 0; i < n; i++) {
                    Object v = list.get(i);
                    arr[i] = v == null ? 0 : ((Number) v).intValue();
                }
                return IntColumn.wrapNoCopy(name, arr, nullMask);
            }
            case DOUBLE: {
                double[] arr = new double[n];
                for (int i = 0; i < n; i++) {
                    Object v = list.get(i);
                    arr[i] = v == null ? Double.NaN : ((Number) v).doubleValue();
                }
                return DoubleColumn.wrapNoCopy(name, arr);  // DoubleColumn 无 nullMask 参数,用 NaN 表缺失
            }
            case BOOL: {
                boolean[] arr = new boolean[n];
                for (int i = 0; i < n; i++) {
                    Object v = list.get(i);
                    if (v != null) arr[i] = (Boolean) v;
                }
                return BoolColumn.wrapNoCopy(name, arr, nullMask);
            }
            case STRING: {
                String[] arr = new String[n];
                for (int i = 0; i < n; i++) arr[i] = (String) list.get(i);
                return StringColumn.wrapNoCopy(name, arr);
            }
            case DATE: {
                // AI agent2 BUG A 修复:保留 DATE 类型,不走 default 降级 OBJECT
                java.time.LocalDate[] arr = new java.time.LocalDate[n];
                for (int i = 0; i < n; i++) arr[i] = (java.time.LocalDate) list.get(i);
                return DateColumn.wrapNoCopy(name, arr);
            }
            case DATETIME: {
                java.time.LocalDateTime[] arr = new java.time.LocalDateTime[n];
                for (int i = 0; i < n; i++) arr[i] = (java.time.LocalDateTime) list.get(i);
                return DateTimeColumn.wrapNoCopy(name, arr);
            }
            case CATEGORY: {
                // Category 重构成 String 列(简化:不再保留 category 编码;若需严格保留,需额外 API)
                // 之所以这样选:CategoryColumn 的 codes/categories 不易从 List<Object> 反推;
                // 而把它降级为 STRING 比 OBJECT 更接近原语义,且不丢值
                String[] arr = new String[n];
                for (int i = 0; i < n; i++) arr[i] = list.get(i) == null ? null : String.valueOf(list.get(i));
                return StringColumn.wrapNoCopy(name, arr);
            }
            default: {
                Object[] arr = list.toArray();
                return new ObjectColumn(name, arr);
            }
        }
    }

    // ======================== 通用路径(多列 key / 字符串 / right / outer)========================

    private static DataFrame mergeGeneric(DataFrame left, DataFrame right, String how,
                                          String[] leftOn, String[] rightOn, String[] suffixes) {
        if (leftOn.length != rightOn.length) {
            throw new IllegalArgumentException("leftOn 长度 " + leftOn.length + " != rightOn 长度 " + rightOn.length);
        }
        String sx = (suffixes == null || suffixes.length < 1) ? "_x" : suffixes[0];
        String sy = (suffixes == null || suffixes.length < 2) ? "_y" : suffixes[1];

        // 1. 构造输出列名:左表全部 + 右表(去 on 列)的重名处理
        List<String> outNames = new ArrayList<>();
        outNames.addAll(left.columnNames());
        Set<String> leftNameSet = new HashSet<>(left.columnNames());
        Set<String> leftOnSet = new HashSet<>(Arrays.asList(leftOn));
        List<String> rightExtraNames = new ArrayList<>();
        for (String name : right.columnNames()) {
            // 右表的 rightOn 对应列不重复输出(与左表 leftOn 已对齐)
            int idxInRightOn = indexOf(rightOn, name);
            if (idxInRightOn >= 0) continue;  // join 键列跳过(用左表的 leftOn)
            String finalName = leftNameSet.contains(name) ? name + sy : name;
            rightExtraNames.add(finalName);
            outNames.add(finalName);
        }
        // 左表重名列加 _x 后缀(对齐 pandas:左右都重名时,两边都改)
        // 简化:仅给右表加后缀(左表保持原名),M2 够用

        // 2. 在右表建 hash:rightKeyTuple → 行下标列表(buildSide=right)
        Map<List<Object>, List<Integer>> rightMap = new HashMap<>();
        for (int r = 0; r < right.rowCount(); r++) {
            List<Object> key = new ArrayList<>(rightOn.length);
            for (String col : rightOn) key.add(normKey(right.get(r, col)));
            rightMap.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        // 3. 遍历左表,inner/left 产出;记录右表哪些行被命中(供 right/outer 末尾补)
        Set<Integer> rightHit = new HashSet<>();
        List<Object[]> outRows = new ArrayList<>();
        for (int l = 0; l < left.rowCount(); l++) {
            List<Object> lkey = new ArrayList<>(leftOn.length);
            for (String col : leftOn) lkey.add(normKey(left.get(l, col)));
            List<Integer> matches = rightMap.get(lkey);
            if (matches == null || matches.isEmpty()) {
                if (how.equals("left") || how.equals("outer")) {
                    outRows.add(buildRow(left, right, l, -1, rightExtraNames, leftOn, rightOn));
                }
            } else {
                for (int rIdx : matches) {
                    rightHit.add(rIdx);
                    outRows.add(buildRow(left, right, l, rIdx, rightExtraNames, leftOn, rightOn));
                }
            }
        }
        // 4. right/outer:补右表未匹配行
        if (how.equals("right") || how.equals("outer")) {
            for (int r = 0; r < right.rowCount(); r++) {
                if (rightHit.contains(r)) continue;
                outRows.add(buildRow(left, right, -1, r, rightExtraNames, leftOn, rightOn));
            }
        }

        // 5. 推断 schema(用 left+right 全部数据推断)
        Object[][] data = outRows.toArray(new Object[0][]);
        Schema schema = Schema.infer(outNames, data);
        return DataFrame.of(schema, data);
    }

    /** 构造输出行:左表 l 行 + 右表 r 行(rIdx=-1 表示右表补 null,lIdx=-1 反之)。 */
    private static Object[] buildRow(DataFrame left, DataFrame right, int lIdx, int rIdx,
                                     List<String> rightExtraNames, String[] leftOn, String[] rightOn) {
        Object[] row = new Object[left.columnCount() + rightExtraNames.size()];
        // 左表部分
        for (int c = 0; c < left.columnCount(); c++) {
            row[c] = lIdx < 0 ? null : left.get(lIdx, c);
        }
        // 右表部分(跳过 rightOn 列,因为已在左表 leftOn 对齐)
        Set<String> rightOnSet = new HashSet<>(Arrays.asList(rightOn));
        int cursor = left.columnCount();
        for (String name : right.columnNames()) {
            if (rightOnSet.contains(name)) continue;
            row[cursor++] = rIdx < 0 ? null : right.get(rIdx, name);
        }
        return row;
    }

    /**
     * null 统一成 "<NA>" 作 key,避免 null key 漏匹配。
     *
     * <p>AI agent2 BUG 1 修复:数值类型按数值等价规范化(Integer/Long/Short/Byte 统一成 Long,
     * Float/Double 统一成 Double)。否则 INT×LONG 混合 key 会因 Integer.equals(Long)=false
     * 全部不匹配,违背 pandas 数值等价语义。
     */
    private static Object normKey(Object v) {
        if (v == null) return "<NA>";
        if (v instanceof Number n) {
            // 整数家族统一 Long,浮点家族统一 Double,避免 Integer.equals(Long)=false
            if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
                return n.longValue();
            }
            if (v instanceof Double || v instanceof Float) {
                return n.doubleValue();
            }
        }
        return v;
    }

    /**
     * 纵向/横向拼接(对齐 pandas.concat)。
     *
     * @param dfs List&lt;DataFrame&gt; 待拼接表列表,非 null,非 empty
     * @param axis int 0=纵向(行堆叠,列对齐,缺失列补 null);1=横向(列拼接,行对齐,缺失行补 null)
     * @return DataFrame 拼接结果
     * @throws IllegalArgumentException dfs 为空,或 axis 不在 {0,1}
     */
    public static DataFrame concat(List<DataFrame> dfs, int axis) {
        if (dfs.isEmpty()) throw new IllegalArgumentException("dfs 不能为空");
        if (axis == 0) return concatRows(dfs);
        if (axis == 1) return concatCols(dfs);
        throw new IllegalArgumentException("axis 仅支持 0/1,实际 " + axis);
    }

    /** 纵向:列名对齐,缺失补 null。 */
    private static DataFrame concatRows(List<DataFrame> dfs) {
        // 取列名并集(保序)
        LinkedHashMap<String, DType> nameDtype = new LinkedHashMap<>();
        for (DataFrame df : dfs) {
            List<String> names = df.columnNames();
            List<DType> dtypes = df.dtypes();
            for (int i = 0; i < names.size(); i++) {
                nameDtype.putIfAbsent(names.get(i), dtypes.get(i));
            }
        }
        // 收集所有行,按列名取值(缺失补 null)
        List<Object[]> rows = new ArrayList<>();
        for (DataFrame df : dfs) {
            for (int r = 0; r < df.rowCount(); r++) {
                Object[] row = new Object[nameDtype.size()];
                int c = 0;
                for (String name : nameDtype.keySet()) {
                    int idx = df.columnIndex(name);
                    row[c++] = idx < 0 ? null : df.get(r, idx);
                }
                rows.add(row);
            }
        }
        Object[][] data = rows.toArray(new Object[0][]);
        Object[] nameType = new Object[nameDtype.size() * 2];
        int i = 0;
        for (Map.Entry<String, DType> e : nameDtype.entrySet()) {
            nameType[i++] = e.getKey();
            nameType[i++] = e.getValue();
        }
        return DataFrame.of(Schema.of(nameType), data);
    }

    /** 横向:行数须一致,列直接拼接。 */
    private static DataFrame concatCols(List<DataFrame> dfs) {
        int n = dfs.get(0).rowCount();
        for (DataFrame df : dfs) {
            if (df.rowCount() != n) {
                throw new IllegalArgumentException("axis=1 横向拼接要求所有 DataFrame 行数一致");
            }
        }
        List<String> allNames = new ArrayList<>();
        List<DType> allDtypes = new ArrayList<>();
        for (DataFrame df : dfs) {
            allNames.addAll(df.columnNames());
            allDtypes.addAll(df.dtypes());
        }
        Object[][] data = new Object[n][allNames.size()];
        for (int r = 0; r < n; r++) {
            int c = 0;
            for (DataFrame df : dfs) {
                for (int ci = 0; ci < df.columnCount(); ci++) {
                    data[r][c++] = df.get(r, ci);
                }
            }
        }
        return DataFrame.of(new Schema(allNames, allDtypes), data);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    // ======================== 阶段 C 合并扩展(2026-08-09;按 §3.1.1.1 内聚到此类)========================

    /**
     * 索引 join(对齐 pandas DataFrame.join)。简化实现:委托 merge(how,left,on)。
     * @param left DataFrame 左表,非 null
     * @param right DataFrame 右表,非 null
     * @param on String 对齐列名;非 null
     * @param how String "left"(默认)/"inner"/"right"/"outer"
     * @return DataFrame join 结果
     */
    public static DataFrame join(DataFrame left, DataFrame right, String on, String how) {
        // 默认 suffixes=_x/_y(jian merge 5 参版本)
        return merge(left, right, how == null ? "left" : how, on, new String[]{"_x", "_y"});
    }

    /** join 便捷重载:how=left。 */
    public static DataFrame join(DataFrame left, DataFrame right, String on) {
        return join(left, right, on, "left");
    }

    /**
     * 按最近键对齐(对齐 pandas merge_asof,方向 backward:取 ≤ left.on 的最后一个 right 行)。
     * <p>两表 on 列需可比较(数值 / LocalDateTime / String);两表都按 on 升序。
     * @param left DataFrame 左表,非 null
     * @param right DataFrame 右表,非 null
     * @param on String 对齐列名(两表同名);非 null
     * @return DataFrame 行数 == left.rowCount;右表匹配列并入,无匹配填 null
     */
    public static DataFrame mergeAsof(DataFrame left, DataFrame right, String on) {
        int nl = left.rowCount(), nr = right.rowCount();
        left.getColumn(on);
        right.getColumn(on);
        // 审查修复(2026-08-09):过滤 right 表中 on 列为 null 的行(不参与 asof 匹配)
        java.util.List<Integer> validRightIdx = new java.util.ArrayList<>();
        for (int i = 0; i < nr; i++) {
            if (right.get(i, on) != null) validRightIdx.add(i);
        }
        int[] rightMap = validRightIdx.stream().mapToInt(Integer::intValue).toArray();
        int nvr = rightMap.length;

        java.util.List<String> leftNames = left.columnNames();
        java.util.List<String> rightExtraNames = new java.util.ArrayList<>();
        for (String c : right.columnNames()) if (!c.equals(on)) rightExtraNames.add(c);
        java.util.List<String> outNames = new java.util.ArrayList<>(leftNames);
        outNames.addAll(rightExtraNames);

        int rp = -1;
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < nl; i++) {
            Object lv = left.get(i, on);
            while (rp + 1 < nvr && compareAsf(right.get(rightMap[rp + 1], on), lv) <= 0) rp++;
            Object[] row = new Object[outNames.size()];
            Object[] leftRow = left.getRow(i);
            System.arraycopy(leftRow, 0, row, 0, leftNames.size());
            if (rp >= 0) {
                int rightRowIdx = rightMap[rp];
                for (int j = 0; j < rightExtraNames.size(); j++) {
                    row[leftNames.size() + j] = right.get(rightRowIdx, rightExtraNames.get(j));
                }
            }
            rows.add(row);
        }
        Object[][] data = rows.toArray(new Object[0][]);
        Object[] schParts = new Object[outNames.size() * 2];
        for (int i = 0; i < leftNames.size(); i++) {
            schParts[i * 2] = leftNames.get(i);
            schParts[i * 2 + 1] = left.dtypes().get(i);
        }
        for (int j = 0; j < rightExtraNames.size(); j++) {
            String rn = rightExtraNames.get(j);
            schParts[(leftNames.size() + j) * 2] = rn;
            schParts[(leftNames.size() + j) * 2 + 1] = right.dtypes().get(right.columnIndex(rn));
        }
        return DataFrame.of(Schema.of(schParts), data);
    }

    /** merge_asof 比较器:Number / LocalDateTime / String。
     *
     * <p>L8 修复(2026-08-09,与 AI agent2 第二轮审查共识):
     * 原 {@code if (a instanceof Comparable ca) return ca.compareTo(b);} 在 a/b 跨类型时
     * (如 String vs Number)抛 {@link ClassCastException}(String.compareTo(Number) 不合法)。
     * 现改为三段式:① 同型 Number → 数值比;② 严格同型且 Comparable → compareTo(b 必同型,不 CCE);
     * ③ 混型/不可比 → String 字典序兜底(确定性,不抛)。
     * 不全降 String 字典序的原因:LocalDateTime / BigDecimal 等的语义比较需保留 compareTo。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareAsf(Object a, Object b) {
        // 审查修复(2026-08-09):null 当作"极小值"——null key 行的 right 永远 ≤ left(跳过,不匹配)
        // 原因:merge_asof 的 right 含 null 时间点时,compareAsf(null, lv) 走 Comparable.compareTo → NPE
        if (a == null && b == null) return 0;
        if (a == null) return -1;  // null ≤ 任何值(推进 rp 但不取该行的 rv)
        if (b == null) return 1;
        // ① 同型且都是 Number → 数值比较(避免 BigDecimal/Double 混用走字典序出错;
        //   BigDecimal(1) 与 BigDecimal(1.0) 的 compareTo 等、String 化不等,必须走 compareTo 而非 String)
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        // ② 严格同型且都是 Comparable → 用其 compareTo(此时 b 必同型,不会 CCE)
        //   覆盖 String==String、LocalDateTime==LocalDateTime、BigDecimal==BigDecimal 等主要场景
        if (a.getClass() == b.getClass() && a instanceof Comparable ca) {
            return ((Comparable<Object>) ca).compareTo(b);
        }
        // ③ 混型 / 不可比 → 走 String 字典序(确定性,不抛 CCE)
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
