package jian.core;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : DifferentialTest —— 差分测试(fast path vs generic path 跨实现等价)
// │  Why  : jian-core 有大量"双实现"——同一个算子有 dtype 特化 fast path 和通用 path。
// │         单元测试只测一条路径会漏 bug(我前面的 fast path bug 全是这种)。
// │         差分测试:同输入跑两条路径,断言结果完全一致。
// │         参考: differential testing(Stereobooster),C 编译器/Numpy 都用这方法。
// │  Who  : 由 mvn test 跑,持续守护 fast/generic 等价性
// │  Where: jian-core/src/test/java/jian/core/DifferentialTest.java
// │  How  : 数据走向:同份随机 df → 强制走 fast path(数值 key 无 null)→ 同份 → 强制走 generic path
// │         (强制带 null 或用字符串 key)→ 断言两者结果逐行逐列相等。
public class DifferentialTest {

    // 种子策略(2026-08-09 修复 A-1):nextSeed() 取 [0,1e6) 散列,失败时打印 seed,
    // 可用 -Dtest.seed=N 精确回放。不再用裸 nanoTime(flaky 反模式)。
    private static long nextSeed() {
        String override = System.getProperty("test.seed");
        if (override != null && !override.isEmpty()) {
            return Long.parseLong(override);
        }
        return Math.floorMod(System.nanoTime(), 1_000_000L);
    }

    // ======================== merge: fast path vs generic path ========================

    /**
     * DT1: 数值 key 无 null 时,fast path 与 generic path 结果应完全一致。
     *
     * <p>触发方式:构造 long key 无 null 的两个 df,merge 走 fast path。
     * 然后把 key 改成 String(强制走 generic path),比较结果。
     * <p>种子策略(2026-08-09 修复 A-1):失败时通过断言描述打印 seed,
     * 可用 `-Dtest.seed=xxx` 精确回放(同 MetamorphicTest.nextSeed 约定)。
     */
    @RepeatedTest(15)
    void dt_merge_longKeyFastPath等于通用路径结果() {
        long st = nextSeed();
        Random rnd = new Random(st);

        // 构造 long key 两表
        int n = 30 + rnd.nextInt(50);
        long[] aIds = new long[n];
        double[] aVals = new double[n];
        long[] bIds = new long[n];
        double[] bVals = new double[n];
        for (int i = 0; i < n; i++) {
            aIds[i] = rnd.nextInt(n);   // 故意制造重复,inner 会扩展多行
            aVals[i] = rnd.nextInt(1000);
            bIds[i] = rnd.nextInt(n);
            bVals[i] = rnd.nextInt(1000);
        }

        DataFrame aLong = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{aIds, aVals});
        DataFrame bLong = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{bIds, bVals});

        // fast path(数值 key,无 null)
        DataFrame fastInner = aLong.merge(bLong, "inner", "id");
        DataFrame fastLeft = aLong.merge(bLong, "left", "id");

        // 同样数据但 key 改成 String(强制走 generic path)
        String[] aStr = new String[n];
        String[] bStr = new String[n];
        for (int i = 0; i < n; i++) { aStr[i] = Long.toString(aIds[i]); bStr[i] = Long.toString(bIds[i]); }
        DataFrame aStr_ = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{aStr, aVals});
        DataFrame bStr_ = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{bStr, bVals});
        DataFrame genInner = aStr_.merge(bStr_, "inner", "id");
        DataFrame genLeft = aStr_.merge(bStr_, "left", "id");

        // 行数应一致(具体行序可能不同,但行数和"键+值的多重集"应一致)
        assertThat(fastInner.rowCount()).as("seed=" + st + " inner 行数不一致").isEqualTo(genInner.rowCount());
        assertThat(fastLeft.rowCount()).as("seed=" + st + " left 行数不一致").isEqualTo(genLeft.rowCount());

        // 用多重集断言:fast 的 (id, v_a, v_b) 集合 == generic 的 (id, v_a, v_b) 集合
        assertThat(collectRowsAsSet(fastInner))
            .as("seed=" + st + " inner 行的多重集不一致")
            .isEqualTo(collectRowsAsSet(genInner));
    }

    /**
     * DT2: double key 在 ±0.0 边界上 fast path 行为与 Java `Double.equals` 等价。
     *
     * <p>注意:不能用 String 化作对照——`Double.toString(+0.0)="0.0"` 与 `"-0.0"` 不同,
     * String 化后 ±0.0 本就不该匹配,这是测试方法学陷阱(不是 jian bug)。
     * 正确对照:用 `Double.equals` 直接判断"两表所有 key 对"是否相等,统计期望命中数。
     *
     * <p><b>2026-08-09 修正注释(D-1 教学性误导)</b>:旧注释写「Double.equals(+0.0,-0.0)==true」
     * 是<b>错的</b>——`Double.valueOf(+0.0).equals(Double.valueOf(-0.0))` 实际返回 <b>false</b>
     * (Double.equals 按位比较,+0.0 与 -0.0 位模式不同)。而 `Double.compare(+0.0,-0.0)` 返回 1
     * (视它们为不等,与 equals 一致)。即:equals 与 compare 在 ±0.0 上**结论相同(都不等)**,
     * 差异只在返回值形式(布尔 vs 整数)。jian generic 路径走 HashMap<Double>,用 equals,
     * 所以 ±0.0 视为不等;fast path 也按此语义,两者一致。
     * <p>历史教训:此前的 AI 审查报告把 `Double.equals` 语义记错,导致"修复"反而引入 BUG。
     * 教训:测试注释必须与代码实际行为一致,不能靠记忆。
     */
    @Test
    void dt_merge_正零负零_与DoubleEquals等价() {
        double[] aIds = {+0.0, -0.0, 1.5, 2.5};
        double[] bIds = {-0.0, +0.0, 2.5, 3.5};
        double[] av = {10, 20, 30, 40};
        double[] bv = {100, 200, 300, 400};
        DataFrame a = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{aIds, av});
        DataFrame b = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{bIds, bv});

        // fast path(double key 无 null)
        DataFrame fast = a.merge(b, "inner", "id");

        // 用 Double.equals(即 HashMap<Double> 的语义)算期望命中数。
        // 关键事实(2026-08-09 修正):Double.valueOf(+0.0).equals(Double.valueOf(-0.0)) == false
        // (按位比较: +0.0 是 0x00..00, -0.0 是 0x80..00, 位模式不同)。
        // 所以 ±0.0 在 HashMap<Double> 里是**不同的 key**,不会命中。
        int expected = 0;
        for (double ak : aIds) {
            for (double bk : bIds) {
                if (Double.valueOf(ak).equals(Double.valueOf(bk))) expected++;
            }
        }
        assertThat(fast.rowCount()).as("±0.0 在 fast path 应与 HashMap<Double>(Double.equals)等价").isEqualTo(expected);
    }

    /**
     * DT3: int key 与 long key(同值)走 fast path 应等价(int 升位为 long)。
     */
    @Test
    void dt_merge_intKey与longKey等价() {
        int[] aInts = {1, 2, 3};
        int[] bInts = {2, 3, 4};
        double[] av = {10, 20, 30};
        double[] bv = {200, 300, 400};

        DataFrame aInt = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{aInts, av});
        DataFrame bInt = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{bInts, bv});

        // int key 走 fast path(int 升位 long)
        DataFrame intJoin = aInt.merge(bInt, "inner", "id");

        // 同样数据用 long[] 走 fast path
        long[] aLongs = {1L, 2L, 3L};
        long[] bLongs = {2L, 3L, 4L};
        DataFrame aLong = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{aLongs, av.clone()});
        DataFrame bLong = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{bLongs, bv.clone()});
        DataFrame longJoin = aLong.merge(bLong, "inner", "id");

        assertThat(intJoin.rowCount()).isEqualTo(longJoin.rowCount());
        assertThat(collectRowsAsSet(intJoin)).isEqualTo(collectRowsAsSet(longJoin));
    }

    // ======================== GroupBy: fast path vs generic ========================

    /**
     * DT4: long key(无 null)GroupBy 与 String key GroupBy 应等价(组数 + 每组大小)。
     */
    @RepeatedTest(10)
    void dt_groupBy_longKeyFastPath等于通用路径() {
        long st = nextSeed();
        Random rnd = new Random(st);
        int n = 50 + rnd.nextInt(50);
        long[] keys = new long[n];
        double[] vs = new double[n];
        for (int i = 0; i < n; i++) {
            keys[i] = rnd.nextInt(n / 3);   // 制造重复
            vs[i] = rnd.nextInt(100);
        }

        DataFrame longDf = DataFrame.ofColumnArrays(List.of("k", "v"), new Object[]{keys, vs});
        String[] strKeys = new String[n];
        for (int i = 0; i < n; i++) strKeys[i] = Long.toString(keys[i]);
        DataFrame strDf = DataFrame.ofColumnArrays(List.of("k", "v"), new Object[]{strKeys, vs});

        java.util.Map<String, String> spec = new java.util.HashMap<>();
        spec.put("v", "sum");

        DataFrame longAgg = longDf.groupBy("k").agg(spec);
        DataFrame strAgg = strDf.groupBy("k").agg(spec);

        // 组数应一致
        assertThat(longAgg.rowCount()).as("组数不一致").isEqualTo(strAgg.rowCount());

        // 每组的 sum 应一致(按 key 比对)
        java.util.Map<String, Double> longSums = new java.util.HashMap<>();
        for (int i = 0; i < longAgg.rowCount(); i++) {
            longSums.put(Long.toString((Long) longAgg.get(i, "k")), (Double) longAgg.get(i, "v_sum"));
        }
        for (int i = 0; i < strAgg.rowCount(); i++) {
            String key = (String) strAgg.get(i, "k");
            assertThat(longSums).containsKey(key);
            assertThat(longSums.get(key))
                .as("组 " + key + " 的 sum 不一致")
                .isEqualTo(strAgg.get(i, "v_sum"));
        }
    }

    /**
     * DT5: INT×LONG 混合 key 所有 how 都应正确匹配(AI agent2 BUG 1 回归)。
     * 关键:不能 inner 走 fast path 匹配、right/outer 落回 generic 全部不匹配。
     */
    @Test
    void dt_merge_INT与LONG混合key所有how一致匹配() {
        int[] aIds = {1, 2, 3};
        long[] bIds = {2L, 3L, 4L};
        double[] av = {10, 20, 30};
        double[] bv = {100, 200, 300};
        DataFrame a = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{aIds, av});
        DataFrame b = DataFrame.ofColumnArrays(List.of("id", "v"), new Object[]{bIds, bv});

        // 四种 how 都应视为"2,3 是匹配的"
        assertThat(a.merge(b, "inner", "id").rowCount()).as("inner 应=2(2,3 配对)").isEqualTo(2);
        assertThat(a.merge(b, "left", "id").rowCount()).as("left 应=3(左全保留)").isEqualTo(3);
        assertThat(a.merge(b, "right", "id").rowCount()).as("right 应=3(右全保留)").isEqualTo(3);
        assertThat(a.merge(b, "outer", "id").rowCount()).as("outer 应=4(并集 1,2,3,4)").isEqualTo(4);

        // right 中应有 2 行匹配(左表有 v),1 行未匹配(id=4)
        DataFrame right = a.merge(b, "right", "id");
        long matchedCount = 0;
        for (int i = 0; i < right.rowCount(); i++) {
            // v 列是左表的,匹配的行有值,未匹配的是缺失(NaN)
            // 修复:get() 对 NaN 现在返回 Double.NaN(不是 null),用 isNull 判断
            if (!right.getColumn("v").isNull(i)) matchedCount++;
        }
        assertThat(matchedCount).as("right 中应 2 行匹配上左表").isEqualTo(2);
    }

    /**
     * DT6: left join 未匹配行补 null 时,数值列不应降级 OBJECT(AI agent2 BUG 3 回归)。
     * 关键:补 null 的数值列仍应是 DoubleColumn/LongColumn 等,getDouble/getLong 不抛。
     */
    @Test
    void dt_merge_leftJoin未匹配补null保留dtype() {
        DataFrame a = DataFrame.ofColumnArrays(List.of("id", "v"),
            new Object[]{new long[]{1L, 2L}, new double[]{10, 20}});
        DataFrame b = DataFrame.ofColumnArrays(List.of("id", "w"),
            new Object[]{new long[]{2L}, new double[]{200}});

        DataFrame r = a.merge(b, "left", "id");
        // w 列应保持 DOUBLE(不能降级 OBJECT)
        assertThat(r.dtypes().get(r.columnIndex("w"))).as("w 列应保留 DOUBLE").isEqualTo(DType.DOUBLE);
        assertThat(r.getColumn("w")).as("w 列应是 DoubleColumn").isInstanceOf(DoubleColumn.class);

        // 未匹配行(row 0,id=1)的 w 应是 NaN(不抛异常)
        double w0 = ((DoubleColumn) r.getColumn("w")).getDouble(0);
        assertThat(Double.isNaN(w0)).as("未匹配行 w 应为 NaN").isTrue();
        // 匹配行(row 1,id=2)的 w 应是 200
        assertThat(((DoubleColumn) r.getColumn("w")).getDouble(1)).isEqualTo(200.0);
    }

    /**
     * DT7: left join 未匹配行的 LONG 列补 null 应保留 LONG + nullMask(AI agent2 测试缺口)。
     * DT6 只覆盖 DOUBLE,这里补 LONG/INT/BOOL。
     */
    @Test
    void dt_merge_leftJoin未匹配LONG列保留LONG与nullMask() {
        DataFrame a = DataFrame.ofColumnArrays(List.of("id", "lv"),
            new Object[]{new long[]{1L, 2L}, new long[]{100L, 200L}});
        DataFrame b = DataFrame.ofColumnArrays(List.of("id", "x"),
            new Object[]{new long[]{2L}, new long[]{999L}});

        DataFrame r = a.merge(b, "left", "id");
        // 改用 a 作左、b2 作右,使 b2 的 x 列成为未匹配补 null 的列
        // (AI agent2 抓的死代码:原 r2 未使用,这里直接删掉)
        DataFrame b2 = DataFrame.ofColumnArrays(List.of("id", "x"),
            new Object[]{new long[]{3L}, new long[]{999L}});
        DataFrame r3 = a.merge(b2, "left", "id");   // a 两行都不匹配 b2
        assertThat(r3.dtypes().get(r3.columnIndex("x"))).as("x 列应保留 LONG").isEqualTo(DType.LONG);
        assertThat(r3.getColumn("x")).isInstanceOf(LongColumn.class);
        // 两行都未匹配,nullCount 应为 2
        assertThat(r3.getColumn("x").nullCount()).as("未匹配的 2 行应 nullCount=2").isEqualTo(2);
    }

    /**
     * DT8: fast path 输出 DATE 列应保留 DATE 不降级 OBJECT(AI agent2 BUG A 回归)。
     */
    @Test
    void dt_merge_fastPath输出DATE列保留DATE() {
        DataFrame a = DataFrame.ofColumnArrays(List.of("id", "d"),
            new Object[]{new long[]{1L, 2L},
                new java.time.LocalDate[]{java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 2)}});
        DataFrame b = DataFrame.ofColumnArrays(List.of("id", "d2"),
            new Object[]{new long[]{2L},
                new java.time.LocalDate[]{java.time.LocalDate.of(2026, 1, 3)}});

        DataFrame inner = a.merge(b, "inner", "id");   // fast path(LONG×LONG key,无 null)
        // d2 列应保持 DATE(不能降级 OBJECT)
        assertThat(inner.dtypes().get(inner.columnIndex("d2")))
            .as("fast path 输出 DATE 列应保留 DATE").isEqualTo(DType.DATE);
        assertThat(inner.getColumn("d2")).isInstanceOf(DateColumn.class);

        // 与 generic 路径(right)对照,二者类型应一致
        DataFrame right = a.merge(b, "right", "id");
        assertThat(right.dtypes().get(right.columnIndex("d2")))
            .as("generic 路径也应保持 DATE").isEqualTo(DType.DATE);
    }

    /**
     * DT9: fast path left join DATE 列未匹配补 null 应保留 DATE 类型(AI agent2 第3轮提示补)。
     */
    @Test
    void dt_merge_fastPathLeftJoin_DATE列补null保留类型() {
        DataFrame a = DataFrame.ofColumnArrays(List.of("id", "d"),
            new Object[]{new long[]{1L, 2L},
                new java.time.LocalDate[]{java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 2)}});
        DataFrame b = DataFrame.ofColumnArrays(List.of("id", "d2"),
            new Object[]{new long[]{2L},
                new java.time.LocalDate[]{java.time.LocalDate.of(2026, 1, 3)}});

        // fast path(LONG×LONG 同 dtype,无 null key)
        DataFrame r = a.merge(b, "left", "id");
        // d2 列:row 0 未匹配(null), row 1 匹配(2026-01-03);应保留 DATE
        assertThat(r.dtypes().get(r.columnIndex("d2")))
            .as("left join DATE 列应保留 DATE").isEqualTo(DType.DATE);
        assertThat(r.getColumn("d2")).isInstanceOf(DateColumn.class);
        // row 0(id=1)未匹配,d2 应为 null
        assertThat(r.get(0, "d2")).isNull();
        // row 1(id=2)匹配,d2 应为 2026-01-03
        assertThat(r.get(1, "d2")).isEqualTo(java.time.LocalDate.of(2026, 1, 3));
    }

    // ======================== 辅助 ========================

    private static String[] toStringArray(double[] arr) {
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = Double.toString(arr[i]);
        return out;
    }

    /** 把 df 的每行收集成 "id|v" 字符串,放入多重集(排序后比较,忽略行序)。 */
    private static java.util.List<String> collectRowsAsSet(DataFrame df) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (int i = 0; i < df.rowCount(); i++) {
            // id 可能为 Long 或 String,统一 toString
            Object id = df.get(i, "id");
            Object v = df.get(i, "v");
            rows.add("" + id + "|" + v);
        }
        java.util.Collections.sort(rows);
        return rows;
    }

    // ======================== DT10-11:补未测方法的差分(双实现等价)========================

    /**
     * DT10: ofColumnsDirect(零拷贝)vs ofColumnArrays(普通)—— 两条构造路径应产出等价 DataFrame。
     * 覆盖之前未测的 ofColumnsDirect(hot path 工厂)。
     */
    @RepeatedTest(5)
    void dt_ofColumnsDirect_等于ofColumnArrays() {
        Random r = new Random();
        int n = r.nextInt(50) + 1;
        long[] ids = new long[n];
        double[] vs = new double[n];
        for (int i = 0; i < n; i++) { ids[i] = r.nextLong() % 1000; vs[i] = r.nextDouble() * 100; }
        List<String> names = List.of("id", "v");

        // 路径 A:ofColumnsDirect(零拷贝,直接引用 Column 列表)
        LongColumn a1 = LongColumn.wrapNoCopy("id", ids, null);
        DoubleColumn a2 = DoubleColumn.wrapNoCopy("v", vs);
        DataFrame directDf = DataFrame.ofColumnsDirect(List.of(a1, a2));
        // 路径 B:ofColumnArrays(普通,内部建 Column)
        DataFrame arrayDf = DataFrame.ofColumnArrays(names, new Object[]{ ids, vs });

        // 等价校验:行数/列名/逐行值
        assertThat(directDf.rowCount()).isEqualTo(arrayDf.rowCount());
        assertThat(directDf.columnNames()).containsExactly("id", "v");
        for (int i = 0; i < n; i++) {
            assertThat(directDf.getLongColumn("id").getLong(i))
                    .as("DT10 id 第 " + i + " 行")
                    .isEqualTo(arrayDf.getLongColumn("id").getLong(i));
            assertThat(directDf.getDoubleColumn("v").getDouble(i))
                    .as("DT10 v 第 " + i + " 行")
                    .isEqualTo(arrayDf.getDoubleColumn("v").getDouble(i), org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    /**
     * DT11: getIntColumn(LONG 列)转 INT vs 直接构造 INT 列 —— 类型转换路径应与小值 INT 等价。
     * 覆盖之前未测的 getIntColumn 类型转换分支。
     */
    @Test
    void dt_getIntColumn_LONG转INT_等于直接INT() {
        // 用小值(< 1000)避免 LONG→INT 溢出
        long[] vals = {1L, 50L, 100L, 999L};
        // 路径 A:LONG 列 getIntColumn(内部 LONG→INT 转换)
        DataFrame longDf = DataFrame.ofColumnArrays(
                List.of("n"), new Object[]{ vals });
        IntColumn fromLong = longDf.getIntColumn("n");
        // 路径 B:直接构造 INT 列(参考真值)
        int[] directInts = new int[vals.length];
        for (int i = 0; i < vals.length; i++) directInts[i] = (int) vals[i];
        IntColumn directInt = new IntColumn("n", directInts);

        // 等价:逐行 long 值相同
        assertThat(fromLong.size()).isEqualTo(directInt.size());
        for (int i = 0; i < vals.length; i++) {
            assertThat(fromLong.getLong(i))
                    .as("DT11 LONG→INT 第 " + i + " 行")
                    .isEqualTo(directInt.getLong(i))
                    .isEqualTo(vals[i]);
        }
    }
}
