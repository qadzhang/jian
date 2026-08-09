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

    /**
     * 创建 Engine(对齐 sqlalchemy.create_engine)。
     *
     * @param dbType DbType 数据库类型枚举,取值范围:POSTGRESQL/MYSQL/H2/SQLITE/ORACLE/DORIS/ACCESS 之一
     * @param config EngineConfig 连接配置(host/port/user/password/database/poolSize/readOnly 等)
     * @return Engine 已初始化连接池的引擎实例
     * @throws ModuleNotLoadedException 当 dbType 对应的 JDBC 驱动不在 classpath 时抛出
     */
    public static Engine create(DbType dbType, EngineConfig config) {
        return new Engine(dbType, config);
    }

    /**
     * 从 SQLAlchemy 风格 URL 创建(对齐规范 §2.1)。
     * URL 形如 "postgresql://user:pass@host:port/db";password 可用 ${ENV_VAR} 占位。
     *
     * @param sqlalchemyUrl String SQLAlchemy 风格 URL,约束:不能为 null;须形如 scheme://[user[:pass]@]host[:port]/db
     * @return Engine 已初始化连接池的引擎实例
     * @throws ModuleNotLoadedException       当驱动缺失时抛出
     * @throws IllegalArgumentException       当 URL 格式非法或 scheme 不支持时抛出
     */
    public static Engine fromUrl(String sqlalchemyUrl) {
        ParsedUrl parsed = parseUrl(sqlalchemyUrl);
        return new Engine(parsed.dbType, parsed.config);
    }

    /**
     * URL 解析结果(不立即建 Engine,便于测试)。
     *
     * @param dbType DbType 数据库类型枚举
     * @param config EngineConfig 解析得到的连接配置
     */
    public record ParsedUrl(DbType dbType, EngineConfig config) {}

    /**
     * 仅解析 SQLAlchemy URL,不建 Engine(不触发驱动加载)。
     *
     * @param sqlalchemyUrl String SQLAlchemy 风格 URL,约束:不能为 null;须形如 scheme://[user[:pass]@]host[:port]/db
     * @return ParsedUrl 解析结果(含 dbType 与 EngineConfig)
     * @throws IllegalArgumentException 当 URL 格式非法或 scheme 不支持时抛出
     */
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

    /**
     * 借连接(自动提交)。
     *
     * @return Connection 从连接池借出的 JDBC 连接(用完须 close 归还)
     * @throws SQLException 当借连接失败时抛出
     */
    public Connection connect() throws SQLException {
        return ds.getConnection();
    }

    /**
     * 借连接 + 关闭自动提交(对齐 sqlalchemy engine.begin)。
     * <p>语义:用户在 try-with-resources 块内显式 commit();若抛异常,JDBC 语义下未提交自动回滚。
     *
     * @return Connection 已关闭自动提交的连接(事务模式)
     * @throws SQLException 当借连接或设置 autoCommit 失败时抛出
     */
    public Connection begin() throws SQLException {
        Connection conn = ds.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * 提交事务。
     *
     * @param conn Connection 事务连接,约束:须为 begin() 借出的连接
     * @throws SQLException 当提交失败时抛出
     */
    public void commit(Connection conn) throws SQLException { conn.commit(); }

    /**
     * 回滚事务。
     *
     * @param conn Connection 事务连接,约束:须为 begin() 借出的连接
     * @throws SQLException 当回滚失败时抛出
     */
    public void rollback(Connection conn) throws SQLException { conn.rollback(); }

    /**
     * 暴露底层 DataSource(供 jian-sql-expr/orm 用)。
     *
     * @return DataSource HikariCP 连接池
     */
    public DataSource dataSource() { return ds; }

    /**
     * 类型安全 SQL 构建入口(对齐规范 05 §2.2 engine.dsl())。
     * <pre>{@code
     * Result<Record> r = engine.dsl().ctx().selectFrom("users").fetch();
     * }</pre>
     * 需要 jian-sql-expr jar(本方法所在 jar 已依赖它)。
     *
     * @return SqlBuilder 类型安全查询构建器(已绑定本引擎的 DataSource 与方言)
     */
    public jian.sql.expr.SqlBuilder dsl() {
        return jian.sql.expr.SqlBuilder.create(ds, toExprDialect(dbType));
    }

    /**
     * 原生 SQL 查询入口(对齐规范 05 §2.2 engine.sql("...", params).fetch())。
     * <pre>{@code
     * Result<Record> r = engine.sql("SELECT * FROM users WHERE age &gt; ?", 18).fetch();
     * }</pre>
     * 值一律走 ? 占位符参数化绑定(防注入)。
     * <p><b>Web 安全修复(2026-08-08)</b>:只读模式(readOnly=true)下,拦截写操作(DROP/DELETE/INSERT/UPDATE 等)。
     * 之前 checkReadOnly 是死代码(写了但没调用);现在 sql() 入口强制调用。
     *
     * @param sql    String SQL 模板,约束:不能为 null;值用 ? 占位;只读模式下拦截 DROP/DELETE/INSERT/UPDATE 等写操作
     * @param params Object... 绑定到 ? 的参数值,顺序与 SQL 中的 ? 一致;可省略
     * @return SqlBuilder 已注入原生 SQL 与参数的构建器
     * @throws SecurityException 当 readOnly=true 且 SQL 为写操作时抛出
     */
    public jian.sql.expr.SqlBuilder sql(String sql, Object... params) {
        checkReadOnly(sql);   // Web 安全:只读模式拦截写操作(2026-08-08 修复死代码)
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

    /**
     * @return DbType 当前引擎的数据库类型
     */
    public DbType dbType() { return dbType; }

    /**
     * @return EngineConfig 当前引擎的连接配置
     */
    public EngineConfig config() { return config; }

    /**
     * @return boolean 是否只读模式(拦截写操作)
     */
    public boolean isReadOnly() { return readOnly; }

    /**
     * 校验 SQL 是否允许在只读模式下执行(规范 §3.2 安全)。
     * 只读模式拦截 DROP/DELETE/TRUNCATE/ALTER/CREATE/GRANT/INSERT/UPDATE。
     *
     * <p>安全:先剥掉 SQL 前导空白、行注释(-- ...)与块注释,再按整词匹配危险关键字,
     * 防 "块注释 + DROP TABLE" 这类绕过。
     *
     * @param sql String 待校验的 SQL,约束:不能为 null
     * @throws SecurityException 当 readOnly=true 且 SQL 为写操作时抛出
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
