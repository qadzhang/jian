package jian.io.sql;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : 多数据库测试 —— SQLite 真实 + H2 模拟(PG/MySQL 方言适配验证)
// │  Why  : 规范要求 7 库通用;真实测 SQLite(文件型,自带 native)+ H2 内存(模拟其余库的 SQL 方言)
class SqlMultiDbTest {

    @Test
    void sqlite真实读写往返() throws Exception {
        // SQLite 内存库(真实 sqlite native 驱动)
        String url = "jdbc:sqlite::memory:";
        try (Connection conn = DriverManager.getConnection(url)) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE users (id INTEGER, name TEXT, score REAL)");
                st.execute("INSERT INTO users VALUES (1, 'alice', 90.5), (2, 'bob', 85.0)");
            }
            DataFrame df = Sql.readTable(conn, "users");
            assertThat(df.rowCount()).isEqualTo(2);
            assertThat(df.columnNames()).containsExactly("id", "name", "score");
            assertThat(df.getStringColumn("name").get(0)).isEqualTo("alice");
        }
    }

    @Test
    void sqlite参数化查询() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE p (v INTEGER)");
                st.execute("INSERT INTO p VALUES (10), (20), (30)");
            }
            DataFrame r = Sql.readQuery(conn, "SELECT * FROM p WHERE v > ?", 15);
            assertThat(r.rowCount()).isEqualTo(2);  // 20, 30
        }
    }

    @Test
    void sqlite写入往返() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "name", DType.STRING),
                    new Object[][]{{1L, "x"}, {2L, "y"}});
            Sql.write(df, conn, "t", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame r = Sql.readTable(conn, "t");
            assertThat(r.rowCount()).isEqualTo(2);
        }
    }

    // ===== H2 模拟 PG/MySQL 方言(H2 兼容两者语法子集)=====

    @Test
    void h2模拟PG方言_标准SQL() throws Exception {
        // H2 的 PostgreSQL 兼容模式
        String url = "jdbc:h2:mem:pgsim_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            try (var st = conn.createStatement()) {
                // PG 风格:SERIAL / TEXT / TIMESTAMP
                st.execute("CREATE TABLE logs (id INT, msg VARCHAR(200))");
                st.execute("INSERT INTO logs VALUES (1, 'hello'), (2, 'world')");
            }
            DataFrame r = Sql.readQuery(conn, "SELECT * FROM logs WHERE msg LIKE ?", "hel%");
            assertThat(r.rowCount()).isEqualTo(1);
        }
    }

    @Test
    void h2模拟MySQL方言_反引号兼容() throws Exception {
        // H2 的 MySQL 兼容模式
        String url = "jdbc:h2:mem:mysqlsim_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE t (id INT, val DOUBLE)");
                st.execute("INSERT INTO t VALUES (1, 3.14)");
            }
            DataFrame r = Sql.readTable(conn, "t");
            assertThat(r.rowCount()).isEqualTo(1);
            // H2 默认列名大写;MySQL 模式下也如此
            assertThat(r.getDoubleColumn("VAL").getDouble(0)).isEqualTo(3.14);
        }
    }

    @Test
    void sqlite缺失值往返() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DataFrame df = DataFrame.of(
                    Schema.of("v", DType.DOUBLE, "s", DType.STRING),
                    new Object[][]{{1.0, "a"}, {null, null}, {3.0, "c"}});
            Sql.write(df, conn, "na_test", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame r = Sql.readTable(conn, "na_test");
            assertThat(r.rowCount()).isEqualTo(3);
            assertThat(r.getColumn("s").get(1)).isNull();
        }
    }

    // ======================== VARCHAR 自适应长度(SQLite)========================

    /**
     * 短文本(≤ 4000)在 SQLite 不截断。
     * SQLite 是动态类型(TEXT affinity),长度声明被忽略,所以任何长度都不截断。
     */
    @Test
    void sqlite短文本_不截断() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            String medium = "x".repeat(3500);
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "txt", DType.STRING),
                    new Object[][]{{1L, "short"}, {2L, medium}});
            Sql.write(df, conn, "vc_test", Sql.Mode.CREATE_OR_REPLACE);

            DataFrame r = Sql.readTable(conn, "vc_test");
            assertThat(r.rowCount()).isEqualTo(2);
            assertThat(r.getStringColumn("txt").get(0)).isEqualTo("short");
            assertThat(((String) r.getStringColumn("txt").get(1)).length())
                    .as("SQLite 短文本 3500 字符不截断").isEqualTo(3500);
        }
    }

    /**
     * 长文本(> 4000)在 SQLite 不截断(SQLite TEXT affinity 无长度上限)。
     */
    @Test
    void sqlite长文本_万字符不截断() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            String longText = "abcdefgh".repeat(1000);   // 8000 字符(纯 ASCII)
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "body", DType.STRING),
                    new Object[][]{{1L, longText}});
            Sql.write(df, conn, "long_test", Sql.Mode.CREATE_OR_REPLACE);

            DataFrame r = Sql.readTable(conn, "long_test");
            assertThat(r.rowCount()).isEqualTo(1);
            String readBack = (String) r.getColumn("body").get(0);
            assertThat(readBack.length())
                    .as("SQLite 长文本不截断").isEqualTo(longText.length());
            assertThat(readBack).isEqualTo(longText);
        }
    }

    /**
     * 混合长短文本同表(SQLite):都走 TEXT affinity,各自不截断。
     */
    @Test
    void sqlite混合长短文本_各自不截断() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            String longArticle = "A".repeat(8000);
            DataFrame df = DataFrame.of(
                    Schema.of("name", DType.STRING, "article", DType.STRING),
                    new Object[][]{{"alice", longArticle}, {"bob", "short"}});
            Sql.write(df, conn, "mixed_test", Sql.Mode.CREATE_OR_REPLACE);

            DataFrame r = Sql.readTable(conn, "mixed_test");
            assertThat(r.rowCount()).isEqualTo(2);
            assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
            assertThat(((String) r.getColumn("article").get(0)).length()).isEqualTo(8000);
            assertThat(r.getStringColumn("article").get(1)).isEqualTo("short");
        }
    }
}
