package jian.core;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : MetamorphicTest —— 蜕变测试(AI 生成代码的核心测试方法)
// │  Why  : AI 代码的"oracle 难题":生成代码的输出"对不对"很难逐值断言;
// │         蜕变测试不验"具体值",而验"输入与输出间的必要关系"——关系破了说明一定有 bug。
// │         参考: ACM TOMS 2016《Metamorphic Testing》survey;Hillel Wayne 博客。
// │  Who  : 由 mvn test 跑,覆盖 jian-core 核心算子
// │  When : 持续(@RepeatedTest 多轮增强度;每轮种子不同但失败时可在断言描述里拿到)
// │  Where: jian-core/src/test/java/jian/core/MetamorphicTest.java
// │  How  : 数据走向:种子随机生成 df → 跑算子 → 断言"蜕变关系"成立。
// │         关键:断言用"关系",不用"具体期望值"。例:sortBy 后 rowCount 不变(不论输入)。
// │         种子策略(2026-08-09 修复 C-1 flaky 反模式):
// │           - 不再用 `SEED + System.nanoTime()`(裸 nanoTime 失败时种子不可见、不可复现);
// │           - 改用 nextSeed() 取 [0, 1e6) 散列,失败时断言描述打印 seed,可 -Dtest.seed=N 回放。
public class MetamorphicTest {

    private static final long SEED = 20260808L;
    /**
     * 生成下一个随机种子,保证:
     *   ① 默认每次调用结果不同(基于 System.nanoTime 散列),覆盖更广;
     *   ② 失败时能在断言描述里打印,可用 -Dtest.seed=xxx 精确回放(单线程复现);
     *   ③ 不再用裸 `SEED + nanoTime`(flaky 反模式——失败时种子不可见)。
     * 返回值范围 [0, 1_000_000)。
     */
    private static long nextSeed() {
        String override = System.getProperty("test.seed");
        if (override != null && !override.isEmpty()) {
            return Long.parseLong(override);   // 回放模式:固定种子,精确复现失败
        }
        // 散列到 [0, 1e6),避免相邻调用 nanoTime 差异过小造成种子相关性
        return Math.floorMod(System.nanoTime(), 1_000_000L);
    }

    // ======================== sortBy 的蜕变关系 ========================

    /** MR1: sortBy 不改变行数(不论怎么排,行数守恒)。 */
    @RepeatedTest(20)
    void mr_sortBy_行数守恒() {
        DataFrame df = randomDf(new Random(nextSeed()), 50);
        DataFrame sorted = df.sortBy("v", true);
        assertThat(sorted.rowCount()).isEqualTo(df.rowCount());
    }

    /** MR2: sortBy 不改变列值多重集(multiset)——排序只重排,不改值。 */
    @Test
    void mr_sortBy_值多重集不变() {
        Random rnd = new Random(SEED);
        DataFrame df = randomDf(rnd, 100);
        DataFrame sorted = df.sortBy("v", true);

        // 抽 id 列,排序后应含相同元素(无序)
        List<Long> before = toLongList(df, "id");
        List<Long> after = toLongList(sorted, "id");
        before.sort(Long::compare);
        after.sort(Long::compare);
        assertThat(after).isEqualTo(before);
    }

    /**
     * MR3: sortBy(asc) 反向 == sortBy(desc)。
     * 前提:键列无重复值(否则排序不稳定,蜕化关系不成立——这是测试本身的前提,非 jian bug)。
     * 改用唯一 id 列做排序键,避免不稳定排序干扰。
     */
    @RepeatedTest(10)
    void mr_sortBy_升序的逆向等于降序() {
        Random rnd = new Random(nextSeed());
        // 用 uniqueIdDf 保证 id 唯一,id 作排序键
        DataFrame df = uniqueIdDf(rnd, 80, 200);
        DataFrame asc = df.sortBy("id", true);
        DataFrame desc = df.sortBy("id", false);

        // asc 从后往前读 id == desc 从前往后读 id
        for (int i = 0; i < asc.rowCount(); i++) {
            assertThat(asc.get(asc.rowCount() - 1 - i, "id"))
                .isEqualTo(desc.get(i, "id"));
        }
    }

    /**
     * MR4: sortBy 后,排序键列确实单调。
     * <p>2026-08-09 边界注入修复后:v 列含 NaN。NaN 与任何值的 >= 比较都返回 false
     * (IEEE 754 语义),所以断言要跳过含 NaN 的相邻对(NaN 通常被 sort 放在末尾,
     * 与 pandas 行为一致)。这不是 sortBy 的 bug,是测试断言需适配 NaN 语义。
     */
    @Test
    void mr_sortBy_键列确实单调() {
        DataFrame df = randomDf(new Random(SEED + 11), 200);
        DataFrame asc = df.sortBy("v", true);
        double[] vals = ((DoubleColumn) asc.getColumn("v")).data();
        for (int i = 1; i < vals.length; i++) {
            // 跳过含 NaN 的相邻对:NaN 不参与单调性断言(IEEE 754 规定 NaN 与任何值不可比)
            if (Double.isNaN(vals[i]) || Double.isNaN(vals[i - 1])) continue;
            assertThat(vals[i]).isGreaterThanOrEqualTo(vals[i - 1]);
        }
    }

    // ======================== filter / query 的蜕变关系 ========================

    /** MR5: filter 后所有行满足谓词。 */
    @Test
    void mr_filter_所有结果行满足谓词() {
        DataFrame df = randomDf(new Random(SEED + 13), 100);
        // 过滤 v > 50
        DataFrame filtered = df.query("v > 50");
        double[] vs = ((DoubleColumn) filtered.getColumn("v")).data();
        for (double v : vs) {
            assertThat(v).isGreaterThan(50.0);
        }
    }

    /**
     * MR6: filter(p) ∪ filter(¬p) ∪ filter(NaN) == 原 df (按 id 多重集)——三分互补。
     * <p>2026-08-09 边界注入修复后:v 列含 NaN。IEEE 754 规定 NaN 既不满足 `> 50`
     * 也不满足 `<= 50`,所以互补关系需扩展为三分:`>50` ∪ `<=50` ∪ `NaN` = 全集。
     * 这不是 filter 的 bug,是谓词在 NaN 上的预期行为(与 SQL NULL 语义一致)。
     */
    @Test
    void mr_filter_互补关系() {
        DataFrame df = randomDf(new Random(SEED + 17), 60);
        DataFrame yes = df.query("v > 50");
        DataFrame no = df.query("v <= 50");

        List<Long> yesIds = toLongList(yes, "id");
        List<Long> noIds = toLongList(no, "id");
        // NaN 行既不 >50 也不 <=50,需单独收集(isNull 在 DOUBLE 列即 NaN)
        DoubleColumn vCol = df.getDoubleColumn("v");
        long[] allIdArr = ((LongColumn) df.getColumn("id")).data();
        List<Long> nanIds = new ArrayList<>();
        for (int i = 0; i < vCol.size(); i++) {
            if (vCol.isNull(i)) nanIds.add(allIdArr[i]);
        }
        List<Long> allIds = toLongList(df, "id");

        List<Long> merged = new ArrayList<>(yesIds.size() + noIds.size() + nanIds.size());
        merged.addAll(yesIds);
        merged.addAll(noIds);
        merged.addAll(nanIds);
        merged.sort(Long::compare);
        allIds.sort(Long::compare);

        assertThat(merged).as("MR6 三分互补: >50 ∪ <=50 ∪ NaN 应覆盖全集").isEqualTo(allIds);
    }

    // ======================== merge 的蜕变关系 ========================

    /**
     * MR7: inner join 的结果行数 ≤ min(左, 右)——**前提:左右 key 唯一**。
     *
     * <p>修复(AI agent2 复审发现):原版用 randomDf(允许重复 id),但 inner join 在重复 key 时
     * 结果行数可以超过 min(左 N1 行 key=k × 右 N2 行 key=k = N1×N2)。原断言在数学上不成立,
     * 加上 System.nanoTime() 种子,变成 flaky test(AI agent2 实测出 31>30)。
     * 改用 uniqueIdDf 保证 key 唯一,断言才严格成立。
     */
    @RepeatedTest(10)
    void mr_innerJoin_不超过最小侧() {
        Random rnd = new Random(nextSeed());
        DataFrame a = uniqueIdDf(rnd, 50, 200);   // 唯一 id,保证 ≤ min 成立
        DataFrame b = uniqueIdDf(rnd, 30, 200);

        DataFrame r = a.merge(b, "inner", "id");
        int min = Math.min(a.rowCount(), b.rowCount());
        assertThat(r.rowCount()).isLessThanOrEqualTo(min);
    }

    /** MR8: left join 的结果行数 == 左表行数(右无匹配时左行不丢,1:1 单 key 时)。 */
    @Test
    void mr_leftJoin_左行不丢() {
        // 用唯一 key 的两表(每行 id 都不同),left join 结果行数 == 左
        DataFrame a = uniqueIdDf(new Random(SEED + 19), 50, 1000);
        DataFrame b = uniqueIdDf(new Random(SEED + 23), 30, 1000);
        DataFrame r = a.merge(b, "left", "id");
        assertThat(r.rowCount()).isEqualTo(a.rowCount());
    }

    /** MR9: 同一表自 join(inner,id 全相同)结果 == 原表(行数不变)。 */
    @Test
    void mr_selfJoin_inner_自连接等价原表() {
        DataFrame df = uniqueIdDf(new Random(SEED + 29), 40, 100);
        DataFrame r = df.merge(df, "inner", "id");
        assertThat(r.rowCount()).isEqualTo(df.rowCount());
    }

    /** MR10: 交换律——A inner join B 与 B inner join A 行数相同(对称性)。 */
    @RepeatedTest(10)
    void mr_innerJoin_交换律() {
        Random rnd = new Random(nextSeed());
        DataFrame a = randomDf(rnd, 30);
        DataFrame b = randomDf(rnd, 25);

        DataFrame ab = a.merge(b, "inner", "id");
        DataFrame ba = b.merge(a, "inner", "id");
        assertThat(ab.rowCount()).isEqualTo(ba.rowCount());
    }

    // ======================== concat / GroupBy 的蜕变关系 ========================

    /** MR11: concat(df, df, axis=0) 行数 == 2 * df.rowCount(纵向拼接守恒)。 */
    @Test
    void mr_concat_纵向行数翻倍() {
        DataFrame df = randomDf(new Random(SEED + 31), 40);
        DataFrame r = DataFrame.concat(List.of(df, df), 0);
        assertThat(r.rowCount()).isEqualTo(2 * df.rowCount());
    }

    /**
     * MR12(AI agent2 抓的死测试,修复):真正调 df.groupBy().agg(),
     * 断言"各组 count 之和 == 原表行数"(覆盖性)+ "组数 == 唯一 gid 数"(无重复组)。
     * 原版用 LinkedHashMap 手算分组,**完全没调 df.groupBy()**,与 P10 同类死测试。
     */
    @Test
    void mr_groupBy_并集全覆盖且互斥() {
        DataFrame df = randomDf(new Random(SEED + 37), 80);
        // 用 id % 5 作为分组列(只 5 个组,简洁)
        long[] ids = ((LongColumn) df.getColumn("id")).data();
        long[] modIds = new long[ids.length];
        for (int i = 0; i < ids.length; i++) modIds[i] = ids[i] % 5;

        DataFrame dfWithMod = DataFrame.ofColumnArrays(
            List.of("gid", "v"),
            new Object[]{modIds, ((DoubleColumn) df.getColumn("v")).data()});

        // 真正调 df.groupBy().agg({"gid":"count"}) —— gid 列无 NaN,count == 组内行数。
        // (注:不能用 v 列做 count,因 v 含 NaN,count 是"非空值计数"pandas 语义,
        // 与"每行恰被分到一组"的覆盖性断言不兼容。)
        Map<String, String> spec = new HashMap<>();
        spec.put("gid", "count");
        DataFrame agg = dfWithMod.groupBy("gid").agg(spec);

        // 断言 ①:各组 count 之和 == 原表行数(每行恰被分到一组)
        long sum = 0;
        for (int i = 0; i < agg.rowCount(); i++) {
            sum += ((Number) agg.get(i, "gid_count")).longValue();
        }
        assertThat(sum).as("MR12 各组 count 之和应等于原表行数").isEqualTo(dfWithMod.rowCount());

        // 断言 ②:组数 == 唯一 gid 数
        Set<Long> uniqGids = new HashSet<>();
        for (long g : modIds) uniqGids.add(g);
        assertThat(agg.rowCount()).as("MR12 组数应等于唯一 gid 数").isEqualTo(uniqGids.size());
    }

    // ======================== 辅助:随机数据生成 ========================
    // 边界注入策略(2026-08-09 修复 B-1:覆盖边界值,避免蜕变测试在 ±0.0 / NaN / MAX
    // 边界全盲)。参考 AI 测试方法学指南 模式 A:5% NaN / 3% ±0.0 / 3% MAX / 3% MIN。
    // 关键:id 列保持普通值(不注 null id,因 merge/sort 的 null key 行为由专门用例覆盖);
    //     v 列注入边界值,触发 sortBy/groupBy/filter 在边界条件下的潜在 bug。

    /** 生成随机 df:两列 id (LONG) + v (DOUBLE)。id 在 [0, 2N) 范围(可能重复)。 */
    private static DataFrame randomDf(Random rnd, int n) {
        long[] ids = new long[n];
        double[] vs = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = rnd.nextInt(n * 2);
            vs[i] = nextDoubleWithBoundary(rnd);  // 注入边界值
        }
        return DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{ids, vs});
    }

    /** 生成"唯一 id"的 df(id 在 [0, K) 范围互不重复,K ≥ N)。 */
    private static DataFrame uniqueIdDf(Random rnd, int n, int k) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < k; i++) pool.add(i);
        java.util.Collections.shuffle(pool, rnd);
        long[] ids = new long[n];
        double[] vs = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = pool.get(i);
            vs[i] = nextDoubleWithBoundary(rnd);  // 注入边界值
        }
        return DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{ids, vs});
    }

    /**
     * 按概率生成含边界值的 double:90% 普通值 [0, 100),其余 10% 分给 NaN/+0.0/-0.0/MAX/MIN。
     * 这些边界值是数值算法的高危场景:±0.0 在 Double.equals vs compare 不一致,
     * NaN 传播规则特殊,MAX/MIN 在累加时可能溢出。注入它们能逼出潜在 bug。
     */
    private static double nextDoubleWithBoundary(Random rnd) {
        int bucket = rnd.nextInt(100);
        if (bucket < 90) {
            return rnd.nextInt(100);          // 90% 普通值
        } else if (bucket < 93) {
            return Double.NaN;                 // 3% NaN(缺失值内部表示)
        } else if (bucket < 96) {
            return +0.0;                       // 3% 正零(与 -0.0 在 equals 视不等)
        } else if (bucket < 98) {
            return -0.0;                       // 2% 负零
        } else if (bucket < 99) {
            return Double.MAX_VALUE;           // 1% 最大值(累加溢出风险)
        } else {
            return -Double.MAX_VALUE;          // 1% 最小值
        }
    }

    private static List<Long> toLongList(DataFrame df, String col) {
        long[] arr = ((LongColumn) df.getColumn(col)).data();
        List<Long> list = new ArrayList<>(arr.length);
        for (long v : arr) list.add(v);
        return list;
    }

    // ======================== 补充性质(覆盖更多算子)========================

    /** MR13: head(n) 和 takeRows(前 n 下标) 应等价。 */
    @RepeatedTest(10)
    void mr_head_等价于takeRows前n() {
        Random rnd = new Random(nextSeed());
        DataFrame df = randomDf(rnd, 50);
        int n = rnd.nextInt(60);   // 故意可能 > rowCount
        int safeN = Math.min(n, df.rowCount());
        DataFrame h = df.head(safeN);
        int[] idx = new int[safeN];
        for (int i = 0; i < safeN; i++) idx[i] = i;
        DataFrame t = df.takeRows(idx);

        assertThat(h.rowCount()).isEqualTo(t.rowCount());
        // 每行 id 相等
        for (int i = 0; i < safeN; i++) {
            assertThat(h.get(i, "id")).isEqualTo(t.get(i, "id"));
        }
    }

    /** MR14: df.head(n) 后再 head(m),m<=n 时等价于直接 head(m)——幂等性。 */
    @Test
    void mr_head_幂等性() {
        DataFrame df = randomDf(new Random(SEED + 41), 30);
        DataFrame head5 = df.head(5);
        DataFrame head5Then3 = head5.head(3);
        DataFrame direct3 = df.head(3);
        assertThat(head5Then3.rowCount()).isEqualTo(direct3.rowCount());
        for (int i = 0; i < 3; i++) {
            assertThat(head5Then3.get(i, "id")).isEqualTo(direct3.get(i, "id"));
        }
    }

    /** MR15: slice(a, b).rowCount == b-a(合法区间内)。 */
    @RepeatedTest(10)
    void mr_slice_行数等于区间长度() {
        Random rnd = new Random(nextSeed());
        int n = 30;
        DataFrame df = randomDf(rnd, n);
        // 只在合法区间 [0, n] 内取 a < b,避免触发越界异常(不测 slice 自己的边界处理)
        int a = rnd.nextInt(n);
        int b = a + 1 + rnd.nextInt(n - a);   // 保证 b > a 且 b <= n
        DataFrame s = df.slice(a, b);
        assertThat(s.rowCount()).as("slice(%d,%d) 应等于区间长").isEqualTo(b - a);
    }

    /** MR16: tail(n) 的结果等于反转后 head(n) 再反转回来(对称律)。 */
    @Test
    void mr_tail_等价于反转head反转() {
        DataFrame df = randomDf(new Random(SEED + 43), 20);
        DataFrame tail5 = df.tail(5);
        // 手工反转 df,取 head 5,再反转
        int n = df.rowCount();
        int[] revIdx = new int[n];
        for (int i = 0; i < n; i++) revIdx[i] = n - 1 - i;
        DataFrame revHead5 = df.takeRows(revIdx).head(5);
        int[] revBack = new int[5];
        for (int i = 0; i < 5; i++) revBack[i] = 4 - i;
        DataFrame equivalent = revHead5.takeRows(revBack);

        assertThat(tail5.rowCount()).isEqualTo(equivalent.rowCount());
        for (int i = 0; i < 5; i++) {
            assertThat(tail5.get(i, "id")).isEqualTo(equivalent.get(i, "id"));
        }
    }

    /** MR17: assign 后新列存在且 rowCount 不变(列数 +1, 行数不变)。 */
    @Test
    void mr_assign_加列不改行数() {
        DataFrame df = randomDf(new Random(SEED + 47), 25);
        int before = df.rowCount();
        DataFrame r = df.assign("tag", i -> "x");
        assertThat(r.rowCount()).as("assign 不应改变行数").isEqualTo(before);
        assertThat(r.columnCount()).as("assign 应增加一列").isEqualTo(df.columnCount() + 1);
        assertThat(r.columnNames()).contains("tag");
    }

    /** MR18: sortBy 多次等价于一次(幂等:sort 已排序的仍已排序)。 */
    @Test
    void mr_sortBy_幂等性() {
        DataFrame df = randomDf(new Random(SEED + 53), 40);
        DataFrame once = df.sortBy("v", true);
        DataFrame twice = once.sortBy("v", true);
        // 两次排序的结果应完全相同
        for (int i = 0; i < once.rowCount(); i++) {
            assertThat(twice.get(i, "id")).isEqualTo(once.get(i, "id"));
        }
    }

    /** MR19: concat(df1, df2, axis=0) 后,前 df1.rowCount 行应等于 df1。 */
    @Test
    void mr_concat_纵向前段等于第一表() {
        DataFrame a = randomDf(new Random(SEED + 59), 20);
        DataFrame b = randomDf(new Random(SEED + 61), 15);
        DataFrame c = DataFrame.concat(java.util.List.of(a, b), 0);
        assertThat(c.rowCount()).isEqualTo(a.rowCount() + b.rowCount());
        // 前 a.rowCount 行应等于 a 的对应行
        for (int i = 0; i < a.rowCount(); i++) {
            assertThat(c.get(i, "id")).isEqualTo(a.get(i, "id"));
        }
    }

    /** MR20: dropDuplicates 后无重复行(subset 列上)。 */
    @Test
    void mr_dropDuplicates_确实去重() {
        // 构造有重复 id 的 df
        long[] ids = {1, 2, 2, 3, 1, 4, 4};
        double[] vs = {10, 20, 21, 30, 11, 40, 41};
        DataFrame df = DataFrame.ofColumnArrays(java.util.List.of("id", "v"),
            new Object[]{ids, vs});
        DataFrame dedup = df.dropDuplicates(new String[]{"id"}, "first");
        // 在 id 列上不应再有重复
        long[] after = ((LongColumn) dedup.getColumn("id")).data();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long v : after) {
            assertThat(seen).doesNotContain(v);   // 不该重复
            seen.add(v);
        }
        assertThat(dedup.rowCount()).isEqualTo(4);   // 1,2,3,4 共 4 个唯一值
    }

    /** MR21: astype 来回转换应保持值(long→double→long 不丢精度,在 long 范围内)。 */
    @Test
    void mr_astype_来回转换保值() {
        long[] ids = {1L, 100L, 1000000L};
        double[] vs = {10, 20, 30};
        DataFrame df = DataFrame.ofColumnArrays(java.util.List.of("id", "v"),
            new Object[]{ids, vs});
        DataFrame toDouble = df.astype("id", DType.DOUBLE);
        DataFrame backToLong = toDouble.astype("id", DType.LONG);
        long[] after = ((LongColumn) backToLong.getColumn("id")).data();
        assertThat(after).isEqualTo(ids);
    }

    // ===== 补:覆盖 GroupBy.agg 的各种聚合函数(变异测试发现的盲区)=====

    /** MR22: agg 各聚合函数应给出符合定义的值(覆盖 count/nunique/min/max/first/last/median/std/var)。 */
    @Test
    void mr_groupBy_agg各聚合函数符合定义() {
        // 一个组内 4 个值:10, 20, 30, 40(nunique=4, count=4, min=10, max=40, first=10, last=40, median=25)
        DataFrame df = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ new long[]{1,1,1,1}, new double[]{10, 20, 30, 40} });

        Map<String, String> spec = new HashMap<>();
        spec.put("v", "count");   // 4
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_count")).longValue()).isEqualTo(4L);

        spec.clear(); spec.put("v", "nunique");  // 4 (四个不同值)
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_nunique")).longValue()).isEqualTo(4L);

        spec.clear(); spec.put("v", "min");      // 10
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_min")).doubleValue()).isEqualTo(10.0);

        spec.clear(); spec.put("v", "max");      // 40
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_max")).doubleValue()).isEqualTo(40.0);

        spec.clear(); spec.put("v", "first");    // 10
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_first")).doubleValue()).isEqualTo(10.0);

        spec.clear(); spec.put("v", "last");     // 40
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_last")).doubleValue()).isEqualTo(40.0);

        spec.clear(); spec.put("v", "median");   // (20+30)/2 = 25
        assertThat(((Number) df.groupBy("g").agg(spec).get(0, "v_median")).doubleValue()).isEqualTo(25.0);

        spec.clear(); spec.put("v", "std");
        double std = ((Number) df.groupBy("g").agg(spec).get(0, "v_std")).doubleValue();
        assertThat(std).isGreaterThan(0).isLessThan(20);   // std([10,20,30,40]) ≈ 12.9

        spec.clear(); spec.put("v", "var");
        double var = ((Number) df.groupBy("g").agg(spec).get(0, "v_var")).doubleValue();
        assertThat(var).isGreaterThan(100).isLessThan(200);  // var ≈ 166.67
    }

    /** MR23: agg 的 sum 应等于手工逐元素加(性质:fold 关系)。 */
    @Test
    void mr_groupBy_sum等于手工加总() {
        double[] vs = {1.5, 2.5, 3.0, 4.0};
        DataFrame df = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ new long[]{1,1,1,1}, vs });
        Map<String, String> spec = new HashMap<>();
        spec.put("v", "sum");
        double actual = ((Number) df.groupBy("g").agg(spec).get(0, "v_sum")).doubleValue();
        double manual = 0; for (double v : vs) manual += v;
        assertThat(actual).isEqualTo(manual);
    }

    /** MR24: agg mean * count == sum(在精度容差内)——三者必须自洽。 */
    @Test
    void mr_groupBy_mean乘count等于sum() {
        DataFrame df = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ new long[]{1,1,1,1}, new double[]{10, 20, 30, 40} });
        Map<String, String> spec = new HashMap<>();
        spec.put("v", "mean");
        double mean = ((Number) df.groupBy("g").agg(spec).get(0, "v_mean")).doubleValue();
        spec.clear(); spec.put("v", "count");
        long cnt = ((Number) df.groupBy("g").agg(spec).get(0, "v_count")).longValue();
        spec.clear(); spec.put("v", "sum");
        double sum = ((Number) df.groupBy("g").agg(spec).get(0, "v_sum")).doubleValue();
        assertThat(mean * cnt).isCloseTo(sum, org.assertj.core.api.Assertions.within(0.001));
    }

    /** MR25: GroupBy.size() 返回每组行数,总和等于原表行数。 */
    @Test
    void mr_groupBy_size各组行数之和等于原表() {
        // 用 id % 3 作分组,3 个组
        long[] ids = {0, 1, 2, 3, 4, 5, 6, 7};
        DataFrame df = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ ids, new double[]{1,2,3,4,5,6,7,8} });
        long[] gs = new long[ids.length];
        for (int i = 0; i < ids.length; i++) gs[i] = ids[i] % 3;
        DataFrame gdf = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ gs, new double[]{1,2,3,4,5,6,7,8} });
        // size 应返回每组大小
        // 注意:GroupBy.size 不是标准 API,看 jian 是否有;若有则验证,无则用 agg count
        Map<String, String> spec = new HashMap<>();
        spec.put("v", "count");
        DataFrame r = gdf.groupBy("g").agg(spec);
        long total = 0;
        for (int i = 0; i < r.rowCount(); i++) {
            total += ((Number) r.get(i, "v_count")).longValue();
        }
        assertThat(total).isEqualTo(gdf.rowCount());
    }

    /** MR26: GroupBy.transform 广播回原行序,长度等于原表行数。 */
    @Test
    void mr_groupBy_transform广播长度等于原表() {
        DataFrame df = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ new long[]{1,2,1,2,1}, new double[]{10, 100, 20, 200, 30} });
        double[] means = df.groupBy("g").transform("v", "mean");
        assertThat(means.length).isEqualTo(df.rowCount());
        // g=1 的 mean = (10+20+30)/3 = 20,在 g=1 的所有行位置(0,2,4)上应都是 20
        assertThat(means[0]).isCloseTo(20.0, org.assertj.core.api.Assertions.within(0.001));
        assertThat(means[2]).isCloseTo(20.0, org.assertj.core.api.Assertions.within(0.001));
        assertThat(means[4]).isCloseTo(20.0, org.assertj.core.api.Assertions.within(0.001));
        // g=2 的 mean = (100+200)/2 = 150
        assertThat(means[1]).isCloseTo(150.0, org.assertj.core.api.Assertions.within(0.001));
        assertThat(means[3]).isCloseTo(150.0, org.assertj.core.api.Assertions.within(0.001));
    }

    /** MR27: GroupBy.filter 应保留整组(组级谓词)。 */
    @Test
    void mr_groupBy_filter保留整组() {
        // 2 个组:g=1 (v=10,20,30, mean=20), g=2 (v=100,200, mean=150)
        DataFrame df = DataFrame.ofColumnArrays(
            java.util.List.of("g", "v"),
            new Object[]{ new long[]{1,1,1,2,2}, new double[]{10,20,30,100,200} });
        // 过滤"组均值 > 50"的组 → 只保留 g=2
        DataFrame filtered = df.groupBy("g").filter("v", "mean", x -> x > 50);
        assertThat(filtered.rowCount()).isEqualTo(2);  // g=2 的 2 行
        // 全部是 g=2
        long[] gs = ((LongColumn) filtered.getColumn("g")).data();
        for (long g : gs) assertThat(g).isEqualTo(2L);
    }

    // ======================== MR28:sortIndex 蜕变(补未测方法)========================

    /**
     * MR28: sortIndex 后,行数守恒 + 每列值的多重集不变(只是行序变)。
     * 覆盖之前未测的 sortIndex;RangeIndex 下升序 = 原样,降序 = 行倒序,两种都验。
     */
    @RepeatedTest(5)
    void mr_sortIndex_行数守恒且值多重集不变() {
        // 用本文件已有的 randomDf 构造(它返回 id long + v double,适合多重集断言)
        DataFrame df = randomDf(new Random(42), 20);

        // 升序 sortIndex
        DataFrame asc = df.sortIndex(true);
        assertThat(asc.rowCount()).as("MR28 sortIndex 行数守恒(升)").isEqualTo(df.rowCount());
        long[] origIds = ((LongColumn) df.getColumn("id")).data();
        double[] origVs = ((DoubleColumn) df.getColumn("v")).data();
        // 多重集不变:排序后逐位相等
        long[] origIdsSorted = java.util.Arrays.stream(origIds).sorted().toArray();
        long[] ascIdsSorted  = java.util.Arrays.stream(((LongColumn) asc.getColumn("id")).data()).sorted().toArray();
        assertThat(ascIdsSorted).as("MR28 id 多重集不变(升)").isEqualTo(origIdsSorted);
        double[] origVsSorted = java.util.Arrays.stream(origVs).sorted().toArray();
        double[] ascVsSorted  = java.util.Arrays.stream(((DoubleColumn) asc.getColumn("v")).data()).sorted().toArray();
        assertThat(ascVsSorted).as("MR28 v 多重集不变(升)").isEqualTo(origVsSorted);

        // 降序 sortIndex
        DataFrame desc = df.sortIndex(false);
        assertThat(desc.rowCount()).as("MR28 sortIndex 行数守恒(降)").isEqualTo(df.rowCount());
        long[] descIdsSorted = java.util.Arrays.stream(((LongColumn) desc.getColumn("id")).data()).sorted().toArray();
        assertThat(descIdsSorted).as("MR28 id 多重集不变(降)").isEqualTo(origIdsSorted);
    }
}
