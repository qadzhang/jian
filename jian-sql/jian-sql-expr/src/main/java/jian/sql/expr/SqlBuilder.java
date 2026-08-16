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
 * Result<Record> r = qb.ctx().selectFrom("users").where("age &gt; ?", 18).orderBy(DSL.field("age").desc()).fetch();
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

    /**
     * @param ds      DataSource 数据源,约束:不能为 null(用于借连接)
     * @param dialect Dialect jian 方言枚举,取值范围:POSTGRESQL/MYSQL/DORIS/H2/SQLITE/ORACLE/ACCESS/DEFAULT 之一
     * @return SqlBuilder 已绑定数据源与方言的构建器实例(尚未借连接)
     */
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
        /**
         * @return SQLDialect 对应的 jOOQ 方言枚举
         */
        public SQLDialect jooq() { return jooq; }
    }

    private String pendingSql;            // query() 设置的待执行 SQL
    private Object[] pendingParams = new Object[0];

    /**
     * 预置原生 SQL(参数化),后续 {@link #fetch()} / {@link #execute()} 执行。
     * 支持 engine.sql("...", params) 链式入口(规范 05 §2.2)。
     *
     * @param sql    String 原生 SQL 模板,约束:不能为 null;值用 ? 占位防注入
     * @param params Object... 绑定到 ? 的参数值,顺序与 SQL 中的 ? 一致;可为 null(按无参处理)
     * @return SqlBuilder 自身(链式)
     */
    public SqlBuilder query(String sql, Object... params) {
        this.pendingSql = sql;
        this.pendingParams = params == null ? new Object[0] : params;
        return this;
    }

    /**
     * 执行 {@link #query} 预置的 SELECT,返回结果。
     * <p>完成后自动归还自有连接,链式 {@code engine.sql(...).fetch()} 不泄漏池连接。
     */
    public Result<Record> fetch() {
        if (pendingSql == null) throw new IllegalStateException("先调用 query(sql, params) 再 fetch()");
        return fetch(pendingSql, pendingParams);
    }

    /** 执行 {@link #query} 预置的 DML,返回影响行数。完成后自动归还自有连接(同 {@link #fetch()})。 */
    public int execute() {
        if (pendingSql == null) throw new IllegalStateException("先调用 query(sql, params) 再 execute()");
        return execute(pendingSql, pendingParams);
    }

    /**
     * 绑定外部连接(事务内复用同一 Connection,close 时不关此连接)。
     *
     * @param conn Connection 外部传入的连接,约束:不能为 null;close() 时不会关闭它
     * @return SqlBuilder 自身(链式)
     */
    public SqlBuilder withConnection(Connection conn) {
        this.ownedConn = null;
        this.ctx = DSL.using(conn, dialect);
        return this;
    }

    /**
     * 暴露 jOOQ DSLContext(类型安全 DSL 入口,用户直接用 jOOQ 链式 API)。
     *
     * @return DSLContext 已绑定连接与方言的 jOOQ 上下文;首次调用时按需借连接
     */
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

    /**
     * 直接执行原生 SQL(参数化,防注入)。
     * <p>因为链式 {@code engine.sql(...).fetch()} 不显式 close 时,HikariCP 无 finalizer
     * 兜底,每次调用净泄漏一个池连接(默认池 10,第 11 次即等待超时),
     * 所以执行完成(含失败路径,finally)后自动归还本类自取的连接
     * (ownedConn 置 null 并 close,幂等);外部经 {@link #withConnection} 绑定的连接不动。
     *
     * @param sql    String SELECT SQL 模板,约束:不能为 null;值用 ? 占位
     * @param params Object... 绑定到 ? 的参数值,顺序与 SQL 中的 ? 一致
     * @return Result<Record> 查询结果集
     */
    public Result<Record> fetch(String sql, Object... params) {
        try {
            return ctx().fetch(sql, params);
        } finally {
            releaseOwnedConnection("fetch");
        }
    }

    /**
     * 执行 DML(INSERT/UPDATE/DELETE),返回影响行数。
     * <p>与 {@link #fetch(String, Object...)} 同款,完成(含失败路径)后自动归还自有连接。
     *
     * @param sql    String DML SQL 模板,约束:不能为 null;值用 ? 占位
     * @param params Object... 绑定到 ? 的参数值,顺序与 SQL 中的 ? 一致
     * @return int 受影响行数
     */
    public int execute(String sql, Object... params) {
        try {
            return ctx().execute(sql, params);
        } finally {
            releaseOwnedConnection("execute");
        }
    }

    // ┌─ What : 归还本类自取的连接(防连接泄漏的核心)
    // │  Why  : ctx() 首次调用会 ds.getConnection() 借一个池连接;fetch/execute 完成后若不归还,
    // │         链式用法(不显式 close)每次净泄漏一个连接;HikariCP 无 finalizer 兜底。
    // │  Who  : fetch(String,...)/execute(String,...) 的 finally + close()
    // │  When : 每次执行完成后(成功/失败都走);close() 兼容复用
    // │  Where: jian-sql-expr/SqlBuilder.java
    // │  How  : 数据走向:ownedConn(自取连接)→ close() 归还池 → 置 null → ctx 一并置 null
    // │           (下次 ctx() 重新借新连接,行为等价于一次性构建器)。
    // │         关键变量变化:ownedConn(null→连接→null)、ctx(null→DSL→null,幂等可重复调)。
    // │         逻辑路线:
    // │           路径 A(ownedConn==null,外部绑定或已归还)→ 直接返回,不动外部连接;
    // │           路径 B(close 抛 SQLException)→ 不抛出(防掩盖主异常),stderr 留痕,ownedConn 仍置 null;
    // │           路径 C(正常)→ close 归还 + 双置 null。
    private void releaseOwnedConnection(String op) {
        if (ownedConn == null) return;
        try {
            ownedConn.close();
        } catch (SQLException e) {
            // 不抛出(finally 里抛异常会掩盖主异常),但留痕:错误码 + SQLState + 消息,够运维定位
            System.err.println("[jian-sql] SqlBuilder." + op + " 归还连接失败(可能已回收):"
                    + " errorCode=" + e.getErrorCode()
                    + " sqlState=" + e.getSQLState()
                    + " msg=" + e.getMessage());
        } finally {
            ownedConn = null;
            ctx = null;   // 旧 ctx 绑着已关连接,置 null 防误复用(下次 ctx() 重借)
        }
    }

    @Override public void close() {
        // 只关闭本类自取的 Connection(外部传入的不关)。
        // 与 fetch/execute 的自动归还共用同一幂等实现(ownedConn 已是 null 时为空操作)。
        // close 失败通常不致命(连接可能已被底层池回收),但**不静吞**:
        // 至少打到 stderr 让运维可见(避免连接池耗尽时无声无息)。
        releaseOwnedConnection("close");
    }
}
