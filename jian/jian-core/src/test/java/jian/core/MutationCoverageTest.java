package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : 变异覆盖补强测试 —— 针对 PITest SURVIVED 变异的定向补测(ai-code-testing 方法学:变异测试闭环)
// │  Why  : 变异测试显示 GroupBy.size(40%杀死率)/buildColumn(19 SURVIVED)/ColumnarHashMap(66.7%)
// │         的 null 判断/instanceof 分发/flag 分支没被现有测试覆盖(只断言 rowCount/聚合值,
// │         不断言 dtype/key值/null mask,分支翻转后"看起来还对")。本测试定向杀这些变异,攻 ≥80%。
// │  Who  : 变异测试流程(mutation testing 靶点分析)
// │  When : GroupBy/ColumnarHashMap/DataFrame.buildColumn 改动时
// │  Where: jian-core/MutationCoverageTest.java
// │  How  : 数据走向:逐 dtype/边界构造 df → groupBy().size() / buildColumn(经 DataFrame.of)
// │           → 断言 getColumn("key").dtype() + isNull(i) + 值 → 钉死每个 instanceof/null 分支。
// │         关键变量:key 列 dtype(随组键类型变)、isNull(i)(缺失行)、findLong 返回(首现下标)。
// │         逻辑路线:T1 杀 size 的 instanceof 分发链;T2 杀 buildGroups 的 null/±0.0 归一;
// │           T6 杀 ColumnarHashMap.buildFromInt 的冲突探测链。
class MutationCoverageTest {

    // ======================== T1: GroupBy.size key 列 dtype 矩阵(杀 size 12 个 SURVIVED) ========================

    @Test
    void groupBy_size_key列dtype随组键类型() {
        // INT 列内部升位 LONG 存储(§3.5)→ groupBy key 为 Long → key 列 LONG
        DataFrame di = DataFrame.of(Schema.of("g", DType.INT, "v", DType.DOUBLE),
                new Object[][]{{1, 1.0}, {2, 2.0}, {1, 3.0}});
        DataFrame ri = di.groupBy("g").size();
        assertThat(ri.getColumn("key").dtype()).isEqualTo(DType.LONG);
        assertThat(ri.getLongColumn("key").data()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(ri.getLongColumn("size").data()).containsExactlyInAnyOrder(2L, 1L);   // g=1 有2行,g=2 有1行

        // LONG 列 → key LONG(L336)
        DataFrame dl = DataFrame.of(Schema.of("g", DType.LONG, "v", DType.DOUBLE),
                new Object[][]{{1L, 1.0}, {2L, 2.0}});
        assertThat(dl.groupBy("g").size().getColumn("key").dtype()).isEqualTo(DType.LONG);

        // DOUBLE 列 → key DOUBLE(L337)
        DataFrame dd = DataFrame.of(Schema.of("g", DType.DOUBLE, "v", DType.DOUBLE),
                new Object[][]{{1.5, 1.0}, {2.5, 2.0}});
        assertThat(dd.groupBy("g").size().getColumn("key").dtype()).isEqualTo(DType.DOUBLE);

        // BOOL 列 → key BOOL(L338,generic 路径仍把 Boolean key 保留)
        DataFrame db = DataFrame.of(Schema.of("g", DType.BOOL, "v", DType.DOUBLE),
                new Object[][]{{true, 1.0}, {false, 2.0}});
        assertThat(db.groupBy("g").size().getColumn("key").dtype()).isEqualTo(DType.BOOL);

        // 多列分组 → key STRING(toString,L326/L327:多列 singleCol=false)
        DataFrame ds = DataFrame.of(Schema.of("g", DType.STRING, "h", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"a", "x", 1.0}, {"b", "y", 2.0}});
        DataFrame rm = ds.groupBy("g", "h").size();
        assertThat(rm.getColumn("key").dtype()).isEqualTo(DType.STRING);
    }

    // ======================== T2: buildGroups 的 null 键与 ±0.0 归一(杀 buildGroups 8-10 个) ========================

    @Test
    void groupBy_null键独立成组与正负零归一() {
        // LONG 列含 null → null 键独立 "<NA>" 组(不应被吞进 0 组)
        DataFrame df = DataFrame.of(Schema.of("g", DType.LONG, "v", DType.DOUBLE),
                new Object[][]{{1L, 1.0}, {null, 2.0}, {0L, 3.0}, {1L, 4.0}});
        DataFrame r = df.groupBy("g").agg("v", "count");
        assertThat(r.rowCount()).isEqualTo(3);   // 3 组:1 / null / 0
        assertThat(r.getLongColumn("v_count").data()).containsExactlyInAnyOrder(2L, 1L, 1L);

        // 多列 + DOUBLE ±0.0:generic 路径把 -0.0 归入 +0.0 同组(§10.16 #6)
        DataFrame dz = DataFrame.of(Schema.of("a", DType.STRING, "b", DType.DOUBLE),
                new Object[][]{{"x", 0.0}, {"x", -0.0}, {"y", 1.0}});
        assertThat(dz.groupBy("a", "b").size().rowCount()).isEqualTo(2);   // (x,0.0)同组 + (y,1.0)

        // STRING null → 独立缺失组(key 列含缺失组时 OBJECT + null 行标签,
        // 不用 "<NA>" 字符串 —— 避免 null 字符串与 Long 混型触发 toNumber NFE)
        DataFrame dn = DataFrame.of(Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{null, 1.0}, {"A", 2.0}});
        assertThat(dn.groupBy("g").size().getColumn("key").toObjectArray())
                .containsExactlyInAnyOrder(null, "A");
    }

    // ======================== T6: ColumnarHashMap.buildFromInt 冲突探测(杀 buildFromInt L116/117) ========================

    @Test
    void columnarHashMap_int键冲突与重复键保持首现下标() {
        // What:大量重复+冲突的 int 键,buildFromInt 为每个键记录下标(覆盖/最后语义)。
        // Why :L116(探测循环 while bucketFirst[slot]!=-1)/L117(键相等 break)变异会返回错误下标或 -1。
        // How :600 个键值域 0..299 → 必然大量冲突+重复;对照 java.util.HashMap 的 put(覆盖→最后下标)。
        int[] keys = new int[600];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < keys.length; i++) keys[i] = rnd.nextInt(300);
        ColumnarHashMap m = ColumnarHashMap.buildFromInt(keys);
        java.util.Map<Integer, Integer> lastIdx = new java.util.HashMap<>();
        for (int i = 0; i < keys.length; i++) lastIdx.put(keys[i], i);   // put 覆盖 → 最后下标(对齐实现)
        for (java.util.Map.Entry<Integer, Integer> e : lastIdx.entrySet()) {
            assertThat(m.findLong(e.getKey())).isEqualTo(e.getValue());   // 最后下标;变异会返错误值/-1
        }
    }

    // ======================== T3: DataFrame.buildColumn 各 dtype 的 null 行/null 单元格(杀 buildColumn 19 个) ========================

    @Test
    void buildColumn_各dtype的null行与null单元格() {
        // What:整行 null + 单元格 null,各 dtype 的 isNull/getDouble 应正确。
        // Why :buildColumn 的 rows[r]==null 短路(L316/326/335)若被 RemoveConditional 变异 → null 行取 rows[r][c] NPE;
        //       v==null 分支(L345/355)若变异 → null 值不保留 → 误走解析分支返垃圾值。
        // How :构造含"整行 null + 单元格全 null"的多 dtype df → 逐列断言 isNull(i)/getDouble/get。
        DataFrame df = DataFrame.of(
                Schema.of("i", DType.INT, "l", DType.LONG, "d", DType.DOUBLE, "s", DType.STRING, "b", DType.BOOL),
                new Object[][]{{1, 1L, 1.0, "a", true}, null, {null, null, null, null, null}});
        assertThat(df.rowCount()).isEqualTo(3);
        // 整行 null(第1行)→ 各列缺失
        assertThat(df.getColumn("i").isNull(1)).isTrue();
        assertThat(df.getColumn("l").isNull(1)).isTrue();
        assertThat(df.getColumn("d").isNull(1)).isTrue();
        assertThat(df.getColumn("s").isNull(1)).isTrue();
        assertThat(df.getColumn("b").isNull(1)).isTrue();
        // 单元格 null(第2行全 null):DOUBLE→NaN(§3.5),STRING→null
        assertThat(df.getColumn("i").isNull(2)).isTrue();
        assertThat(Double.isNaN(df.getColumn("d").getDouble(2))).isTrue();
        assertThat(df.getColumn("s").get(2)).isNull();
    }

    // ======================== T5: DataFrame.sample 参数边界(杀 sampleImpl 9 个) ========================

    @Test
    void sample_参数边界全分支() {
        // What:sample 的参数边界(n<0/total==0/!replace&&n>total/n==0/replace&&n==total 五条路径)。
        // Why :sampleImpl 的校验 if 变异(RemoveConditional/ConditionalsBoundary)会让本该抛的不抛、或不该抛的抛。
        // How :成对断言(抛 IAE / 返正常)钉死每条校验分支。
        DataFrame df = DataFrame.of(Schema.of("id", DType.LONG),
                new Object[][]{{0L}, {1L}, {2L}, {3L}, {4L}});
        assertThat(df.sample(0, false, 1).rowCount()).isEqualTo(0);    // n=0 → 空(n<0 才抛)
        assertThatThrownBy(() -> df.sample(-1, false, 1))               // n<0 → 抛
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(df.sample(5, true, 1).rowCount()).isEqualTo(5);     // replace=true 放行 n==total
        assertThatThrownBy(() -> df.sample(6, false, 1))                // !replace && n>total → 抛
                .isInstanceOf(IllegalArgumentException.class);
        DataFrame empty = DataFrame.of(Schema.of("id", DType.LONG), new Object[][]{});
        assertThatThrownBy(() -> empty.sample(1, true, 1))              // total==0 && n>0 → 抛
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildColumn_字符串形式解析BOOL与DATETIME与DATE() {
        // What:Schema 指定 BOOL/DATETIME/DATE 但行数据是 String → buildColumn 应 trim+parse。
        // Why :杀 buildColumn 的 String→类型 转换分支(L348 BOOL parseBoolean、L366 DATETIME replace+parse、L376 DATE parse);
        //      这些 instanceof 分支的 ELSE/IF 变异会让 String 值强转 → CCE。
        // How :构造 String 值的 BOOL/DATETIME/DATE 列 → 断言解析后的精确值。
        DataFrame fb = DataFrame.of(Schema.of("b", DType.BOOL),
                new Object[][]{{" true "}, {"FALSE"}});
        assertThat(fb.getColumn("b").get(0)).isEqualTo(Boolean.TRUE);   // trim + parseBoolean
        assertThat(fb.getColumn("b").get(1)).isEqualTo(Boolean.FALSE);

        DataFrame fd = DataFrame.of(Schema.of("dt", DType.DATETIME, "dd", DType.DATE),
                new Object[][]{{"2024-01-02 03:04:05", "2024-01-02"}});
        assertThat(fd.getColumn("dt").get(0))
                .isEqualTo(java.time.LocalDateTime.parse("2024-01-02T03:04:05"));   // 空格→T 再 parse
        assertThat(fd.getColumn("dd").get(0)).isEqualTo(java.time.LocalDate.of(2024, 1, 2));
    }

    @Test
    void sample_镜像Random精确复现采样序列() {
        // What:同 seed 的 sample(replace=true)应精确复现 Random 序列。
        // Why :杀 sampleImpl 洗牌/采样循环(L2347 rng.nextInt(total))——MathMutator/循环删除变异会让序列错。
        // How :用 java.util.Random(42) 重建采样序列,断言 sample 返回的 id 列与之一致(镜像 oracle)。
        DataFrame df = DataFrame.of(Schema.of("id", DType.LONG),
                new Object[][]{{0L}, {1L}, {2L}, {3L}, {4L}});
        java.util.Random mirror = new java.util.Random(42);
        long[] expected = new long[5];
        for (int k = 0; k < 5; k++) expected[k] = mirror.nextInt(5);   // 对齐 sampleImpl: picked[k]=rng.nextInt(total)
        assertThat(df.sample(5, true, 42).getLongColumn("id").data()).isEqualTo(expected);
    }

    @Test
    void dataframe_toString_含列名行数摘要与截断标记() {
        // What:toString 含列名/数据/行数摘要/truncate 标记。
        // Why :杀 toString 的 appendRow/format/truncate 分支(SURVIVED 17 个);精确断言行数摘要杀 MathMutator,
        //      truncate 标记杀 if(truncate) 分支。
        DataFrame df = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        String s = df.toString();
        assertThat(s).contains("id", "name", "alice", "bob");
        assertThat(s).contains("[2 rows × 2 columns]");   // 行数×列数摘要(杀 nRows/columns 算术变异)

        // 大表(>默认 maxRows)→ 应有 "..." 截断标记
        Object[][] big = new Object[200][1];
        for (int i = 0; i < 200; i++) big[i][0] = (long) i;
        String bs = DataFrame.of(Schema.of("v", DType.LONG), big).toString();
        assertThat(bs).contains("...");                    // 截断标记(杀 truncate 分支)
        assertThat(bs).contains("[200 rows × 1 columns]");
    }
}
