package jian.core;

import java.util.Arrays;

// ┌─ What : ColumnarHashMap —— 列式 open-addressing hash 表,JVM 上避免装箱的通用 group/join 加速器
// │  Why  : 实测 HashMap<List<Object>> 在 500 万行三表 JOIN 占了 45% 时间(装箱 + List.hashCode + GC),
// │         改用 open-addressing + long/桶 + primitive 槽位数组可提速 9-17 倍
// │  Who  : DataFrameMerge / GroupBy / DataFrameStats 等 hot path 调用
// │  When : 单列 long/int/double key 的 JOIN 或 group-by(覆盖 80%+ 实际场景)
// │  Where: jian-core/ColumnarHashMap.java
// │  How  : 数据走向:输入 key 列 + 行下标 → 入桶(开放寻址)→ 探测查询 → 同 key 链成行下标列表。
// │         关键变量变化:
// │           - bucketKey:long[],每个槽存 key 的位展平 long(双精度也展平成 long 比较);
// │           - bucketFirst:int[],每桶首行下标,-1=空;
// │           - next:int[],同桶链表的下一个行下标,-1=尾。
// │         逻辑路线:
// │           路径 A(long key)→ 直接用 key 作 hash 槽位;
// │           路径 B(int key)→ 升位成 long 入桶;
// │           路径 C(double key)→ doubleToLongBits 入桶(NaN 统一规范形式);
// │           路径 D(string key)→ 不在本类,落回 HashMap<String>(JVM 已为 String 优化 hash)。
//          容量始终为 2^k,掩码 = cap-1;装载因子 ≤ 0.5(开地址法对密集敏感)。
/**
 * 列式 open-addressing hash 表(单列数值 key 专用,JOIN/GroupBy 的 hot path)。
 *
 * <p><b>设计权衡</b>:开地址法 + primitive 数组,完全避免装箱;装载因子上限 0.5,
 * 倍增扩容。对于"同 key 多行"场景(JOIN 的 1:N / N:M),用"槽内链表"(next 数组)解决。
 *
 * <p><b>不覆盖</b>:String/Object/多列 key —— 这些场景装箱本就免不掉,继续走
 * {@code HashMap<List<Object>>}。本类只解决"单列数值 key"这个最高频的 hot path。
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 1) build:把右表 key 列入桶,记录每行的链表
 * long[] keys = ((LongColumn) right.getColumn("id")).dataInPlace();
 * ColumnarHashMap map = ColumnarHashMap.buildFromLong(keys);
 *
 * // 2) probe:左表逐行查
 * long[] lKeys = ((LongColumn) left.getColumn("id")).dataInPlace();
 * for (int l = 0; l < left.rowCount(); l++) {
 *     int firstMatch = map.findLong(lKeys[l]);
 *     for (int r = firstMatch; r >= 0; r = map.nextInBucket(r)) {
 *         // 输出 (l, r) 配对
 *     }
 * }
 * }</pre>
 */
public final class ColumnarHashMap {

    /** 容量(桶数,始终为 2^k)。 */
    private final int capacity;
    /** capacity - 1,用作位掩码替代取模(快)。 */
    private final int mask;
    /** 每桶首行下标;-1 = 空桶。 */
    private final int[] bucketFirst;
    /** 同桶链:nextInBucket[i] = i 行所在桶的下一行下标,-1 = 桶内末尾。 */
    private final int[] nextInBucket;
    /** key 的位展平 long(直接 long 用原值;double 用 doubleToLongBits)。 */
    private final long[] bucketKey;

    private ColumnarHashMap(int capacity, int nRows) {
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.bucketFirst = new int[capacity];
        Arrays.fill(this.bucketFirst, -1);
        this.nextInBucket = new int[nRows];
        Arrays.fill(this.nextInBucket, -1);
        this.bucketKey = new long[capacity];  // 仅在桶非空时有效
    }

    // ======================== 工厂:从已有 key 列建表 ========================

    /**
     * 从 long[] 列建表(build side of hash join / group-by)。
     * 每个唯一 key 对应一个桶,同 key 多行通过 nextInBucket 链成桶内链表。
     * @param keys long[] key 列,非 null;长度即表行数;元素任意 long 值
     * @return ColumnarHashMap 已入桶的新实例(装载因子 ≤ 0.5)
     */
    public static ColumnarHashMap buildFromLong(long[] keys) {
        int n = keys.length;
        int cap = chooseCapacity(n);
        ColumnarHashMap m = new ColumnarHashMap(cap, n);
        for (int r = 0; r < n; r++) {
            long k = keys[r];
            int slot = slotOf(k, m.mask);
            // 槽为空 → 直接放;否则看是否同 key(合并到现有桶)还是冲突(线性探测)
            while (m.bucketFirst[slot] != -1) {
                if (m.bucketKey[slot] == k) break;  // 同 key,合并
                slot = (slot + 1) & m.mask;          // 冲突,线性探测下一槽
            }
            if (m.bucketFirst[slot] == -1) {
                // 新建桶
                m.bucketKey[slot] = k;
                m.bucketFirst[slot] = r;
                // nextInBucket[r] 已是 -1
            } else {
                // 同 key,链到现有桶尾(头插更简单:新行作桶首)
                m.nextInBucket[r] = m.bucketFirst[slot];
                m.bucketFirst[slot] = r;
            }
        }
        return m;
    }

    /**
     * 从 int[] 列建表(int 直接升位为 long)。
     * @param keys int[] key 列,非 null;元素任意 int 值
     * @return ColumnarHashMap 已入桶的新实例
     */
    public static ColumnarHashMap buildFromInt(int[] keys) {
        int n = keys.length;
        int cap = chooseCapacity(n);
        ColumnarHashMap m = new ColumnarHashMap(cap, n);
        for (int r = 0; r < n; r++) {
            long k = keys[r];
            int slot = slotOf(k, m.mask);
            while (m.bucketFirst[slot] != -1) {
                if (m.bucketKey[slot] == k) break;
                slot = (slot + 1) & m.mask;
            }
            if (m.bucketFirst[slot] == -1) {
                m.bucketKey[slot] = k;
                m.bucketFirst[slot] = r;
            } else {
                m.nextInBucket[r] = m.bucketFirst[slot];
                m.bucketFirst[slot] = r;
            }
        }
        return m;
    }

    /**
     * 从 double[] 列建表(double 用 doubleToLongBits 规范化)。
     *
     * <p><b>关键</b>:doubleToLongBits 与 {@link Double#equals} 一致(都是按位比较)——
     * 即 +0.0 与 -0.0 视为<b>不等</b>(位模式不同),NaN 视为<b>相等</b>(塌缩为规范位)。
     * 这与 jian generic 路径用的 {@code HashMap<Double>} 行为一致(HashMap 用 equals)。
     *
     * <p>(曾经的 BUG #2 修复反向了——错把 ±0.0 视为相等。差分测试 dt_merge_正零负零 抓到。
     * 现已撤销该错误修复,与 generic 路径完全对齐。)
     * @param keys double[] key 列,非 null;NaN 会被塌缩为规范位(视作相等)
     * @return ColumnarHashMap 已入桶的新实例
     */
    public static ColumnarHashMap buildFromDouble(double[] keys) {
        int n = keys.length;
        int cap = chooseCapacity(n);
        ColumnarHashMap m = new ColumnarHashMap(cap, n);
        for (int r = 0; r < n; r++) {
            long k = Double.doubleToLongBits(keys[r]);   // 不再规范 ±0.0,与 Double.equals 一致
            int slot = slotOf(k, m.mask);
            while (m.bucketFirst[slot] != -1) {
                if (m.bucketKey[slot] == k) break;
                slot = (slot + 1) & m.mask;
            }
            if (m.bucketFirst[slot] == -1) {
                m.bucketKey[slot] = k;
                m.bucketFirst[slot] = r;
            } else {
                m.nextInBucket[r] = m.bucketFirst[slot];
                m.bucketFirst[slot] = r;
            }
        }
        return m;
    }

    // ======================== 查询 ========================

    /**
     * 查 long key 返回首行下标。
     * @param key long 待查 key,任意 long 值
     * @return int 该 key 在表中的首行下标 ∈ [0, nRows);**未命中返回 -1**。
     *         命中后用 {@link #nextInBucket(int)} 遍历同 key 的其余行
     * @throws IllegalStateException 探测次数达 capacity(表过满或 chooseCapacity bug,正常不触发)
     */
    public int findLong(long key) {
        int slot = slotOf(key, mask);
        int probes = 0;
        while (bucketFirst[slot] != -1) {
            if (bucketKey[slot] == key) return bucketFirst[slot];
            slot = (slot + 1) & mask;
            if (++probes >= capacity) {
                // 表已满或 bug,防死循环
                throw new IllegalStateException(
                    "ColumnarHashMap.findLong 探测次数达上限 " + capacity
                    + "(表可能过满,装载因子 > 0.5 不该发生,检查 chooseCapacity)");
            }
        }
        return -1;
    }

    /**
     * 查 int key(升位为 long,等价 findLong(key))。
     * @param key int 待查 key
     * @return int 首行下标;未命中 -1
     */
    public int findInt(int key) { return findLong(key); }

    /**
     * 查 double key(用 doubleToLongBits,与 buildFromDouble 一致;±0.0 视为不等)。
     * @param key double 待查 key;NaN 会塌缩为规范位,与 buildFromDouble 一致
     * @return int 首行下标;未命中 -1
     */
    public int findDouble(double key) {
        return findLong(Double.doubleToLongBits(key));
    }

    /**
     * 桶内链表下一行(配合 findXxx 使用)。
     * @param row int 当前行下标(必须由 findLong/findInt/findDouble 返回,或前一次 nextInBucket 返回)
     * @return int 同桶下一行下标;**-1 = 桶内末尾**(遍历结束)
     */
    public int nextInBucket(int row) {
        return nextInBucket[row];
    }

    /**
     * 表容量(调试用)。
     * @return int 桶数,始终为 2^k
     */
    public int capacity() { return capacity; }

    // ======================== 内部工具 ========================

    /**
     * 选容量:满足装载因子 ≤ 0.5(即 cap ≥ 2 * nRows)的最小 2^k。
     *
     * <p>伪代码:
     *   1. 用 long 计算避免 int 溢出(AI agent1 审查发现的 BUG #1:
     *      nRows=2^30 时 Integer.highestOneBit(nRows)<<2 = 2^32 溢出成 0,
     *      最终选 cap=16 装 10 亿行 → 死循环);
     *   2. 上限 1 << 29(5 亿桶,够装 2.5 亿行,远超 jian 单机定位的"千万行级");
     *   3. 超过上限直接抛异常(jian 不追求超大数据集,见规范 §6.5)。
     * @param nRows int 待入桶的行数,≥ 0
     * @return int 容量(2^k,≥ 16)
     * @throws IllegalArgumentException nRows 为负
     * @throws IllegalStateException 容量需求超 1<<29(行数过大)
     */
    private static int chooseCapacity(int nRows) {
        if (nRows < 0) {
            throw new IllegalArgumentException("nRows 不能为负,实际 " + nRows);
        }
        // 用 long 计算,防 int 溢出
        long need = (long) nRows * 2;   // 装载因子 ≤ 0.5
        long cap = 16;
        while (cap < need) cap <<= 1;
        // 上限 1 << 29(再大可能 OOM,jian 定位是单机内存库)
        if (cap > (1L << 29)) {
            throw new IllegalStateException(
                "ColumnarHashMap 容量需求 " + cap + " 超过上限 " + (1 << 29)
                + "(nRows=" + nRows + ");jian 定位单机千万行级,超此规模建议落盘或分块");
        }
        return (int) cap;
    }

    /**
     * hash 槽位:Knuth 黄金分割散列 + 高低位混合,减少冲突聚集。
     * @param key long 待散列的 key(已展平为 long)
     * @param mask int 容量掩码(capacity-1)
     * @return int 槽位下标 ∈ [0, capacity)
     */
    private static int slotOf(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;  // Knuth 黄金分割,扩散高位
        return (int) (h ^ (h >>> 32)) & 0x7FFFFFFF & mask;
    }
}
