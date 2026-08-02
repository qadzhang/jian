package jian.sql.bridge;

import jian.core.DataFrame;
import jian.sql.engine.DbType;
import jian.sql.engine.Engine;
import jian.sql.engine.EngineConfig;
import jian.sql.expr.SqlBuilder;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class SqlBridgeTest {

    private Engine h2Engine() throws Exception {
        Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:bridge_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
        try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT, name VARCHAR(100), age INT)");
            st.execute("INSERT INTO users VALUES (1,'alice',30), (2,'bob',25), (3,'carol',40)");
        }
        return engine;
    }

    @Test
    void fetchAsDataFrame() throws Exception {
        try (Engine engine = h2Engine()) {
            DataFrame df = SqlBridge.fetchAsDataFrame(engine, "SELECT * FROM users WHERE age > ?", 28);
            assertThat(df.rowCount()).isEqualTo(2);
            assertThat(df.columnNames()).containsExactly("ID", "NAME", "AGE");
            assertThat(df.getStringColumn("NAME").get(0)).isEqualTo("alice");
        }
    }

    @Test
    void connection直接转() throws Exception {
        try (Engine engine = h2Engine(); Connection conn = engine.connect()) {
            DataFrame df = SqlBridge.toDataFrame(conn, "SELECT name, age FROM users");
            assertThat(df.rowCount()).isEqualTo(3);
            assertThat(df.columnNames()).containsExactly("NAME", "AGE");
        }
    }

    @Test
    void jOOQResult转() throws Exception {
        try (Engine engine = h2Engine(); Connection conn = engine.connect();
             SqlBuilder qb = SqlBuilder.create(engine.dataSource(), SqlBuilder.Dialect.H2).withConnection(conn)) {
            var r = qb.ctx().selectFrom("users").where("age > ?", 28).fetch();
            DataFrame df = SqlBridge.toDataFrame(r);
            assertThat(df.rowCount()).isEqualTo(2);
        }
    }

    @Test
    void 缺失值处理() throws Exception {
        try (Engine engine = h2Engine();
             Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO users VALUES (4, NULL, NULL)");
            DataFrame df = SqlBridge.toDataFrame(conn, "SELECT * FROM users WHERE id = 4");
            assertThat(df.rowCount()).isEqualTo(1);
            assertThat(df.getColumn("NAME").get(0)).isNull();
        }
    }

    @Test
    void 空结果() throws Exception {
        try (Engine engine = h2Engine()) {
            DataFrame df = SqlBridge.fetchAsDataFrame(engine, "SELECT * FROM users WHERE age > 999");
            assertThat(df.rowCount()).isEqualTo(0);
        }
    }
}
