package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : DslRegressionTest —— jian-dsl 回归测试集:固化 SQL/DML/表达式引擎行为
// │  Why  : 因为静默丢列、静默吞异常、静默回退这类"看起来能跑但结果错"的路径
// │         比直接崩溃更危险,所以用强断言锁住既有正确行为,防回归
// │  Who  : jian-dsl 模块测试套件
// │  When : mvn test(jian-dsl 模块)
// │  Where: jian-dsl/src/test/java/jian/dsl/DslRegressionTest.java
// │  How  : 数据走向:构造小 DataFrame → Dsl.sql/df.sql/query 入口 → 断言行数/列名/值/异常文案。
// │         覆盖域:
// │           ① DML WHERE 语义(错误列/混型/可空列);
// │           ② SELECT 表达式列与投影(算术/三元/无括号/仅表达式项);
// │           ③ JOIN ON 完整解析(多条件/复合键/右前左后/重名后缀);
// │           ④ 集合运算(UNION 切分括号感知/EXCEPT 去重/列数校验);
// │           ⑤ 语法边角(OFFSET 分页/派生表配平/字面量感知/科学计数法/大整数/嵌套 CASE);
// │           ⑥ 线程安全(并发 CTE 递归计数)与能力自省。
class DslRegressionTest {

    private DataFrame empsDf() {
        return DataFrame.of(
            Schema.of("name", DType.STRING, "dept", DType.STRING, "salary", DType.DOUBLE),
            new Object[][]{
                {"alice", "RD", 10000.0},
                {"bob", "RD", 12000.0},
                {"carol", "PM", 8000.0},
                {"dave", "PM", 9000.0}});
    }

    static DataFrame sampleDf() {
        return DataFrame.of(Schema.of("a", DType.LONG, "b", DType.LONG, "s", DType.STRING),
                new Object[][]{{1L, 10L, "x"}, {2L, 20L, null}, {3L, 30L, "z"}});
    }

    // ======================== DML WHERE 语义 ========================

    /**
     * 回归:UPDATE 的 WHERE 引用不存在的列 → 必须抛 IAE(不静默跳过所有行)。
     *
     * <p>因为 catch 后空 skip 会让所有行被视为不匹配,UPDATE 返回原表(0 行受影响),
     * 用户拿到"看起来成功但什么都没改"的结果,所以必须抛 IAE 带 WHERE 原文。
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
     * 回归:DELETE 的 WHERE 引用不存在的列 → 必须抛 IAE(不静默保留所有行)。
     *
     * <p>本测试锁"不存在列"这条独立路径;混型 WHERE 的行为另见
     * delete_WHERE混型条件抛IAE对齐pandas。
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
     * 回归:DELETE 的 WHERE 出现混型顺序比较
     * (STRING 列 > 数值,或 DOUBLE 列 > 字符串)必须抛 IAE —— 对齐 pandas TypeError。
     *
     * <p>核心契约:混型顺序比较必须报错,绝不静默返回(见 doc/00-overview.md §10.16)。
     */
    @Test
    void delete_WHERE混型条件抛IAE对齐pandas() {
        DataFrame df = empsDf();
        // STRING 列 name vs 整数 100 → 混型顺序比较
        assertThatThrownBy(() -> SqlDml.execute(df,
            "DELETE FROM ${t} WHERE name > 100", Map.of("t", df)))
            .isInstanceOf(IllegalArgumentException.class);
        // DOUBLE 列 salary vs 字符串 'x' → 混型顺序比较
        assertThatThrownBy(() -> SqlDml.execute(df,
            "DELETE FROM ${t} WHERE salary > 'x'", Map.of("t", df)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 回归:可空非 DOUBLE 列(如 LONG)的 WHERE 顺序比较必须排除缺失行,不抛 IAE。
     *
     * <p>因为 LONG 列缺失行(get→null)若落到混型分支会抛 IAE、导致 WHERE 整查询崩溃,
     * 而 pandas 对 NaN&gt;25 返回 false(排除该行)不抛。本测试锁定:null 行被排除、
     * 查询不崩溃、只命中 age=30。
     */
    @Test
    void delete_WHERE可空LONG列不崩溃且排除缺失行() {
        DataFrame df = DataFrame.of(
                Schema.of("age", DType.LONG, "n", DType.STRING),
                new Object[][]{{20L, "a"}, {null, "b"}, {30L, "c"}});
        SqlDml.DmlResult r = SqlDml.execute(df,
            "DELETE FROM ${t} WHERE age > 25", Map.of("t", df));
        // age>25 命中 age=30(1 行);null 行被排除(NaN>25→false 语义)不抛;age=20 不命中
        assertThat(r.affectedRows).isEqualTo(1);
        assertThat(r.result.rowCount()).isEqualTo(2);   // 剩 [20, null]
    }

    /**
     * 回归:UPDATE 合法 WHERE 仍正常工作(不被错误检查破坏)。
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

    // ======================== SELECT 表达式列与投影 ========================

    /**
     * 回归:SELECT 表达式 alias 求值失败 → 抛 IAE 带 alias(不静默退回原 df)。
     *
     * <p>表达式列委托 PrattEngine 真实求值(算术/三元全支持);
     * 本测试锁"求值失败仍抛 IAE 带 alias" —— 用一个引用不存在列的表达式触发解析失败。
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
            throw new AssertionError("applyExprColumn 应抛异常但未抛");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            // PrattEngine 直接抛 IAE(列不存在)
            assertThat(cause).isInstanceOf(IllegalArgumentException.class);
            // 消息里应能定位到是哪个表达式/列出问题
            assertThat(cause.getMessage()).containsAnyOf("nonexistent_col", "flag", "不存在");
        }
    }

    /**
     * 回归:evalCondFallback 方法已删 —— 复杂条件不再被正则切错后静默过滤整表。
     *
     * <p>因为 fallback 正则只匹配 "数字 op 数字",复杂条件(如 "a > 1 AND b < 2")
     * 正则切错会 return false 过滤整表;删掉 fallback 后 PrattEngine.query 失败直接抛,
     * 不再有"看起来能跑但结果错"的路径。
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
     * 回归:合法 WHERE 仍能正常过滤(evalCond 走 PrattEngine 成功路径不受影响)。
     */
    @Test
    void where_合法条件仍正常过滤() {
        DataFrame df = empsDf();
        DataFrame r = Dsl.query(df, "salary > 9000");
        assertThat(r.rowCount()).isEqualTo(2);  // alice(10000) + bob(12000)
    }

    /**
     * 回归:算术表达式列真实求值(用户期望:"(salary+1000) 在 SQL 里写了就该算出来")。
     *
     * <p>因为恒返 null 会静默产出全 null 列、抛异常又属逃避,所以表达式列
     * 委托 PrattEngine 真实求值。本测试经 Dsl.sql 黑盒验证算术运算符(+ - * /)真实产出数值列。
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
     * 回归:三元表达式在表达式列统一委托 PrattEngine 后仍正常。
     *
     * <p>不能因为加了算术支持就破坏三元;测两种括号形态 + 嵌套三元。
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

    @Test
    void 无括号表达式列求值产出() {
        // 因为无括号表达式(salary + 1000 AS total)不以 "(" 开头时若被 selectColumns
        // 静默丢弃,列会消失,所以表达式项判定不要求括号
        DataFrame r = Dsl.sql("SELECT name, salary + 1000 AS total FROM ${t}", empsDf());
        assertThat(r.columnNames()).containsExactly("name", "total");
        assertThat(r.rowCount()).isEqualTo(4);
        assertThat(r.getDoubleColumn("total").get(0)).isEqualTo(11000.0);
        assertThat(r.getDoubleColumn("total").get(1)).isEqualTo(13000.0);
    }

    @Test
    void 拼错列名抛IAE不再静默返回全表() {
        // 因为 cols 空时兜底 return df 会让 SELECT nmae 静默返回全部列(拼错无提示),所以抛 IAE
        assertThatThrownBy(() -> Dsl.sql("SELECT nmae FROM ${t}", empsDf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELECT 列不存在")
                .hasMessageContaining("nmae");
    }

    @Test
    void 表达式引用不存在的列抛IAE带原因() {
        // 表达式项求值失败 → IAE 带项文本与原因(applyExprColumn 包装)
        assertThatThrownBy(() -> Dsl.sql("SELECT salary + nope AS s FROM ${t}", empsDf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("求值失败");
    }

    @Test
    void 仅一个表达式项返回单列() {
        // 投影门控:cols 为空但表达式项非空 → 只返回表达式列
        // (若用 !cols.isEmpty() 门控会整表 + 新列全返回)
        DataFrame r = Dsl.sql("SELECT (salary + 1000) AS total FROM ${t}", empsDf());
        assertThat(r.columnNames()).containsExactly("total");
        assertThat(r.rowCount()).isEqualTo(4);
        assertThat(r.getDoubleColumn("total").get(2)).isEqualTo(9000.0);  // carol 8000 + 1000
    }

    @Test
    void CASE无ELSE无括号正常产出() {
        // 无括号 CASE 项不能无声消失;无 ELSE 的 CASE 必须展开(SQL 语义:缺 ELSE 结果为 NULL)
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        DataFrame r = Dsl.sql("SELECT CASE WHEN v > 1 THEN 'x' END AS b FROM ${t}", df);
        assertThat(r.columnNames()).containsExactly("b");
        assertThat(r.rowCount()).isEqualTo(3);
        // SQL 语义:无 ELSE → NULL(缺失)
        assertThat(r.getColumn("b").get(0)).isNull();
        assertThat(r.getColumn("b").get(1)).isEqualTo("x");
        assertThat(r.getColumn("b").get(2)).isEqualTo("x");
    }

    // ======================== 语法边角(CTE/CASE/字面量) ========================

    @Test
    void 裸名CTE引用不崩() {
        // 因为 "${t}" 恰是 Matcher 命名组语法,裸名替换若不避开组引用解析会抛
        // IllegalArgumentException: No group with name {t}
        DataFrame r = sampleDf().sql("WITH t AS (SELECT * FROM this WHERE a > 1) SELECT a FROM t");
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void 嵌套CASE展开可求值() {
        // 因为内层 CASE 若不展开,产物 "(a>2 ? (CASE WHEN ... : 2 END) ELSE 3)" 会抛"缺 )"
        DataFrame r = jian.dsl.Dsl.sql("SELECT CASE WHEN a > 2 THEN (CASE WHEN b > 20 THEN 100 ELSE 200 END) ELSE 300 END AS c FROM ${t}", sampleDf());
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getColumn("c").get(2)).isEqualTo(100L);  // a=3>2, b=30>20 → 100
        assertThat(r.getColumn("c").get(0)).isEqualTo(300L);  // a=1 不满足外层 → 300
    }

    @Test
    void DML数值列当布尔抛IAE() {
        // 因为 DELETE WHERE b(数值列)静默删除 b!=0 的行会掩盖逻辑 bug,
        // 与 query 引擎对齐抛 IAE
        assertThatThrownBy(() -> jian.dsl.Dsl.sql("DELETE FROM ${t} WHERE b", sampleDf()))
                .hasStackTraceContaining("布尔");
    }

    @Test
    void 大整数字面量精确匹配_主引擎() {
        // 9223372036854775806 经 double 舍入为 2^63 会误匹配 Long.MAX 行,必须按 long 精确
        DataFrame d = DataFrame.of(Schema.of("a", DType.LONG),
                new Object[][]{{9223372036854775806L}, {9223372036854775807L}});
        assertThat(d.query("a == 9223372036854775806").rowCount()).isEqualTo(1);
        assertThat(d.query("a == 9223372036854775807").rowCount()).isEqualTo(1);
    }

    @Test
    void 大整数字面量精确匹配_兜底引擎() {
        // 双引擎同步:SimpleQueryParser 路径同样精确
        DataFrame d = DataFrame.of(Schema.of("a", DType.LONG),
                new Object[][]{{9223372036854775806L}, {9223372036854775807L}});
        int hit = 0;
        for (boolean b : jian.core.SimpleQueryParser.evaluate(d, "a == 9223372036854775806")) if (b) hit++;
        assertThat(hit).isEqualTo(1);
    }

    @Test
    void 字符串拼接遇null传播缺失() {
        // s + "x" 对 null 行产出 "nullx" 是污染数据;对齐 pandas → 缺失(null)
        DataFrame r = jian.dsl.Dsl.eval(sampleDf(), "c = s + 'x'");
        assertThat(r.getColumn("c").get(0)).isEqualTo("xx");
        assertThat(r.getColumn("c").isNull(1)).isTrue();   // null 行 → 缺失
        assertThat(r.getColumn("c").get(2)).isEqualTo("zx");
    }

    @Test
    void 字符串字面量含UNION不误判集合运算() {
        // 因为引号内 UNION 若被当集合运算,SQL 拆错会报"字符串未闭合",所以检测先剥字面量
        DataFrame r = jian.dsl.Dsl.sql("SELECT s FROM ${t} WHERE s == 'UNION'", sampleDf());
        assertThat(r.rowCount()).isEqualTo(0);   // 无 UNION 字符串行,正常返回空表不崩
    }

    @Test
    void 无GROUPBY的非聚合列抛IAE() {
        // 因为 SELECT a, sum(b) 静默丢 a 列是无声数据丢失,对齐 SQLite misuse of aggregate 抛 IAE
        assertThatThrownBy(() -> jian.dsl.Dsl.sql("SELECT a, sum(b) FROM ${t}", sampleDf()))
                .hasStackTraceContaining("GROUP BY");
    }

    @Test
    void UPDATE_SET支持表达式() {
        // SET b = b * 2 按行求值(可引用同表列)
        SqlDml.DmlResult dml = SqlDml.execute(sampleDf(), "UPDATE ${t} SET b = b * 2 WHERE a > 1", java.util.Map.of("t", sampleDf()));
        assertThat(dml.affectedRows).isEqualTo(2);
        DataFrame updated = dml.result;
        assertThat(updated.getColumn("b").get(0)).isEqualTo(10L);  // a=1 未命中
        assertThat(updated.getColumn("b").get(1)).isEqualTo(40L);  // 20*2
        assertThat(updated.getColumn("b").get(2)).isEqualTo(60L);
    }

    @Test
    void avg聚合可用_等价mean() {
        // avg 是 SQL 标准聚合,语义等价 mean(README/Dsl javadoc 示例使用)
        DataFrame r = jian.dsl.Dsl.sql("SELECT city, avg(score) AS avg_score FROM ${t} GROUP BY city",
                DataFrame.of(Schema.of("city", DType.STRING, "score", DType.DOUBLE),
                        new Object[][]{{"bj", 80.0}, {"bj", 90.0}, {"sh", 70.0}}));
        assertThat(r.rowCount()).isEqualTo(2);
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < r.rowCount(); i++) m.put((String) r.getColumn("city").get(i), r.getColumn("avg_score").get(i));
        assertThat(((Number) m.get("bj")).doubleValue()).isEqualTo(85.0);
        assertThat(((Number) m.get("sh")).doubleValue()).isEqualTo(70.0);
    }

    // ======================== 集合运算 ========================

    @Test
    void UNION_ALL列数不等抛教学IAE() {
        // 因为 4 列 UNION 3 列直接 concat 会触发裸 NFE(混型值进首见 dtype 列),所以先校验列数
        DataFrame t0 = DataFrame.of(Schema.of("a", DType.INT, "b", DType.LONG, "c", DType.DOUBLE, "d", DType.STRING),
                new Object[][]{{1, 2L, 3.0, "x"}});
        DataFrame t1 = DataFrame.of(Schema.of("a", DType.STRING, "b", DType.DOUBLE, "c", DType.STRING),
                new Object[][]{{"na", -66.14, " y"}});
        assertThatThrownBy(() -> jian.dsl.Dsl.sql("SELECT * FROM ${t0} UNION ALL SELECT * FROM ${t1}", t0, t1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列数不一致");
    }

    @Test
    void concat同名列dtype冲突升OBJECT() {
        // UNION ALL 同列数但 dtype 冲突(INT vs STRING)→ OBJECT 承载(不裸抛 NFE)
        DataFrame l = DataFrame.of(Schema.of("a", DType.LONG), new Object[][]{{1L}});
        DataFrame r = DataFrame.of(Schema.of("a", DType.STRING), new Object[][]{{"x"}});
        DataFrame c = DataFrame.concat(l, r);
        assertThat(c.getColumn("a").dtype()).isEqualTo(DType.OBJECT);
        assertThat(c.getColumn("a").get(0)).isEqualTo(1L);
        assertThat(c.getColumn("a").get(1)).isEqualTo("x");
    }

    @Test
    void SELECT重复列保留不静默去重() {
        // SELECT c2,c2,c0 保留 3 列(SQLite/pandas 同款;去重 = 数据静默丢失一列)
        DataFrame t = DataFrame.of(Schema.of("c0", DType.LONG, "c2", DType.STRING),
                new Object[][]{{1L, "a"}, {2L, "b"}});
        DataFrame r = jian.dsl.Dsl.sql("SELECT c2, c2, c0 FROM ${t}", t);
        assertThat(r.columnCount()).as("重复列保留为 3 列").isEqualTo(3);
        assertThat(r.columnNames().get(1)).startsWith("c2");   // 第二次出现带后缀(c2_2)
        assertThat(r.getColumn(r.columnNames().get(1)).get(0)).isEqualTo("a");  // 值正确
    }

    @Test
    void 空表无GROUPBY聚合恒一行() {
        // SQLite/pandas "SELECT count(*) FROM 空表" = 1 行 0
        DataFrame t = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[0][]);
        DataFrame r = jian.dsl.Dsl.sql("SELECT count(*) AS cnt FROM ${t}", t);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(((Number) r.getColumn("cnt").get(0)).longValue()).isEqualTo(0);
    }

    @Test
    void 子查询内的UNION_ALL不再切坏外层() {
        // 因为子查询内的 UNION 若被当外层运算符切分,会报"子查询括号未闭合"
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        DataFrame r = Dsl.sql(
            "SELECT v FROM ${t} WHERE v IN (SELECT v FROM ${t} WHERE v > 2 UNION ALL SELECT v FROM ${t} WHERE v < 2)",
            df);
        // 子查询:v>2 → [3];v<2 → [1];UNION ALL → [3,1];外层 v IN (3,1) → 2 行
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(java.util.Arrays.asList(r.getLongColumn("v").toObjectArray()))
                .containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void 子查询内去重UNION同样不切坏外层() {
        // executeSetOperations 的切分也须括号感知(UNION 去重形态)
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        DataFrame r = Dsl.sql(
            "SELECT v FROM ${t} WHERE v IN (SELECT v FROM ${t} WHERE v > 1 UNION SELECT v FROM ${t} WHERE v > 2)",
            df);
        // 右侧并集去重 {2,3};外层 v IN (2,3) → 2 行
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void EXCEPT去重对齐SQL语义() {
        // SQL 集合运算是去重语义:[1,1,2] EXCEPT [>1 集合] 应 1 行(SQLite 同)
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {1L}, {2L}});
        DataFrame r = Dsl.sql("SELECT v FROM ${t} EXCEPT SELECT v FROM ${t} WHERE v > 1", df);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getLongColumn("v").get(0)).isEqualTo(1L);
    }

    @Test
    void 两侧同列不同名不崩() {
        // 行签名按列【位置】取值,不依赖两侧列名相同
        DataFrame l = DataFrame.of(Schema.of("a", DType.LONG), new Object[][]{{1L}, {2L}});
        DataFrame r2 = DataFrame.of(Schema.of("b", DType.LONG), new Object[][]{{2L}, {3L}});
        DataFrame r = Dsl.sql("SELECT a FROM ${x} INTERSECT SELECT b FROM ${y}", l, r2);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getLongColumn("a").get(0)).isEqualTo(2L);
    }

    @Test
    void 两侧列数不等抛IAE() {
        DataFrame l = DataFrame.of(Schema.of("a", DType.LONG, "c", DType.LONG),
                new Object[][]{{1L, 2L}});
        DataFrame r2 = DataFrame.of(Schema.of("b", DType.LONG), new Object[][]{{1L}});
        assertThatThrownBy(() -> Dsl.sql("SELECT a, c FROM ${x} INTERSECT SELECT b FROM ${y}", l, r2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列数不一致");
    }

    // ======================== JOIN ON 完整解析 ========================

    @Test
    void ON多条件_AND附加条件生效() {
        // AND l.lv > 15 不能被静默丢弃(SQL 语义应 1 行,不是 2 行)
        DataFrame left = DataFrame.of(
            Schema.of("k", DType.LONG, "lv", DType.LONG),
            new Object[][]{{1L, 10L}, {2L, 20L}});
        DataFrame right = DataFrame.of(
            Schema.of("k", DType.LONG, "w", DType.STRING),
            new Object[][]{{1L, "a"}, {2L, "b"}});
        DataFrame r = Dsl.sql("SELECT * FROM ${l} JOIN ${r} ON l.k = r.k AND l.lv > 15", left, right);
        assertThat(r.rowCount()).isEqualTo(1);               // 只有 k=2(lv=20 > 15)
        assertThat(r.getLongColumn("k").get(0)).isEqualTo(2L);
    }

    @Test
    void ON右前左后_按列定向() {
        // ON r.k2 = l.k1 是合法 SQL:等式按"列存在于哪侧表"定向,不固定先写者为左
        DataFrame left = DataFrame.of(
            Schema.of("k1", DType.LONG, "v", DType.STRING),
            new Object[][]{{1L, "x"}, {2L, "y"}});
        DataFrame right = DataFrame.of(
            Schema.of("k2", DType.LONG, "w", DType.STRING),
            new Object[][]{{1L, "p"}, {2L, "q"}});
        DataFrame r = Dsl.sql("SELECT * FROM ${l} JOIN ${r} ON r.k2 = l.k1", left, right);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(java.util.Arrays.asList(r.getStringColumn("w").toObjectArray()))
                .containsExactly("p", "q");
    }

    @Test
    void ON重名列附加条件_后缀正确映射() {
        // 两侧同名列 v(非 join key)→ merge 后 v_x/v_y;l.v > r.v 必须映射到不同后缀列
        DataFrame left = DataFrame.of(
            Schema.of("k", DType.LONG, "v", DType.LONG),
            new Object[][]{{1L, 10L}, {2L, 20L}});
        DataFrame right = DataFrame.of(
            Schema.of("k", DType.LONG, "v", DType.LONG),
            new Object[][]{{1L, 15L}, {2L, 5L}});
        DataFrame r = Dsl.sql("SELECT * FROM ${l} JOIN ${r} ON l.k = r.k AND l.v > r.v", left, right);
        // 行1:10>15 假;行2:20>5 真 → 1 行(k=2)
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getLongColumn("k").get(0)).isEqualTo(2L);
    }

    @Test
    void ON多等式复合键() {
        // 多对等式全部作为 merge key(复合键),不是只取第一个
        DataFrame left = DataFrame.of(
            Schema.of("a", DType.LONG, "b", DType.LONG, "v", DType.STRING),
            new Object[][]{{1L, 2L, "x"}, {1L, 3L, "y"}});
        DataFrame right = DataFrame.of(
            Schema.of("a", DType.LONG, "b", DType.LONG, "w", DType.STRING),
            new Object[][]{{1L, 3L, "q"}});
        DataFrame r = Dsl.sql("SELECT * FROM ${l} JOIN ${r} ON l.a = r.a AND l.b = r.b", left, right);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("v").get(0)).isEqualTo("y");
    }

    @Test
    void ON等式列不存在抛IAE() {
        DataFrame left = DataFrame.of(Schema.of("k", DType.LONG), new Object[][]{{1L}});
        DataFrame right = DataFrame.of(Schema.of("w", DType.LONG), new Object[][]{{1L}});
        assertThatThrownBy(() -> Dsl.sql("SELECT * FROM ${l} JOIN ${r} ON l.k = r.nothing", left, right))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("等式列不存在");
    }

    // ======================== 分页与派生表 ========================

    @Test
    void Oracle标准分页_ORDER_BY后OFFSET_FETCH() {
        // OFFSET 段不能被吞进排序列名(否则报 "ORDER BY 列不存在:[salary DESC OFFSET 1 ROWS]")
        DataFrame r = Dsl.sql(
            "SELECT name, salary FROM ${t} ORDER BY salary DESC OFFSET 1 ROWS FETCH FIRST 1 ROWS ONLY",
            empsDf());
        assertThat(r.rowCount()).isEqualTo(1);
        // 薪资降序 12000(bob)/10000(alice)/9000/8000,OFFSET 1 跳过 bob,取 1 行 → alice(10000)
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getDoubleColumn("salary").get(0)).isEqualTo(10000.0);
    }

    @Test
    void WHERE后独立OFFSET() {
        // 独立 OFFSET 段不能被吞进 WHERE 表达式(否则报"尾部多余 token 'OFFSET'")
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        DataFrame r = Dsl.sql("SELECT v FROM ${t} WHERE v > 1 OFFSET 1", df);
        // v>1 → [2,3];OFFSET 1 → [3]
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getLongColumn("v").get(0)).isEqualTo(3L);
    }

    @Test
    void 派生表内含聚合函数括号() {
        // 派生表子查询含括号时须按深度配平提取(懒惰正则会截成 "SELECT dept, sum(salary" 报错)
        DataFrame r = Dsl.sql(
            "SELECT dept FROM (SELECT dept, sum(salary) AS s FROM ${t} GROUP BY dept) AS t2",
            empsDf());
        assertThat(r.rowCount()).isEqualTo(2);   // RD / PM
        assertThat(java.util.Arrays.asList(r.getStringColumn("dept").toObjectArray()))
                .containsExactlyInAnyOrder("RD", "PM");
    }

    @Test
    void 字面量含SELECT不误判子查询() {
        // 字符串字面量里的 "(SELECT" 不当子查询执行
        DataFrame df = DataFrame.of(Schema.of("name", DType.STRING),
                new Object[][]{{"alice"}, {"x(SELECT y)z"}});
        DataFrame r = Dsl.sql("SELECT name FROM ${t} WHERE name == 'x(SELECT y)z'", df);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("x(SELECT y)z");
    }

    // ======================== 线程安全与能力自省 ========================

    @Test
    void 两线程并发CTE子查询无虚假嵌套过深() throws Exception {
        // 递归深度走方法参数(调用链独享):两线程各嵌套 2 层不能相加成 > 3 而虚假抛
        // "CTE/子查询嵌套过深(>3 层)"
        DataFrame df = empsDf();
        String sql = "WITH rd AS (SELECT * FROM this WHERE dept == 'RD') "
                + "SELECT name FROM ${rd} WHERE salary IN (SELECT salary FROM ${rd})";
        final int iters = 100;
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Thread t1 = new Thread(() -> runIterations(df, sql, iters, failures));
        Thread t2 = new Thread(() -> runIterations(df, sql, iters, failures));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        // 任一次虚假"嵌套过深"或行数错误都算失败
        assertThat(failures).isEmpty();
    }

    /** 单线程跑 iters 次 CTE+子查询,异常与行数错误都记入 failures(并发安全收集)。 */
    private void runIterations(DataFrame df, String sql, int iters, List<String> failures) {
        for (int i = 0; i < iters; i++) {
            try {
                DataFrame r = df.sql(sql);
                if (r.rowCount() != 2) {
                    failures.add("行数错误:" + r.rowCount());
                }
            } catch (RuntimeException e) {
                // 捕 RuntimeException(ModuleNotLoadedException/IAE 等全覆盖):
                // 线程内异常若逃逸会静默吞掉 → 测试空过,这里显式记录
                failures.add("迭代 " + i + " 异常:" + e.getMessage());
            }
        }
    }

    @Test
    void 科学计数法_整数指数() {
        // 1e5 不能被拆 NUM(1)+IDENT(e5) 报 "尾部多余 token 'e5'"
        DataFrame df = DataFrame.of(Schema.of("x", DType.DOUBLE),
                new Object[][]{{100000.0}, {1.0}});
        DataFrame r = Dsl.query(df, "x == 1e5");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getDoubleColumn("x").get(0)).isEqualTo(100000.0);
    }

    @Test
    void 科学计数法_负指数小数() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{0.001}, {0.002}});
        DataFrame r = Dsl.query(df, "v > 1.5e-3");
        assertThat(r.rowCount()).isEqualTo(1);   // 0.002 > 0.0015
        assertThat(r.getDoubleColumn("v").get(0)).isEqualTo(0.002);
    }

    @Test
    void supports_SELECT_EXPR为true() {
        // 能力声明与实现/测试一致,不误导上层回退决策
        SqlEngineInterface engine = SqlEngines.current();
        assertThat(engine).isInstanceOf(SqlRegexEngine.class);
        assertThat(engine.supports(SqlEngineInterface.Capability.SELECT_EXPR)).isTrue();
    }
}
