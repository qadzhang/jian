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

    /**
     * @param message String 异常消息,约束:可为 null(建议给出可读说明)
     */
    public JianSqlException(String message) {
        this(message, null, null, null);
    }

    /**
     * @param message String 异常消息,约束:可为 null
     * @param cause   Throwable 原始异常,约束:可为 null
     */
    public JianSqlException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * @param message       String 异常消息,约束:可为 null(建议含 DbType 与脱敏 URL)
     * @param dbType        DbType 数据库类型,约束:可为 null(未知时不带)
     * @param sanitizedUrl  String 脱敏后的 JDBC URL(密码已替换),约束:可为 null
     * @param cause         Throwable 原始异常,约束:可为 null
     */
    public JianSqlException(String message, DbType dbType, String sanitizedUrl, Throwable cause) {
        super(message, cause);
        this.dbType = dbType;
        this.sanitizedUrl = sanitizedUrl;
    }

    /**
     * @return DbType 关联的数据库类型,可能为 null
     */
    public DbType dbType() { return dbType; }

    /**
     * @return String 脱敏后的 JDBC URL(密码已替换为 ***),可能为 null
     */
    public String sanitizedUrl() { return sanitizedUrl; }

    /**
     * 脱敏:把 URL 中密码段(user:pass@ → user:***@)替换掉,防异常信息泄漏凭据。
     *
     * <p>因为要与 {@code Engine.parseUrl} 的定位口径对齐,所以:
     * ① '@' 取 {@code lastIndexOf('@')}(密码可含 @,host/db 不含,如 user:p@ss@host);
     * ② 密码起点取 userinfo 内<b>第一个</b> ':'(对齐 parseUrl 的 indexOf(':') 切分,
     *   密码可含 ':' 如 p@ss:word,取最后一个 ':' 会把前半段明文残留);
     * ③ 无 "://" 的畸形 URL(如 postgresql:user:pass@host)也保守脱敏( '@' 前最后一个 ':'
     *   且不紧贴 '@'),而非原样放行 —— 畸形 URL 同样可能带密码;
     * ④ Oracle thin(jdbc:oracle:thin:@host:1521:db)的 ':' 紧贴 '@'(无密码段),跳过不误替换。
     *
     * @param url String 原始 JDBC URL,约束:可为 null(返回 null)
     * @return String 脱敏后的 URL(密码段替换为 ***);无 '@'、无密码段或为 null 时原样返回
     */
    public static String sanitize(String url) {
        if (url == null) return null;
        // 逻辑路线:
        //   路径 A(无 '@')→ 无 userinfo 段 → 原样返回;
        //   路径 B(有 "://" 且 '@' 在其后)→ 密码 = userinfo 内第一个 ':' 到 lastIndexOf('@')
        //             整段替换 ***(密码可含 ':' 与 '@');
        //   路径 C(userinfo 内无 ':' 或 ':' 在 '@' 之后)→ 只有 user 无密码 → 原样返回;
        //   路径 D(无 "://" 的畸形 URL)→ '@' 前最后一个 ':' 且不紧贴 '@'(≥1 字符密码)
        //             才替换 —— Oracle thin 的 ":@" 紧贴形态跳过,不误替换。
        int at = url.lastIndexOf('@');
        if (at < 0) return url;
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            if (at <= scheme + 2) return url;   // '@' 不在 userinfo 位置,异常形态,不动
            int colon = url.indexOf(':', scheme + 3);
            if (colon > 0 && colon < at) {
                return url.substring(0, colon + 1) + "***" + url.substring(at);
            }
            return url;
        }
        int colon = url.lastIndexOf(':', at);
        if (colon > 0 && colon < at - 1) {
            return url.substring(0, colon + 1) + "***" + url.substring(at);
        }
        return url;
    }
}
