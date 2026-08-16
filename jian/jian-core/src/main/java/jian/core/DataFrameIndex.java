package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// ┌─ What : DataFrameIndex —— 行选择与索引变换(loc/resetIndex/setIndex/sample/applyRow 实现,从 DataFrame.java 拆出)
// │  Why  : 落实 §3.1 ≤600 行红线;*Impl 族 ~150 行自包含(已是 static(df) 形式,零改造直搬)。
// │  Who  : 由 DataFrame.loc/resetIndex/setIndex/sample/applyRow 委托调用
// │  When : 按标签选行(loc)/Index↔列互转/随机采样/按行应用函数
// │  Where: jian-core/DataFrameIndex.java
// │  How  : loc:RangeIndex 分支三道检查(非 Number/非整数/超 int 范围)后按位置取;显式标签分支
// │         normLabelKey 数值归一(Integer(3)≡Long(3),±0.0 同键)+ HashMap 一次建 O(M) + 逐查 O(K)。
// │         sample:replace=true 直接 nextInt;false 走 Fisher-Yates 部分洗牌(镜像 Random 测试锁定)。
final class DataFrameIndex {
    private DataFrameIndex() {}

    /** loc:按行标签选行(RangeIndex 时标签=位置;显式标签数值归一匹配,对齐 pandas)。 */
    static DataFrame loc(DataFrame df, Object[] labels) {
        if (df.index().isRange()) {
            // 三道检查:非 Number 抛 IAE(带类型提示);非整数抛 IAE;超 int 范围抛 IOOBE(防 intValue 静默溢出)
            int[] idx = new int[labels.length];
            int n = df.rowCount();
            for (int k = 0; k < labels.length; k++) {
                Object label = labels[k];
                if (!(label instanceof Number)) {
                    throw new IllegalArgumentException(
                        "RangeIndex 的标签须为数字,实际第 " + k + " 个标签: "
                        + (label == null ? "null" : label.getClass().getSimpleName() + "「" + label + "」"));
                }
                double dv = ((Number) label).doubleValue();
                if (dv != Math.rint(dv)) {
                    throw new IllegalArgumentException(
                        "RangeIndex 标签须为整数,实际第 " + k + " 个标签为 " + dv);
                }
                if (dv > Integer.MAX_VALUE || dv < Integer.MIN_VALUE) {
                    throw new IndexOutOfBoundsException("标签 " + dv + " 超出 int 范围,RangeIndex 不支持");
                }
                int rowIdx = (int) dv;
                if (rowIdx < 0 || rowIdx >= n) {
                    throw new IndexOutOfBoundsException(
                        "标签 " + rowIdx + " 越界(RangeIndex 行数 " + n + ")");
                }
                idx[k] = rowIdx;
            }
            return df.takeRows(idx);
        }
        // 显式标签:数值归一后 HashMap 索引一次建(O(M)),逐查 O(K);重复标签全部保留
        Object[] all = df.index().labels();
        java.util.Map<Object, java.util.List<Integer>> idxMap = new java.util.HashMap<>();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == null) continue;
            idxMap.computeIfAbsent(normLabelKey(all[i]), x -> new ArrayList<>()).add(i);
        }
        List<Integer> hit = new ArrayList<>();
        for (Object label : labels) {
            if (label == null) continue;
            java.util.List<Integer> rows = idxMap.get(normLabelKey(label));
            if (rows != null) hit.addAll(rows);
        }
        int[] idx = new int[hit.size()];
        for (int k = 0; k < idx.length; k++) idx[k] = hit.get(k);
        return df.takeRows(idx);
    }

    /** 标签键归一:Number 统一为 Long(整数)/Double(小数,±0.0 同键);其余原样。对齐 pandas"数值相等即同标签"。 */
    private static Object normLabelKey(Object v) {
        if (v instanceof Number num) {
            double d = num.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return (long) d;
            return d == 0.0 ? 0.0 : d;
        }
        return v;
    }

    /** resetIndex 实现:Index 转普通列,新表回 RangeIndex。 */
    static DataFrame resetIndexImpl(DataFrame df, String indexCol) {
        if (df.index().isRange() || indexCol == null || indexCol.isEmpty()) {
            return DataFrame.ofColumnsDirect(copyColumns(df));
        }
        Object[] labels = df.index().labels();
        if (labels == null) return DataFrame.ofColumnsDirect(copyColumns(df));
        Column idxCol = DataFrameTypes.inferColumnFromArray(indexCol, labels);
        List<Column> cols = copyColumns(df);
        cols.add(idxCol);
        return DataFrame.ofColumnsDirect(cols);
    }

    // ┌─ What : setIndex 实现 —— 普通列提升为 Index(单列 → 平铺标签;多列 → MultiIndex 复合标签)
    // │  Why  : 对齐 pandas set_index;因为只把 cols 全部从数据列剔除、Index 却只用 cols[0]
    // │         构建会让 setIndex("a","b") 后 b 列数据彻底丢失,所以多列键全部进 MultiIndex。
    // │  Who  : 由 DataFrame.setIndex(String[]) / setIndex(String[], boolean) 委托
    // │  When : 把普通列提升为行索引
    // │  Where: jian-core/DataFrameIndex.java
    // │  How  : 数据走向:
    // │           路径 A(单列)→ 列值数组 → Index.of(labels);
    // │           路径 B(多列)→ 各列值装 levels[][] → MultiIndex.of(names, levels)(级长度
    // │             一致性由 MultiIndex 构造器校验)→ 每行的各级值打包成 List<Object> 复合标签 → Index.of。
    // │         关键变量变化:
    // │           - levels:Object[][] levels[k][i] = 第 k 个键列第 i 行的值;
    // │           - labels:Object[] 第 i 个元素 = [level0[i], level1[i], ...] 的 List(值语义,支持 loc 精确查找)。
    // │         逻辑路线:drop=true 时全部键列提升(remaining 剔除);drop=false 时键列保留在数据列中。
    // │         不变量:行数不变;单列路径零改动。
    /** setIndex 实现:普通列提升为 Index(多列时经 MultiIndex 构建复合标签,值不丢失)。 */
    static DataFrame setIndexImpl(DataFrame df, String[] cols, boolean drop) {
        if (cols == null || cols.length == 0) {
            throw new IllegalArgumentException("set_index cols 至少 1 列");
        }
        for (String c : cols) df.getColumn(c);  // 校验列存在
        Set<String> promoted = new HashSet<>(Arrays.asList(cols));
        List<Column> remaining = new ArrayList<>();
        for (String c : df.columnNames()) if (!drop || !promoted.contains(c)) remaining.add(df.getColumn(c));

        // 多列键 → MultiIndex(N 级)→ 复合标签;单列 → 平铺标签
        Object[] labels;
        if (cols.length == 1) {
            Column c0 = df.getColumn(cols[0]);
            labels = new Object[df.rowCount()];
            for (int i = 0; i < labels.length; i++) labels[i] = c0.get(i);
        } else {
            // levels[k][i]:第 k 个键列第 i 行的值;交 MultiIndex.of 做级长度校验并保留各级名(键列名)
            Object[][] levels = new Object[cols.length][];
            for (int k = 0; k < cols.length; k++) {
                Column ck = df.getColumn(cols[k]);
                Object[] lv = new Object[df.rowCount()];
                for (int i = 0; i < lv.length; i++) lv[i] = ck.get(i);
                levels[k] = lv;
            }
            MultiIndex multi = MultiIndex.of(cols, levels);
            // 行标签 = 各级值的 List(内容相等即同标签,loc(List.of(...)) 可精确命中)
            labels = new Object[df.rowCount()];
            for (int i = 0; i < labels.length; i++) {
                List<Object> compound = new ArrayList<>(cols.length);
                for (int k = 0; k < cols.length; k++) compound.add(multi.get(k, i));
                labels[i] = compound;
            }
        }
        // 因为唯一列被提升 → remaining 空 → 0 列表 rowCount=0,再 withIndex(N 行)抛
        // newIndex.size()≠rowCount,所以改走 0 列 N 行工厂(pandas set_index 单列返回 N rows × 0 cols)
        if (remaining.isEmpty()) {
            return DataFrame.ofZeroColumnsWithIndex(df.rowCount(), Index.of(labels));
        }
        DataFrame result = DataFrame.ofColumnsDirect(remaining);
        return result.withIndex(Index.of(labels));
    }

    /** sample 实现:随机采样(可复现种子;replace=true nextInt,false Fisher-Yates 部分洗牌)。 */
    static DataFrame sampleImpl(DataFrame df, int n, boolean replace, long seed) {
        int total = df.rowCount();
        if (n < 0) throw new IllegalArgumentException("sample n 不能为负:" + n);
        if (total == 0 && n > 0) throw new IllegalArgumentException("sample n=" + n + " 但 rowCount=0");
        if (!replace && n > total) {
            throw new IllegalArgumentException("sample n=" + n + " > rowCount=" + total + "(需 replace=true)");
        }
        java.util.Random rng = new java.util.Random(seed);
        int[] picked = new int[n];
        if (replace) {
            for (int k = 0; k < n; k++) picked[k] = rng.nextInt(total);
        } else {
            int[] pool = new int[total];
            for (int i = 0; i < total; i++) pool[i] = i;
            for (int k = 0; k < n; k++) {
                int j = k + rng.nextInt(total - k);
                int tmp = pool[k]; pool[k] = pool[j]; pool[j] = tmp;
                picked[k] = pool[k];
            }
        }
        return df.takeRows(picked);
    }

    /** applyRow 实现:按行应用函数生成新列(类型经 inferColumnFromArray 推断)。 */
    static DataFrame applyRowImpl(DataFrame df, String newCol,
                                  java.util.function.Function<Object[], Object> fn) {
        int n = df.rowCount();
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = fn.apply(df.getRow(i));
        Column newColObj = DataFrameTypes.inferColumnFromArray(newCol, out);
        List<Column> cols = new ArrayList<>(df.columnCount() + 1);
        for (String c : df.columnNames()) cols.add(df.getColumn(c));
        cols.add(newColObj);
        return DataFrame.ofColumnsDirect(cols);
    }

    /** 内部:复制 df 的所有列(Column 自身不可变,引用复制即可)。 */
    private static List<Column> copyColumns(DataFrame df) {
        List<Column> cols = new ArrayList<>(df.columnCount());
        for (String c : df.columnNames()) cols.add(df.getColumn(c));
        return cols;
    }
}
