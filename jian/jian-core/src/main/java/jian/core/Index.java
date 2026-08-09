package jian.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

// ┌─ What : Index —— DataFrame 行标签(对齐 pandas Index)
// │  Why  : DataFrame = Index(行标签)+ 列;行标签支持按位置(RangeIndex)和按标签(任意 Object)
// │  Who  : DataFrame 持有;loc/iloc 通过它做标签/位置查找
// │  When : DataFrame 构造/变换/查询
// │  Where: jian-core/Index.java
// │  How  : 数据走向:构造时给标签数组 → 变换时同步重排/切片 → 保持与列长度一致。
// │         关键变量变化:
// │           - labels:Object[](任意类型,常见 String/Long);
// │           - RangeIndex 是优化特例(0..n-1),无显式 labels。
// │         逻辑路线:
// │           路径 A(默认 RangeIndex)→ 不分配 labels 数组,按位置 i 即标签 i;
// │           路径 B(显式标签)→ labels[i] 是第 i 行的标签。
/**
 * DataFrame 行标签,对齐 pandas Index。
 *
 * <p>两种形态:
 * <ul>
 *   <li>{@link #range(int)}:RangeIndex,0..n-1(默认,无显式数组,省内存);</li>
 *   <li>{@link #of(Object[])}:显式标签数组(常见 String/Long)。</li>
 * </ul>
 *
 * <p><b>不可变</b>:变换返回新 Index。
 */
public final class Index {

    private final Object[] labels;
    private final boolean isRange;
    private final int rangeSize;  // RangeIndex 的长度(labels=null 时用)

    private Index(Object[] labels, boolean isRange, int rangeSize) {
        this.labels = labels;
        this.isRange = isRange;
        this.rangeSize = rangeSize;
    }

    /**
     * 默认 RangeIndex:标签 = 0..n-1,不分配 labels 数组(省内存)。
     * @param n int 长度,≥ 0;RangeIndex 的标签即 0 到 n-1
     * @return Index RangeIndex 实例(isRange()==true,labels()==null)
     */
    public static Index range(int n) {
        return new Index(null, true, n);
    }

    /**
     * 显式标签构造(常见 String[]/Long[],任意 Object 允许)。
     * @param labels Object[] 标签数组,非 null,长度即 Index 长度;允许含 null 元素(不推荐)
     * @return Index 显式标签实例(isRange()==false);内部 clone 一份 labels 防外部修改
     */
    public static Index of(Object... labels) {
        Objects.requireNonNull(labels, "labels 不能为 null");
        return new Index(labels.clone(), false, labels.length);
    }

    /**
     * 长度(标签数)。
     * @return int RangeIndex 返回 rangeSize;显式标签返回 labels.length;总 ≥ 0
     */
    public int size() { return isRange ? rangeSize : labels.length; }

    /**
     * 是否 RangeIndex(默认 0..n-1 形态)。
     * @return boolean true=RangeIndex(labels()==null);false=显式标签
     */
    public boolean isRange() { return isRange; }

    /**
     * 取第 i 个标签。
     * @param i int 位置下标,范围 [0, size());越界抛 IndexOutOfBoundsException
     * @return Object 第 i 个标签:RangeIndex 返回 Integer(i);显式标签返回 labels[i](可能为 null)
     */
    public Object get(int i) {
        if (isRange) return i;
        return labels[i];
    }

    /**
     * 取标签数组副本(防外部修改;不可变优先)。
     * @return Object[] 显式标签返回 clone 副本(长度 == size());RangeIndex 返回 null(无显式数组)
     */
    public Object[] labels() { return isRange ? null : labels.clone(); }

    /**
     * 切片 [start, end)(左闭右开,对齐 pandas 行切片)。
     * @param start int 起始下标(含),范围 [0, size()]
     * @param end   int 结束下标(不含),范围 [start, size()]
     * @return Index 新实例:RangeIndex 仍是 RangeIndex(长度 end-start);显式标签返回新数组
     */
    public Index slice(int start, int end) {
        if (isRange) return Index.range(end - start);  // 子范围仍是 RangeIndex
        return new Index(Arrays.copyOfRange(labels, start, end), false, end - start);
    }

    /**
     * 按掩码筛选(保留 mask[i]==true 的标签)。
     * @param mask    boolean[] 掩码数组,长度必须 == size();非 null
     * @param newSize int 筛选后保留的标签数(必须 == mask 中 true 的个数),≥ 0
     * @return Index 新实例:RangeIndex 返回 Index.range(newSize);显式标签返回长度 newSize 的新数组
     */
    public Index filter(boolean[] mask, int newSize) {
        if (isRange) return Index.range(newSize);
        Object[] out = new Object[newSize];
        int j = 0;
        for (int i = 0; i < labels.length; i++) if (mask[i]) out[j++] = labels[i];
        return new Index(out, false, out.length);
    }

    /**
     * 按下标选取(对齐 pandas take/iloc)。
     * @param indices int[] 位置下标数组,每个 ∈ [0, size());允许重复、乱序;非 null
     * @return Index 新实例,长度 == indices.length。
     *         <b>注意</b>:RangeIndex 经 take 后退化为显式标签(标签值为 indices 里的下标值)
     */
    public Index take(int[] indices) {
        if (isRange) {
            Object[] out = new Object[indices.length];
            for (int k = 0; k < indices.length; k++) out[k] = indices[k];
            return new Index(out, false, out.length);
        }
        Object[] out = new Object[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = labels[indices[k]];
        return new Index(out, false, out.length);
    }

    /**
     * 去重(对齐 pandas Index.unique):返回仅包含首次出现标签的新 Index,保序。
     * RangeIndex 无重复,直接返回自身同长度的新 RangeIndex。
     *
     * @return Index 新实例;标签按首次出现顺序保留
     */
    public Index unique() {
        if (isRange) return range(rangeSize);  // 0..n-1 本身无重复
        Set<Object> seen = new HashSet<>();
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Object lbl : labels) {
            if (seen.add(lbl)) out.add(lbl);  // add 返回 false 即已存在
        }
        return new Index(out.toArray(), false, out.size());
    }

    /**
     * unique 的别名(对齐 pandas Index.drop_duplicates,两者等价)。
     *
     * @return Index 同 {@link #unique()}
     */
    public Index dropDuplicates() { return unique(); }

    /**
     * 成员判定(对齐 pandas Index.isin):返回每行标签是否在 values 中的 boolean[]。
     *
     * @param values Object[] 候选值集合,非 null;元素类型应与标签兼容
     * @return boolean[] 长度 == size();true=该行标签在 values 中
     */
    public boolean[] isin(Object[] values) {
        Set<Object> set = new HashSet<>(Arrays.asList(values));
        boolean[] mask = new boolean[size()];
        if (isRange) {
            for (int i = 0; i < rangeSize; i++) mask[i] = set.contains(i);
        } else {
            for (int i = 0; i < labels.length; i++) mask[i] = set.contains(labels[i]);
        }
        return mask;
    }

    /**
     * 字符串描述(用于调试/打印)。
     * @return String RangeIndex 返回 "RangeIndex";显式标签返回 "Index[...]"
     */
    @Override public String toString() {
        if (isRange) return "RangeIndex";
        return "Index" + Arrays.toString(labels);
    }
}
