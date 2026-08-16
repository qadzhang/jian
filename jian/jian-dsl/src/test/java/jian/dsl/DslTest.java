package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DslTest {

    @Test
    void L1_query比较与逻辑() {
        DataFrame df = df();
        DataFrame r = Dsl.query(df, "age > 25 && city == 'SH'");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void L1_queryBetween() {
        DataFrame df = df();
        DataFrame r = Dsl.query(df, "age between 26 and 40");
        assertThat(r.rowCount()).isEqualTo(2);  // alice, carol
    }

    @Test
    void L1_queryLike() {
        DataFrame df = df();
        DataFrame r = Dsl.query(df, "city like 'S%'");
        assertThat(r.rowCount()).isEqualTo(2);  // SH, SZ
    }

    @Test
    void L1_queryIn() {
        DataFrame df = df();
        DataFrame r = Dsl.query(df, "city in ('SH', 'BJ')");
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void L1_queryIsNull() {
        DataFrame df = DataFrame.of(Schema.of("n", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"a", 1.0}, {"b", null}});
        assertThat(Dsl.query(df, "v is null").rowCount()).isEqualTo(1);
        assertThat(Dsl.query(df, "v is not null").rowCount()).isEqualTo(1);
    }

    @Test
    void L1_query参数绑定() {
        DataFrame df = df();
        Params p = Params.of("threshold", 28).with("city", "SH");
        DataFrame r = Dsl.query(df, "age > ${threshold} && city == ${city}", p);
        assertThat(r.rowCount()).isEqualTo(1);
    }

    @Test
    void L2_eval派生列() {
        DataFrame df = DataFrame.of(Schema.of("price", DType.DOUBLE, "qty", DType.LONG),
                new Object[][]{{10.0, 2L}, {5.0, 3L}});
        DataFrame r = Dsl.eval(df, "total = price * qty");
        assertThat(r.columnNames()).contains("total");
        assertThat(r.getDoubleColumn("total").getDouble(0)).isEqualTo(20.0);
    }

    @Test
    void L2_eval三元() {
        DataFrame df = df();
        DataFrame r = Dsl.eval(df, "grade = age >= 35 ? 'OLD' : 'YOUNG'");
        assertThat(r.getStringColumn("grade").get(0)).isEqualTo("YOUNG");  // alice 30
        assertThat(r.getStringColumn("grade").get(2)).isEqualTo("OLD");     // carol 40
    }

    @Test
    void L2_eval多语句() {
        DataFrame df = DataFrame.of(Schema.of("price", DType.DOUBLE, "qty", DType.LONG),
                new Object[][]{{10.0, 2L}});
        DataFrame r = Dsl.eval(df, "total = price * qty; tax = total * 0.1");
        assertThat(r.columnNames()).contains("total", "tax");
        assertThat(r.getDoubleColumn("tax").getDouble(0)).isEqualTo(2.0);
    }

    @Test
    void L2_eval字符串拼接() {
        DataFrame df = df();
        DataFrame r = Dsl.eval(df, "label = name + '-' + city");
        assertThat(r.getStringColumn("label").get(0)).isEqualTo("alice-SH");
    }

    @Test
    void 字符串单引号翻倍转义() {
        // 转义语义为 ANSI SQL 标准 '' 翻倍
        // (写入端 \\'、读取端反斜杠吞字符的旧语义在 MySQL 8+ NO_BACKSLASH_ESCAPES 下可注入)
        DataFrame d2 = DataFrame.of(
                Schema.of("name", DType.STRING, "age", DType.INT),
                new Object[][]{{"it's", 30}, {"alice", 25}});
        // 读取端:'' 解析为字面量单引号
        assertThat(Dsl.query(d2, "name == 'it''s'").rowCount()).isEqualTo(1);
        // 写入端:参数绑定含单引号,经 expandParams 转 '' 后能正确匹配
        assertThat(Dsl.query(d2, "name == ${who}", Params.of("who", "it's")).rowCount()).isEqualTo(1);
        // 反斜杠保留字面值:'\q' 按两字符 \q 解析,不吞反斜杠
        DataFrame d3 = DataFrame.of(
                Schema.of("s", DType.STRING),
                new Object[][]{{"a\\qb"}});
        assertThat(Dsl.query(d3, "s == 'a\\qb'").rowCount()).isEqualTo(1);
    }

    @Test
    void L3_selectWhere() {
        DataFrame df = df();
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE age > 28", df);
        assertThat(r.rowCount()).isEqualTo(2);  // alice(30), carol(40)
    }

    @Test
    void L3_groupByOrderLimit() {
        DataFrame df = DataFrame.of(
                Schema.of("dept", DType.STRING, "salary", DType.DOUBLE),
                new Object[][]{{"RD", 10000.0}, {"PM", 8000.0}, {"RD", 12000.0}, {"PM", 9000.0}});
        // AS 别名真重命名(聚合输出 salary_mean → avg_sal),
        // ORDER BY/HAVING 均可引用别名;旧写法 ORDER BY salary_mean 是 v1 简化期的约定,已废弃
        DataFrame r = Dsl.sql("SELECT dept, mean(salary) AS avg_sal FROM ${t} GROUP BY dept ORDER BY avg_sal DESC LIMIT 1", df);
        assertThat(r.rowCount()).isEqualTo(1);  // RD 平均 11000 > PM 平均 8500
        assertThat(r.getStringColumn("dept").get(0)).isEqualTo("RD");
    }

    @Test
    void L3_fetchFirstOracle方言() {
        DataFrame df = df();
        DataFrame r = Dsl.sql("SELECT * FROM ${t} FETCH FIRST 2 ROWS ONLY", SqlDialect.ORACLE, df);
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void L3_unionAll() {
        DataFrame df = df();
        DataFrame df2 = DataFrame.of(
                Schema.of("name", DType.STRING, "age", DType.LONG, "city", DType.STRING),
                new Object[][]{{"david", 50L, "HZ"}});
        DataFrame r = Dsl.sql("SELECT * FROM ${x} UNION ALL SELECT * FROM ${y}", df, df2);
        assertThat(r.rowCount()).isEqualTo(4);  // 原 3 + df2 1
    }

    @Test
    void L3_join() {
        DataFrame left = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        DataFrame right = DataFrame.of(Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{1L, 30L}, {3L, 40L}});
        DataFrame r = Dsl.sql("SELECT * FROM ${a} JOIN ${b} ON a.id = b.id", left, right);
        assertThat(r.rowCount()).isEqualTo(1);  // inner join id=1
    }

    @Test
    void L3_PLSQL隔离() {
        DataFrame df = df();
        assertThatThrownBy(() -> Dsl.sql("BEGIN INSERT INTO x VALUES(1); END;", df))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELECT");
    }

    @Test
    void dialectFromEnv() {
        // 默认
        assertThat(SqlDialect.fromEnv()).isEqualTo(SqlDialect.DEFAULT);
    }

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("name", DType.STRING, "age", DType.LONG, "city", DType.STRING),
                new Object[][]{
                        {"alice", 30L, "SH"},
                        {"bob", 25L, "BJ"},
                        {"carol", 40L, "SZ"}
                });
    }

    @Test
    void L3_leftJoin左表全保留() {
        DataFrame left = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}, {3L, "carol"}});
        DataFrame right = DataFrame.of(Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{1L, 30L}});  // 只有 id=1
        DataFrame r = Dsl.sql("SELECT * FROM ${a} LEFT JOIN ${b} ON a.id = b.id", left, right);
        assertThat(r.rowCount()).isEqualTo(3);  // 左表 3 行全保留
        // bob/carol 的 age 应为 null
        assertThat(r.getColumn("age").get(1)).isNull();
        assertThat(r.getColumn("age").get(2)).isNull();
    }

    @Test
    void L3_rightJoin右表全保留() {
        DataFrame left = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}});
        DataFrame right = DataFrame.of(Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{1L, 30L}, {2L, 25L}, {3L, 40L}});
        DataFrame r = Dsl.sql("SELECT * FROM ${a} RIGHT JOIN ${b} ON a.id = b.id", left, right);
        assertThat(r.rowCount()).isEqualTo(3);  // 右表 3 行全保留
    }

    @Test
    void L3_fullOuterJoin全保留() {
        DataFrame left = DataFrame.of(Schema.of("id", DType.LONG, "n", DType.STRING),
                new Object[][]{{1L, "a"}, {2L, "b"}});
        DataFrame right = DataFrame.of(Schema.of("id", DType.LONG, "v", DType.LONG),
                new Object[][]{{2L, 20L}, {3L, 30L}});
        DataFrame r = Dsl.sql("SELECT * FROM ${a} FULL OUTER JOIN ${b} ON a.id = b.id", left, right);
        assertThat(r.rowCount()).isEqualTo(3);  // a(1) + b-match(2) + c(3)
    }

    @Test
    void L3_链式多表JOIN() {
        DataFrame a = DataFrame.of(Schema.of("aid", DType.LONG, "bid", DType.LONG),
                new Object[][]{{1L, 10L}, {2L, 20L}});
        DataFrame b = DataFrame.of(Schema.of("bid", DType.LONG, "cid", DType.LONG),
                new Object[][]{{10L, 100L}, {20L, 200L}});
        DataFrame c = DataFrame.of(Schema.of("cid", DType.LONG, "name", DType.STRING),
                new Object[][]{{100L, "x"}, {200L, "y"}, {300L, "z"}});
        DataFrame r = Dsl.sql(
                "SELECT * FROM ${a} JOIN ${b} ON a.bid = b.bid JOIN ${c} ON b.cid = c.cid",
                a, b, c);
        assertThat(r.rowCount()).isEqualTo(2);  // 两路匹配
    }

    @Test
    void L3_innerJoin关键字显式() {
        DataFrame left = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "a"}, {2L, "b"}});
        DataFrame right = DataFrame.of(Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{1L, 30L}, {3L, 40L}});
        DataFrame r = Dsl.sql("SELECT * FROM ${a} INNER JOIN ${b} ON a.id = b.id", left, right);
        assertThat(r.rowCount()).isEqualTo(1);
    }

    @Test
    void L3_子查询IN() {
        // 子查询:外层 WHERE age IN (内层查询的 age)
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "age", DType.LONG, "dept", DType.STRING),
                new Object[][]{{"alice", 30, "RD"}, {"bob", 25, "PM"}, {"carol", 40, "RD"}, {"dave", 30, "PM"}});
        // 找 RD 部门的年龄,再筛选同年龄的人(自连接效果)
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE age IN (SELECT age FROM ${t} WHERE dept == 'RD')", df);
        // RD 的 age = {30, 40},匹配 alice(30) carol(40) dave(30)
        assertThat(r.rowCount()).isEqualTo(3);
    }

    @Test
    void L3_标量子查询比较() {
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{"a", 80.0}, {"b", 95.0}, {"c", 70.0}, {"d", 95.0}});
        // 找 score > 平均分的人(平均 85)
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE score > (SELECT mean(score) FROM ${t})", df);
        // score > 85 → b(95), d(95)
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void L3_子查询多层最多2层() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        // 3 层嵌套,应抛异常
        try {
            Dsl.sql("SELECT * FROM ${t} WHERE v IN (SELECT v FROM ${t} WHERE v IN (SELECT v FROM ${t} WHERE v IN (SELECT v FROM ${t})))", df);
            org.assertj.core.api.Assertions.fail("应抛嵌套超限异常");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("超过 2 层");
        }
    }

    @Test
    void L3_子查询2层允许() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        // 2 层嵌套,允许
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE v IN (SELECT v FROM ${t} WHERE v IN (1, 2))", df);
        assertThat(r.rowCount()).isEqualTo(2);  // v=1, 2
    }

    // ======================== 安全与健壮性回归 =========================

    @Test
    void L1_空值函数nvl() {
        DataFrame df = DataFrame.of(Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{1.0, 2.0}, {null, 3.0}, {null, null}});
        // nvl(a, b):取第一个非缺失
        DataFrame r = Dsl.eval(df, "v = nvl(a, b)");
        assertThat(r.getColumn("v").get(0)).isEqualTo(1.0);
        assertThat(r.getColumn("v").get(1)).isEqualTo(3.0);
        // 第 3 行 a/b 都缺失 → nvl 返回 null,但赋给 DOUBLE 列后内部用 NaN 表示缺失
        // (AGENTS.md §3.5),用 isNull() 判断而非 get()==null
        assertThat(r.getColumn("v").isNull(2)).isTrue();
    }

    @Test
    void L1_空值函数coalesce与ifnull同效() {
        DataFrame df = DataFrame.of(Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{null, 7.0}});
        assertThat(Dsl.eval(df, "v = coalesce(a, b)").getColumn("v").get(0)).isEqualTo(7.0);
        assertThat(Dsl.eval(df, "v = ifnull(a, b)").getColumn("v").get(0)).isEqualTo(7.0);
    }

    @Test
    void L1_like不注入正则() {
        // 安全回归:like 模式除 % _ 外全部按字面量匹配(防正则注入)
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"a.b"}, {"axb"}, {"abc"}});
        // "a.b" 字面量只匹配 a.b,不匹配 axb(字面点不当正则通配符)
        assertThat(Dsl.query(df, "s like 'a.b'").rowCount()).isEqualTo(1);
        // 元字符 ( ) [ ] 等也按字面量
        DataFrame df2 = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"x(y)"}, {"xay"}});
        assertThat(Dsl.query(df2, "s like 'x(y)'").rowCount()).isEqualTo(1);
        // % 仍是通配
        assertThat(Dsl.query(df, "s like 'a%'").rowCount()).isEqualTo(3);
    }

    @Test
    void L3_selectDistinct去重() {
        DataFrame df = DataFrame.of(Schema.of("city", DType.STRING, "v", DType.LONG),
                new Object[][]{{"SH", 1L}, {"SH", 2L}, {"BJ", 3L}});
        DataFrame r = Dsl.sql("SELECT DISTINCT city FROM ${t}", df);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("city");
    }

    @Test
    void L3_limitOffset分页() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}, {4L}});
        // PG/MySQL 风格 LIMIT n OFFSET m
        DataFrame r = Dsl.sql("SELECT * FROM ${t} LIMIT 2 OFFSET 1", df);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getLongColumn("v").get(0)).isEqualTo(2L);
        assertThat(r.getLongColumn("v").get(1)).isEqualTo(3L);
        // Oracle 风格 OFFSET m ROWS FETCH FIRST n ROWS ONLY
        DataFrame r2 = Dsl.sql("SELECT * FROM ${t} OFFSET 2 ROWS FETCH FIRST 1 ROWS ONLY", SqlDialect.ORACLE, df);
        assertThat(r2.rowCount()).isEqualTo(1);
        assertThat(r2.getLongColumn("v").get(0)).isEqualTo(3L);
        // 独立 OFFSET(取剩余全部)
        DataFrame r3 = Dsl.sql("SELECT * FROM ${t} OFFSET 3", df);
        assertThat(r3.rowCount()).isEqualTo(1);
    }

    @Test
    void L3_占位与this混用报错不NPE() {
        // 安全回归:${} 占位 + FROM this 混用必须给明确报错,而不是 NPE
        DataFrame df = df();
        assertThatThrownBy(() -> Dsl.sql("SELECT * FROM this JOIN ${t} ON a.id = b.id", df))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("this");
    }

    @Test
    void L3_未知数据源报错() {
        // 子查询里的数据源名无法识别 → 明确报错(不静默当 this)
        DataFrame df = df();
        assertThatThrownBy(() -> Dsl.sql("SELECT * FROM ${t} WHERE age IN (SELECT age FROM nope)", df))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法识别的数据源");
    }

    @Test
    void L3_方言重载占位数校验() {
        DataFrame df = df();
        // 2 个占位但只给 1 个 df,必须报错(不静默少绑)
        assertThatThrownBy(() -> Dsl.sql("SELECT * FROM ${a} JOIN ${b} ON a.id = b.id",
                SqlDialect.DEFAULT, df))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一一对应");
    }

    @Test
    void DataFrame_eval与sql经SPI() {
        // df.eval() / df.sql() 经 DslEngine SPI 由 jian-dsl 实现(规范 07 §2.2)
        DataFrame df = DataFrame.of(Schema.of("price", DType.DOUBLE, "qty", DType.LONG),
                new Object[][]{{10.0, 2L}, {5.0, 3L}});
        DataFrame e = df.eval("total = price * qty");
        assertThat(e.columnNames()).contains("total");
        assertThat(e.getDoubleColumn("total").get(0)).isEqualTo(20.0);
        // df.sql:接收者为主表 this
        DataFrame r = df.sql("SELECT price, qty FROM this WHERE qty > 2");
        assertThat(r.rowCount()).isEqualTo(1);
        // df.sql 混用 this 与 ${} 绑定(inner join on price:this.price=10.0 与 b.price=10.0 匹配 1 行)
        DataFrame other = DataFrame.of(Schema.of("price", DType.DOUBLE),
                new Object[][]{{10.0}});
        DataFrame r2 = df.sql("SELECT * FROM this JOIN ${b} ON this.price = b.price", other);
        assertThat(r2.rowCount()).isEqualTo(1);
    }

    @Test
    void L3_静态入口无占位报错() {
        // 静态入口 Dsl.sql 无 ${} 占位时没有主表概念(未发布,无"兼容旧写法"):必须明确报错
        DataFrame df = df();
        assertThatThrownBy(() -> Dsl.sql("SELECT name, age FROM this WHERE age > 26", df))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${} 表名占位");
        // 方言重载同样拦截
        assertThatThrownBy(() -> Dsl.sql("SELECT * FROM DUAL", SqlDialect.ORACLE, df))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${} 表名占位");
        // 主表语义只挂在 DataFrame 上:df.sql 里 this/DUAL 都正常
        DataFrame r = df.sql("SELECT * FROM DUAL WHERE city == 'BJ'");
        assertThat(r.rowCount()).isEqualTo(1);
        DataFrame r2 = df.sql("SELECT name, age FROM this WHERE age > 26");
        assertThat(r2.rowCount()).isEqualTo(2);
    }
}

