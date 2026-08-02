package jian.core;

import java.util.Arrays;
import java.util.Objects;

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

    /** 默认 RangeIndex:0..n-1。 */
    public static Index range(int n) {
        return new Index(null, true, n);
    }

    /** 显式标签(Object[],常见 String[]/Long[])。 */
    public static Index of(Object... labels) {
        Objects.requireNonNull(labels, "labels 不能为 null");
        return new Index(labels.clone(), false, labels.length);
    }

    /** 长度(RangeIndex 需外部传 n)。 */
    /** 长度。 */
    public int size() { return isRange ? rangeSize : labels.length; }
    /** 是否 RangeIndex。 */
    public boolean isRange() { return isRange; }

    /** 取第 i 个标签。 */
    public Object get(int i) {
        if (isRange) return i;
        return labels[i];
    }

    /** 取标签数组(RangeIndex 返回 null)。 */
    public Object[] labels() { return isRange ? null : labels.clone(); }

    /** 切片 [start, end)。 */
    public Index slice(int start, int end) {
        if (isRange) return Index.range(end - start);  // 子范围仍是 RangeIndex
        return new Index(Arrays.copyOfRange(labels, start, end), false, end - start);
    }

    /** 按掩码筛选。 */
    public Index filter(boolean[] mask, int newSize) {
        if (isRange) return Index.range(newSize);
        Object[] out = new Object[newSize];
        int j = 0;
        for (int i = 0; i < labels.length; i++) if (mask[i]) out[j++] = labels[i];
        return new Index(out, false, out.length);
    }

    /** 按下标选取。 */
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

    @Override public String toString() {
        if (isRange) return "RangeIndex";
        return "Index" + Arrays.toString(labels);
    }
}
