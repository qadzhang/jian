package jian.sql.engine;

// ┌─ What : DbType —— 7 种数据库枚举(对齐规范 05 §3.1)
// │  Why  : Engine 适配 7 库,每个 DbType 持有 jdbcUrlPrefix/defaultPort/driverClassName
// │  Who  : 由 Engine.create/EngineConfig 持有;反射探测驱动时用 driverClassName
// │  When : 创建 Engine / 探测驱动
// │  Where: jian-sql-engine/DbType.java
// │  How  : 关键变量变化:每个枚举值持有 4 个属性(prefix/port/driver/dummySql 用于探测)。
// │         逻辑路线:
// │           路径 A(反射探测)→ Class.forName(driverClassName) 试加载;
// │           路径 B(找不到)→ 不强制失败,等真正建连接时由 JDBC 抛;
// │           路径 C(fromUrl)→ 从 SQLAlchemy URL 解析 DbType。
/**
 * 支持的 7 种数据库枚举。
 *
 * <p><b>driverClassName 只用于反射探测驱动是否存在</b>(规范 §3.1),不强制加载。
 * 真正建连接由 JDBC DriverManager 完成(驱动 jar 由用户按需引)。
 */
public enum DbType {
    POSTGRESQL("jdbc:postgresql://%s:%s/%s", 5432, "org.postgresql.Driver"),
    MYSQL("jdbc:mysql://%s:%s/%s", 3306, "com.mysql.cj.jdbc.Driver"),
    DORIS("jdbc:mysql://%s:%s/%s", 9030, "com.mysql.cj.jdbc.Driver"),  // Doris 用 MySQL 协议
    SQLITE("jdbc:sqlite:%s", 0, "org.sqlite.JDBC"),
    H2("jdbc:h2:%s", 0, "org.h2.Driver"),
    ORACLE("jdbc:oracle:thin:@%s:%s:%s", 1521, "oracle.jdbc.OracleDriver"),
    ACCESS("jdbc:ucanaccess://%s", 0, "net.sf.ucanaccess.jdbc.UcanaccessDriver");

    private final String urlPattern;
    private final int defaultPort;
    private final String driverClassName;

    DbType(String urlPattern, int defaultPort, String driverClassName) {
        this.urlPattern = urlPattern;
        this.defaultPort = defaultPort;
        this.driverClassName = driverClassName;
    }

    /**
     * @return String JDBC URL 模板(含 %s 占位符)
     */
    public String urlPattern() { return urlPattern; }

    /**
     * @return int 默认端口(文件型数据库为 0)
     */
    public int defaultPort() { return defaultPort; }

    /**
     * @return String JDBC 驱动全限定类名(用于反射探测)
     */
    public String driverClassName() { return driverClassName; }

    /**
     * 驱动缺失时的安装提示(maven 坐标;版本统一见 doc/00-overview.md §2.3,不在此写死)。
     *
     * @return String maven 坐标字符串(groupId:artifactId)
     */
    public String driverHint() {
        return switch (this) {
            case POSTGRESQL -> "org.postgresql:postgresql";
            case MYSQL, DORIS -> "com.mysql:mysql-connector-j";
            case SQLITE -> "org.xerial:sqlite-jdbc";
            case H2 -> "com.h2database:h2";
            case ORACLE -> "com.oracle.database.jdbc:ojdbc8";
            case ACCESS -> "net.sf.ucanaccess:ucanaccess";
        };
    }

    /**
     * 拼 JDBC URL(按 host/port/database 三段,适用 PG/MySQL/Doris/Oracle)。
     *
     * @param host     String 主机名或 IP,约束:不能为 null
     * @param port     int 端口;port &lt;= 0 时回退到 defaultPort
     * @param database String 数据库名,约束:可为空串
     * @return String 拼好的 JDBC URL
     */
    public String jdbcUrl(String host, int port, String database) {
        if (port <= 0) port = defaultPort;
        return String.format(urlPattern, host, port, database);
    }

    /**
     * 拼 JDBC URL(单段,适用 SQLite/H2/Access 文件型)。
     *
     * @param path String 文件路径或内存库标识(如 /data/app.db 或 mem:test),约束:不能为 null
     * @return String 拼好的 JDBC URL
     */
    public String jdbcUrl(String path) {
        return String.format(urlPattern, path);
    }

    /**
     * 从 SQLAlchemy 风格 URL 解析 DbType(如 "postgresql://user:pass@host/db" → POSTGRESQL)。
     *
     * @param sqlalchemyUrl String SQLAlchemy 风格 URL,约束:不能为 null;前缀须为 postgresql/mysql/doris/sqlite/h2/oracle/access 之一
     * @return DbType 解析出的数据库类型枚举
     * @throws IllegalArgumentException 当 URL scheme 无法识别时抛出
     */
    public static DbType fromUrl(String sqlalchemyUrl) {
        String lower = sqlalchemyUrl.toLowerCase();
        if (lower.startsWith("postgresql:") || lower.startsWith("postgres:")) return POSTGRESQL;
        if (lower.startsWith("mysql:")) return MYSQL;
        if (lower.startsWith("doris:")) return DORIS;
        if (lower.startsWith("sqlite:")) return SQLITE;
        if (lower.startsWith("h2:")) return H2;
        if (lower.startsWith("oracle:")) return ORACLE;
        if (lower.startsWith("access:") || lower.startsWith("ucanaccess:")) return ACCESS;
        throw new IllegalArgumentException("无法识别的数据库 URL:" + sqlalchemyUrl);
    }
}
