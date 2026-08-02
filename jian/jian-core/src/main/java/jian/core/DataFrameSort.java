package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

// ┌─ What : DataFrameSort —— 排序(对齐 pandas sort_values / sort_index / nlargest / nsmallest)
// │  Why  : 排序是数据分析最高频操作之一;独立 companion 避免 DataFrame 主类超长
// │  Who  : DataFrame.sortBy/sortIndex/nlargest/nsmallest 委托此类
// │  When : 排序、TopN
// │  Where: jian-core/DataFrameSort.java
// │  How  : 数据走向:DataFrame → 取排序键列 → 生成 Integer[row] 下标数组 →
// │         Comparator 按多列优先级比较 → 排序下标 → 各列 take(下标)→ 新 DataFrame。
// │         关键变量变化:
// │           - order:Integer[行数],初始 0..n-1,排序后给出新行序;
// │           - nullsLast/ascending 通过 Comparator 链配置。
// │         逻辑路线:
// │           路径 A(单列升序)→ Comparator.comparing(colGetter);
// │           路径 B(多列混合升降序)→ Comparator 链 thenComparing;
// │           路径 C(列类型不可比)→ ClassCastException 自然抛出。
/**
 * DataFrame 排序工具,对齐 pandas sort_values/sort_index。
 *
 * @see DataFrame#sortBy(String[], boolean[])
 */
public final class DataFrameSort {

    private DataFrameSort() {}

    /**
     * 按多列排序(对齐 pandas sort_values)。
     *
     * @param df 目标
     * @param byCols 排序键列名(长度 ≥ 1)
     * @param ascending 每列是否升序(长度 = byCols.length)
     * @param naPosition 缺失放 "first" 或 "last"(对齐 pandas na_position)
     */
    public static DataFrame sortValues(DataFrame df, String[] byCols, boolean[] ascending, String naPosition) {
        if (byCols.length == 0) throw new IllegalArgumentException("byCols 不能为空");
        if (ascending.length != byCols.length) {
            throw new IllegalArgumentException("ascending 长度 " + ascending.length + " != byCols 长度 " + byCols.length);
        }
        int n = df.rowCount();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;

        boolean nullsLast = !"first".equalsIgnoreCase(naPosition);
        // 构造多列 Comparator:第一列优先级最高,依次 thenComparing 链后续列
        Comparator<Integer> cmp = null;
        for (int k = 0; k < byCols.length; k++) {
            final Column col = df.getColumn(byCols[k]);
            final boolean asc = ascending[k];
            Comparator<Integer> c = rowCmp(col, asc, nullsLast);
            cmp = (cmp == null) ? c : cmp.thenComparing(c);
        }
        Arrays.sort(order, cmp);
        return df.takeRows(toInt(order));
    }

    /** 单列行比较器:null 视最小或最大,按 asc 决定方向。 */
    private static Comparator<Integer> rowCmp(Column col, boolean asc, boolean nullsLast) {
        return (i1, i2) -> {
            Object a = col.get(i1);
            Object b = col.get(i2);
            if (a == null && b == null) return 0;
            if (a == null) return nullsLast ? 1 : -1;
            if (b == null) return nullsLast ? -1 : 1;
            int r;
            if (a instanceof Number && b instanceof Number) {
                r = Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            } else {
                @SuppressWarnings("unchecked")
                Comparable<Object> ca = (Comparable<Object>) a;
                r = ca.compareTo(b);
            }
            return asc ? r : -r;
        };
    }

    /** 按行索引排序(对齐 pandas sort_index)。RangeIndex 下无意义;显式标签时按标签排序。 */
    public static DataFrame sortIndex(DataFrame df, boolean ascending) {
        if (df.index().isRange()) {
            // RangeIndex 本身已有序
            return ascending ? df : reverseRows(df);
        }
        Object[] labels = df.index().labels();
        Integer[] order = new Integer[labels.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Comparator<Integer> cmp = (i1, i2) -> {
            @SuppressWarnings("unchecked")
            Comparable<Object> c1 = (Comparable<Object>) labels[i1];
            int r = c1.compareTo(labels[i2]);
            return ascending ? r : -r;
        };
        Arrays.sort(order, cmp);
        return df.takeRows(toInt(order));
    }

    /** TopN 最大(对齐 pandas nlargest):按 byCol 降序取前 n 行。 */
    public static DataFrame nlargest(DataFrame df, int n, String byCol) {
        DataFrame sorted = sortValues(df, new String[]{byCol}, new boolean[]{false}, "last");
        return sorted.head(n);
    }

    /** TopN 最小(对齐 pandas nsmallest)。 */
    public static DataFrame nsmallest(DataFrame df, int n, String byCol) {
        DataFrame sorted = sortValues(df, new String[]{byCol}, new boolean[]{true}, "last");
        return sorted.head(n);
    }

    /** 倒序行(用于 sort_index descending on RangeIndex)。 */
    private static DataFrame reverseRows(DataFrame df) {
        int n = df.rowCount();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = n - 1 - i;
        return df.takeRows(idx);
    }

    private static int[] toInt(Integer[] arr) {
        int[] r = new int[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = arr[i];
        return r;
    }
}
