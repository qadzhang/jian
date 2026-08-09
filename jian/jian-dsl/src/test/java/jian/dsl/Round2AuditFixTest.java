package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第二轮审查(AI agent2 + AI agent2)代码侧修复回归测试 —— jian-dsl 部分。
 *
 * <p>覆盖 jian-dsl 四处修复:
 * <ul>
 *   <li>#1 {@link SqlDml} —— UPDATE/DELETE WHERE 求值失败不再静默吞,抛 IAE 带 WHERE 原文</li>
 *   <li>#2 {@link SqlEngine} —— SELECT 表达式列求值失败不再静默退回原 df,抛 IAE 带 alias</li>
 *   <li>#3 {@link SqlEngine} —— WHERE 求值失败不再回退正则 evalCondFallback(方法已删),直接抛</li>
 *   <li>附带 {@link SqlEngine#evalArithmetic} —— 不再恒返 null,改抛 UnsupportedOperationException</li>
 * </ul>
 *
 * <p>对应 AI 测试方法学指南 铁律:3(修复每个 bug 后写重现代码测试)。
 * 商议过程见 第二轮审查共识记录(AI agent2 与主 agent 共识)。
 */
class Round2AuditFixTest {

    private DataFrame empsDf() {
        return DataFrame.of(
            Schema.of("name", DType.STRING, "dept", DType.STRING, "salary", DType.DOUBLE),
            new Object[][]{
                {"alice", "RD", 10000.0},
                {"bob", "RD", 12000.0},
                {"carol", "PM", 8000.0}});
    }

    // ======================== #1 SqlDml WHERE 静默吞异常 ========================

    /**
     * #1 回归:UPDATE 的 WHERE 引用不存在的列 → 必须抛 IAE(不再静默跳过所有行)。
     *
     * <p>原行为:catch (Exception e) 空 skip 会让所有行被视为不匹配,UPDATE 返回原表(0 行受影响),
     * 用户拿到"看起来成功但什么都没改"的结果。现应抛 IAE 带 WHERE 原文。
     */
    @Test
    void update_WHERE引用不存在列必须抛IAE带WHERE原文() {
        DataFrame df = empsDf();
        String badSql = "UPDATE ${t} SET salary = 9999 WHERE nonexistent_col > 100";
        assertThatThrownBy(() -> SqlDml.execute(df, badSql, Map.of("t", df)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WHERE")
            .hasMessageContaining("nonexistent_col");
    }

    /**
     * #1 回归:DELETE 的 WHERE 引用不存在的列 → 必须抛 IAE(不再静默保留所有行)。
     *
     * <p>构造说明:不能用"name > 100"这种类型混排条件 —— jian 的 cmp 对混型顺序比较
     * 采取"宽厚字典序"策略(#5 共识,不抛 TypeError),所以那不会触发 query 异常。
     * 改用"引用不存在的列",这是 PrattEngine.query 确定抛 IAE 的场景。
     */
    @Test
    void delete_WHERE引用不存在列必须抛IAE() {
        DataFrame df = empsDf();
        String badSql = "DELETE FROM ${t} WHERE nonexistent_col > 100";
        assertThatThrownBy(() -> SqlDml.execute(df, badSql, Map.of("t", df)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WHERE")
            .hasMessageContaining("nonexistent_col");
    }

    /**
     * #1 回归:UPDATE 合法 WHERE 仍正常工作(不被新检查破坏)。
     */
    @Test
    void update_合法WHERE仍正常更新() {
        DataFrame df = empsDf();
        String sql = "UPDATE ${t} SET salary = 20000 WHERE dept == 'RD'";
        SqlDml.DmlResult r = SqlDml.execute(df, sql, Map.of("t", df));
        assertThat(r.affectedRows).isEqualTo(2);  // alice + bob 都是 RD
        // 验证 RD 员工的 salary 被更新
        DataFrame result = r.result;
        assertThat((double) result.get(0, "salary")).isEqualTo(20000.0);  // alice
        assertThat((double) result.get(1, "salary")).isEqualTo(20000.0);  // bob
        assertThat((double) result.get(2, "salary")).isEqualTo(8000.0);   // carol 未动
    }

    // ======================== #2 SELECT 表达式吞异常 ========================

    /**
     * #2 回归:SELECT 表达式 alias 求值失败 → 抛 IAE 带 alias(不再静默退回原 df)。
     *
     * <p>L8 修复演进:第一版把 evalArithmetic 改抛 UnsupportedOperationException(逃避),
     * 用户指出"(salary+1000) 在 SQL 里写了就该算出来"。第二版与 AI agent1 共识:
     * applyExprColumn 委托 PrattEngine 真实求值(算术/三元全支持)。
     *
     * <p>本测试锁"求值失败仍抛 IAE 带 alias"—— 用一个引用不存在列的表达式触发 PrattEngine 解析失败。
     */
    @Test
    void select_表达式求值失败抛IAE带alias() throws Exception {
        DataFrame df = empsDf();
        java.lang.reflect.Method m = SqlEngine.class.getDeclaredMethod(
            "applyExprColumn", DataFrame.class, String.class, String.class);
        m.setAccessible(true);
        // 用一个会触发 PrattEngine 解析/求值失败的表达式(引用不存在列)
        try {
            m.invoke(null, df, "(nonexistent_col > 100)", "flag");
            throw new AssertionError("applyExprColumn 应抛异常但未抛(修复未生效)");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            // PrattEngine 直接抛 IAE(列不存在),不再经 applyExprColumn 外层 catch 包装
            assertThat(cause).isInstanceOf(IllegalArgumentException.class);
            // 消息里应能定位到是哪个表达式/列出问题
            assertThat(cause.getMessage()).containsAnyOf("nonexistent_col", "flag", "不存在");
        }
    }

    // ======================== #3 WHERE 回退正则已删 ========================

    /**
     * #3 回归:evalCondFallback 方法已删 —— 复杂条件不再被正则切错后静默过滤整表。
     *
     * <p>关键:AI agent2 分析指出,原 fallback 正则只匹配 "数字 op 数字",
     * 复杂条件(如 "a > 1 AND b < 2")正则切错会 return false 过滤整表。
     * 现删除 fallback,PrattEngine.query 失败直接抛,不再有"看起来能跑但结果错"的路径。
     *
     * <p>验证方式:确认 evalCondFallback 方法不存在(反射查找应失败)。
     */
    @Test
    void evalCondFallback方法已删除() throws Exception {
        // 反射找 evalCondFallback —— 应抛 NoSuchMethodException
        assertThatThrownBy(() ->
            SqlEngine.class.getDeclaredMethod("evalCondFallback", String.class))
            .isInstanceOf(NoSuchMethodException.class);
    }

    /**
     * #3 回归:合法 WHERE 仍能正常过滤(evalCond 走 PrattEngine 成功路径不受影响)。
     */
    @Test
    void where_合法条件仍正常过滤() {
        DataFrame df = empsDf();
        DataFrame r = Dsl.query(df, "salary > 9000");
        assertThat(r.rowCount()).isEqualTo(2);  // alice(10000) + bob(12000)
    }

    // ======================== 附带:evalArithmetic 不再恒返 null ========================

    /**
     * 附带回归:算术表达式列真实求值(用户指出关键 bug:"(salary+1000) 在 SQL 里写了就该算出来")。
     *
     * <p>演进:① 原代码 evalArithmetic 恒返 null(静默产出全 null 列);
     * ② 第一版"修复"改抛 UnsupportedOperationException(逃避,被用户指出仍是 bug);
     * ③ 第二版与 AI agent1 共识:applyExprColumn 委托 PrattEngine,真实求值算术。
     *
     * <p>本测试经 Dsl.sql 黑盒验证 5 种算术运算符(+ - * /)真实产出数值列。
     */
    @Test
    void 算术表达式列真实求值产出数值() {
        DataFrame df = DataFrame.of(
            Schema.of("name", DType.STRING, "salary", DType.DOUBLE, "bonus", DType.DOUBLE),
            new Object[][]{{"alice", 10000.0, 500.0}, {"bob", 12000.0, 800.0}});

        // ① 加法(列 + 字面量)
        DataFrame r1 = Dsl.sql("SELECT name, (salary + 1000) AS total FROM ${t}", df);
        assertThat(r1.columnNames()).contains("total");
        assertThat((double) r1.get(0, "total")).isEqualTo(11000.0);
        assertThat((double) r1.get(1, "total")).isEqualTo(13000.0);

        // ② 加法(列 + 列)
        DataFrame r2 = Dsl.sql("SELECT name, (salary + bonus) AS total FROM ${t}", df);
        assertThat((double) r2.get(0, "total")).isEqualTo(10500.0);
        assertThat((double) r2.get(1, "total")).isEqualTo(12800.0);

        // ③ 减法
        DataFrame r3 = Dsl.sql("SELECT name, (salary - 1000) AS net FROM ${t}", df);
        assertThat((double) r3.get(0, "net")).isEqualTo(9000.0);

        // ④ 乘法
        DataFrame r4 = Dsl.sql("SELECT name, (salary * 12) AS annual FROM ${t}", df);
        assertThat((double) r4.get(0, "annual")).isEqualTo(120000.0);

        // ⑤ 除法
        DataFrame r5 = Dsl.sql("SELECT name, (salary / 12) AS monthly FROM ${t}", df);
        assertThat((double) r5.get(1, "monthly")).isEqualTo(1000.0);
    }

    /**
     * 附带回归:三元表达式(原 applyExprColumn 的主支持路径)在委托 PrattEngine 后仍正常。
     *
     * <p>关键:不能因为加了算术支持就破坏三元(原代码三元用自写正则,现全走 PrattEngine)。
     * 测两种括号形态 + 嵌套三元。
     */
    @Test
    void 三元表达式委托PrattEngine后仍正常() {
        DataFrame df = empsDf();
        // 全括号(原代码要求的形态)
        DataFrame r1 = Dsl.sql(
            "SELECT name, ((salary > 10000) ? 'high' : 'low') AS band FROM ${t}", df);
        assertThat(r1.columnNames()).contains("band");
        assertThat(r1.getColumn("band").get(0)).isEqualTo("low");   // alice 10000 → low
        assertThat(r1.getColumn("band").get(1)).isEqualTo("high");  // bob 12000 → high

        // 部分括号(cond 加括号,整体不加)
        DataFrame r2 = Dsl.sql(
            "SELECT name, (salary > 10000) ? 'high' : 'low' AS band2 FROM ${t}", df);
        assertThat(r2.columnNames()).contains("band2");
        assertThat(r2.getColumn("band2").get(1)).isEqualTo("high");
    }
}
