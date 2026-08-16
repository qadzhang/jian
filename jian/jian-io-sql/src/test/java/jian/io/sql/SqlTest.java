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
            // 因为只验行数不能证明 null 真被保留(§3.5 契约),所以补齐:
            // 行数 + 三行取值 + 中间行确实缺失
            assertThat(r.rowCount()).isEqualTo(3);
            assertThat(r.getDoubleColumn("V").getDouble(0)).isEqualTo(1.0);
            assertThat(r.getDoubleColumn("V").getDouble(2)).isEqualTo(3.0);
            assertThat(r.getDoubleColumn("V").isNull(1))
                    .as("NULL 经 JDBC 读回应保留为缺失(不是 0.0 也不是 NaN 字面量)")
                    .isTrue();
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
     * SQL 注入防护:readTable 的表名只接受白名单
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
     * write 的 DROP/CREATE/INSERT 表名也必须过白名单 —— 因为只校验 readTable 的表名时,
     * write 仍可直接注入 "x; DROP TABLE secret; --",所以 write 全路径统一校验。
     */
    @Test
    void write_非法表名抛IAE挡住SQL注入() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(Schema.of("id", DType.INT), new Object[][]{{1}});
            String evil = "x; DROP TABLE secret; --";
            assertThatThrownBy(() -> Sql.write(df, conn, evil, Sql.Mode.CREATE_OR_REPLACE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法表名");
            // 所有模式统一拦截(OVERWRITE 走 DROP 路径,APPEND 走 INSERT 路径)
            assertThatThrownBy(() -> Sql.write(df, conn, evil, Sql.Mode.OVERWRITE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sql.write(df, conn, evil, Sql.Mode.APPEND))
                    .isInstanceOf(IllegalArgumentException.class);
            // 合法表名放行
            Sql.write(df, conn, "safe_tbl", Sql.Mode.CREATE_OR_REPLACE);
            assertThat(Sql.readTable(conn, "safe_tbl").rowCount()).isEqualTo(1);
        }
    }

    /**
     * CREATE TABLE / INSERT 的列名也必须过白名单 —— 因为列名直接拼入 SQL 时,
     * "id); DROP TABLE users; --" 形式可注入,所以列名同样走标识符白名单校验。
     */
    @Test
    void write_非法列名抛IAE挡住SQL注入() throws Exception {
        try (Connection conn = h2()) {
            String evilCol = "id); DROP TABLE secret; --";
            DataFrame df = DataFrame.of(Schema.of(evilCol, DType.INT), new Object[][]{{1}});
            assertThatThrownBy(() -> Sql.write(df, conn, "t1", Sql.Mode.CREATE_OR_REPLACE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法列名");
            // 合法列名正常往返
            DataFrame ok = DataFrame.of(Schema.of("id", DType.INT, "name", DType.STRING),
                    new Object[][]{{1, "a"}});
            Sql.write(ok, conn, "t2", Sql.Mode.CREATE_OR_REPLACE);
            assertThat(Sql.readTable(conn, "t2").rowCount()).isEqualTo(1);
        }
    }

    /**
     * 合法的 schema.table 应被生产白名单放行,注入式表名应被拒绝。
     * 因为用本文件内的正则副本断言与生产 Sql.readTable 的白名单零耦合
     * (白名单被删改时测试依旧全绿,是假守卫),所以走真链路验证:
     * H2 建真实 SCHEMA.USERS 表 → readTable 放行且读回数据;注入表名 → 生产白名单抛 IAE。
     */
    @Test
    void readTable_合法schema_表名放行() throws Exception {
        try (Connection conn = h2();
             java.sql.Statement st = conn.createStatement()) {
            // H2 未加引号的标识符统一转大写:CREATE SCHEMA schema → SCHEMA,与 readTable 拼接的
            // SELECT * FROM schema.users(H2 解析为 SCHEMA.USERS)精确对应
            st.execute("CREATE SCHEMA IF NOT EXISTS schema");
            st.execute("CREATE TABLE schema.users(ID BIGINT)");
            st.execute("INSERT INTO schema.users VALUES (1)");
            DataFrame r = Sql.readTable(conn, "schema.users");   // 点号表名经生产白名单放行
            assertThat(r.rowCount()).isEqualTo(1);
            assertThat(r.getColumn("ID").get(0)).isEqualTo(1L);
            // 拒绝路径:生产 Sql.readTable 的白名单(非本地副本)必须拦下注入式/非法表名
            assertThatThrownBy(() -> Sql.readTable(conn, "users; DROP TABLE schema.users"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法表名");
            assertThatThrownBy(() -> Sql.readTable(conn, "bad name"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
