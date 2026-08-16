package jian.core;

// ┌─ What : 外部 AI 协作复审发现的缺陷修复后的回归测试(AuditRegressionTest)
// │  Why  : ai-code-testing 铁律 —— 每个修复必须配"重现代码"测试防回归;
// │         覆盖 DType 提升/大数比较/判重归一/pivot dtype/mergeAsof 缺失键/
// │         整数求和/超 long 字面量/Resampler dtype/构造与转换契约统一
// │  Who  : 本轮修复(jian-core 多文件)配套;后续复审的对照基线
// │  When : 每次 jian-core 测试运行
// │  Where: jian-core/src/test/java/jian/core/AuditRegressionTest.java
// │  How  : 每个 BUG 一至多个用例,先重现旧行为的错误期望(修复前失败),修复后应全绿;
// │         覆盖口径:DType.promote 混型抛错 / valueEquals 大整数精度 / ±0.0 判重 /
// │         pivotTable dtype 分派 / mergeAsof 缺失键 / 整数列 long 求和 / Resampler dtype。
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AuditRegressionTest {

    // ═══════════════ DType.promote BOOL×非数值混型 ═══════════════

    @Test
    void promote_BOOL与非数值混型_抛IAE() {
        // 修复前:BOOL+DATE/DATETIME/CATEGORY 漏进数值分支返回 INT(与 cmp 混型抛错口径分裂)
        assertThatThrownBy(() -> DType.promote(DType.BOOL, DType.DATE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DType.promote(DType.BOOL, DType.DATETIME))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DType.promote(DType.CATEGORY, DType.BOOL))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promote_BOOL与数值族_仍正常提升() {
        assertThat(DType.promote(DType.BOOL, DType.INT)).isEqualTo(DType.INT);
        assertThat(DType.promote(DType.BOOL, DType.LONG)).isEqualTo(DType.LONG);
        assertThat(DType.promote(DType.BOOL, DType.DOUBLE)).isEqualTo(DType.DOUBLE);
        assertThat(DType.promote(DType.BOOL, DType.OBJECT)).isEqualTo(DType.OBJECT);   // OBJECT 兜底不受影响
    }

    // ═══════════════ valueEquals / cmp 大整数精度 ═══════════════

    @Test
    void valueEquals_大整数与浮点_不丢精度() {
        // 2^53+1 与 2^53 的 double 投影相同,但数值不等(修复前 doubleValue 直比误判相等)
        assertThat(DataFrameCompare.valueEquals(9_007_199_254_740_993L, 9_007_199_254_740_992.0)).isFalse();
        assertThat(DataFrameCompare.valueEquals(9_007_199_254_740_992.0, 9_007_199_254_740_993L)).isFalse();
        // 常规跨型相等不受影响
        assertThat(DataFrameCompare.valueEquals(30L, 30.0)).isTrue();
        assertThat(DataFrameCompare.valueEquals(30L, 30.5)).isFalse();
        // BigInteger 走 BigDecimal 精确(修复前落 double 路径:> 2^53 的值被折叠)
        assertThat(DataFrameCompare.valueEquals(new java.math.BigInteger("9007199254740993"),
                                                9_007_199_254_740_992.0)).isFalse();
        assertThat(DataFrameCompare.valueEquals(new java.math.BigInteger("9007199254740992"),
                                                9_007_199_254_740_992.0)).isTrue();
    }

    @Test
    void cmp_混型方向_浮点左值() {
        // 修复中引入并当场修掉的方向 bug:浮点列 vs 整数字面量的 < > 方向必须正确
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{2.0}, {0.5}});
        assertThat(SimpleQueryParser.evaluate(df, "v > 1")).containsExactly(true, false);
        assertThat(SimpleQueryParser.evaluate(df, "v < 1")).containsExactly(false, true);
    }

    @Test
    void cmp_大整数字面量与DOUBLE列_精确比较() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{9_007_199_254_740_992.0}});
        // 2^53(列值)< 2^53+1(long 字面量)→ true;修复前 doubleValue 直比两者相等
        assertThat(SimpleQueryParser.evaluate(df, "v < 9007199254740993")).containsExactly(true);
        assertThat(SimpleQueryParser.evaluate(df, "v == 9007199254740992")).containsExactly(true);
        assertThat(SimpleQueryParser.evaluate(df, "v == 9007199254740993")).containsExactly(false);
    }

    // ═══════════════ dropDuplicates / duplicated ±0.0 归一 ═══════════════

    @Test
    void dropDuplicates_正零负零归一_NaN天然判重() {
        // pandas:drop_duplicates 把 ±0.0 视为相等、NaN 视为相等 → [0.0, NaN, 1.0] 共 3 行
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{
            {+0.0}, {-0.0}, {Double.NaN}, {Double.NaN}, {1.0}});
        DataFrame r = df.dropDuplicates();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getColumn("v").getDouble(0)).isEqualTo(0.0);
        assertThat(r.getRow(1)[0]).isNull();   // NaN 行经 IO 边界转 null(§3.5)
        // keep=last / false 同口径
        assertThat(df.dropDuplicates(null, "last").rowCount()).isEqualTo(3);
        assertThat(df.duplicated()).containsExactly(false, true, false, true, false);
    }

    @Test
    void pivot_正零负零不裂列() {
        DataFrame df = DataFrame.of(
            Schema.of("i", DType.STRING, "c", DType.DOUBLE, "v", DType.DOUBLE),
            new Object[][]{{"a", +0.0, 1.0}, {"a", -0.0, 2.0}, {"b", 1.0, 3.0}});
        // 修复前:+0.0 与 -0.0 裂成两列;修复后合并为一列"0.0"
        DataFrame pt = df.pivotTable("i", "c", "v", "sum");
        assertThat(pt.columnCount()).isEqualTo(3);   // i + "0.0" + "1.0"
    }

    // ═══════════════ pivotTable 输出 dtype 分派 ═══════════════

    @Test
    void pivotTable_first聚合保留源dtype() {
        DataFrame df = DataFrame.of(
            Schema.of("row", DType.STRING, "col", DType.STRING, "name", DType.STRING),
            new Object[][]{{"r1", "c1", "alice"}, {"r1", "c2", "bob"}, {"r2", "c1", "carol"}});
        DataFrame r = df.pivotTable("row", "col", "name", "first");
        // 修复前:聚合列一律标 DOUBLE,String 值塞进 DOUBLE 列强转失败/失真
        assertThat(r.getColumn("c1").dtype()).isEqualTo(DType.STRING);
        assertThat(r.getColumn("c2").dtype()).isEqualTo(DType.STRING);
        assertThat(r.get(0, "c1")).isEqualTo("alice");
        assertThat(r.get(1, "c1")).isEqualTo("carol");
    }

    @Test
    void pivotTable_count聚合输出LONG() {
        DataFrame df = DataFrame.of(
            Schema.of("i", DType.STRING, "c", DType.STRING, "v", DType.DOUBLE),
            new Object[][]{{"a", "x", 1.0}, {"a", "x", 2.0}, {"b", "x", 3.0}});
        DataFrame r = df.pivotTable("i", "c", "v", "count");
        assertThat(r.getColumn("x").dtype()).isEqualTo(DType.LONG);   // 对齐 GroupBy.agg count
        assertThat(r.get(0, "x")).isEqualTo(2L);
        assertThat(r.get(1, "x")).isEqualTo(1L);
    }

    @Test
    void pivotTable_sum_整数列输出LONG_大和精确() {
        DataFrame df = DataFrame.of(
            Schema.of("i", DType.STRING, "c", DType.STRING, "v", DType.LONG),
            new Object[][]{{"a", "x", 4_500_000_000_000_000L}, {"a", "x", 4_500_000_000_000_001L}});
        DataFrame r = df.pivotTable("i", "c", "v", "sum");
        assertThat(r.getColumn("x").dtype()).isEqualTo(DType.LONG);
        // 9e15 在 double 可表示域内但两个相邻 long 之和需要 long 累计才精确
        assertThat(r.get(0, "x")).isEqualTo(9_000_000_000_000_001L);
    }

    @Test
    void pivotTable_sum_BOOL列输出LONG_true计数() {
        DataFrame df = DataFrame.of(
            Schema.of("i", DType.STRING, "c", DType.STRING, "v", DType.BOOL),
            new Object[][]{{"a", "x", true}, {"a", "x", true}, {"a", "x", false}});
        DataFrame r = df.pivotTable("i", "c", "v", "sum");
        assertThat(r.getColumn("x").dtype()).isEqualTo(DType.LONG);
        assertThat(r.get(0, "x")).isEqualTo(2L);   // true 计数,对齐 GroupBy.agg BOOL sum
    }

    // ═══════════════ mergeAsof 缺失键语义 ═══════════════

    @Test
    void mergeAsof_null左键抛IAE_对齐pandas() {
        // pandas 1.5.3 实测:merge_asof 对 null 左键抛 ValueError
        //("Merge keys contain null values on left side"),不容忍缺失键
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "lv", DType.STRING),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), "L1"},
                {null, "L2(null 键)"},
                {LocalDateTime.of(2026, 1, 3, 0, 0), "L3"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "rv", DType.STRING),
            new Object[][]{{LocalDateTime.of(2026, 1, 1, 0, 0), "R1"}});
        // 修复前:null 左键复用上一行的匹配(rp 不重置);修复后:对齐 pandas 直接抛 IAE
        assertThatThrownBy(() -> left.mergeAsof(right, "ts"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("left");
    }

    @Test
    void mergeAsof_右键缺失抛IAE_对齐pandas() {
        // 修复前:右表过滤用 get()!=null,DOUBLE 列 NaN 右键漏网;
        // 修复后:isNull 权威判定,NaN 右键同样抛 IAE(pandas 同输入抛 ValueError)
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.DOUBLE, "lv", DType.STRING),
            new Object[][]{{2.0, "L1"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.DOUBLE, "rv", DType.STRING),
            new Object[][]{{Double.NaN, "Rnan"}, {1.0, "R1"}});
        assertThatThrownBy(() -> left.mergeAsof(right, "ts"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("right");
    }

    @Test
    void mergeAsof_清洗后正常backward匹配() {
        // 缺失键被清洗(dropna)后,backward 语义与单调推进保持正确
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "lv", DType.STRING),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), "L1"},
                {LocalDateTime.of(2026, 1, 3, 0, 0), "L3"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "rv", DType.STRING),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 0, 0), "R1"},
                {LocalDateTime.of(2026, 1, 2, 0, 0), "R2"}});
        DataFrame r = left.mergeAsof(right, "ts");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getRow(0)[2]).isEqualTo("R1");
        assertThat(r.getRow(1)[2]).isEqualTo("R2");
    }

    // ═══════════════ 整数列 long 求和精度 ═══════════════

    @Test
    void colSum_大整数对消_精确() {
        // 2^53+1 与 2^53 的 double 投影相同:double 累加得 0.0,long 累加得 1
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
            new Object[][]{{9_007_199_254_740_993L}, {-9_007_199_254_740_992L}});
        assertThat(df.colSum("v")).isEqualTo(1.0);
    }

    // ═══════════════ 超 long 整数字面量 fail-fast(core 侧)═══════════════

    @Test
    void 字面量超long抛IAE_科学计数法照常() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{200.0}});
        // 修复前:9223372036854775808 静默回退 double(折成 9.223372036854776E18)
        assertThatThrownBy(() -> SimpleQueryParser.evaluate(df, "v > 9223372036854775808"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超出 long 范围");
        // 科学计数法不受影响(显式近似路径)
        assertThat(SimpleQueryParser.evaluate(df, "v > 1e2")).containsExactly(true);
    }

    // ═══════════════ Resampler 聚合 dtype 对齐 GroupBy ═══════════════

    @Test
    void resampler_sum_整数列输出LONG() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG, "f", DType.BOOL),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 1, 0), 2L, true},
                {LocalDateTime.of(2026, 1, 1, 2, 0), 3L, false},
                {LocalDateTime.of(2026, 1, 2, 1, 0), 4L, true}});
        DataFrame r = df.resample("ts", "1D").sum();
        // 修复前:一律 DOUBLE(1.0/5.0),与 GroupBy.agg 的 LONG 口径分裂
        assertThat(r.getColumn("v_sum").dtype()).isEqualTo(DType.LONG);
        assertThat(r.get(0, "v_sum")).isEqualTo(5L);
        assertThat(r.get(1, "v_sum")).isEqualTo(4L);
        // BOOL sum = true 计数(LONG),对齐 GroupBy.agg
        assertThat(r.getColumn("f_sum").dtype()).isEqualTo(DType.LONG);
        assertThat(r.get(0, "f_sum")).isEqualTo(1L);
        assertThat(r.get(1, "f_sum")).isEqualTo(1L);
    }

    @Test
    void resampler_count输出LONG() {
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.DOUBLE),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 1, 0), 1.0},
                {LocalDateTime.of(2026, 1, 1, 2, 0), 2.0},
                {LocalDateTime.of(2026, 1, 2, 1, 0), 3.0}});
        DataFrame r = df.resample("ts", "1D").count();
        assertThat(r.getColumn("v_count").dtype()).isEqualTo(DType.LONG);
        assertThat(r.get(0, "v_count")).isEqualTo(2L);
        assertThat(r.get(1, "v_count")).isEqualTo(1L);
    }

    @Test
    void resampler_空桶缺失语义保持() {
        // §10.16#14 回归锚:无观测桶 sum/count 返回缺失(LONG 列为 null),不回退 0
        DataFrame df = DataFrame.of(
            Schema.of("ts", DType.DATETIME, "v", DType.LONG),
            new Object[][]{
                {LocalDateTime.of(2026, 1, 1, 1, 0), 1L},
                {LocalDateTime.of(2026, 1, 3, 1, 0), 2L}});   // 1/2 整日无观测
        DataFrame r = df.resample("ts", "1D").sum();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getRow(1)[1]).isNull();   // 1/2 桶无观测 → 缺失
        assertThat(r.get(0, "v_sum")).isEqualTo(1L);
        assertThat(r.get(2, "v_sum")).isEqualTo(2L);
    }

    // ═══════════════ BOOL 构造与转换契约统一 ═══════════════

    @Test
    void of_BOOL_schema_数值元素_不抛CCE_非零即true() {
        // 修复前:构造分支 (String) 强转,Number 元素直接 ClassCastException
        DataFrame df = DataFrame.of(Schema.of("v", DType.BOOL),
            new Object[][]{{1}, {0}, {2.0}, {1L}, {-3}});
        boolean[] vals = new boolean[5];
        for (int i = 0; i < 5; i++) vals[i] = (Boolean) df.getColumn("v").get(i);
        assertThat(vals).containsExactly(true, false, true, true, true);   // 非零即 true(对齐 pandas)
    }

    @Test
    void of_BOOL_schema_字符串与astype路径一致() {
        // 修复前:parseBoolean 连 "1" 都判 false,与 astype 分裂;修复后两路同口径
        Object[][] input = {{"true"}, {"TRUE"}, {"1"}, {"0"}, {"yes"}, {" false "}};
        DataFrame byConstruct = DataFrame.of(Schema.of("v", DType.BOOL), input);
        DataFrame byAstype = DataFrame.of(Schema.of("v", DType.STRING), input).astype("v", DType.BOOL);
        for (int i = 0; i < input.length; i++) {
            assertThat((Boolean) byConstruct.getColumn("v").get(i))
                .as("第 %d 行构造与 astype 应一致", i)
                .isEqualTo((Boolean) byAstype.getColumn("v").get(i));
        }
        // §10.16#15 声明差异锚:仅 "true"/"1" 为 true,"yes"/"false" 一律 false
        assertThat((Boolean) byConstruct.getColumn("v").get(4)).isFalse();
        assertThat((Boolean) byConstruct.getColumn("v").get(5)).isFalse();
    }

    // ═══════════════ 时间跨类型 / 数值报错上下文 / BigInteger 超域 ═══════════════

    @Test
    void of_DATETIME_schema跨类型元素_不抛CCE() {
        // 修复前:LocalDate 元素落入 ((String) v) 强转抛 CCE
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME), new Object[][]{
            {java.time.LocalDate.of(2026, 1, 1)},                     // LocalDate → atStartOfDay
            {java.time.LocalDateTime.of(2026, 1, 2, 12, 0)},          // 直取
            {"2026-01-03 08:30:00"}});                                 // 空格分隔字符串
        assertThat(df.getColumn("ts").get(0))
            .isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(df.getColumn("ts").get(2))
            .isEqualTo(java.time.LocalDateTime.of(2026, 1, 3, 8, 30, 0));
    }

    @Test
    void of_DATE_schema_LocalDateTime元素_不抛CCE() {
        // 修复前:LocalDateTime 元素落入 ((String) v) 强转抛 CCE
        DataFrame df = DataFrame.of(Schema.of("d", DType.DATE), new Object[][]{
            {java.time.LocalDateTime.of(2026, 1, 1, 23, 59)},   // → toLocalDate
            {java.time.LocalDate.of(2026, 1, 2)},                // 直取
            {"2026-01-03"}});
        assertThat(df.getColumn("d").get(0)).isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(df.getColumn("d").get(2)).isEqualTo(java.time.LocalDate.of(2026, 1, 3));
    }

    @Test
    void of_数值schema_非法字符串_抛IAE带上下文() {
        // 修复前:裸 NumberFormatException("For input string: \"abc\""),无列名/行号
        assertThatThrownBy(() -> DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {"abc"}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("列 'v'").hasMessageContaining("第 1 行");
        assertThatThrownBy(() -> DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{"3.14abc"}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("列 'v'");
    }

    @Test
    void astype_INT超long域的BigInteger_抛IAE不静默回绕() {
        // 修复前:convertColumn INT 对 BigInteger 裸 intValue() 静默截断(数据损坏)
        DataFrame df = DataFrame.of(Schema.of("v", DType.OBJECT),
            new Object[][]{{new java.math.BigInteger("99999999999999999999")}});
        assertThatThrownBy(() -> df.astype("v", DType.INT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超出 long 范围");
        // BigDecimal 带小数同样 fail-fast(与 construct/LONG 分支同口径)
        DataFrame df2 = DataFrame.of(Schema.of("v", DType.OBJECT),
            new Object[][]{{new java.math.BigDecimal("1e30")}});
        assertThatThrownBy(() -> df2.astype("v", DType.INT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void astype_INT_long域内超int_回绕对齐pandas() {
        // [对齐声明] pandas 1.5.3/numpy 1.24 实测:int64→int32 静默回绕
        //(pd.Series([5_000_000_000]).astype('int32') == 705032704),jian 同行为不制造新差异
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
            new Object[][]{{5_000_000_000L}});
        DataFrame r = df.astype("v", DType.INT);
        assertThat(r.getColumn("v").get(0)).isEqualTo(705032704);
    }
}
