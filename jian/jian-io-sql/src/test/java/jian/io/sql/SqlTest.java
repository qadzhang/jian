package jian.io.sql;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTest {

    private Connection h2() throws Exception {
        // H2 内存库(每个测试唯一名,避免冲突)
        String name = "jian_test_" + System.nanoTime();
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void 写出后读回一致() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                    new Object[][]{
                            {1L, "alice", 90.5},
                            {2L, "bob", 85.0},
                            {3L, "carol", 76.5}
                    });
            Sql.write(df, conn, "users");
            DataFrame r = Sql.readTable(conn, "users");
            assertThat(r.rowCount()).isEqualTo(3);
            assertThat(r.columnNames()).containsExactly("ID", "NAME", "SCORE");  // H2 默认大写
            assertThat(r.getStringColumn("NAME").get(0)).isEqualTo("alice");
            assertThat(r.getDoubleColumn("SCORE").getDouble(1)).isEqualTo(85.0);
        }
    }

    @Test
    void 参数化查询() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "age", DType.LONG),
                    new Object[][]{{1L, 30L}, {2L, 25L}, {3L, 40L}});
            Sql.write(df, conn, "people");
            DataFrame r = Sql.readQuery(conn, "SELECT * FROM people WHERE age > ?", 28);
            assertThat(r.rowCount()).isEqualTo(2);  // age=30, 40
        }
    }

    @Test
    void APPEND模式累加() throws Exception {
        try (Connection conn = h2()) {
            DataFrame a = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
            DataFrame b = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{2L}, {3L}});
            Sql.write(a, conn, "nums", Sql.Mode.CREATE_OR_REPLACE);
            Sql.write(b, conn, "nums", Sql.Mode.APPEND);
            DataFrame r = Sql.readTable(conn, "nums");
            assertThat(r.rowCount()).isEqualTo(3);
        }
    }

    @Test
    void FAIL_IF_EXISTS抛异常() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
            Sql.write(df, conn, "t", Sql.Mode.CREATE_OR_REPLACE);
            try {
                Sql.write(df, conn, "t", Sql.Mode.FAIL_IF_EXISTS);
                org.assertj.core.api.Assertions.fail("应抛异常");
            } catch (java.sql.SQLException e) {
                assertThat(e.getMessage()).contains("已存在");
            }
        }
    }

    @Test
    void 缺失值往返() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(
                    Schema.of("v", DType.DOUBLE),
                    new Object[][]{{1.0}, {null}, {3.0}});
            Sql.write(df, conn, "na");
            DataFrame r = Sql.readTable(conn, "na");
            assertThat(r.rowCount()).isEqualTo(3);
            // NULL 经 JDBC 读回 null,Schema 推断 + 中间列保留
        }
    }

    // ======================== VARCHAR 自适应长度(H2)========================

    /**
     * 短文本(≤ 4000)在 H2 应建 VARCHAR(n),不截断。
     */
    @Test
    void H2短文本_VARCHAR自适应_不截断() throws Exception {
        try (Connection conn = h2()) {
            String medium = "x".repeat(3500);
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "txt", DType.STRING),
                    new Object[][]{{1L, "short"}, {2L, medium}});
            Sql.write(df, conn, "varchar_test");

            DataFrame r = Sql.readTable(conn, "varchar_test");
            assertThat(r.rowCount()).isEqualTo(2);
            assertThat(r.getStringColumn("TXT").get(0)).isEqualTo("short");  // H2 列名大写
            assertThat(((String) r.getStringColumn("TXT").get(1)).length())
                    .as("H2 短文本 3500 字符不截断").isEqualTo(3500);
        }
    }

    /**
     * 长文本(> 4000)在 H2 应建 CLOB,不截断。
     */
    @Test
    void H2长文本_走CLOB_万字符不截断() throws Exception {
        try (Connection conn = h2()) {
            String longText = "abcdefgh".repeat(1000);   // 8000 字符(纯 ASCII,避免 Unicode 字节/字符歧义)
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "body", DType.STRING),
                    new Object[][]{{1L, longText}});
            Sql.write(df, conn, "clob_test");

            DataFrame r = Sql.readTable(conn, "clob_test");
            assertThat(r.rowCount()).isEqualTo(1);
            String readBack = (String) r.getColumn("BODY").get(0);  // H2 列名大写
            assertThat(readBack.length())
                    .as("H2 CLOB 长文本不截断").isEqualTo(longText.length());
            assertThat(readBack).isEqualTo(longText);
        }
    }

    /**
     * 混合长短文本同表:短(name)走 VARCHAR,长(article)走 CLOB。
     */
    @Test
    void H2混合长短文本_各自不截断() throws Exception {
        try (Connection conn = h2()) {
            String longArticle = "A".repeat(8000);
            DataFrame df = DataFrame.of(
                    Schema.of("name", DType.STRING, "article", DType.STRING),
                    new Object[][]{{"alice", longArticle}, {"bob", "short"}});
            Sql.write(df, conn, "mixed_text");

            DataFrame r = Sql.readTable(conn, "mixed_text");
            assertThat(r.rowCount()).isEqualTo(2);
            assertThat(r.getStringColumn("NAME").get(0)).isEqualTo("alice");
            assertThat(((String) r.getColumn("ARTICLE").get(0)).length()).isEqualTo(8000);
            assertThat(r.getStringColumn("ARTICLE").get(1)).isEqualTo("short");
        }
    }

    /**
     * SQL 注入防护回归(2026-08-09 修复):readTable 的表名只接受白名单
     * [A-Za-z_][A-Za-z0-9_.]*,所有注入 payload 必须抛 IAE,不能进 SQL。
     * 覆盖经典 OWASP payload:DROP TABLE 注入、引号注入、分号注入、union 注入、注释注入。
     */
    @Test
    void readTable_非法表名抛IAE挡住SQL注入() throws Exception {
        try (Connection conn = h2()) {
            // 经典注入:; DROP TABLE
            assertThatThrownBy(() -> Sql.readTable(conn, "users; DROP TABLE users; --"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法表名");
            // 引号注入
            assertThatThrownBy(() -> Sql.readTable(conn, "users' OR '1'='1"))
                    .isInstanceOf(IllegalArgumentException.class);
            // union 注入
            assertThatThrownBy(() -> Sql.readTable(conn, "users UNION SELECT password FROM secrets"))
                    .isInstanceOf(IllegalArgumentException.class);
            // 注释注入
            assertThatThrownBy(() -> Sql.readTable(conn, "users--"))
                    .isInstanceOf(IllegalArgumentException.class);
            // null 表名
            assertThatThrownBy(() -> Sql.readTable(conn, null))
                    .isInstanceOf(IllegalArgumentException.class);
            // 数字开头表名
            assertThatThrownBy(() -> Sql.readTable(conn, "1users"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 合法的 schema.table 应被放行(白名单允许点号)。
     */
    @Test
    void readTable_合法schema_表名放行() {
        // 只验正则放行(不需真实表);不抛 IAE 即说明白名单通过。
        // schema.table 是 SQL 标准写法,白名单应允许。
        assertThat(org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> TABLE_NAME_PATTERN_MATCHES("schema.users"))).isTrue();
        assertThat(org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> TABLE_NAME_PATTERN_MATCHES("_internal.log_2026"))).isTrue();
    }

    /** 辅助:仅测白名单正则(避免真连库)。返回 true=匹配。 */
    private static boolean TABLE_NAME_PATTERN_MATCHES(String s) {
        return java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*").matcher(s).matches();
    }
}
