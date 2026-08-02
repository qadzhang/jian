package jian.sql.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

// ┌─ What : Engine —— 数据库连接管理(对齐规范 05 §2.1 / sqlalchemy.create_engine,基于 HikariCP)
// │  Why  : 规范 05;Engine = DbType + EngineConfig + HikariDataSource,提供 connect/begin/close
// │  Who  : 用户经 Engine.create 创建;后续 jian-sql-expr/orm 复用
// │  When : 任何数据库交互
// │  Where: jian-sql-engine/Engine.java
// │  How  : 数据走向:EngineConfig + DbType → 拼 JDBC URL → HikariConfig → HikariDataSource → Connection。
// │         关键变量变化:
// │           - ds:HikariDataSource 单例缓存(Engine 生命周期内复用);
// │           - readOnly:拦截 DROP/DELETE/TRUNCATE(规范 §3.2 安全)。
// │         逻辑路线:
// │           路径 A(connect)→ 借连接,自动提交;
// │           路径 B(begin)→ 借连接 + setAutoCommit(false),try-with-resources 用完回滚/提交;
// │           路径 C(readOnly + 写 SQL)→ 抛 SecurityException;
// │           路径 D(close)→ 关闭整个连接池。
/**
 * 数据库连接管理,对齐 sqlalchemy.create_engine(基于 HikariCP)。
 *
 * <p>用法:
 * <pre>{@code
 * Engine engine = Engine.create(DbType.POSTGRESQL, EngineConfig.fromEnv());
 * try (Connection conn = engine.begin()) {
 *     // 自动提交/回滚
 * }
 *
 * // 或从 SQLAlchemy URL:
 * Engine engine = Engine.fromUrl("postgresql://user:${DB_PASSWORD}@host:5432/db");
 * }</pre>
 */
public final class Engine implements AutoCloseable {

    private final DbType dbType;
    private final EngineConfig config;
    private final HikariDataSource ds;
    private final boolean readOnly;

    private Engine(DbType dbType, EngineConfig config) {
        this.dbType = dbType;
        this.config = config;
        this.readOnly = config.readOnly;
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(buildJdbcUrl(dbType, config));
        hc.setUsername(config.user);
        hc.setPassword(config.password);
        hc.setMaximumPoolSize(config.poolSize);
        // 显式指定 driverClassName;驱动缺失抛友好异常(规范 §4,而不是打警告后让 JDBC 抛难排查的错)
        if (!isDriverAvailable(dbType)) {
            throw new ModuleNotLoadedException(
                    "jian-sql 未找到数据库驱动 " + dbType.driverClassName() + ";"
                            + "请引入 " + dbType.driverHint()
                            + " jar(版本见 doc/00-overview.md §2.3),或将驱动 jar 加入 classpath");
        }
        hc.setDriverClassName(dbType.driverClassName());
        this.ds = new HikariDataSource(hc);
    }

    /** 创建 Engine(对齐 sqlalchemy.create_engine)。 */
    public static Engine create(DbType dbType, EngineConfig config) {
        return new Engine(dbType, config);
    }

    /**
     * 从 SQLAlchemy 风格 URL 创建(对齐规范 §2.1)。
     * URL 形如 "postgresql://user:pass@host:port/db";password 可用 ${ENV_VAR} 占位。
     */
    public static Engine fromUrl(String sqlalchemyUrl) {
        ParsedUrl parsed = parseUrl(sqlalchemyUrl);
        return new Engine(parsed.dbType, parsed.config);
    }

    /** URL 解析结果(不立即建 Engine,便于测试)。 */
    public record ParsedUrl(DbType dbType, EngineConfig config) {}

    /** 仅解析 SQLAlchemy URL,不建 Engine(不触发驱动加载)。 */
    public static ParsedUrl parseUrl(String sqlalchemyUrl) {
        DbType dbType = DbType.fromUrl(sqlalchemyUrl);
        String body = sqlalchemyUrl.substring(sqlalchemyUrl.indexOf("://") + 3);
        String user = "";
        String password = "";
        String hostDb = body;
        // 无 @ 段(未带用户密码)也允许:整段都算 host/db(修复原 substring(0,-1) 崩溃)
        int atIdx = body.indexOf('@');
        if (atIdx >= 0) {
            String userPass = body.substring(0, atIdx);
            hostDb = body.substring(atIdx + 1);
            String[] up = userPass.split(":", 2);
            user = up[0];
            password = expandEnv(up.length > 1 ? up[1] : "");
        }
        String host; int port; String database;
        if (hostDb.contains("/")) {
            String hp = hostDb.substring(0, hostDb.indexOf('/'));
            database = hostDb.substring(hostDb.indexOf('/') + 1);
            if (hp.contains(":")) {
                host = hp.substring(0, hp.indexOf(':'));
                port = Integer.parseInt(hp.substring(hp.indexOf(':') + 1));
            } else { host = hp; port = dbType.defaultPort(); }
        } else {
            host = hostDb; port = dbType.defaultPort(); database = "";
        }
        EngineConfig cfg = EngineConfig.builder()
                .host(host).port(port).user(user).password(password).database(database).build();
        return new ParsedUrl(dbType, cfg);
    }

    /** 展开 ${ENV_VAR} 占位为环境变量值(优先 env,回退系统属性;零本机绑定,规范 §3.2)。 */
    private static String expandEnv(String s) {
        java.util.regex.Matcher m = Pattern.compile("\\$\\{(\\w+)\\}").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = System.getenv(key);
            if (val == null) val = System.getProperty(key);
            m.appendReplacement(sb, val == null ? "" : java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 拼 JDBC URL(按 DbType + 文件型/网络型)。 */
    private static String buildJdbcUrl(DbType dbType, EngineConfig config) {
        // 文件型(SQLite/H2/Access)用 path;网络型用 host:port/database
        switch (dbType) {
            case SQLITE:
            case H2:
            case ACCESS:
                return dbType.jdbcUrl(config.path != null ? config.path : config.database);
            default:
                return dbType.jdbcUrl(config.host, config.port, config.database);
        }
    }

    /** 反射探测驱动是否在 classpath(不强制加载,只返回 true/false)。 */
    private static boolean isDriverAvailable(DbType dbType) {
        try {
            Class.forName(dbType.driverClassName(), false, Engine.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 借连接(自动提交)。 */
    public Connection connect() throws SQLException {
        return ds.getConnection();
    }

    /**
     * 借连接 + 关闭自动提交(对齐 sqlalchemy engine.begin)。
     * <p>语义:用户在 try-with-resources 块内显式 commit();若抛异常,JDBC 语义下未提交自动回滚。
     */
    public Connection begin() throws SQLException {
        Connection conn = ds.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    /** 提交事务。 */
    public void commit(Connection conn) throws SQLException { conn.commit(); }

    /** 回滚事务。 */
    public void rollback(Connection conn) throws SQLException { conn.rollback(); }

    /** 暴露底层 DataSource(供 jian-sql-expr/orm 用)。 */
    public DataSource dataSource() { return ds; }

    /**
     * 类型安全 SQL 构建入口(对齐规范 05 §2.2 engine.dsl())。
     * <pre>{@code
     * Result<Record> r = engine.dsl().ctx().selectFrom("users").fetch();
     * }</pre>
     * 需要 jian-sql-expr jar(本方法所在 jar 已依赖它)。
     */
    public jian.sql.expr.SqlBuilder dsl() {
        return jian.sql.expr.SqlBuilder.create(ds, toExprDialect(dbType));
    }

    /**
     * 原生 SQL 查询入口(对齐规范 05 §2.2 engine.sql("...", params).fetch())。
     * <pre>{@code
     * Result<Record> r = engine.sql("SELECT * FROM users WHERE age > ?", 18).fetch();
     * }</pre>
     * 值一律走 ? 占位符参数化绑定(防注入)。
     */
    public jian.sql.expr.SqlBuilder sql(String sql, Object... params) {
        return dsl().query(sql, params);
    }

    /** DbType → SqlBuilder.Dialect(DORIS 与 MySQL 同协议)。 */
    private static jian.sql.expr.SqlBuilder.Dialect toExprDialect(DbType t) {
        return switch (t) {
            case POSTGRESQL -> jian.sql.expr.SqlBuilder.Dialect.POSTGRESQL;
            case MYSQL, DORIS -> jian.sql.expr.SqlBuilder.Dialect.MYSQL;
            case H2 -> jian.sql.expr.SqlBuilder.Dialect.H2;
            case SQLITE -> jian.sql.expr.SqlBuilder.Dialect.SQLITE;
            case ORACLE -> jian.sql.expr.SqlBuilder.Dialect.ORACLE;
            case ACCESS -> jian.sql.expr.SqlBuilder.Dialect.ACCESS;
        };
    }

    public DbType dbType() { return dbType; }
    public EngineConfig config() { return config; }
    public boolean isReadOnly() { return readOnly; }

    /**
     * 校验 SQL 是否允许在只读模式下执行(规范 §3.2 安全)。
     * 只读模式拦截 DROP/DELETE/TRUNCATE/ALTER/CREATE/GRANT/INSERT/UPDATE。
     *
     * <p>安全:先剥掉 SQL 前导空白、行注释(-- ...)与块注释,再按整词匹配危险关键字,
     * 防 "块注释 + DROP TABLE" 这类绕过。
     */
    public void checkReadOnly(String sql) {
        if (!readOnly) return;
        // 剥前导空白与注释(循环剥,直到开头不再是注释)
        String s = sql;
        boolean changed = true;
        while (changed && !s.isBlank()) {
            changed = false;
            String t = s.stripLeading();
            if (t.startsWith("--")) {
                int nl = t.indexOf('\n');
                s = (nl >= 0 ? t.substring(nl + 1) : "").stripLeading();
                changed = true;
            } else if (t.startsWith("/*")) {
                int end = t.indexOf("*/");
                s = (end >= 0 ? t.substring(end + 2) : "").stripLeading();
                changed = true;
            }
        }
        String upper = s.toUpperCase().stripLeading();
        // 整词匹配:避免 "DROPX" 误拦,也避免 "SELECT drop_col" 漏网(后者以 SELECT 开头,天然放行)
        if (java.util.regex.Pattern.matches("(?s)(DROP|DELETE|TRUNCATE|ALTER|CREATE|GRANT|INSERT|UPDATE)\\b.*", upper)) {
            throw new SecurityException("只读模式禁止写操作:" + sql.substring(0, Math.min(50, sql.length())) + "...");
        }
    }

    @Override public void close() {
        if (ds != null && !ds.isClosed()) ds.close();
    }
}
