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
// │           路径 C(列内混型/不可比)→ 抛 IllegalArgumentException(对齐 pandas sort_values
// │             的 TypeError;不做 String 字典序回落)。
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
     * @param df        DataFrame 目标表,非 null
     * @param byCols    String[] 排序键列名,长度 ≥ 1;每个列必须存在;非 null
     * @param ascending boolean[] 每列是否升序,true=升序 false=降序;长度必须 == byCols.length;非 null
     * @param naPosition String 缺失值位置:"first"=放最前;其它(默认 "last")=放最后;null 视为 "last"
     * @return DataFrame 重排行序后的新表(行数不变,列不变,只是行顺序变)
     * @throws IllegalArgumentException byCols 为空,或 ascending 长度不匹配
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

    /**
     * 单列行比较器:null 视最小或最大,按 asc 决定方向。
     * 非 Number 混型(如 String vs Integer)不强转 Comparable(会抛裸 CCE)。
     * 因为对齐 pandas sort_values 混型抛 TypeError(与 §10.16 #4 的 cmp/PrattEngine
     * 家族统一口径,String 字典序回落是未声明偏离),所以混型/不可比的顺序比较抛 IllegalArgumentException。
     * 含 null 的比较语义不变(null 走 isNull 分支先行短路,不进类型比较)。
     * @param col       Column 待比较的列
     * @param asc       boolean true=升序;false=降序
     * @param nullsLast boolean true=null 排最后;false=null 排最前
     * @return Comparator&lt;Integer&gt; 行下标比较器,接收两个行下标返回比较结果
     * @throws IllegalArgumentException 同列内出现混型(如数值 vs 字符串)顺序比较时
     */
    private static Comparator<Integer> rowCmp(Column col, boolean asc, boolean nullsLast) {
        return (i1, i2) -> {
            // 因为 DoubleColumn.get(NaN) 返回 Double.NaN(不是 null),所以缺失判断用 isNull 而非 get()==null
            boolean aNull = col.isNull(i1);
            boolean bNull = col.isNull(i2);
            if (aNull && bNull) return 0;
            if (aNull) return nullsLast ? 1 : -1;
            if (bNull) return nullsLast ? -1 : 1;
            Object a = col.get(i1);
            Object b = col.get(i2);
            int r;
            if (a instanceof Number && b instanceof Number) {
                r = Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            } else if (a.getClass() == b.getClass() && a instanceof Comparable) {
                @SuppressWarnings("unchecked")
                Comparable<Object> ca = (Comparable<Object>) a;
                r = ca.compareTo(b);
            } else {
                // 混型/不可比 → 抛 IAE(对齐 pandas sort_values 的 TypeError;
                // 字典序回落会让 [1, "a"] 与 ["a", 1] 排出与 pandas 相反且未声明的顺序)
                throw new IllegalArgumentException(
                    "sortBy 混型比较不支持(对齐 pandas sort_values 抛 TypeError):列 '" + col.name()
                    + "' 第 " + i1 + " 行 " + a.getClass().getSimpleName() + "(" + a
                    + ") 与第 " + i2 + " 行 " + b.getClass().getSimpleName() + "(" + b + ") 类型不同;"
                    + "请先 astype 统一类型或拆分排序");
            }
            return asc ? r : -r;
        };
    }

    /**
     * 按行索引排序(对齐 pandas sort_index)。RangeIndex 下无意义(0..n-1 已有序);显式标签时按标签排序。
     * @param df        DataFrame 目标表,非 null
     * @param ascending boolean true=升序;false=降序
     * @return DataFrame 按行索引排序后的新表
     */
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

    /**
     * TopN 最大(对齐 pandas nlargest):按 byCol 降序取前 n 行。
     * 大数据集用堆排 O(N log K)(全排序为 O(N log N));
     * 比较器带行下标 tie-break,结果与全排序一致(稳定语义)。
     * @param df    DataFrame 目标表,非 null
     * @param n     int 取前 n 行,≥ 0(0 返回空表,n &gt; 行数返回全表排序)
     * @param byCol String 排序列名,必须存在;非 null
     * @return DataFrame 前 n 行(byCol 降序,null 排最后)
     */
    public static DataFrame nlargest(DataFrame df, int n, String byCol) {
        if (n <= 0) return df.head(0);
        int total = df.rowCount();
        if (n >= total) return sortValues(df, new String[]{byCol}, new boolean[]{false}, "last");
        Column col = df.getColumn(byCol);
        // 非缺失行数 m:决定 NaN 行是否进前 n 个(sortBy 语义:null/NaN 排最后,
        // head(n) 在前 n 个名额未满时才轮到 NaN 行)
        int m = 0;
        for (int i = 0; i < total; i++) if (!col.isNull(i)) m++;
        if (n <= m) {
            // 前 n 个全是非缺失行:只在非缺失行上做最小堆 O(N log K)
            // 同值"下标大的视为更小"先出堆 → 留下同值中下标小的(与稳定排序一致)
            Comparator<Integer> heapAsc = (a, b) -> {
                int r = rowCmp(col, true, true).compare(a, b);
                return r != 0 ? r : Integer.compare(b, a);
            };
            java.util.PriorityQueue<Integer> heap = new java.util.PriorityQueue<>(heapAsc);
            for (int i = 0; i < total; i++) {
                if (col.isNull(i)) continue;
                heap.offer(i);
                if (heap.size() > n) heap.poll();
            }
            Integer[] top = heap.toArray(new Integer[0]);
            // 输出:值降序 + 下标升序(对齐 sortBy 降序的稳定语义)
            java.util.Arrays.sort(top, (a, b) -> {
                int r = rowCmp(col, false, true).compare(a, b);
                return r != 0 ? r : Integer.compare(a, b);
            });
            return df.takeRows(toInt(top));
        }
        // n > m:前 n 个 = 全部非缺失行(降序)+ 前 (n-m) 个 NaN 行(下标序)
        // —— 与 sortBy(desc).head(n) 完全一致(null/NaN 排最后)
        java.util.List<Integer> valid = new java.util.ArrayList<>();
        java.util.List<Integer> naRows = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (col.isNull(i)) naRows.add(i); else valid.add(i);
        }
        valid.sort((a, b) -> rowCmp(col, false, true).compare(a, b));  // 值降序(稳定)
        Integer[] top = new Integer[n];
        int k = 0;
        for (int i = 0; i < valid.size() && k < n; i++) top[k++] = valid.get(i);
        for (int i = 0; i < naRows.size() && k < n; i++) top[k++] = naRows.get(i);
        return df.takeRows(toInt(top));
    }

    /**
     * TopN 最小(对齐 pandas nsmallest):按 byCol 升序取前 n 行。
     * 堆排 O(N log K),与 nlargest 对称。
     * @param df    DataFrame 目标表,非 null
     * @param n     int 取前 n 行,≥ 0(0 返回空表,n &gt; 行数返回全表排序)
     * @param byCol String 排序列名,必须存在;非 null
     * @return DataFrame 前 n 行(byCol 升序,null 排最后)
     */
    public static DataFrame nsmallest(DataFrame df, int n, String byCol) {
        if (n <= 0) return df.head(0);
        int total = df.rowCount();
        if (n >= total) return sortValues(df, new String[]{byCol}, new boolean[]{true}, "last");
        Column col = df.getColumn(byCol);
        // 非缺失行数 m(与 nlargest 对称:NaN 行排最后,前 n 个名额未满才轮到)
        int m = 0;
        for (int i = 0; i < total; i++) if (!col.isNull(i)) m++;
        if (n <= m) {
            // 只在非缺失行上做最大堆 O(N log K):同值"下标大的视为更小"先出堆
            Comparator<Integer> heapDesc = (a, b) -> {
                int r = rowCmp(col, false, true).compare(a, b);
                return r != 0 ? r : Integer.compare(b, a);
            };
            java.util.PriorityQueue<Integer> heap = new java.util.PriorityQueue<>(heapDesc);
            for (int i = 0; i < total; i++) {
                if (col.isNull(i)) continue;
                heap.offer(i);
                if (heap.size() > n) heap.poll();
            }
            Integer[] top = heap.toArray(new Integer[0]);
            // 输出:值升序 + 下标升序(对齐 sortBy 升序的稳定语义)
            java.util.Arrays.sort(top, (a, b) -> {
                int r = rowCmp(col, true, true).compare(a, b);
                return r != 0 ? r : Integer.compare(a, b);
            });
            return df.takeRows(toInt(top));
        }
        // n > m:前 n 个 = 全部非缺失行(升序)+ 前 (n-m) 个 NaN 行(下标序)
        java.util.List<Integer> valid = new java.util.ArrayList<>();
        java.util.List<Integer> naRows = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (col.isNull(i)) naRows.add(i); else valid.add(i);
        }
        valid.sort((a, b) -> rowCmp(col, true, true).compare(a, b));  // 值升序(稳定)
        Integer[] top = new Integer[n];
        int k = 0;
        for (int i = 0; i < valid.size() && k < n; i++) top[k++] = valid.get(i);
        for (int i = 0; i < naRows.size() && k < n; i++) top[k++] = naRows.get(i);
        return df.takeRows(toInt(top));
    }

    // ┌─ What : 极值位置 —— 列最大/最小值所在首行的下标(对齐 pandas idxmax / idxmin)
    // │  Why  : 与 nlargest/nsmallest 同源(都是"找极值位置"),按 AGENTS.md §3.1.1.1 内聚到此
    // │  Who  : 由 DataFrame.idxmax / idxmin 单行委托
    // │  When : DataFrame.idxmax / idxmin 委托调用时
    // │  How  : 单遍扫描列值,跳过缺失(null)与 NaN,记录首个极值下标;空表/全缺失返回 -1
    /**
     * 列最大值所在首行下标(对齐 pandas DataFrame.idxmax)。
     * <p>缺失值(null/NaN)跳过;空表或全缺失返回 -1。
     * @param df  DataFrame 目标表,非 null
     * @param col String 列名,必须存在且为数值列;非 null
     * @return int 最大值首行下标 ∈ [0, rowCount());空表/全缺失时 -1
     */
    public static int idxmax(DataFrame df, String col) {
        Column c = df.getColumn(col);
        int n = df.rowCount();
        if (n == 0) return -1;
        double best = Double.NaN;
        int bestIdx = -1;
        for (int i = 0; i < n; i++) {
            if (c.isNull(i)) continue;
            double v = c.getDouble(i);
            if (Double.isNaN(v)) continue;
            if (bestIdx < 0 || v > best) { best = v; bestIdx = i; }
        }
        return bestIdx;
    }

    /**
     * 列最小值所在首行下标(对齐 pandas DataFrame.idxmin)。语义同 {@link #idxmax}。
     * @param df DataFrame 目标表;非 null
     * @param col String 列名;非 null
     */
    public static int idxmin(DataFrame df, String col) {
        Column c = df.getColumn(col);
        int n = df.rowCount();
        if (n == 0) return -1;
        double best = Double.NaN;
        int bestIdx = -1;
        for (int i = 0; i < n; i++) {
            if (c.isNull(i)) continue;
            double v = c.getDouble(i);
            if (Double.isNaN(v)) continue;
            if (bestIdx < 0 || v < best) { best = v; bestIdx = i; }
        }
        return bestIdx;
    }

    /**
     * 倒序行(用于 sort_index descending on RangeIndex)。
     * @param df DataFrame 目标表
     * @return DataFrame 行序倒置后的新表
     */
    private static DataFrame reverseRows(DataFrame df) {
        int n = df.rowCount();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = n - 1 - i;
        return df.takeRows(idx);
    }

    /**
     * Integer[] → int[] 拆箱。
     * @param arr Integer[] 装箱数组
     * @return int[] 拆箱后的 primitive 数组
     */
    private static int[] toInt(Integer[] arr) {
        int[] r = new int[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = arr[i];
        return r;
    }
}
