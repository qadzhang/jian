package jian.sql.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineTest {

    private Engine h2Engine() {
        // H2 内存库
        return Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:jian_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
    }

    @Test
    void 建表查询往返() throws Exception {
        try (Engine engine = h2Engine();
             Connection conn = engine.connect();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE u (id BIGINT, name VARCHAR(100))");
            st.execute("INSERT INTO u VALUES (1, 'alice'), (2, 'bob')");
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM u")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2L);
            }
        }
    }

    @Test
    void begin事务_正常提交() throws Exception {
        try (Engine engine = h2Engine()) {
            try (Connection setup = engine.connect(); Statement st = setup.createStatement()) {
                st.execute("CREATE TABLE t (v BIGINT)");
            }
            try (Connection conn = engine.begin(); Statement st = conn.createStatement()) {
                st.execute("INSERT INTO t VALUES (10)");
                conn.commit();  // 正常提交
            }
            try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
                try (var rs = st.executeQuery("SELECT count(*) FROM t")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(1L);  // 已提交保留
                }
            }
        }
    }

    @Test
    void begin事务_异常回滚() throws Exception {
        try (Engine engine = h2Engine()) {
            try (Connection setup = engine.connect(); Statement st = setup.createStatement()) {
                st.execute("CREATE TABLE t (v BIGINT)");
            }
            try {
                try (Connection conn = engine.begin(); Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO t VALUES (10)");
                    throw new RuntimeException("模拟业务异常");
                }
            } catch (RuntimeException ignored) {
                // try-with-resources close 时 H2 默认回滚未提交事务
            }
            try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
                try (var rs = st.executeQuery("SELECT count(*) FROM t")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(0L);  // 回滚后无数据
                }
            }
        }
    }

    @Test
    void 只读模式拦截写操作() {
        EngineConfig cfg = EngineConfig.builder()
                .path("mem:ro_test").user("sa").password("").readOnly(true).build();
        try (Engine engine = Engine.create(DbType.H2, cfg)) {
            assertThatThrownBy(() -> engine.checkReadOnly("DROP TABLE x"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("只读模式禁止写操作");
            assertThatThrownBy(() -> engine.checkReadOnly("DELETE FROM x"))
                    .isInstanceOf(SecurityException.class);
            // SELECT 不拦截
            engine.checkReadOnly("SELECT * FROM x");
        }
    }

    @Test
    void parseUrl解析() {
        // 仅解析,不建 Engine(避免触发 PG 驱动加载)
        Engine.ParsedUrl p = Engine.parseUrl("postgresql://user:pass@localhost:5432/mydb");
        assertThat(p.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(p.config().host).isEqualTo("localhost");
        assertThat(p.config().port).isEqualTo(5432);
        assertThat(p.config().database).isEqualTo("mydb");
    }

    @Test
    void parseUrl环境变量占位() {
        System.setProperty("DB_TEST_PW", "secret123");
        Engine.ParsedUrl p = Engine.parseUrl("mysql://root:${DB_TEST_PW}@localhost/test");
        assertThat(p.dbType()).isEqualTo(DbType.MYSQL);
        assertThat(p.config().password).isEqualTo("secret123");
    }

    @Test
    void DbType枚举属性() {
        assertThat(DbType.POSTGRESQL.defaultPort()).isEqualTo(5432);
        assertThat(DbType.MYSQL.defaultPort()).isEqualTo(3306);
        assertThat(DbType.ORACLE.defaultPort()).isEqualTo(1521);
        assertThat(DbType.POSTGRESQL.jdbcUrl("h", 5432, "db")).contains("jdbc:postgresql://h:5432/db");
        assertThat(DbType.SQLITE.jdbcUrl("/path/x.db")).isEqualTo("jdbc:sqlite:/path/x.db");
    }

    // ======================== 2026-08-02 审查修复回归 ========================

    @Test
    void parseUrl无用户密码段不崩溃() {
        // 安全回归:URL 不带 user:pass@ 时,原实现 substring(0, -1) 崩溃
        Engine.ParsedUrl p = Engine.parseUrl("postgresql://localhost:5432/mydb");
        assertThat(p.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(p.config().host).isEqualTo("localhost");
        assertThat(p.config().port).isEqualTo(5432);
        assertThat(p.config().database).isEqualTo("mydb");
        assertThat(p.config().user).isEmpty();
    }

    @Test
    void 只读模式防注释绕过() {
        // 安全回归:/* 注释 */ + DROP 不再能绕过只读拦截
        EngineConfig cfg = EngineConfig.builder()
                .path("mem:ro_test2").user("sa").password("").readOnly(true).build();
        try (Engine engine = Engine.create(DbType.H2, cfg)) {
            assertThatThrownBy(() -> engine.checkReadOnly("/* x */ DROP TABLE t"))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> engine.checkReadOnly("-- 注释\nDELETE FROM t"))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> engine.checkReadOnly("  \n/* a */ /* b */ TRUNCATE TABLE t"))
                    .isInstanceOf(SecurityException.class);
            // SELECT 带注释仍放行
            engine.checkReadOnly("/* 说明 */ SELECT * FROM t");
        }
    }

    @Test
    void engineDsl与sql入口() throws Exception {
        // 规范 05 §2.2:engine.dsl() / engine.sql(...).fetch()
        try (Engine engine = h2Engine()) {
            try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE u (id BIGINT, name VARCHAR(100))");
                st.execute("INSERT INTO u VALUES (1, 'alice'), (2, 'bob')");
            }
            // engine.sql("...", params).fetch():原生 SQL 参数化
            org.jooq.Result<org.jooq.Record> r = engine.sql("SELECT name FROM u WHERE id > ?", 1).fetch();
            assertThat(r.size()).isEqualTo(1);
            assertThat(r.get(0).get(0).toString()).isEqualTo("bob");  // SELECT 只选 name 一列
            // engine.dsl():类型安全 DSL
            org.jooq.Result<org.jooq.Record> r2 = engine.dsl().ctx()
                    .selectFrom("u").where("id = ?", 2).fetch();
            assertThat(r2.size()).isEqualTo(1);
        }
    }

    @Test
    void JianSqlException脱敏URL() {
        // 安全:异常信息不得泄漏密码
        String u = JianSqlException.sanitize("jdbc:postgresql://user:secret@host:5432/db");
        assertThat(u).isEqualTo("jdbc:postgresql://user:***@host:5432/db");
        assertThat(u).doesNotContain("secret");
        // 无密码段原样返回
        assertThat(JianSqlException.sanitize("jdbc:sqlite:/tmp/x.db")).isEqualTo("jdbc:sqlite:/tmp/x.db");
    }

    @Test
    void 驱动缺失抛ModuleNotLoaded() {
        // 规范 §4:PG 驱动不在 test classpath,建 Engine 必须抛带安装提示的异常
        EngineConfig cfg = EngineConfig.builder()
                .host("localhost").port(5432).user("u").password("p").database("d").build();
        assertThatThrownBy(() -> Engine.create(DbType.POSTGRESQL, cfg))
                .isInstanceOf(ModuleNotLoadedException.class)
                .hasMessageContaining("org.postgresql:postgresql");
    }
}
