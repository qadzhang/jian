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
     * @param left 左表
     * @param right 右表
     * @param how "inner"/"left"/"right"/"outer"
     * @param on join 键列名(左右同名的列;左右不同名用 leftOn/rightOn 版本)
     * @param suffixes 重名列后缀,默认 ["_x", "_y"](null 用默认)
     */
    public static DataFrame merge(DataFrame left, DataFrame right, String how, String on, String[] suffixes) {
        return merge(left, right, how, new String[]{on}, new String[]{on}, suffixes);
    }

    /**
     * 关系 join(多列键 + 左右不同名)。
     *
     * @param leftOn 左表 join 键列(顺序与 rightOn 对应)
     * @param rightOn 右表 join 键列
     */
    public static DataFrame merge(DataFrame left, DataFrame right, String how,
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

    /** null 统一成 "<NA>" 作 key,避免 null key 漏匹配。 */
    private static Object normKey(Object v) { return v == null ? "<NA>" : v; }

    /**
     * 纵向/横向拼接(对齐 pandas.concat)。
     *
     * @param axis 0=纵向(行堆叠,列对齐);1=横向(列拼接,行对齐)
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
}
