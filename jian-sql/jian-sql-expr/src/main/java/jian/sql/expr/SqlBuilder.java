package jian.sql.expr;

import org.jooq.CloseableDSLContext;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

// ┌─ What : SqlBuilder —— 类型安全 SQL 表达式构建(对齐规范 05 §2.2,基于 jOOQ 3.21.6 运行时模式)
// │  Why  : 规范 05 §2.2 运行时模式;类型安全 + 防 SQL 注入(参数化)
// │  Who  : 用户经 Engine.dsl() 或 SqlBuilder.create 创建
// │  When : 动态 schema / 一次性脚本场景
// │  Where: jian-sql-expr/SqlBuilder.java
// │  How  : 数据走向:DSLContext(暴露)→ 用户用 jOOQ 原生链式 → 参数化 SQL → fetch Result。
// │         关键变量变化:ctx 是 jOOQ DSLContext,绑定 Connection/DataSource + 方言。
/**
 * 类型安全 SQL 表达式构建,基于 jOOQ 3.21.6 运行时模式(规范 §2.2)。
 *
 * <p>用法:
 * <pre>{@code
 * SqlBuilder qb = SqlBuilder.create(dataSource, SqlBuilder.Dialect.H2).withConnection(conn);
 *
 * // 方式 A:直接用 jOOQ DSL(类型安全)
 * Result<Record> r = qb.ctx().selectFrom("users").where("age > ?", 18).orderBy(DSL.field("age").desc()).fetch();
 *
 * // 方式 B:原生 SQL(参数化防注入)
 * Result<Record> r2 = qb.fetch("SELECT * FROM users WHERE name = ?", "alice");
 * }</pre>
 *
 * <p><b>防注入</b>:值用 ? 占位符,jOOQ 用 PreparedStatement 绑定(规范 §3.2)。
 */
public final class SqlBuilder implements AutoCloseable {

    private final DataSource ds;
    private final SQLDialect dialect;
    private DSLContext ctx;
    private Connection ownedConn;  // 本类自取的连接(用完关);boundConn 是外部传入(不关)

    private SqlBuilder(DataSource ds, SQLDialect dialect) {
        this.ds = ds;
        this.dialect = dialect;
    }

    public static SqlBuilder create(DataSource ds, Dialect dialect) {
        return new SqlBuilder(ds, dialect.jooq());
    }

    /** jian 方言 → jOOQ SQLDialect(jOOQ OSS Edition 不含 Oracle/DB2 等商业方言,用 DEFAULT)。 */
    public enum Dialect {
        POSTGRESQL(SQLDialect.POSTGRES),
        MYSQL(SQLDialect.MYSQL),
        DORIS(SQLDialect.MYSQL),      // Doris 用 MySQL 协议
        H2(SQLDialect.H2),
        SQLITE(SQLDialect.SQLITE),
        ORACLE(SQLDialect.DEFAULT),   // jOOQ OSS 不含 Oracle,用 DEFAULT(通用 SQL)
        ACCESS(SQLDialect.DEFAULT),
        DEFAULT(SQLDialect.DEFAULT);

        private final SQLDialect jooq;
        Dialect(SQLDialect j) { this.jooq = j; }
        public SQLDialect jooq() { return jooq; }
    }

    private String pendingSql;            // query() 设置的待执行 SQL
    private Object[] pendingParams = new Object[0];

    /**
     * 预置原生 SQL(参数化),后续 {@link #fetch()} / {@link #execute()} 执行。
     * 支持 engine.sql("...", params) 链式入口(规范 05 §2.2)。
     */
    public SqlBuilder query(String sql, Object... params) {
        this.pendingSql = sql;
        this.pendingParams = params == null ? new Object[0] : params;
        return this;
    }

    /** 执行 {@link #query} 预置的 SELECT,返回结果。 */
    public Result<Record> fetch() {
        if (pendingSql == null) throw new IllegalStateException("先调用 query(sql, params) 再 fetch()");
        return fetch(pendingSql, pendingParams);
    }

    /** 执行 {@link #query} 预置的 DML,返回影响行数。 */
    public int execute() {
        if (pendingSql == null) throw new IllegalStateException("先调用 query(sql, params) 再 execute()");
        return execute(pendingSql, pendingParams);
    }

    /** 绑定外部连接(事务内复用同一 Connection,close 时不关此连接)。 */
    public SqlBuilder withConnection(Connection conn) {
        this.ownedConn = null;
        this.ctx = DSL.using(conn, dialect);
        return this;
    }

    /** 暴露 jOOQ DSLContext(类型安全 DSL 入口,用户直接用 jOOQ 链式 API)。 */
    public DSLContext ctx() {
        if (ctx != null) return ctx;
        try {
            ownedConn = ds.getConnection();
            ctx = DSL.using(ownedConn, dialect);
            return ctx;
        } catch (SQLException e) {
            throw new RuntimeException("获取连接失败:" + e.getMessage(), e);
        }
    }

    /** 直接执行原生 SQL(参数化,防注入)。 */
    public Result<Record> fetch(String sql, Object... params) {
        return ctx().fetch(sql, params);
    }

    /** 执行 DML(INSERT/UPDATE/DELETE),返回影响行数。 */
    public int execute(String sql, Object... params) {
        return ctx().execute(sql, params);
    }

    @Override public void close() {
        // 只关闭本类自取的 Connection(外部传入的不关)
        if (ownedConn != null) {
            try { ownedConn.close(); } catch (SQLException ignored) {}
        }
    }
}
