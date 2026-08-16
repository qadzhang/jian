package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.core.SimpleQueryParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : EngineConformanceTest —— 双引擎语法矩阵一致性回归
// │  Why  : jian 的 df.query() 有两条执行路径 —— jian-dsl 的 PrattEngine(SPI 主路径,用户实际
// │         引 jian-dsl/fat jar 时走)与 jian-core 的 SimpleQueryParser(兜底路径)。
// │         因为两引擎语法矩阵若不一致(算术/''转义/中文 Pratt 有而 SQP 无,not in SQP 有而 Pratt 无),
// │         测试桥又只测兜底路径,会出现"测试全绿但用户主路径挂了"的盲区,所以锁定两边一致。
// │  Who  : 每次任一引擎改语法后必须跑本测试(改 query 表达式能力 = 改本类矩阵同步加用例)
// │  When : mvn -pl jian/jian-dsl test
// │  Where: jian-dsl/src/test/java/jian/dsl/EngineConformanceTest.java
// │  How  : 数据走向:同一 df + 同一表达式 → ①SimpleQueryParser.evaluate(df, expr) 得兜底掩码
// │           → ②df.query(expr)(经 DslEngine SPI,本模块在场 = PrattEngine 主路径)得结果行
// │           → ③按掩码过滤同一 df 得兜底结果行 → ④逐行逐值比对两条路径输出。
// │         逻辑路线:每个表达式走 assertConform;异常类表达式(数值 &&)断言两边都抛 IAE
// │           且消息都含"布尔操作数"教学提示。
/**
 * 双引擎(SimpleQueryParser 兜底 / PrattEngine 主路径)语法矩阵一致性回归。
 *
 * <p>主路径与兜底路径的支持矩阵曾经漂移,本测试锁定两边一致。
 */
class EngineConformanceTest {

    /** 构造固定测试表:数值/字符串/布尔/含缺失/特殊列名,覆盖双引擎所有语法面。 */
    private DataFrame sample() {
        Map<String, Object[]> m = new LinkedHashMap<>();
        m.put("a", new Object[]{1, 2, 3});
        m.put("b", new Object[]{4, 5, 6});
        m.put("name", new Object[]{"O'Brien", "Bob", "Alice"});
        m.put("col x", new Object[]{1, 2, 3});          // 反引号标识符
        return DataFrame.ofColumns(m);
    }

    private DataFrame withNullAndBool() {
        // v 含 NaN 缺失;flag 布尔(含 null)
        return DataFrame.of(Schema.of("v", DType.DOUBLE, "flag", DType.BOOL),
                new Object[][]{
                        {1.0, Boolean.TRUE},
                        {Double.NaN, Boolean.FALSE},
                        {3.0, null}});
    }

    /** 断言:expr 在兜底引擎(SQP mask)与主路径(df.query)给出完全相同的行集(按全部列值)。 */
    private void assertConform(DataFrame df, String expr) {
        boolean[] mask = SimpleQueryParser.evaluate(df, expr);
        List<String> rowSqp = filterRows(df, mask);
        DataFrame viaQuery = df.query(expr);   // 本模块在场 → DslEngine SPI → PrattEngine
        List<String> rowPratt = new ArrayList<>();
        for (int r = 0; r < viaQuery.rowCount(); r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < viaQuery.columnCount(); c++) sb.append(viaQuery.get(r, c)).append('|');
            rowPratt.add(sb.toString());
        }
        assertThat(rowPratt).as("双引擎结果不一致 expr=%s", expr).containsExactlyElementsOf(rowSqp);
    }

    private List<String> filterRows(DataFrame df, boolean[] mask) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < df.rowCount(); r++) {
            if (!mask[r]) continue;
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < df.columnCount(); c++) sb.append(df.get(r, c)).append('|');
            out.add(sb.toString());
        }
        return out;
    }

    @Test
    void 谓词_not_in_两词形式_双引擎一致() {
        assertConform(sample(), "a not in (2, 4)");
    }

    @Test
    void 谓词_notin_单字形式_双引擎一致() {
        assertConform(sample(), "a notin (2)");
    }

    @Test
    void 谓词_not_like_双引擎一致() {
        assertConform(sample(), "name not like 'B%'");
    }

    @Test
    void 谓词_not_between_双引擎一致() {
        assertConform(sample(), "a not between 2 and 3");
    }

    @Test
    void 反引号标识符_双引擎一致() {
        assertConform(sample(), "`col x` > 1 && a < 3");
    }

    @Test
    void 算术乘除模_双引擎一致() {
        assertConform(sample(), "a * b > 8");
        assertConform(sample(), "b / a >= 2");
        assertConform(sample(), "a % 2 == 1");
    }

    @Test
    void 算术加减与一元负号_双引擎一致() {
        assertConform(sample(), "a + 1 == 2");
        assertConform(sample(), "a - 1 > 1");
        assertConform(sample(), "-a < -2");
    }

    @Test
    void 字符串拼接_双引擎一致() {
        assertConform(sample(), "name + 'X' == 'BobX'");
    }

    @Test
    void 单引号翻倍转义_双引擎一致() {
        assertConform(sample(), "name == 'O''Brien'");
    }

    @Test
    void 双引号字符串_双引擎一致() {
        assertConform(sample(), "name == \"O'Brien\"");
    }

    @Test
    void 反斜杠转义_双引擎一致() {
        assertConform(sample(), "name == 'O\\'Brien'");
    }

    @Test
    void is_true与is_false_双引擎一致() {
        DataFrame df = withNullAndBool();
        assertConform(df, "flag is true");
        assertConform(df, "flag is false");
        assertConform(df, "flag is not true");
        assertConform(df, "flag is not false");
    }

    @Test
    void is_null对NaN缺失_双引擎一致() {
        DataFrame df = withNullAndBool();
        assertConform(df, "v is null");
        assertConform(df, "v is not null");
        assertConform(df, "flag is null");
    }

    @Test
    void like转义百分号_双引擎一致() {
        assertConform(sample(), "name like 'O\\%'");   // 字面 O%,不匹配任何行(两引擎同返空)
    }

    @Test
    void in列表含列引用_双引擎一致() {
        assertConform(sample(), "a in (a, 99)");        // a[r] 恒等于 a[r] → 全命中
    }

    @Test
    void 中文列名_双引擎一致() {
        Map<String, Object[]> m = new LinkedHashMap<>();
        m.put("分数", new Object[]{60, 90, 30});
        DataFrame df = DataFrame.ofColumns(m);
        assertConform(df, "分数 > 50");
        assertConform(df, "分数 between 40 and 80");
    }

    @Test
    void 数值做逻辑运算_双引擎都fail_fast() {
        // 因为数值隐式当布尔会掩盖逻辑错误,所以数值不做布尔解释,两引擎都抛 IAE 且消息可教学
        DataFrame df = sample();
        assertThatThrownBy(() -> SimpleQueryParser.evaluate(df, "a && b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("布尔");
        assertThatThrownBy(() -> df.query("a && b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("布尔");
    }

    @Test
    void 多小数点_双引擎都报带位置错误() {
        DataFrame df = sample();
        assertThatThrownBy(() -> SimpleQueryParser.evaluate(df, "a > 1.2.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("位置");
        assertThatThrownBy(() -> df.query("a > 1.2.3"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void 逻辑组合全语法矩阵_双引擎一致() {
        assertConform(sample(), "(a > 1 && b < 6) || name == 'Alice'");
        assertConform(sample(), "!(a == 1) && `col x` >= 2");
        assertConform(sample(), "a not in (1) && (b == 5 || name like 'A%')");
    }
}
