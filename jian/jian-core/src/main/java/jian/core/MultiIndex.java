package jian.core;

import java.util.Arrays;
import java.util.Objects;

// ┌─ What : MultiIndex —— 二级行标签(对齐 pandas.MultiIndex,v1 限 2 级)
// │  Why  : 规范 01 §1.3 / §4.5;多级索引用于层次化数据(如部门→员工)
// │  Who  : 由 DataFrame.setIndex(col1, col2) 创建
// │  When : 层次化分组、stack/unstack
// │  Where: jian-core/MultiIndex.java
/**
 * 二级 MultiIndex,对齐 pandas.MultiIndex(v1 限 2 级,规范 01 §4.5)。
 *
 * <p>用法:
 * <pre>{@code
 * MultiIndex mi = MultiIndex.of(
 *     new Object[]{"RD", "RD", "PM", "PM"},    // level 0
 *     new Object[]{1, 2, 3, 4});                 // level 1
 * DataFrame df2 = df.setMultiIndex("dept", "uid");
 * }</pre>
 */
public final class MultiIndex {

    private final Object[] level0;
    private final Object[] level1;

    public MultiIndex(Object[] level0, Object[] level1) {
        if (level0.length != level1.length) {
            throw new IllegalArgumentException("level0 长度 " + level0.length + " ≠ level1 长度 " + level1.length);
        }
        this.level0 = level0.clone();
        this.level1 = level1.clone();
    }

    public static MultiIndex of(Object[] level0, Object[] level1) {
        return new MultiIndex(level0, level1);
    }

    public int size() { return level0.length; }

    /** 取第 i 行的 level 0 值。 */
    public Object getLevel0(int i) { return level0[i]; }

    /** 取第 i 行的 level 1 值。 */
    public Object getLevel1(int i) { return level1[i]; }

    /** 取 level 0 数组。 */
    public Object[] level0() { return level0.clone(); }

    /** 取 level 1 数组。 */
    public Object[] level1() { return level1.clone(); }

    /** 切片。 */
    public MultiIndex slice(int start, int end) {
        return new MultiIndex(
                Arrays.copyOfRange(level0, start, end),
                Arrays.copyOfRange(level1, start, end));
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("MultiIndex[len=" + size() + "]\n");
        int cap = Math.min(size(), 10);
        for (int i = 0; i < cap; i++) {
            sb.append(level0[i]).append(" / ").append(level1[i]).append('\n');
        }
        if (size() > cap) sb.append("...\n");
        return sb.toString();
    }
}
