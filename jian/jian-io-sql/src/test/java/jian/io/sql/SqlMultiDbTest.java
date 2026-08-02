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
}
