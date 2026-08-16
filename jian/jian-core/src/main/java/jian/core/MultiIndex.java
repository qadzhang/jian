package jian.core;

import java.util.Arrays;
import java.util.Objects;

// ┌─ What : MultiIndex —— N 级行标签(对齐 pandas.MultiIndex;支持从 2 级扩到 N 级)
// │  Why  : 规范 01 §1.3 / §4.5;多级索引用于层次化数据(部门→员工→日期);stack/unstack 依赖
// │  Who  : 由 DataFrame.setIndex(col1, col2, ...) 创建;用户链式 droplevel/swaplevel/reorder_levels
// │  When : 层次化分组、stack/unstack、resample PARTITION BY
// │  Where: jian-core/MultiIndex.java
// │  How  : 数据走向:内部用 Object[][] levels(N 级 × nRows);每级可选 name(String,null=未命名)。
// │         关键变量变化:
// │           - levels:Object[][] levels[level][row]
// │           - names:String[] 各级名(长度 == 级数,允许元素 null)
// │         逻辑路线:
// │           路径 A(2 级兼容)→ 旧 of(level0, level1) 转为 levels=[level0, level1]
// │           路径 B(N 级新 API)→ of(names, levels) 直接构造
// │         不变量:所有 level 长度必须相同;names 长度(若非 null)必须 == levels.length。
/**
 * N 级 MultiIndex,对齐 pandas.MultiIndex。
 *
 * <p>从 v1 的 2 级(level0/level1)扩展到 N 级(levels[level][row]);
 * 旧的 {@code of(Object[], Object[])} 2 级构造保留为便捷别名。
 *
 * <p>用法:
 * <pre>{@code
 * // N 级(推荐)
 * MultiIndex mi = MultiIndex.of(
 *     new String[]{"dept", "uid"},                    // 各级名
 *     new Object[][]{                                  // 各级标签
 *         new Object[]{"RD", "RD", "PM", "PM"},
 *         new Object[]{1, 2, 3, 4}});
 *
 * // 2 级(便捷兼容)
 * MultiIndex mi2 = MultiIndex.of(
 *     new Object[]{"RD", "RD", "PM", "PM"},
 *     new Object[]{1, 2, 3, 4});
 * }</pre>
 */
public final class MultiIndex {

    private final Object[][] levels;        // levels[level][row]
    private final String[] names;           // 各级名(可含 null)

    /**
     * N 级公开构造(默认拷贝)。
     * @param names  String[] 各级名;允许 null(无名)或元素 null;长度可 ≠ levels.length(短时补 null)
     * @param levels Object[][] levels[level][row],非 null;各级长度必须相同
     * @throws IllegalArgumentException 各级长度不一致 或 levels.length == 0
     */
    public MultiIndex(String[] names, Object[][] levels) {
        Objects.requireNonNull(levels, "levels 不能为 null");
        if (levels.length == 0) {
            throw new IllegalArgumentException("MultiIndex 至少 1 级(levels.length==0)");
        }
        int n = levels[0].length;
        for (int k = 1; k < levels.length; k++) {
            if (levels[k].length != n) {
                throw new IllegalArgumentException(
                    "level " + k + " 长度 " + levels[k].length + " ≠ level 0 长度 " + n);
            }
        }
        // 深拷贝各级数组(防外部修改)
        this.levels = new Object[levels.length][];
        for (int k = 0; k < levels.length; k++) this.levels[k] = levels[k].clone();
        // names 补齐到级数
        String[] normalizedNames = new String[levels.length];
        if (names != null) {
            System.arraycopy(names, 0, normalizedNames, 0,
                Math.min(names.length, normalizedNames.length));
        }
        this.names = normalizedNames;
    }

    /**
     * 2 级便捷构造(向后兼容 v1 的 of(Object[], Object[]) 入参形态)。
     * level0/level1 为 null 时抛明确 IAE(不裸 NPE)。
     * @param level0 Object[] 第 0 级,非 null
     * @param level1 Object[] 第 1 级,非 null;长度必须 == level0.length
     */
    public MultiIndex(Object[] level0, Object[] level1) {
        this(null, new Object[][]{
            java.util.Objects.requireNonNull(level0, "MultiIndex level0 不能为 null"),
            java.util.Objects.requireNonNull(level1, "MultiIndex level1 不能为 null")});
    }

    /**
     * N 级工厂(便捷)。
     * @param names  String[] 各级名;允许 null
     * @param levels Object[][] levels[level][row],非 null;各级长度必须相同
     * @return MultiIndex 新实例(深拷贝入参)
     */
    public static MultiIndex of(String[] names, Object[][] levels) {
        return new MultiIndex(names, levels);
    }

    /**
     * 2 级工厂(向后兼容 v1)。
     * @param level0 Object[] 第 0 级,非 null
     * @param level1 Object[] 第 1 级,非 null;长度 == level0.length
     * @return MultiIndex 2 级实例
     */
    public static MultiIndex of(Object[] level0, Object[] level1) {
        return new MultiIndex(level0, level1);
    }

    /**
     * @return int 级数(N 级 MultiIndex 的 N),≥ 1
     */
    public int numLevels() { return levels.length; }

    /**
     * @return int 行数(标签数),≥ 0;等于任一 level 的长度
     */
    public int size() { return levels[0].length; }

    /**
     * 取第 level 级、第 i 行的标签值。
     * @param level int 级下标 ∈ [0, numLevels());越界抛 IndexOutOfBoundsException
     * @param i     int 行下标 ∈ [0, size());越界抛 IndexOutOfBoundsException
     * @return Object 标签值(可能为 null)
     */
    public Object get(int level, int i) { return levels[level][i]; }

    /**
     * 2 级兼容方法(等价于 get(0, i))。
     * @param i int 行下标 ∈ [0, size())
     * @return Object level 0 第 i 行值
     */
    public Object getLevel0(int i) { return levels[0][i]; }

    /**
     * 2 级兼容方法(等价于 get(1, i))。
     * @param i int 行下标 ∈ [0, size())
     * @return Object level 1 第 i 行值
     * @throws ArrayIndexOutOfBoundsException 当 numLevels() < 2
     */
    public Object getLevel1(int i) { return levels[1][i]; }

    /**
     * 取第 level 级整级标签副本。
     * @param level int 级下标 ∈ [0, numLevels())
     * @return Object[] 该级标签副本,长度 == size()
     */
    public Object[] level(int level) { return levels[level].clone(); }

    /**
     * 2 级兼容(等价于 level(0))。
     * @return Object[] level0 副本
     */
    public Object[] level0() { return levels[0].clone(); }

    /**
     * 2 级兼容(等价于 level(1))。
     * @return Object[] level1 副本
     * @throws ArrayIndexOutOfBoundsException 当 numLevels() < 2
     */
    public Object[] level1() { return levels[1].clone(); }

    /**
     * 各级名。
     * @return String[] 长度 == numLevels();元素可为 null(未命名);返回副本
     */
    public String[] names() { return names.clone(); }

    /**
     * 某级名。
     * @param level int 级下标
     * @return String 级名,未命名时返回 null
     */
    public String name(int level) { return names[level]; }

    /**
     * 切片 [start, end)(各级同步切片)。
     * @param start int 起始(含)
     * @param end   int 结束(不含)
     * @return MultiIndex 新实例,长度 = end-start,级数不变
     */
    public MultiIndex slice(int start, int end) {
        Object[][] newLevels = new Object[levels.length][];
        for (int k = 0; k < levels.length; k++) {
            newLevels[k] = Arrays.copyOfRange(levels[k], start, end);
        }
        return new MultiIndex(names, newLevels);
    }

    /**
     * 删除若干级(对齐 pandas MultiIndex.droplevel)。
     * 重复下标先去重 —— 因为 levelIndices=[0,0,0] 在 3 级索引上等价于删 1 个唯一级
     * (按 length 判断会误报"不能删除所有级"),所以先去重再判断。
     * @param levelIndices int[] 要删除的级下标(0-based);可乱序、可重复;不能删完(至少留 1 级)
     * @return MultiIndex 新实例,级数 = numLevels() - 去重后下标数
     * @throws IllegalArgumentException 删完所有级 或 levelIndices 含越界下标
     */
    public MultiIndex droplevel(int... levelIndices) {
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
        for (int lv : levelIndices) unique.add(lv);
        if (unique.size() >= levels.length) {
            throw new IllegalArgumentException("droplevel 不能删除所有级(至少留 1 级)");
        }
        boolean[] remove = new boolean[levels.length];
        for (int lv : unique) {
            if (lv < 0 || lv >= levels.length) {
                throw new IllegalArgumentException("droplevel 下标越界:" + lv);
            }
            remove[lv] = true;
        }
        int newN = levels.length - unique.size();
        Object[][] newLevels = new Object[newN][];
        String[] newNames = new String[newN];
        int j = 0;
        for (int k = 0; k < levels.length; k++) {
            if (!remove[k]) {
                newLevels[j] = levels[k].clone();
                newNames[j] = names[k];
                j++;
            }
        }
        return new MultiIndex(newNames, newLevels);
    }

    /**
     * 交换两级(对齐 pandas MultiIndex.swaplevel)。
     * @param i int 第一个级下标
     * @param j int 第二个级下标
     * @return MultiIndex 新实例(级数不变,i 与 j 两级交换)
     */
    public MultiIndex swaplevel(int i, int j) {
        if (i < 0 || i >= levels.length || j < 0 || j >= levels.length) {
            throw new IllegalArgumentException("swaplevel 下标越界:i=" + i + " j=" + j);
        }
        Object[][] newLevels = new Object[levels.length][];
        String[] newNames = names.clone();
        for (int k = 0; k < levels.length; k++) newLevels[k] = levels[k].clone();
        Object[] tmpLevel = newLevels[i]; newLevels[i] = newLevels[j]; newLevels[j] = tmpLevel;
        String tmpName = newNames[i]; newNames[i] = newNames[j]; newNames[j] = tmpName;
        return new MultiIndex(newNames, newLevels);
    }

    /**
     * 重排各级顺序(对齐 pandas MultiIndex.reorder_levels)。
     * @param order int[] 新顺序,长度必须 == numLevels();每个下标 ∈ [0, numLevels());不允许重复
     * @return MultiIndex 新实例(各级按 order 重排)
     * @throws IllegalArgumentException order 长度不符 / 含越界 / 含重复下标
     */
    public MultiIndex reorder_levels(int... order) {
        if (order.length != levels.length) {
            throw new IllegalArgumentException(
                "reorder_levels 顺序长度 " + order.length + " ≠ 级数 " + levels.length);
        }
        boolean[] used = new boolean[levels.length];
        for (int lv : order) {
            if (lv < 0 || lv >= levels.length) {
                throw new IllegalArgumentException("reorder_levels 下标越界:" + lv);
            }
            if (used[lv]) {
                throw new IllegalArgumentException("reorder_levels 含重复下标:" + lv);
            }
            used[lv] = true;
        }
        Object[][] newLevels = new Object[levels.length][];
        String[] newNames = new String[levels.length];
        for (int k = 0; k < levels.length; k++) {
            newLevels[k] = levels[order[k]].clone();
            newNames[k] = names[order[k]];
        }
        return new MultiIndex(newNames, newLevels);
    }

    /**
     * @return String 多行格式,最多前 10 行 + "..."(行数 > 10 时);每行 "/"-分隔各级
     */
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("MultiIndex[levels=").append(numLevels())
            .append(", len=").append(size()).append("]\n");
        int cap = Math.min(size(), 10);
        for (int i = 0; i < cap; i++) {
            for (int k = 0; k < numLevels(); k++) {
                if (k > 0) sb.append(" / ");
                sb.append(levels[k][i]);
            }
            sb.append('\n');
        }
        if (size() > cap) sb.append("...\n");
        return sb.toString();
    }
}
