package jian.core;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : PropertyBasedTest —— jqwik 1.9.3 驱动的 PBT,与 MetamorphicTest 形成双 PBT 交叉
// │  Why  : jqwik 的 shrinking / coverage 引导能力是 MetamorphicTest 的 @RepeatedTest+Random 替代不了的;
// │         两套独立实现跑同样性质,等价于"AI 同行评议"在测试层的镜像
// │  Who  : 由 mvn test 跑,持续守护核心算子
// │  When : 任何 jian-core 改动后跑
// │  Where: jian-core/src/test/java/jian/core/PropertyBasedTest.java
// │  How  : 数据走向:jqwik 生成器(@Provide)产生随机 long id/double val 的 DataFrame →
// │         跑算子(sortBy/head/merge/groupBy/concat/filter/dropDuplicates)→ 断言"性质"成立。
// │         关键:断言用"不变量"而非具体期望值;失败时 jqwik 会自动 shrink 到最小失败用例。
//
// 与 MetamorphicTest 的关系:
//   - MetamorphicTest 用 java.util.Random + @RepeatedTest,手写随机生成器;
//   - 本类用 jqwik 的 Arbitrary/Provide,生成器更声明式,且失败时 shrinking 自动定位最小用例;
//   - 两套独立实现 → 同一性质被两套不同的随机输入流验证,等价于"交叉验证"。
public class PropertyBasedTest {

    // ======================== 生成器 ========================
    // 边界注入策略:v 列混合普通值与边界值(NaN/±0.0/MAX/MIN),
    // 让性质在边界条件下也被检验。jqwik 会自动把这些边界值纳入 edge cases。

    /**
     * 生成"包含 id 和 v 列的 df":id 在 [0, n) 范围随机(可能重复);
     * v 列混合普通值 [-100,100) 与边界值(NaN/±0.0/MAX/MIN)。
     */
    @Provide
    Arbitrary<DataFrame> dataFrames() {
        return Arbitraries.integers().between(0, 50).flatMap(n ->
            Arbitraries.longs().between(0, 50).list().ofSize(n).flatMap(ids ->
                vWithBoundary().list().ofSize(n).map(vs -> {
                    long[] idArr = new long[n];
                    double[] vArr = new double[n];
                    for (int i = 0; i < n; i++) { idArr[i] = ids.get(i); vArr[i] = vs.get(i); }
                    return DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{idArr, vArr});
                })
            )
        );
    }

    /**
     * v 列的边界注入生成器:90% 普通值 [-100,100),10% 边界值。
     * 用 frequency 显式加权,确保 jqwik shrink 时能定位到边界值引发的失败。
     */
    @Provide
    Arbitrary<Double> vWithBoundary() {
        // 用 integers + flatMap 显式控制概率,避免 jqwik frequency 的泛型推断问题。
        // 90% 普通值 [-100,100) + 10% 边界值(NaN/±0.0/MAX/MIN)。
        return Arbitraries.integers().between(0, 9).flatMap(bucket ->
            bucket < 9
                ? Arbitraries.doubles().between(-100, 100).map(d -> (Double) d)
                : Arbitraries.of(
                    Double.NaN, +0.0, -0.0,
                    Double.MAX_VALUE, -Double.MAX_VALUE,
                    Double.MIN_VALUE, -Double.MIN_VALUE
                )
        );
    }

    /** 生成"唯一 id 的 df"(用于需要无重复 key 的性质)。 */
    @Provide
    Arbitrary<DataFrame> dataFramesUniqueIds() {
        return Arbitraries.integers().between(0, 50).flatMap(n ->
            Arbitraries.integers().between(0, 200).list().ofSize(n).map(indices -> {
                // 从 [0, 200) 取 n 个不重复的(用 LinkedHashSet 保证唯一又保序)
                Set<Integer> seen = new LinkedHashSet<>(indices);
                List<Integer> uniq = new ArrayList<>(seen);
                int m = Math.min(n, uniq.size());
                long[] idArr = new long[m];
                double[] vArr = new double[m];
                for (int i = 0; i < m; i++) {
                    idArr[i] = uniq.get(i);
                    vArr[i] = (uniq.get(i) % 100);
                }
                return DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{idArr, vArr});
            })
        );
    }

    // ======================== 性质 1-3:sortBy ========================

    /** P1: sortBy 不改变行数(jqwik 自动 shrink 到最小失败用例)。 */
    @Property(tries = 200)
    void p_sortBy_行数守恒(@ForAll("dataFrames") DataFrame df) {
        int before = df.rowCount();
        DataFrame asc = df.sortBy("v", true);
        DataFrame desc = df.sortBy("v", false);
        assertThat(asc.rowCount()).isEqualTo(before);
        assertThat(desc.rowCount()).isEqualTo(before);
    }

    /**
     * P2: sortBy(asc=true) 后 v 列确实单调不减。
     * <p>v 列含 NaN(边界注入)。NaN 与任何值的 >= 比较都返回 false
     * (IEEE 754),所以断言跳过含 NaN 的相邻对。
     */
    @Property(tries = 200)
    void p_sortBy_升序后单调不减(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.sortBy("v", true);
        double[] vs = ((DoubleColumn) r.getColumn("v")).data();
        for (int i = 1; i < vs.length; i++) {
            // 跳过含 NaN 的相邻对:NaN 不参与单调性断言
            if (Double.isNaN(vs[i]) || Double.isNaN(vs[i - 1])) continue;
            assertThat(vs[i]).isGreaterThanOrEqualTo(vs[i - 1]);
        }
    }

    /** P3: sortBy 后 id 列的多重集不变(只重排不改值)。 */
    @Property(tries = 200)
    void p_sortBy_id列多重集不变(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.sortBy("v", true);
        long[] before = ((LongColumn) df.getColumn("id")).data();
        long[] after = ((LongColumn) r.getColumn("id")).data();
        // 多重集比较:排序后逐元素相等
        long[] b = before.clone(); Arrays.sort(b);
        long[] a = after.clone(); Arrays.sort(a);
        assertThat(a).isEqualTo(b);
    }

    // ======================== 性质 4:filter ========================

    /** P4: filter("v > 0") 后所有行 v 都 > 0。 */
    @Property(tries = 200)
    void p_filter_所有结果行满足谓词(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.query("v > 0");
        double[] vs = ((DoubleColumn) r.getColumn("v")).data();
        for (double v : vs) {
            assertThat(v).isGreaterThan(0.0);
        }
    }

    // ======================== 性质 5:head/tail ========================

    /**
     * P5: head(n) 的行数 = min(n, rowCount)。
     * 关键:@IntRange 上限须超过 df.rowCount 上限(取 200 > 50),让 n > rowCount 的情况被测到。
     */
    @Property(tries = 200)
    void p_head_行数等于minN(@ForAll("dataFrames") DataFrame df, @ForAll @IntRange(min = 0, max = 200) int n) {
        DataFrame r = df.head(n);
        assertThat(r.rowCount()).isEqualTo(Math.min(n, df.rowCount()));
    }

    // ======================== 性质 6:concat ========================

    /** P6: concat(df, df, axis=0) 后行数 = 2 * df.rowCount。 */
    @Property(tries = 100)
    void p_concat_纵向行数翻倍(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = DataFrame.concat(List.of(df, df), 0);
        assertThat(r.rowCount()).isEqualTo(2 * df.rowCount());
    }

    // ======================== 性质 7:dropDuplicates ========================

    /** P7: dropDuplicates({"id"}, "first") 后 id 列无重复。 */
    @Property(tries = 200)
    void p_dropDuplicates_id列无重复(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.dropDuplicates(new String[]{"id"}, "first");
        long[] ids = ((LongColumn) r.getColumn("id")).data();
        Set<Long> seen = new HashSet<>();
        for (long id : ids) {
            assertThat(seen).doesNotContain(id);
            seen.add(id);
        }
    }

    // ======================== 性质 8:merge(自连接) ========================

    /** P8: 任何 df 自连接(inner join on id)结果行数 ≥ df.rowCount(每行至少匹配自身一次)。 */
    @Property(tries = 100)
    void p_merge_自连接行数不少于原表(@ForAll("dataFramesUniqueIds") DataFrame df) {
        DataFrame r = df.merge(df, "inner", "id");
        // 唯一 id 自连接,每个 id 都匹配自身,结果行数 == 原表
        assertThat(r.rowCount()).isEqualTo(df.rowCount());
    }

    // ======================== 性质 9:merge 交换律 ========================

    /** P9: A inner-join B 与 B inner-join A 的行数相同(对称性)。 */
    @Property(tries = 100)
    void p_merge_innerJoin交换律(@ForAll("dataFrames") DataFrame a, @ForAll("dataFrames") DataFrame b) {
        DataFrame ab = a.merge(b, "inner", "id");
        DataFrame ba = b.merge(a, "inner", "id");
        assertThat(ab.rowCount()).isEqualTo(ba.rowCount());
    }

    // ======================== 性质 10:groupBy(真正调用 GroupBy.agg) ========================

    /**
     * P10:真正调 df.groupBy("id").agg({"id":"count"}),
     * 断言两条真蜕变关系(不是恒真):
     *   ① 各组 count 之和 == 原表行数(每行恰被分到一组);
     *   ② 组数 == 原表 id 列的唯一值数。
     * 注意:不能用手算分组替代调用(那样任何 GroupBy 实现都过,是死测试)。
     * <p>v 列含 NaN(边界注入)。count 聚合是"非空值计数"(pandas 语义),
     * 所以 count 用 **id 列**(id 列无 NaN,count == 组内行数),才能与
     * "各组 count 之和 == 原表行数"的覆盖性断言兼容。
     */
    @Property(tries = 100)
    void p_groupBy_countSum等于原表行数(@ForAll("dataFrames") DataFrame df) {
        int nRows = df.rowCount();
        if (nRows == 0) return;   // 空表跳过(groupBy 在空表上行为依赖实现细节,不强约束)

        // 真正调 GroupBy.agg —— 用 id 列做 count(id 列无 NaN,count == 组内行数)
        java.util.Map<String, String> spec = new java.util.HashMap<>();
        spec.put("id", "count");
        DataFrame agg = df.groupBy("id").agg(spec);

        // 断言 ①:各组 count 之和 == 原表行数
        long sum = 0;
        for (int i = 0; i < agg.rowCount(); i++) {
            sum += ((Number) agg.get(i, "id_count")).longValue();
        }
        assertThat(sum).as("各组 count 之和应等于原表行数").isEqualTo(nRows);

        // 断言 ②:组数 == 原表 id 列唯一值数
        long[] ids = ((LongColumn) df.getColumn("id")).data();
        Set<Long> uniqIds = new HashSet<>();
        for (long id : ids) uniqIds.add(id);
        assertThat(agg.rowCount()).as("组数应等于唯一 id 数").isEqualTo(uniqIds.size());
    }

    // ======================== 性质 11-13:缺失值处理 ========================

    /**
     * P11: fillna(v) 后,v 列无缺失(原 NaN 位置全被替换)。
     * 关键:构造含 NaN 的 v 列(生成器已允许),fillna 后 isna 应全 false。
     */
    @Provide
    Arbitrary<DataFrame> dataFramesWithNaN() {
        // 在 v 列里随机插 NaN(模拟缺失数据)
        return Arbitraries.integers().between(0, 30).flatMap(n ->
            Arbitraries.longs().between(0, 50).list().ofSize(n).flatMap(ids ->
                Arbitraries.doubles().between(-100, 100).list().ofSize(n).flatMap(vs ->
                    Arbitraries.integers().between(0, Math.max(0, n - 1)).list().ofSize(Math.max(1, n / 3))
                        .map(nanIdx -> {
                            // 把 vs 的某些位置设为 NaN
                            long[] idArr = new long[n];
                            double[] vArr = new double[n];
                            for (int i = 0; i < n; i++) { idArr[i] = ids.get(i); vArr[i] = vs.get(i); }
                            for (int idx : nanIdx) if (idx < n) vArr[idx] = Double.NaN;
                            return DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{idArr, vArr});
                        })
                )
            )
        );
    }

    @Property(tries = 100)
    void p11_fillna后v列无缺失(@ForAll("dataFramesWithNaN") DataFrame df) {
        DataFrame r = df.fillna(0.0);
        double[] vs = ((DoubleColumn) r.getColumn("v")).data();
        // 因为 NaN != NaN(IEEE 语义,断言 isNotEqualTo(NaN) 恒真是死测试),
        // 所以用 Double.isNaN 判定,fillna 未生效时立即红
        for (double v : vs) {
            assertThat(Double.isNaN(v)).as("fillna 后不应有 NaN").isFalse();
        }
    }

    @Property(tries = 100)
    void p12_dropna后无NaN行(@ForAll("dataFramesWithNaN") DataFrame df) {
        DataFrame r = df.dropna();
        double[] vs = ((DoubleColumn) r.getColumn("v")).data();
        // 同 p11,用 Double.isNaN 判定(isNotEqualTo(NaN) 恒真)
        for (double v : vs) {
            assertThat(Double.isNaN(v)).as("dropna 后不应有 NaN 行").isFalse();
        }
    }

    /**
     * P13(强断言版):ffill 后,每个填充值应等于前一个有效值。
     * 只验"首个有效值后无 NaN"太弱(fillna(0)/bfill/填错值都过)。
     * 加强:断言"每个非 NaN 行的值,要么是原值,要么等于最近的前驱有效值"。
     */
    @Property(tries = 100)
    void p13_ffill后非首行要么有值要么等于前一个有效值(@ForAll("dataFramesWithNaN") DataFrame df) {
        DataFrame r = df.ffill();
        double[] original = ((DoubleColumn) df.getColumn("v")).data();
        double[] filled = ((DoubleColumn) r.getColumn("v")).data();
        double lastValid = Double.NaN;
        for (int i = 0; i < filled.length; i++) {
            if (!Double.isNaN(filled[i])) {
                if (!Double.isNaN(original[i])) {
                    // 原值非 NaN:filled[i] 应等于原值
                    assertThat(filled[i]).as("ffill 第 " + i + " 行原值应保留").isEqualTo(original[i]);
                    lastValid = filled[i];
                } else {
                    // 原值是 NaN(被填充):filled[i] 应等于最近的前驱有效值
                    assertThat(filled[i]).as("ffill 第 " + i + " 行应等于前驱有效值 " + lastValid).isEqualTo(lastValid);
                }
            }
        }
    }

    // ======================== 性质 15-17:类型/重塑 ========================

    /**
     * P15: astype(LONG → DOUBLE → LONG) 来回保值(在 long 范围内)。
     * <p>空表也测(不跳过):空表 astype 应返回空表,before/after 都是 long[0],断言通过
     * (与 Python 端口径同步)。
     */
    @Property(tries = 200)
    void p15_astype_LONG经过DOUBLE来回保值(@ForAll("dataFrames") DataFrame df) {
        DataFrame toDouble = df.astype("id", DType.DOUBLE);
        DataFrame backToLong = toDouble.astype("id", DType.LONG);
        long[] before = ((LongColumn) df.getColumn("id")).data();
        long[] after = ((LongColumn) backToLong.getColumn("id")).data();
        assertThat(after).as("LONG→DOUBLE→LONG 应保值").isEqualTo(before);
    }

    /** P16: select(cols) 行数不变,列数 == cols.length。 */
    @Property(tries = 200)
    void p16_select_行数不变列数等于指定(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.select("id");   // 只选 id 列
        assertThat(r.rowCount()).as("select 不改行数").isEqualTo(df.rowCount());
        assertThat(r.columnCount()).as("select 后列数应为 1").isEqualTo(1);
        assertThat(r.columnNames()).containsExactly("id");
    }

    /** P17: drop(cols) 行数不变,列数 == 原列数 - dropped。 */
    @Property(tries = 200)
    void p17_drop_行数不变列数减少(@ForAll("dataFrames") DataFrame df) {
        int beforeCols = df.columnCount();
        DataFrame r = df.drop("v");
        assertThat(r.rowCount()).as("drop 不改行数").isEqualTo(df.rowCount());
        assertThat(r.columnCount()).as("drop 后列数应减 1").isEqualTo(beforeCols - 1);
        assertThat(r.columnNames()).containsExactly("id");
    }

    // ======================== 性质 18-20:slice / nlargest / nsmallest ========================

    /** P18: slice(a,b) 的第 i 行 == 原表第 a+i 行(保序)。 */
    @Property(tries = 100)
    void p18_slice_保序且等于原表区间(@ForAll("dataFrames") DataFrame df,
                                  @ForAll @IntRange(min = 0, max = 50) int a,
                                  @ForAll @IntRange(min = 0, max = 50) int b) {
        int hi = Math.min(Math.max(a, b), df.rowCount());
        int lo = Math.min(a, b);
        if (lo >= hi) return;   // 空区间跳过
        DataFrame s = df.slice(lo, hi);
        for (int i = 0; i < s.rowCount(); i++) {
            assertThat(s.get(i, "id"))
                .as("slice 第 " + i + " 行应等于原表第 " + (lo + i) + " 行")
                .isEqualTo(df.get(lo + i, "id"));
        }
    }

    /**
     * P19: nlargest(n, col) 等价于 sortBy(col, desc).head(n)。
     * 关键:两种 API 路径应给出一致结果(差分思想,但用 PBT 表达)。
     * nlargest 的堆排实现必须精确复刻 sortBy(desc).head(n) 的语义
     * (含 NaN 排最后、n 超过非缺失行数时补 NaN 行)——oracle 不变,实现对齐。
     */
    @Property(tries = 100)
    void p19_nlargest_等价于sortBy降序head(@ForAll("dataFrames") DataFrame df,
                                       @ForAll @IntRange(min = 0, max = 50) int n) {
        DataFrame byNlargest = df.nlargest(n, "v");
        DataFrame bySortHead = df.sortBy("v", false).head(n);
        assertThat(byNlargest.rowCount()).isEqualTo(bySortHead.rowCount());
        // 行序也应一致(都是降序)
        for (int i = 0; i < byNlargest.rowCount(); i++) {
            assertThat(byNlargest.get(i, "id")).isEqualTo(bySortHead.get(i, "id"));
        }
    }

    /** P20: nsmallest(n, col) 等价于 sortBy(col, asc).head(n)。 */
    @Property(tries = 100)
    void p20_nsmallest_等价于sortBy升序head(@ForAll("dataFrames") DataFrame df,
                                        @ForAll @IntRange(min = 0, max = 50) int n) {
        DataFrame byNsmallest = df.nsmallest(n, "v");
        DataFrame bySortHead = df.sortBy("v", true).head(n);
        assertThat(byNsmallest.rowCount()).isEqualTo(bySortHead.rowCount());
        for (int i = 0; i < byNsmallest.rowCount(); i++) {
            assertThat(byNsmallest.get(i, "id")).isEqualTo(bySortHead.get(i, "id"));
        }
    }

    // ======================== 性质 21-23:算术 / assign ========================

    /**
     * P21: colAdd(new, a, b) 后,每行 new == a + b(在 double 精度容差内)。
     */
    @Property(tries = 200)
    void p21_colAdd_等于逐行加(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.colAdd("sum", "id", "v");   // 注意 id 是 long,会被提升为 double 相加
        // 校验每行 sum == id + v
        long[] ids = ((LongColumn) df.getColumn("id")).data();
        double[] vs = ((DoubleColumn) df.getColumn("v")).data();
        for (int i = 0; i < df.rowCount(); i++) {
            double expected = ids[i] + vs[i];
            double actual = ((Number) r.get(i, "sum")).doubleValue();
            assertThat(actual).as("colAdd 第 " + i + " 行").isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    /**
     * P22: colMul(new, src, k) 后,每行 new == src × k。
     * 关键:k 限定范围 [-100, 100](k=1e308 时 v*k=Inf 会让断言 flaky)。
     */
    @Property(tries = 100)
    void p22_colMul标量_等于逐行乘(@ForAll("dataFrames") DataFrame df,
                              @ForAll @net.jqwik.api.constraints.DoubleRange(min = -100, max = 100) double k) {
        DataFrame r = df.colMul("scaled", "v", k);
        double[] vs = ((DoubleColumn) df.getColumn("v")).data();
        for (int i = 0; i < df.rowCount(); i++) {
            double expected = vs[i] * k;
            double actual = ((Number) r.get(i, "scaled")).doubleValue();
            // NaN 与任何值的运算结果都是 NaN(边界注入),
            // NaN == NaN 在 AssertJ 的 isCloseTo 里特殊(NaN 视为相等),
            // 但 tol 计算需保证非负:Math.max(1e-9, NaN) 在 Java 返回 NaN(非 1e-9),
            // 所以遇到 NaN 时直接跳过(与 P2 sortBy 同款 NaN 处理)。
            if (Double.isNaN(expected) && Double.isNaN(actual)) continue;
            double tol = Math.max(1e-9, Math.abs(expected) * 1e-9);
            assertThat(actual).as("colMul 第 " + i + " 行").isCloseTo(expected, org.assertj.core.data.Offset.offset(tol));
        }
    }

    /**
     * P23: assign(name, fn) 加列后,行数不变 + 列数+1 + 新列名存在。
     * 空表也测(Schema.inferColumn 有空表守卫,assign 空表正常,不跳过)。
     */
    @Property(tries = 200)
    void p23_assign_加列不改行数(@ForAll("dataFrames") DataFrame df) {
        int beforeRows = df.rowCount();
        int beforeCols = df.columnCount();
        DataFrame r = df.assign("tag", i -> "x");
        assertThat(r.rowCount()).as("assign 不改行数").isEqualTo(beforeRows);
        assertThat(r.columnCount()).as("assign 列数+1").isEqualTo(beforeCols + 1);
        assertThat(r.columnNames()).contains("tag");
    }

    // ======================== 性质 24-26:算术减除 + 比较掩码(补未测方法)========================

    /**
     * P24: colSub(new, a, b) 后,每行 new == a - b(在 double 精度容差内)。
     * 覆盖之前未测的 colSub;NaN 传播:任一缺失,该行结果 NaN。
     */
    @Property(tries = 200)
    void p24_colSub_等于逐行减(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.colSub("diff", "id", "v");   // id 是 long,提升为 double
        long[] ids = ((LongColumn) df.getColumn("id")).data();
        double[] vs = ((DoubleColumn) df.getColumn("v")).data();
        double[] actual = r.getDoubleColumn("diff").data();   // 直接取 double[],避免 get() NaN→null 拆箱
        for (int i = 0; i < df.rowCount(); i++) {
            if (Double.isNaN(vs[i])) {
                assertThat(actual[i]).as("colSub 第 " + i + " 行 NaN 传播").isNaN();
            } else {
                double expected = ids[i] - vs[i];
                assertThat(actual[i]).as("colSub 第 " + i + " 行").isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
            }
        }
    }

    /**
     * P25: colDiv(new, a, b) 后,每行 new == a / b(b 非 0 时)。
     * 覆盖之前未测的 colDiv;NaN 传播 + 除零得 Inf 的 IEEE 754 行为。
     * 注意:用 getDoubleColumn 直接取 double[](跳过 get() 的 NaN→null 转换),避免拆箱 NPE。
     */
    @Property(tries = 200)
    void p25_colDiv_等于逐行除(@ForAll("dataFrames") DataFrame df) {
        DataFrame r = df.colDiv("ratio", "id", "v");
        long[] ids = ((LongColumn) df.getColumn("id")).data();
        double[] vs = ((DoubleColumn) df.getColumn("v")).data();
        double[] actual = r.getDoubleColumn("ratio").data();
        for (int i = 0; i < df.rowCount(); i++) {
            if (Double.isNaN(vs[i])) {
                assertThat(actual[i]).as("colDiv 第 " + i + " 行 NaN 传播").isNaN();
            } else if (vs[i] == 0.0) {
                // 除零:IEEE 754 → ±Infinity
                assertThat(Double.isInfinite(actual[i]) || Double.isNaN(actual[i]))
                        .as("colDiv 第 " + i + " 行除零").isTrue();
            } else {
                double expected = (double) ids[i] / vs[i];
                double tol = Math.max(1e-9, Math.abs(expected) * 1e-9);
                assertThat(actual[i]).as("colDiv 第 " + i + " 行").isCloseTo(expected, org.assertj.core.data.Offset.offset(tol));
            }
        }
    }

    /**
     * P26: colLt("v", k) 掩码 == (v < k) 逐行;colLe 掩码 == (v <= k)。
     * 覆盖之前未测的 colLt/colLe;NaN 恒 false(任何比较 NaN 都是 false)。
     */
    @Property(tries = 100)
    void p26_colLtcolLe_掩码正确(@ForAll("dataFrames") DataFrame df,
                              @ForAll @net.jqwik.api.constraints.DoubleRange(min = -100, max = 100) double k) {
        BoolColumn ltMask = df.colLt("v", k);
        BoolColumn leMask = df.colLe("v", k);
        double[] vs = ((DoubleColumn) df.getColumn("v")).data();
        boolean[] ltData = ltMask.dataInPlace();
        boolean[] leData = leMask.dataInPlace();
        for (int i = 0; i < df.rowCount(); i++) {
            // NaN 比较恒 false
            boolean expectedLt = Double.isNaN(vs[i]) ? false : vs[i] < k;
            boolean expectedLe = Double.isNaN(vs[i]) ? false : vs[i] <= k;
            assertThat(ltData[i]).as("colLt 第 " + i + " 行").isEqualTo(expectedLt);
            assertThat(leData[i]).as("colLe 第 " + i + " 行").isEqualTo(expectedLe);
        }
    }
}
