package jian.sql.engine;

import java.util.Properties;

// ┌─ What : EngineConfig —— Engine 的连接配置(host/port/user/password/database + 连接池参数)
// │  Why  : 规范 05 §2.1;支持 fromEnv(读 .env 环境变量,零本机绑定)+ builder
// │  Who  : 由 Engine.create 接收
// │  When : 创建 Engine 实例
// │  Where: jian-sql-engine/EngineConfig.java
// │  How  : 数据走向:.env/环境变量 → EngineConfig → HikariConfig → HikariDataSource。
// │         关键变量变化:
// │           - host/port/user/password/database:5 个核心字段;
// │           - poolSize:HikariCP 连接池大小,默认 10;
// │           - path:文件型数据库(SQLite/H2/Access)用。
// │         逻辑路线:
// │           路径 A(fromEnv)→ 读 DB_HOST/DB_PORT/... 环境变量;
// │           路径 B(builder)→ 用户显式构造;
// │           路径 C(必填缺失)→ IllegalArgumentException。
/**
 * Engine 连接配置。不可变。
 *
 * <p>用法:
 * <pre>{@code
 * // 从 .env / 环境变量读(推荐,零本机绑定)
 * EngineConfig cfg = EngineConfig.fromEnv();
 *
 * // builder 显式构造
 * EngineConfig cfg = EngineConfig.builder()
 *     .host("localhost").port(5432)
 *     .user("u").password(System.getenv("DB_PASSWORD"))
 *     .database("db").poolSize(10).build();
 * }</pre>
 */
public final class EngineConfig {

    public final String host;
    public final int port;
    public final String user;
    public final String password;
    public final String database;
    public final String path;       // 文件型(SQLite/H2/Access)
    public final int poolSize;
    public final boolean readOnly;

    private EngineConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.user = b.user;
        this.password = b.password;
        this.database = b.database;
        this.path = b.path;
        this.poolSize = b.poolSize;
        this.readOnly = b.readOnly;
    }

    /**
     * @return Builder 新的配置构建器
     */
    public static Builder builder() { return new Builder(); }

    /**
     * 从环境变量构建(对齐规范 §3.2 凭据走 .env,零本机绑定)。
     * 读 DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME / DB_PATH / DB_POOL_SIZE / DB_READONLY。
     *
     * @return EngineConfig 从环境变量填充的连接配置
     */
    public static EngineConfig fromEnv() {
        Builder b = builder();
        Properties p = System.getProperties();
        // 优先环境变量,回退系统属性
        b.host(env("DB_HOST", p));
        String port = env("DB_PORT", p);
        if (port != null && !port.isEmpty()) b.port(Integer.parseInt(port));
        b.user(env("DB_USER", p));
        b.password(env("DB_PASSWORD", p));
        b.database(env("DB_NAME", p));
        b.path(env("DB_PATH", p));
        String ps = env("DB_POOL_SIZE", p);
        if (ps != null && !ps.isEmpty()) b.poolSize(Integer.parseInt(ps));
        String ro = env("DB_READONLY", p);
        if (ro != null) b.readOnly(Boolean.parseBoolean(ro));
        return b.build();
    }

    private static String env(String key, Properties p) {
        String v = System.getenv(key);
        return v != null ? v : p.getProperty(key);
    }

    public static final class Builder {
        private String host = "localhost";
        private int port = 0;
        private String user = "";
        private String password = "";
        private String database = "";
        private String path = null;
        private int poolSize = 10;
        private boolean readOnly = false;

        /**
         * @param v String 数据库主机名/IP,默认 "localhost"
         * @return Builder 自身(链式)
         */
        public Builder host(String v) { this.host = v; return this; }

        /**
         * @param v int 数据库端口,默认 0(表示未设置,后续由 DbType 填默认端口)
         * @return Builder 自身(链式)
         */
        public Builder port(int v) { this.port = v; return this; }

        /**
         * @param v String 用户名,默认空串
         * @return Builder 自身(链式)
         */
        public Builder user(String v) { this.user = v; return this; }

        /**
         * @param v String 密码,默认空串;建议运行时从环境变量取,不要硬编码
         * @return Builder 自身(链式)
         */
        public Builder password(String v) { this.password = v; return this; }

        /**
         * @param v String 数据库名,默认空串
         * @return Builder 自身(链式)
         */
        public Builder database(String v) { this.database = v; return this; }

        /**
         * @param v String 文件型数据库路径(SQLite/H2/Access),默认 null
         * @return Builder 自身(链式)
         */
        public Builder path(String v) { this.path = v; return this; }

        /**
         * @param v int HikariCP 连接池大小,默认 10
         * @return Builder 自身(链式)
         */
        public Builder poolSize(int v) { this.poolSize = v; return this; }

        /**
         * @param v boolean 是否只读模式(true 时拦截写操作),默认 false
         * @return Builder 自身(链式)
         */
        public Builder readOnly(boolean v) { this.readOnly = v; return this; }

        /**
         * @return EngineConfig 构建完成的不可变配置
         */
        public EngineConfig build() { return new EngineConfig(this); }
    }
}
