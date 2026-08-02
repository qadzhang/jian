package jian.sql.engine;

// ┌─ What : JianSqlException —— jian-sql 统一运行时异常(对齐规范 05 §4)
// │  Why  : 规范 05 §4 要求连接失败包装为带 DbType + 脱敏 URL 的友好异常,
// │         而不是把 JDBC 原始异常直接抛给用户(原始异常可能含连接串明文)
// │  Who  : Engine.connect/begin 及 jian-sql-expr/orm 各入口抛出
// │  When : 驱动缺失、连接失败、URL 解析失败等场景
// │  Where: jian-sql-engine/JianSqlException.java
// │  How  : 数据走向:底层 SQLException/驱动异常 → 包装为 JianSqlException(message 含 DbType 与脱敏 URL,不含密码)→ 抛给用户。
// │         逻辑路线:
// │           路径 A(连接失败)→ 包装 SQLException,message 带 dbType + 脱敏 URL;
// │           路径 B(URL 解析失败)→ 包装 IllegalArgumentException,提示合法 URL 格式。
public class JianSqlException extends RuntimeException {

    /** 数据库类型(可为 null)。 */
    private final DbType dbType;

    /** 脱敏后的 JDBC URL(密码已替换为 ***)。 */
    private final String sanitizedUrl;

    public JianSqlException(String message) {
        this(message, null, null, null);
    }

    public JianSqlException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public JianSqlException(String message, DbType dbType, String sanitizedUrl, Throwable cause) {
        super(message, cause);
        this.dbType = dbType;
        this.sanitizedUrl = sanitizedUrl;
    }

    public DbType dbType() { return dbType; }

    public String sanitizedUrl() { return sanitizedUrl; }

    /** 脱敏:把 URL 中密码段(user:pass@ → user:***@)替换掉,防异常信息泄漏凭据。 */
    public static String sanitize(String url) {
        if (url == null) return null;
        int at = url.indexOf('@');
        if (at < 0) return url;
        int colon = url.lastIndexOf(':', at);
        int scheme = url.indexOf("://");
        if (colon > scheme) {
            return url.substring(0, colon + 1) + "***" + url.substring(at);
        }
        return url;
    }
}
