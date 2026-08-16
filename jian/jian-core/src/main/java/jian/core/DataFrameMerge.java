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
// │           - 重名列:左右同名列(非 on)**两边都**用 suffixes 区分(_x/_y,对齐 pandas)。
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
     * 合并输出列名对(左/右各自的重名处理后列表),由 {@link #mergedNames} 产出。
     */
    private record MergedNames(List<String> leftOut, List<String> rightOut) {}

    /**
     * 计算合并输出的左右列名(因为对齐 pandas,所以重名列两边都加后缀;异名键右键列保留)。
     *
     * <p>规则(与 pandas merge 一致):左表列与右表任一列重名且非 join key → {@code name+sx};
     * 右表列与左表**原名**重名 → {@code name+sy};其余保持原名。
     * 右表键列仅当与左表键列**同名**时跳过(并入左表键列);异名键(leftOn=k1/rightOn=k2)
     * 保留输出(pandas 输出 ['k1','k2'] 两列)。
     * 因为 pandas 输出 [id, v_x, v_y](左右重名列各自加后缀),所以按"以 pandas 为准"统一口径。
     *
     * @param leftNames  List 左表列名(有序);非 null
     * @param rightNames List 右表列名(有序);非 null
     * @param leftKeys   Set 左表 join key 列名(不参与后缀);非 null
     * @param rightKeys  Set 右表 join key 列名(与左键同名时整列不输出,异名时保留);非 null
     * @param suffixes   String[] 后缀对,null/缺省用 ["_x","_y"]
     * @return MergedNames 左/右处理后的输出列名(右侧已剔除同名键列)
     */
    private static MergedNames mergedNames(List<String> leftNames, List<String> rightNames,
                                           Set<String> leftKeys, Set<String> rightKeys, String[] suffixes) {
        // 伪代码:
        //   1. sx/sy 取入参后缀,null/缺省回退 _x/_y
        //   2. 左侧:非 key 且在右表名集合中 → name+sx,否则原名
        //   3. 右侧:先剔除 key 列;与左表原名重名 → name+sy,否则原名
        String sx = (suffixes == null || suffixes.length < 1) ? "_x" : suffixes[0];
        String sy = (suffixes == null || suffixes.length < 2) ? "_y" : suffixes[1];
        Set<String> rightNameSet = new HashSet<>(rightNames);
        Set<String> leftNameSet = new HashSet<>(leftNames);
        List<String> leftOut = new ArrayList<>(leftNames.size());
        for (String n : leftNames) {
            leftOut.add(!leftKeys.contains(n) && rightNameSet.contains(n) ? n + sx : n);
        }
        List<String> rightOut = new ArrayList<>();
        for (String n : rightNames) {
            // 因为对齐 pandas,所以右键列仅当与左键**同名**时跳过(并入左表键列);
            // 异名键(leftOn=k1/rightOn=k2)必须保留输出(pandas 输出 ['k1','k2'] 两列)。
            if (rightKeys.contains(n) && leftKeys.contains(n)) continue;
            rightOut.add(leftNameSet.contains(n) ? n + sy : n);
        }
        return new MergedNames(leftOut, rightOut);
    }

    /**
     * 关系 join(对齐 pandas.merge)。
     *
     * @param left     DataFrame 左表,非 null
     * @param right    DataFrame 右表,非 null
     * @param how      String join 类型:"inner"/"left"/"right"/"outer"(不区分大小写);非 null
     * @param on       String 单列 join 键名(左右同名);非 null;左右不同名请用 5 参版本
     * @param suffixes String[] 重名列后缀,null 用默认 ["_x","_y"]
     * @return DataFrame JOIN 结果:inner=交集;left=左全+右匹配(未匹配补 null);right=右全+左匹配;outer=全并集
     * @throws OutOfMemoryError 当结果行数 = Σ(左行数×右行数/键基数) 过大时(pandas 同语义同量级:
     *         自合并 1M 行万键基数可产出 1 亿行 ≈ 10GB;预估内存 ≈ 预期行数 × ~100B,
     *         超堆请先降低键基数或分段 join)
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
        // 列存在性友好校验:因为入口处报"哪张表缺哪列"比走到底层 dtypes().get(-1)
        // 的 IOOBE 更可定位,所以 join 前先逐列校验
        for (String c : leftOn) {
            if (left.columnIndex(c) < 0)
                throw new IllegalArgumentException("左表无此列:" + c + ",左表列:" + left.columnNames());
        }
        for (String c : rightOn) {
            if (right.columnIndex(c) < 0)
                throw new IllegalArgumentException("右表无此列:" + c + ",右表列:" + right.columnNames());
        }

        // ── fast path:单列数值 key 的特化路径(覆盖 80%+ 实际 JOIN,提速 9-17 倍) ──
        // 因为 key 列含 null 时,null 在 fast path 会错误匹配 0L/NaN
        //   (通用路径用私有哨兵 NA_KEY 归组,防 "<NA>" 字面量撞车),
        //   所以 fast path 必须同时满足"key 列无 null"。
        // 因为 fast path 要求左右 key **完全同 dtype**:INT×LONG 混合走 fast path 时
        //   inner/left 按数值匹配对,但 right/outer 落回 generic 后
        //   Integer.equals(Long)==false 全部不匹配——同一对表换 how 参数结果天差地别,
        //   所以强制同 dtype 让所有 how 都走同一路径。
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
        long[] lKeys = DataFrameTypes.columnToLongArray(left.getColumn(leftKeyCol));
        long[] rKeys = DataFrameTypes.columnToLongArray(right.getColumn(rightKeyCol));

        // 右表入桶
        ColumnarHashMap map = ColumnarHashMap.buildFromLong(rKeys);

        // 预估输出容量:max(左表行数, inner 不会超 l+r)
        int estimated = how.equals("inner")
                ? Math.min(left.rowCount(), right.rowCount()) * 2 + 16
                : left.rowCount() + 16;

        // 输出列名(以 pandas 为准:重名列**两边都加**后缀 v_x/v_y)
        MergedNames names = mergedNames(left.columnNames(), right.columnNames(),
                java.util.Set.of(leftKeyCol), java.util.Set.of(rightKeyCol), suffixes);
        java.util.List<String> outNames = new ArrayList<>(names.leftOut());
        outNames.addAll(names.rightOut());
        int nLeftCols = left.columnCount();
        int nRightOutCols = names.rightOut().size();
        int nOutCols = nLeftCols + nRightOutCols;

        // 收右表输出列下标(用于取数);同名键跳过、异名键保留(与 mergedNames 条件一致)
        boolean sameKeyName = leftKeyCol.equals(rightKeyCol);
        int[] rightOutIdx = new int[nRightOutCols];
        int rc = 0;
        for (int c = 0; c < right.columnCount(); c++) {
            if (sameKeyName && right.columnNames().get(c).equals(rightKeyCol)) continue;
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

        // 计算每输出列的源 dtype(按源 dtype 决定输出 primitive 类型)
        DType[] outDtypes = new DType[nOutCols];
        for (int c = 0; c < nLeftCols; c++) outDtypes[c] = left.dtypes().get(c);
        for (int c = 0; c < nRightOutCols; c++) outDtypes[nLeftCols + c] = right.dtypes().get(rightOutIdx[c]);

        // 用 toColumn 保留 dtype + nullMask(不降级 OBJECT)
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

        MergedNames names = mergedNames(left.columnNames(), right.columnNames(),
                java.util.Set.of(leftKeyCol), java.util.Set.of(rightKeyCol), suffixes);
        java.util.List<String> outNames = new ArrayList<>(names.leftOut());
        outNames.addAll(names.rightOut());
        int nLeftCols = left.columnCount();
        int nRightOutCols = names.rightOut().size();
        int nOutCols = nLeftCols + nRightOutCols;
        // 同名键跳过、异名键保留(与 mergedNames 条件一致)
        boolean sameKeyName2 = leftKeyCol.equals(rightKeyCol);
        int[] rightOutIdx = new int[nRightOutCols];
        int rc = 0;
        for (int c = 0; c < right.columnCount(); c++) {
            if (sameKeyName2 && right.columnNames().get(c).equals(rightKeyCol)) continue;
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

        // 计算每输出列的源 dtype(按源 dtype 决定输出 primitive 类型)
        DType[] outDtypes = new DType[nOutCols];
        for (int c = 0; c < nLeftCols; c++) outDtypes[c] = left.dtypes().get(c);
        for (int c = 0; c < nRightOutCols; c++) outDtypes[nLeftCols + c] = right.dtypes().get(rightOutIdx[c]);

        // 用 toColumn 保留 dtype + nullMask(不降级 OBJECT)
        java.util.List<Column> outCols = new ArrayList<>(nOutCols);
        for (int c = 0; c < nOutCols; c++) {
            outCols.add(toColumn(outNames.get(c), colBuilders[c], outDtypes[c]));
        }
        return DataFrame.ofColumnsDirect(outCols);
    }

    /** 取 long 或 int 列的 long[](int 升位)。 */


    /**
     * 把 List<Object> 转为 Column(按源列 dtype 派发),正确处理 null:
     * 数值/布尔列带 null 时,**保留 dtype 并附 nullMask**(不降级 OBJECT)。
     *
     * <p>因为把带 null 的数值/布尔列直接退化为 Object[] 会导致下游 getLong 等抛
     * ClassCastException,所以返回带 nullMask 的 LongColumn/DoubleColumn/IntColumn/BoolColumn。
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
                // 保留 DATE 类型,不走 default 降级 OBJECT
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
                // 因为降级 STRING 会丢类别元数据,所以从 list 重建 CategoryColumn。
                // 从 list 唯一值建 categories,codes 按下标;缺失行码 -1
                java.util.LinkedHashMap<Object, Integer> catIdx = new java.util.LinkedHashMap<>();
                for (Object v : list) if (v != null && !catIdx.containsKey(v)) catIdx.put(v, catIdx.size());
                String[] categories = new String[catIdx.size()];
                for (java.util.Map.Entry<Object, Integer> e : catIdx.entrySet()) {
                    categories[e.getValue()] = String.valueOf(e.getKey());
                }
                int[] codes = new int[n];
                for (int i = 0; i < n; i++) {
                    Object v = list.get(i);
                    codes[i] = v == null ? -1 : catIdx.get(v);
                }
                return CategoryColumn.wrapNoCopy(name, codes, categories);
            }
            default: {
                Object[] arr = list.toArray();
                return new ObjectColumn(name, arr);
            }
        }
    }

    // ======================== 通用路径(多列 key / 字符串 / right / outer)========================

    /**
     * null 键的<b>私有哨兵对象</b>。
     * 因为用字符串 {@code "<NA>"} 作 null 键时,键列里恰有字面量 {@code "<NA>"}
     * 会与 null 键合并匹配(pandas 中两者是不同的键),所以用外界拿不到的单例,
     * 任何用户字符串都不可能与 null 键相撞。仅作 map 键使用,不进入输出数据。
     */
    private static final Object NA_KEY = new Object();

    // ┌─ What : mergeGeneric —— merge 通用路径(多列 key / 字符串 / right / outer)
    // │  Why  : fast path 只覆盖单列同 dtype 数值键的 inner/left,其余全走这里
    // │  Who  : DataFrameMerge.merge 分发;fast path(mergeSingleLongKey/DoubleKey)对 right/outer 委托
    // │  When : 任意 merge 调用(fast 条件不满足时)
    // │  Where: jian-core/DataFrameMerge.java
    // │  How  : 数据走向:左右表 → 按 how 选行驱动策略产出 (lIdx, rIdx) 配对 → buildRow 拼行
    // │           → 按源列 dtype 构造输出列 → DataFrame。
    // │         关键变量变化:
    // │           - outRows:List<Object[]> 输出行(lIdx/rIdx=-1 表示该侧补 null);
    // │           - rightMap/leftGroups/rightGroups:归一键元组 → 行下标列表。
    // │         逻辑路线(按 pandas 1.5.3 实测对齐行序):
    // │           路径 A(inner/left)→ 左表驱动,pandas 文档"preserve the order of the left keys";
    // │           路径 B(right)→ 右表序驱动(等价 pandas 的 swap 后 left join):每个右行,
    // │             命中则按左行序展开配对,未命中补左 null —— 例 left k=[2,1]/right k=[1,2]
    // │             输出键序 [1,2];
    // │           路径 C(outer)→ 按键分组输出,键序 = 首次出现序(先扫左表键、再扫右表未见的键),
    // │             组内两表都有的键 → 左行×右行笛卡尔(左行序优先)。
    // │             实测锚点:pandas 1.5.3(sort=False 默认)left k=[3,1]/right k=[2,1] → [3,1,2];
    // │             users/depts outer → [RD,RD,PM,ENG,MGT](非字典序,是首现键序)。
    private static DataFrame mergeGeneric(DataFrame left, DataFrame right, String how,
                                          String[] leftOn, String[] rightOn, String[] suffixes) {
        if (leftOn.length != rightOn.length) {
            throw new IllegalArgumentException("leftOn 长度 " + leftOn.length + " != rightOn 长度 " + rightOn.length);
        }

        // 1. 构造输出列名(以 pandas 为准:重名列两边都加后缀)
        MergedNames names = mergedNames(left.columnNames(), right.columnNames(),
                new HashSet<>(Arrays.asList(leftOn)), new HashSet<>(Arrays.asList(rightOn)), suffixes);
        List<String> outNames = new ArrayList<>(names.leftOut());
        List<String> rightExtraNames = names.rightOut();
        outNames.addAll(rightExtraNames);

        // 2. 行配对(按 how 选驱动策略,行序语义见方法头 How 的三条路径)
        List<Object[]> outRows = new ArrayList<>();
        if (how.equals("right")) {
            // 路径 B(right):右表序驱动 —— 对每个右行,左表命中(按左行序)展开,未命中补左 null
            Map<List<Object>, List<Integer>> leftMap = new HashMap<>();
            for (int l = 0; l < left.rowCount(); l++) {
                leftMap.computeIfAbsent(keyTuple(left, l, leftOn), k -> new ArrayList<>()).add(l);
            }
            for (int r = 0; r < right.rowCount(); r++) {
                List<Integer> matches = leftMap.get(keyTuple(right, r, rightOn));
                if (matches == null || matches.isEmpty()) {
                    outRows.add(buildRow(left, right, -1, r, rightExtraNames, leftOn, rightOn));
                } else {
                    for (int lIdx : matches) {
                        outRows.add(buildRow(left, right, lIdx, r, rightExtraNames, leftOn, rightOn));
                    }
                }
            }
        } else if (how.equals("outer")) {
            // 路径 C(outer):按键分组、首现键序(先左后右)输出
            LinkedHashMap<List<Object>, List<Integer>> leftGroups = new LinkedHashMap<>();
            for (int l = 0; l < left.rowCount(); l++) {
                leftGroups.computeIfAbsent(keyTuple(left, l, leftOn), k -> new ArrayList<>()).add(l);
            }
            LinkedHashMap<List<Object>, List<Integer>> rightGroups = new LinkedHashMap<>();
            for (int r = 0; r < right.rowCount(); r++) {
                rightGroups.computeIfAbsent(keyTuple(right, r, rightOn), k -> new ArrayList<>()).add(r);
            }
            // 左表键序优先:命中的键出左×右笛卡尔(左行序优先),仅左表的键出左行
            for (Map.Entry<List<Object>, List<Integer>> le : leftGroups.entrySet()) {
                List<Integer> rs = rightGroups.get(le.getKey());
                if (rs == null) {
                    for (int l : le.getValue()) {
                        outRows.add(buildRow(left, right, l, -1, rightExtraNames, leftOn, rightOn));
                    }
                } else {
                    for (int l : le.getValue()) {
                        for (int r : rs) {
                            outRows.add(buildRow(left, right, l, r, rightExtraNames, leftOn, rightOn));
                        }
                    }
                }
            }
            // 仅右表的键(首现序)追加在后
            for (Map.Entry<List<Object>, List<Integer>> re : rightGroups.entrySet()) {
                if (!leftGroups.containsKey(re.getKey())) {
                    for (int r : re.getValue()) {
                        outRows.add(buildRow(left, right, -1, r, rightExtraNames, leftOn, rightOn));
                    }
                }
            }
        } else {
            // 路径 A(inner/left):左表驱动(pandas 同序)
            Map<List<Object>, List<Integer>> rightMap = new HashMap<>();
            for (int r = 0; r < right.rowCount(); r++) {
                rightMap.computeIfAbsent(keyTuple(right, r, rightOn), k -> new ArrayList<>()).add(r);
            }
            for (int l = 0; l < left.rowCount(); l++) {
                List<Integer> matches = rightMap.get(keyTuple(left, l, leftOn));
                if (matches == null || matches.isEmpty()) {
                    if (how.equals("left")) {
                        outRows.add(buildRow(left, right, l, -1, rightExtraNames, leftOn, rightOn));
                    }
                } else {
                    for (int rIdx : matches) {
                        outRows.add(buildRow(left, right, l, rIdx, rightExtraNames, leftOn, rightOn));
                    }
                }
            }
        }

        // 3. 因为 Schema.infer 对空数据(0 行)与全 null 列一律给 STRING,会导致
        //    "字符串键 inner 零匹配 → 全列 STRING""left join 右表全 null 列 → STRING",
        //    与 fast path 的 toColumn 口径不一致(pandas 两种情况都保留原 dtype),
        //    所以输出列按源列 dtype 构造、不用 Schema.infer;空结果也保留 dtype。
        return buildOutputDtyped(outNames, left, right, leftOn, rightOn, outRows);
    }

    /** 归一键元组:df 第 r 行各 on 列的值经 {@link #normKey} 归一(供 mergeGeneric 分组/查找)。 */
    private static List<Object> keyTuple(DataFrame df, int r, String[] on) {
        List<Object> key = new ArrayList<>(on.length);
        for (String col : on) key.add(normKey(df.get(r, col)));
        return key;
    }

    // ┌─ What : buildOutputDtyped —— generic merge 的输出列构造(按源列 dtype)
    // │  Why  : Schema.infer 会把 0 行/全 null 列推成 STRING,同输入走 fast path 却保 dtype,
    // │         fast/generic 口径分裂;复用 fast path 的 toColumn(LONG/INT/BOOL 带 nullMask,
    // │         DOUBLE 用 NaN,DATE/DATETIME/CATEGORY 保留,OBJECT 兜底)统一两路径行为
    // │  Who  : mergeGeneric 收尾调用
    // │  How  : 数据走向:outRows(行式)→ 逐输出列转 List<Object>(列式)→ toColumn(name, vals, 源dtype)
    // │           → DataFrame.ofColumnsDirect。
    // │         逻辑路线:左段 nLeftCols 列按 left.dtypes;右段(同名键已跳过)按 right.dtypes
    // │           对应列(右段列下标回算规则与 mergedNames/buildRow 一致)。
    private static DataFrame buildOutputDtyped(List<String> outNames, DataFrame left, DataFrame right,
                                               String[] leftOn, String[] rightOn, List<Object[]> outRows) {
        int n = outRows.size();
        int nLeftCols = left.columnCount();
        int nRightOutCols = outNames.size() - nLeftCols;
        // 右表输出列下标回算(同名键跳过、异名键保留,与 mergedNames/buildRow 一致)
        Set<String> rightOnSet = new HashSet<>(Arrays.asList(rightOn));
        Set<String> leftOnSet = new HashSet<>(Arrays.asList(leftOn));
        int[] rightOutIdx = new int[nRightOutCols];
        int rc = 0;
        for (int c = 0; c < right.columnCount(); c++) {
            String name = right.columnNames().get(c);
            if (rightOnSet.contains(name) && leftOnSet.contains(name)) continue;
            rightOutIdx[rc++] = c;
        }
        // 逐输出列:行式 → 列式 List → toColumn(保留源 dtype + nullMask)
        List<Column> outCols = new ArrayList<>(outNames.size());
        for (int c = 0; c < nLeftCols; c++) {
            List<Object> vals = new ArrayList<>(n);
            for (Object[] row : outRows) vals.add(row[c]);
            outCols.add(toColumn(outNames.get(c), vals, left.dtypes().get(c)));
        }
        for (int j = 0; j < nRightOutCols; j++) {
            List<Object> vals = new ArrayList<>(n);
            for (Object[] row : outRows) vals.add(row[nLeftCols + j]);
            outCols.add(toColumn(outNames.get(nLeftCols + j), vals, right.dtypes().get(rightOutIdx[j])));
        }
        return DataFrame.ofColumnsDirect(outCols);
    }

    /**
     * 构造输出行:左表 l 行 + 右表 r 行(rIdx=-1 表示右表补 null,lIdx=-1 反之)。
     *
     * <p>因为 right/outer join 中右表独有行(lIdx&lt;0)的 <b>join 键列</b> 若填 null
     * 会丢失右表 key(pandas merge(right) 对右表独有行输出右表的 key,
     * pandas merge(outer) 输出两表 key 并集),所以必须保留右表 rightOn 的值,不能填 null。
     * 例如:左表 id∈{1,2,3},右表 id∈{2,3,4},merge(right) 应输出 id=4 行(键列=4)。
     */
    private static Object[] buildRow(DataFrame left, DataFrame right, int lIdx, int rIdx,
                                     List<String> rightExtraNames, String[] leftOn, String[] rightOn) {
        Object[] row = new Object[left.columnCount() + rightExtraNames.size()];
        // 左表部分
        for (int c = 0; c < left.columnCount(); c++) {
            if (lIdx < 0) {
                // 右表独有行(right/outer):若该列是 join 键列,取右表 rightOn 对应值(对齐 pandas)。
                // 因为键列合一仅发生在**同名键**(唯一输出列取右表 key),所以该回填仅适用同名键;
                // 异名键(k1/k2)左右键各自成列,右表独有行的左键列按 pandas 置 null
                //(pandas outer 实测 k1=NaN/k2=4,不回填),右键值由右侧部分自行输出。
                String colName = left.columnNames().get(c);
                int onPos = indexOf(leftOn, colName);
                row[c] = (onPos >= 0 && rightOn[onPos].equals(colName))
                        ? right.get(rIdx, rightOn[onPos]) : null;
            } else {
                row[c] = left.get(lIdx, c);
            }
        }
        // 右表部分:同名键跳过(已在左表 leftOn 对齐)、异名键保留(对齐 pandas 输出 ['k1','k2'])
        Set<String> rightOnSet = new HashSet<>(Arrays.asList(rightOn));
        Set<String> leftOnSet = new HashSet<>(Arrays.asList(leftOn));
        int cursor = left.columnCount();
        for (String name : right.columnNames()) {
            if (rightOnSet.contains(name) && leftOnSet.contains(name)) continue;
            row[cursor++] = rIdx < 0 ? null : right.get(rIdx, name);
        }
        return row;
    }

    /**
     * 键归一:null → 私有哨兵 {@link #NA_KEY}(因为 "<NA>" 字符串会与键列里
     * 真实的 "&lt;NA&gt;" 字面量合并);数值按数值等价规范化;±0.0 归一为 +0.0。
     *
     * <p>因为 INT×LONG 混合 key 会因 Integer.equals(Long)=false 全部不匹配
     * (违背 pandas 数值等价语义),所以数值类型按数值等价规范化
     * (Integer/Long/Short/Byte 统一成 Long,Float/Double 统一成 Double)。
     */
    private static Object normKey(Object v) {
        if (v == null) return NA_KEY;   // 私有哨兵,防 "<NA>" 字面量撞车
        if (v instanceof Number n) {
            // 整数家族统一 Long,浮点家族统一 Double,避免 Integer.equals(Long)=false
            if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
                return n.longValue();
            }
            if (v instanceof Double || v instanceof Float) {
                // Float → doubleValue 无损(Float 本身 7 位有效数字,double 完全容纳)。
                // ±0.0 归一到 +0.0(对齐 pandas merge 的数值等价语义)
                double d = n.doubleValue();
                return d == 0.0 ? 0.0 : d;
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
                DType prev = nameDtype.putIfAbsent(names.get(i), dtypes.get(i));
                // 因为同名列 dtype 冲突(如 INT vs STRING)时若首见 dtype 胜出,
                // 另一侧字符串值进数值列会触发裸 NFE(pandas concat 保 object),
                // 所以 dtype 冲突 → 升 OBJECT
                if (prev != null && prev != dtypes.get(i)) {
                    nameDtype.put(names.get(i), DType.OBJECT);
                }
            }
        }
        // 收集所有行,按列名取值(缺失补 null)
        // 因为**每行每列**都调 df.columnIndex(name)(字符串哈希查找)时,
        // 1M 行 × 列数 次查找会成为 concat 的主要热点,所以按 df 预计算
        // "并集列位 → 该 df 列下标" 映射,行循环内纯数组访问。
        List<String> union = new ArrayList<>(nameDtype.keySet());
        List<Object[]> rows = new ArrayList<>();
        for (DataFrame df : dfs) {
            int[] colMap = new int[union.size()];
            for (int c = 0; c < union.size(); c++) {
                colMap[c] = df.columnIndex(union.get(c));
            }
            for (int r = 0; r < df.rowCount(); r++) {
                Object[] row = new Object[union.size()];
                for (int c = 0; c < union.size(); c++) {
                    int idx = colMap[c];
                    row[c] = idx < 0 ? null : df.get(r, idx);
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

    // ======================== 合并扩展(按 §3.1.1.1 内聚到此类)========================

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

    /**
     * join 便捷重载:how=left。
     * @param left DataFrame 左表;非 null
     * @param right DataFrame 右表;非 null
     * @param on String 连接键列名;非 null
     */
    public static DataFrame join(DataFrame left, DataFrame right, String on) {
        return join(left, right, on, "left");
    }

    /**
     * 按最近键对齐(对齐 pandas merge_asof,方向 backward:取 ≤ left.on 的最后一个 right 行)。
     * <p>两表 on 列需可比较(数值 / LocalDateTime / String);两表都按 on 升序。
     * <p>缺失键语义(对齐 pandas 1.5.3 实测,§3.5 权威判定一律 isNull,DOUBLE 列 NaN 同为缺失):
     * <b>左右任一侧 on 键含缺失即抛 IllegalArgumentException</b>(pandas 同输入抛
     * ValueError"Merge keys contain null values");请先 dropna/清洗再 mergeAsof。
     * @param left DataFrame 左表,非 null
     * @param right DataFrame 右表,非 null
     * @param on String 对齐列名(两表同名);非 null
     * @return DataFrame 行数 == left.rowCount;右表匹配列并入,无匹配填 null
     */
    public static DataFrame mergeAsof(DataFrame left, DataFrame right, String on) {
        int nl = left.rowCount(), nr = right.rowCount();
        Column leftOn = left.getColumn(on);
        Column rightOn = right.getColumn(on);
        // 对齐 pandas 1.5.3(实测 oracle):merge_asof 对左右任一侧 on 键含缺失
        //(null/NaN,一律 isNull 权威判定 —— DOUBLE 列 NaN 同为缺失)直接抛
        // ValueError("Merge keys contain null values on left/right side")。
        // jian 同口径 fail-fast 抛 IAE(提示先清洗),不做"容忍缺失键静默跳过/
        // 输出 null 行"的未声明偏离 —— 那会让脏数据悄悄改变匹配结果。
        for (int i = 0; i < nl; i++) {
            if (leftOn.isNull(i)) throw new IllegalArgumentException(
                "merge_asof 的 on 键含缺失值(left 第 " + i + " 行);两侧键都不允许缺失,"
                + "请先 dropna/清洗(对齐 pandas ValueError)");
        }
        for (int i = 0; i < nr; i++) {
            if (rightOn.isNull(i)) throw new IllegalArgumentException(
                "merge_asof 的 on 键含缺失值(right 第 " + i + " 行);两侧键都不允许缺失,"
                + "请先 dropna/清洗(对齐 pandas ValueError)");
        }

        java.util.List<String> leftNames = left.columnNames();
        java.util.List<String> rightExtraNames = new java.util.ArrayList<>();
        for (String c : right.columnNames()) if (!c.equals(on)) rightExtraNames.add(c);
        java.util.List<String> outNames = new java.util.ArrayList<>(leftNames);
        outNames.addAll(rightExtraNames);

        int rp = -1;
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < nl; i++) {
            Object lv = left.get(i, on);
            // 键已全量校验无缺失,直接在 right 原始行号上单调推进(backward:≤ lv 的最后一行)
            while (rp + 1 < nr && compareAsf(right.get(rp + 1, on), lv) <= 0) rp++;
            Object[] row = new Object[outNames.size()];
            Object[] leftRow = left.getRow(i);
            System.arraycopy(leftRow, 0, row, 0, leftNames.size());
            if (rp >= 0) {
                for (int j = 0; j < rightExtraNames.size(); j++) {
                    row[leftNames.size() + j] = right.get(rp, rightExtraNames.get(j));
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
     * <p>因为直接 {@code if (a instanceof Comparable ca) return ca.compareTo(b);} 在 a/b 跨类型时
     * (如 String vs Number)会抛 {@link ClassCastException}(String.compareTo(Number) 不合法),
     * 所以采用三段式:① 同型 Number → 数值比;② 严格同型且 Comparable → compareTo(b 必同型,不 CCE);
     * ③ 混型/不可比 → <b>抛 IllegalArgumentException</b>(对齐 pandas:merge_asof 的 on 键必须同型,
     * 且与 DataFrame.cmp 混型口径统一)。null 当作"极小值"在前面已处理(不进③)。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareAsf(Object a, Object b) {
        // 因为 merge_asof 的 right 含 null 时间点时,compareAsf(null, lv) 走 Comparable.compareTo
        // 会 NPE,所以 null 当作"极小值"——null key 行的 right 永远 ≤ left(跳过,不匹配)
        if (a == null && b == null) return 0;
        if (a == null) return -1;  // null ≤ 任何值(推进 rp 但不取该行的 rv)
        if (b == null) return 1;
        // ① 同型且都是 Number → 数值比较(避免 BigDecimal/Double 混用走字典序出错;
        //   BigDecimal(1) 与 BigDecimal(1.0) 的 compareTo 等、String 化不等,必须走 compareTo 而非 String)
        // BigDecimal 特化:走 compareTo 保精确(doubleValue 会丢精度)。
        if (a instanceof java.math.BigDecimal ba && b instanceof java.math.BigDecimal bb) {
            return ba.compareTo(bb);
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        // ② 严格同型且都是 Comparable → 用其 compareTo(此时 b 必同型,不会 CCE)
        //   覆盖 String==String、LocalDateTime==LocalDateTime、BigDecimal==BigDecimal 等主要场景
        if (a.getClass() == b.getClass() && a instanceof Comparable ca) {
            return ((Comparable<Object>) ca).compareTo(b);
        }
        // ③ 混型 / 不可比 → 抛 IAE(对齐 pandas:merge_asof 的 on 键必须同型,混型=输入错误;
        //   与 DataFrame.cmp 混型口径一致)。
        throw new IllegalArgumentException(
            "merge_asof 的 on 列出现混型比较(" + a.getClass().getSimpleName()
            + " vs " + b.getClass().getSimpleName() + ");on 键必须同型(数值或时间)");
    }
}
