package jian.sql.expr;

import org.junit.jupiter.api.Test;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class SqlBuilderTest {

    private Connection h2() throws Exception {
        String name = "jian_expr_" + System.nanoTime();
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT, name VARCHAR(100), age INT)");
            st.execute("INSERT INTO users VALUES (1, 'alice', 30), (2, 'bob', 25), (3, 'carol', 40)");
        }
        return conn;
    }

    @Test
    void jOOQ链式查询() throws Exception {
        try (Connection conn = h2();
             SqlBuilder qb = SqlBuilder.create(toDataSource(conn), SqlBuilder.Dialect.H2).withConnection(conn)) {
            Result<Record> r = qb.ctx().selectFrom("users")
                    .where("age > ?", 28)
                    .orderBy(DSL.field("age").desc())
                    .fetch();
            assertThat(r.size()).isEqualTo(2);  // carol(40), alice(30)
            assertThat(r.getValue(0, "NAME")).isEqualTo("carol");
        }
    }

    @Test
    void 原生SQL参数化() throws Exception {
        try (Connection conn = h2();
             SqlBuilder qb = SqlBuilder.create(toDataSource(conn), SqlBuilder.Dialect.H2).withConnection(conn)) {
            Result<Record> r = qb.fetch("SELECT * FROM users WHERE name = ?", "bob");
            assertThat(r.size()).isEqualTo(1);
            assertThat(r.getValue(0, "AGE")).isEqualTo(25);
        }
    }

    @Test
    void execute执行DML() throws Exception {
        try (Connection conn = h2();
             SqlBuilder qb = SqlBuilder.create(toDataSource(conn), SqlBuilder.Dialect.H2).withConnection(conn)) {
            int affected = qb.execute("UPDATE users SET age = ? WHERE name = ?", 26, "bob");
            assertThat(affected).isEqualTo(1);
            Result<Record> r = qb.fetch("SELECT age FROM users WHERE name = ?", "bob");
            assertThat(r.getValue(0, "AGE")).isEqualTo(26);
        }
    }

    @Test
    void selectFromCount() throws Exception {
        try (Connection conn = h2();
             SqlBuilder qb = SqlBuilder.create(toDataSource(conn), SqlBuilder.Dialect.H2).withConnection(conn)) {
            Result<Record> r = qb.ctx().selectFrom("users").fetch();
            assertThat(r.size()).isEqualTo(3);
        }
    }

    private javax.sql.DataSource toDataSource(Connection conn) {
        return new javax.sql.DataSource() {
            @Override public Connection getConnection() { return conn; }
            @Override public Connection getConnection(String u, String p) { return conn; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int s) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> i) { return null; }
            @Override public boolean isWrapperFor(Class<?> i) { return false; }
        };
    }
}
