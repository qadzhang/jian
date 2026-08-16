package jian.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : CoreQueryRegressionTest —— 查询表达式与比较语义回归测试集:固化 SimpleQueryParser(兜底引擎)/
// │         DataFrameCompare / DataFrameMerge.compareAsf 的行为(字面量语法、混型、缺失、精度的比较口径)
// │  Why  : 因为过滤表达式的每个语法点与比较语义都必须与 pandas 及 jian-dsl 引擎(双引擎)口径一致,
// │         否则同一条表达式在有无 jian-dsl jar 时结果翻转,所以逐语法点用精确断言固化
// │  Who  : jian-core 测试套件(surefire)执行;与 jian-dsl 的 EngineConformanceTest(双引擎矩阵)互补
// │  When : 改动 SimpleQueryParser / DataFrameCompare / compareAsf 相关行为后必须全绿
// │  Where: jian-core/src/test/java/jian/core/CoreQueryRegressionTest.java
// │  How  : 数据走向:固定小表 → SimpleQueryParser.evaluate / DataFrameCompare.cmp / 反射调 compareAsf
// │         → 逐元素断言掩码或抛出的 IAE(带位置/类型提示)。
// │         关键变量:字面量形态(科学计数法/大整数/引号转义)、混型组合(String vs Number)、
// │         缺失行(null 参与 ==/!=/is null 的三值逻辑)。
// │         逻辑路线:合法语法 → 精确掩码;非法语法/混型顺序比较 → IAE(带上下文,不静默、不裸抛)。
class CoreQueryRegressionTest {

    /** 列对构造辅助:df("a", arr, "b", arr) → DataFrame.ofColumns(保插入序)。 */
    private static DataFrame df(Object... colPairs) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (int i = 0; i < colPairs.length; i += 2) m.put((String) colPairs[i], (Object[]) colPairs[i + 1]);
        return DataFrame.ofColumns(m);
    }

    // ======================== SimpleQueryParser:科学计数法 ========================

    @Test
    void query_科学计数法_1e2() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {50.0}, {150.0}, {1000.0}});
        // 1e2 = 100.0:50 < 100 不选,150/1000 选
        boolean[] mask = SimpleQueryParser.evaluate(df, "v > 1e2");
        assertThat(mask).containsExactly(false, false, true, true);
    }

    @Test
    void query_科学计数法_小数与负指数() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{0.001}, {0.01}, {0.1}});
        // 1.5e-3 = 0.0015:仅 0.01/0.1 通过
        assertThat(SimpleQueryParser.evaluate(df, "v > 1.5e-3")).containsExactly(false, true, true);
        // 1E+10 = 1e10:全不通过
        assertThat(SimpleQueryParser.evaluate(df, "v > 1E+10")).containsExactly(false, false, false);
        // -1e-2 = -0.01:全部通过
        assertThat(SimpleQueryParser.evaluate(df, "v > -1e-2")).containsExactly(true, true, true);
    }

    @Test
    void query_科学计数法_回退不误吃标识符() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE, "e10", DType.DOUBLE),
            new Object[][]{{5.0, 1.0}, {15.0, 2.0}});
        // 列名 e10 不能被科学计数法吞掉:1e 后无数字,e10 是合法列名
        assertThat(SimpleQueryParser.evaluate(df, "e10 > 1")).containsExactly(false, true);
    }

    // ======================== SimpleQueryParser:混型与语法边界 ========================

    @Test
    void query_混合类型顺序比较抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING, "n", DType.INT),
            new Object[][]{{"abc", 1}});
        // pandas 对 "abc" > 1 抛 TypeError;jian 对齐抛 IAE(字符串与数值的顺序比较无定义)
        assertThatThrownBy(() -> SimpleQueryParser.evaluate(df, "s > 1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无法比较");
        // 字符串对字符串仍正常
        assertThat(SimpleQueryParser.evaluate(df, "s > 'aaa'")).containsExactly(true);
        // 相等比较跨类型自然不等(1 == "1" 为 false,对齐 pandas)
        assertThat(SimpleQueryParser.evaluate(df, "n == s")).containsExactly(false);
    }

    @Test
    void 兜底引擎大整数字面量精确比较() {
        // SimpleQueryParser 的整数字面量装 Long,BinCmp 走 long 精确路径(与 jian-dsl 引擎同步)
        DataFrame d = DataFrame.of(Schema.of("a", DType.LONG),
                new Object[][]{{9223372036854775806L}, {9223372036854775807L}});
        int hit = 0;
        for (boolean b : SimpleQueryParser.evaluate(d, "a == 9223372036854775806")) if (b) hit++;
        assertThat(hit).as("兜底引擎精确命中 1 行(不走 double 舍入)").isEqualTo(1);
    }

    @Test
    void notin单字不再吃掉左括号() {
        DataFrame d = df("a", new Object[]{1, 2, 3});
        // notin 之后的 ( 是列表开括号,不能被误吃(否则报"位置 9"语法错)
        boolean[] mask = SimpleQueryParser.evaluate(d, "a notin (2, 4)");
        assertThat(mask).containsExactly(true, false, true);
    }

    @Test
    void 反引号支持特殊字符列名() {
        DataFrame d = df("col x", new Object[]{1, 2, 3}, "a-b.c", new Object[]{4, 5, 6});
        assertThat(SimpleQueryParser.evaluate(d, "`col x` > 1")).containsExactly(false, true, true);
        assertThat(SimpleQueryParser.evaluate(d, "`a-b.c` == 5")).containsExactly(false, true, false);
    }

    @Test
    void 算术运算支持() {
        DataFrame d = df("price", new Object[]{3, 10}, "qty", new Object[]{10, 5});
        assertThat(SimpleQueryParser.evaluate(d, "price * qty > 30")).containsExactly(false, true);
        assertThat(SimpleQueryParser.evaluate(d, "price + qty == 13")).containsExactly(true, false);
        assertThat(SimpleQueryParser.evaluate(d, "-price < -5")).containsExactly(false, true);
        assertThat(SimpleQueryParser.evaluate(d, "qty % 3 == 2")).containsExactly(false, true);
    }

    @Test
    void 单引号翻倍转义() {
        DataFrame d = df("name", new Object[]{"O'Brien", "Bob"});
        assertThat(SimpleQueryParser.evaluate(d, "name == 'O''Brien'")).containsExactly(true, false);
        // 三种等价写法
        assertThat(SimpleQueryParser.evaluate(d, "name == \"O'Brien\"")).containsExactly(true, false);
        assertThat(SimpleQueryParser.evaluate(d, "name == 'O\\'Brien'")).containsExactly(true, false);
    }

    @Test
    void is_true与is_false() {
        DataFrame d = df("flag", new Object[]{Boolean.TRUE, Boolean.FALSE, null});
        assertThat(SimpleQueryParser.evaluate(d, "flag is true")).containsExactly(true, false, false);
        assertThat(SimpleQueryParser.evaluate(d, "flag is false")).containsExactly(false, true, false);
        // SQL 三值逻辑:is not true 对 false 与 null 均 true
        assertThat(SimpleQueryParser.evaluate(d, "flag is not true")).containsExactly(false, true, true);
    }

    @Test
    void in列表列引用行级求值() {
        DataFrame d = df("a", new Object[]{1, 2}, "b", new Object[]{5, 2});
        // in 列表里的列引用须逐行求值(parse 期求值会因空表把列引用算成 null → 永不命中)
        assertThat(SimpleQueryParser.evaluate(d, "a in (b, 99)")).containsExactly(false, true);
    }

    @Test
    void 多小数点带位置报错() {
        DataFrame d = df("a", new Object[]{1, 2});
        assertThatThrownBy(() -> SimpleQueryParser.evaluate(d, "a > 1.2.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("位置 4");
    }

    @Test
    void 数值不隐式当布尔() {
        DataFrame d = df("x", new Object[]{1, 2});
        // pandas/numexpr 对数值做逻辑运算符直接语法错;jian 对齐 fail-fast
        assertThatThrownBy(() -> SimpleQueryParser.evaluate(d, "x && x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("布尔");
    }

    @Test
    void isnull把NaN当缺失() {
        DataFrame d = df("v", new Object[]{1.0, Double.NaN, 3.0});
        // §3.5:DOUBLE 列缺失在 get 层是 NaN,is null 须识别(与 PrattEngine 对齐)
        assertThat(SimpleQueryParser.evaluate(d, "v is null")).containsExactly(false, true, false);
    }

    @Test
    void like转义百分号() {
        DataFrame d = df("s", new Object[]{"50%", "501", "5_0"});
        assertThat(SimpleQueryParser.evaluate(d, "s like '50\\%'")).containsExactly(true, false, false);
        assertThat(SimpleQueryParser.evaluate(d, "s like '5\\_0'")).containsExactly(false, false, true);
    }

    @Test
    void 双引擎对齐_not_like与not_between() {
        DataFrame d = df("a", new Object[]{1, 2, 3}, "name", new Object[]{"A", "B", "C"});
        assertThat(SimpleQueryParser.evaluate(d, "a not between 2 and 3")).containsExactly(true, false, false);
        assertThat(SimpleQueryParser.evaluate(d, "name not like 'A%'")).containsExactly(false, true, true);
    }

    @Test
    void 跨类型等号返回false不抛() {
        // pandas query('name == 5') 返回空表不抛 TypeError;jian 全 false 掩码一致
        DataFrame d = df("name", new Object[]{"a", "b"});
        assertThat(SimpleQueryParser.evaluate(d, "name == 5")).containsExactly(false, false);
    }

    @Test
    void query_可空列不等于判断缺失行应命中() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {null}, {3L}});
        // v != 1:第0行 false(1==1);第1行 true(null != 1,对齐 pandas NaN!=1);第2行 true(3!=1)
        DataFrame r = df.query("v != 1");
        assertThat(r.rowCount()).isEqualTo(2);   // 命中 null 行 + 第 3 行(null != 1 为 true)
    }

    // ======================== DataFrameCompare.cmp(元素级比较)========================

    /** 混型 == 恒 false、!= 恒 true(对齐 pandas 1.5.3 实测)。 */
    @Test
    void cmp_混型相等比较恒false对齐pandas() {
        // String vs Number(==)
        assertThat(DataFrameCompare.cmp("1", "==", 1)).isFalse();
        assertThat(DataFrameCompare.cmp("1.0", "==", 1.0)).isFalse();
        assertThat(DataFrameCompare.cmp(1, "==", "1")).isFalse();
        // String vs Number(!=)
        assertThat(DataFrameCompare.cmp("1", "!=", 1)).isTrue();
        assertThat(DataFrameCompare.cmp(1, "!=", "1")).isTrue();
    }

    /** 同型 Number 用 == 数值比(±0.0 等价、NaN≠NaN,与 IEEE/pandas 一致)。 */
    @Test
    void cmp_同型Number数值比正零负零等价() {
        // +0.0 == -0.0(IEEE 数值相等;按 equals 判会错判不等)
        assertThat(DataFrameCompare.cmp(0.0, "==", -0.0)).isTrue();
        assertThat(DataFrameCompare.cmp(-0.0, "==", 0.0)).isTrue();
        // 1L == 1.0(Long vs Double 但都是 Number → 数值比 → 等)
        assertThat(DataFrameCompare.cmp(1L, "==", 1.0)).isTrue();
        assertThat(DataFrameCompare.cmp(1.0, "==", 1L)).isTrue();
        // NaN ≠ NaN
        assertThat(DataFrameCompare.cmp(Double.NaN, "==", Double.NaN)).isFalse();
        assertThat(DataFrameCompare.cmp(Double.NaN, "!=", Double.NaN)).isTrue();
    }

    /** 同型 String 走 compareTo(覆盖 String==String 主用例)。 */
    @Test
    void cmp_同型String走CompareTo() {
        assertThat(DataFrameCompare.cmp("abc", "==", "abc")).isTrue();
        assertThat(DataFrameCompare.cmp("abc", "==", "abd")).isFalse();
        assertThat(DataFrameCompare.cmp("abc", "!=", "abd")).isTrue();
        assertThat(DataFrameCompare.cmp("b", ">", "a")).isTrue();
        assertThat(DataFrameCompare.cmp("a", "<", "b")).isTrue();
    }

    /** 混型顺序比较(> < >= <=)抛 IllegalArgumentException,对齐 pandas 1.5.3 TypeError
     * (与 SimpleQueryParser 的混型口径一致;§10.16 已声明)。
     * == / != 仍恒 false/true(元素级相等,见 cmp_混型相等比较恒false对齐pandas)。 */
    @Test
    void cmp_混型顺序比较抛IAE对齐pandas() {
        // String vs Number 的顺序比较必须抛 IAE(不走字典序)
        assertThatThrownBy(() -> DataFrameCompare.cmp("10", "<", 9))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataFrameCompare.cmp(9, ">", "10"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataFrameCompare.cmp("abc", ">=", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataFrameCompare.cmp(1, "<=", "abc"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** BigDecimal 走 compareTo 精确比较,不丢精度。
     * 因为 BigDecimal extends Number,若落 Number 分支走 doubleValue 会对 double 无法精确表示的值误判,
     * 所以走 compareTo:用 2^53+1(9007199254740993)经典双精度边界 —— compareTo 知它 > 2^53;
     * doubleValue 会舍入为 2^53 → 误判相等。 */
    @Test
    void cmp_BigDecimal走compareTo不丢精度() {
        // 2^53+1 vs 2^53:compareTo 精确知不等(> / != 都成立);doubleValue 两边都舍入为 2^53 → 误判 ==
        var big1 = new java.math.BigDecimal("9007199254740993");   // 2^53+1
        var big2 = new java.math.BigDecimal("9007199254740992");   // 2^53
        assertThat(DataFrameCompare.cmp(big1, ">", big2)).isTrue();    // BigDecimal→compareTo→true
        assertThat(DataFrameCompare.cmp(big1, "==", big2)).isFalse();  // compareTo 不等(走 doubleValue 会误 true)
        assertThat(DataFrameCompare.cmp(big1, "!=", big2)).isTrue();
        // scale 不同但数值相等:1.0 == 1.00 → compareTo 等
        assertThat(DataFrameCompare.cmp(new java.math.BigDecimal("1.0"), "==",
            new java.math.BigDecimal("1.00"))).isTrue();
    }

    // ======================== compareAsf(merge_asof 键比较,反射测私有方法)========================

    /**
     * merge_asof 的 on 列出现 String vs Number 混型时,compareAsf 抛
     * <strong>IllegalArgumentException</strong>(对齐 pandas:merge_asof 的 on 键必须同型)。
     *
     * <p>构造:merge_asof 的 on 列通常同型;这里通过反射直接测 compareAsf 的混型分支(私有方法)。
     * 方法签名变化时本测试会失败 —— 这是有意的。反射 invoke 把异常包成 InvocationTargetException,
     * 故用 hasCauseInstanceOf 检查原始 IAE。
     */
    @Test
    void compareAsf_混型顺序比较抛IAE对齐pandas() throws Exception {
        java.lang.reflect.Method m = DataFrameMerge.class.getDeclaredMethod(
            "compareAsf", Object.class, Object.class);
        m.setAccessible(true);
        // String vs Number —— 必须抛 IAE(不走 String 字典序兜底)
        assertThatThrownBy(() -> m.invoke(null, "abc", 123))
            .hasCauseInstanceOf(IllegalArgumentException.class);
        // 反向:Number vs String
        assertThatThrownBy(() -> m.invoke(null, 123, "abc"))
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 同型 Number 仍走数值比;同型 String 仍走 compareTo
     * (不降级为 String.valueOf 字典序 —— 虽然结果一样,但路径要对)。
     */
    @Test
    void compareAsf_同型路径不受混型分支影响() throws Exception {
        java.lang.reflect.Method m = DataFrameMerge.class.getDeclaredMethod(
            "compareAsf", Object.class, Object.class);
        m.setAccessible(true);
        // 同型 Number:1.0 < 2.0
        assertThat((int) m.invoke(null, 1.0, 2.0)).isNegative();
        assertThat((int) m.invoke(null, 2.0, 1.0)).isPositive();
        assertThat((int) m.invoke(null, 1.5, 1.5)).isZero();
        // 同型 String
        assertThat((int) m.invoke(null, "a", "b")).isNegative();
        assertThat((int) m.invoke(null, "b", "a")).isPositive();
        // null 仍按"极小"处理
        assertThat((int) m.invoke(null, null, 1.0)).isNegative();
        assertThat((int) m.invoke(null, 1.0, null)).isPositive();
        assertThat((int) m.invoke(null, null, null)).isZero();
    }
}
