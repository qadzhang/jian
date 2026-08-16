package jian.sql.expr;

import org.junit.jupiter.api.Test;
import org.jooq.Record;
import org.jooq.Result;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SqlBuilderRegressionTest —— SqlBuilder 回归测试集(固化连接自动归还行为)
// │  Why  : 因为链式 fetch/execute 不显式 close 时,自取连接必须自动归还
// │         (否则每次净泄漏一个连接,默认池 10,第 11 次即耗尽),所以用回归测试固化
// │  Who  : JUnit 5 自动执行
// │  When : mvn test(jian-sql-expr 模块)
// │  Where: jian-sql-expr/src/test/java/jian/sql/expr/SqlBuilderRegressionTest.java
// │  How  : 本模块不依赖 HikariCP/Engine,用"计数数据源"模拟 10 连接上限的池:
// │         getConnection 借出计数 +1、超上限 fail-fast 抛 SQLException(不用真等 30s 超时),
// │         连接 close 时计数 -1(动态代理拦截);fetch/execute 后计数应归零 = 已归还
class SqlBuilderRegressionTest {

    /** 模拟池大小(对齐 HikariCP/EngineConfig 默认 poolSize=10)。 */
    private static final int POOL_MAX = 10;

    /**
     * 计数数据源:跟踪"借出未还"的连接数。
     * <p>How:每次 getConnection 开一条真 H2 连接并包一层 JDK 动态代理 —— close() 时计数 -1
     * 并透传关闭;其它方法透传。计数超 POOL_MAX 时 fail-fast 抛 SQLException(模拟池耗尽,
     * 不引入 HikariCP 依赖,失败快不用等 30 秒 connectionTimeout)。
     */
    private static final class CountingPoolDataSource implements javax.sql.DataSource {
        private final AtomicInteger active = new AtomicInteger();
        private final String url;

        CountingPoolDataSource(String url) { this.url = url; }

        /** 当前借出未还的连接数。 */
        int activeCount() { return active.get(); }

        @Override public Connection getConnection() throws SQLException {
            int n = active.incrementAndGet();
            if (n > POOL_MAX) {
                active.decrementAndGet();
                throw new SQLException("模拟连接池耗尽:借出未还超过上限 " + POOL_MAX
                        + "(链式 fetch 若不归还连接,每次净泄漏一个)");
            }
            Connection raw = DriverManager.getConnection(url, "sa", "");
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "close": active.decrementAndGet(); raw.close(); return null;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            case "toString": return "CountingConn(" + url + ")";
                            default:
                                try {
                                    return method.invoke(raw, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                        }
                    });
        }

        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** 建一个 H2 mem 库(带 users 表)+ 指向它的计数数据源。 */
    private CountingPoolDataSource newPool() throws Exception {
        String url = "jdbc:h2:mem:r9_expr_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT, name VARCHAR(50))");
            st.execute("INSERT INTO users VALUES (1, 'alice'), (2, 'bob')");
        }
        return new CountingPoolDataSource(url);
    }

    @Test
    void 链式fetch循环15次每次归还连接() throws Exception {
        // fetch() 完成即归还,借出计数每轮归零(若不归还,第 11 次 getConnection 即抛 SQLException)
        CountingPoolDataSource ds = newPool();
        for (int i = 0; i < 15; i++) {
            Result<Record> r = SqlBuilder.create(ds, SqlBuilder.Dialect.H2)
                    .query("SELECT id FROM users WHERE id > ?", 0)
                    .fetch();
            assertThat(r.size()).as("第 %d 次循环", i).isEqualTo(2);
            assertThat(ds.activeCount()).as("fetch 后借出计数应归零(第 %d 次)", i).isZero();
        }
    }

    @Test
    void fetch失败路径也在finally归还连接() throws Exception {
        // SQL 报错时异常照抛,但连接必须归还(finally),否则失败路径泄漏更隐蔽
        CountingPoolDataSource ds = newPool();
        SqlBuilder qb = SqlBuilder.create(ds, SqlBuilder.Dialect.H2);
        assertThatThrownBy(() -> qb.query("SELECT * FROM no_such_table_r9").fetch())
                .isInstanceOf(Exception.class);   // jOOQ 包装的 DataAccessException
        assertThat(ds.activeCount()).as("fetch 失败后借出计数应归零").isZero();
        // 归还后池仍可用
        assertThat(qb.query("SELECT COUNT(*) FROM users").fetch().size()).isEqualTo(1);
    }

    @Test
    void execute完成后归还连接且写入生效() throws Exception {
        CountingPoolDataSource ds = newPool();
        SqlBuilder qb = SqlBuilder.create(ds, SqlBuilder.Dialect.H2);
        int affected = qb.query("UPDATE users SET name = ? WHERE id = ?", "carol", 1).execute();
        assertThat(affected).isEqualTo(1);
        assertThat(ds.activeCount()).as("execute 后借出计数应归零").isZero();
        // 连接归还前数据已真正写入(不是关连接回滚)
        assertThat(qb.query("SELECT name FROM users WHERE id = 1").fetch().getValue(0, "NAME"))
                .isEqualTo("carol");
    }

    @Test
    void withConnection外部连接不被自动归还() throws Exception {
        // 兼容性:外部绑定连接(事务复用)不被 fetch 自动关 —— 归还责任在调用方
        CountingPoolDataSource ds = newPool();
        try (Connection external = ds.getConnection()) {
            SqlBuilder qb = SqlBuilder.create(ds, SqlBuilder.Dialect.H2).withConnection(external);
            Result<Record> r = qb.query("SELECT id FROM users").fetch();
            assertThat(r.size()).isEqualTo(2);
            assertThat(ds.activeCount()).as("外部连接仍被调用方持有(计数 1)").isEqualTo(1);
            assertThat(external.isClosed()).as("外部连接未被 fetch 关闭").isFalse();
        }
        assertThat(ds.activeCount()).as("try-with-resources 关闭后归零").isZero();
    }

    @Test
    void close幂等且可重复释放() throws Exception {
        // releaseOwnedConnection 幂等 —— close 两次不重复归还、不抛异常
        CountingPoolDataSource ds = newPool();
        SqlBuilder qb = SqlBuilder.create(ds, SqlBuilder.Dialect.H2);
        assertThat(qb.query("SELECT 1").fetch().size()).isEqualTo(1);
        try (SqlBuilder qb2 = SqlBuilder.create(ds, SqlBuilder.Dialect.H2)) {
            qb2.query("SELECT 1").fetch();   // 已自动归还
        }                                    // close() 再触发 release(空操作)
        qb.close();                          // 归还后再 close(空操作)
        qb.close();                          // 第二次 close(空操作)
        assertThat(ds.activeCount()).isZero();
    }
}
