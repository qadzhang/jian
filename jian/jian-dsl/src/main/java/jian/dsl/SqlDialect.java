package jian.dsl;

// ┌─ What : SqlDialect —— L3 SQL 方言变量(对齐规范 07 §2.4 Oracle/PG/MySQL 三方言)
// │  Why  : 规范 §2.4;分页/空值/日期等方言差异通过此变量影响默认行为
// │  Who  : 由 Dsl.sql(df, sql, dialect) 接收
// │  When : L3 SQL 执行
/**
 * L3 SQL 方言变量(规范 §2.4)。
 *
 * <p>三方言:Oracle(基线)/ PostgreSQL / MySQL。三者主要语法(分页/空值/日期/字符串连接)
 * jian-dsl L3 都认(visitor 层归一化处理),本枚举仅影响默认行为(如未加引号标识符大小写、空值函数优先级)。
 */
public enum SqlDialect {
    /** Oracle 基线:标识符大小写敏感;ROWNUM/FETCH FIRST 分页;NVL 空值;|| 连接。 */
    ORACLE,
    /** PostgreSQL:LIMIT/FETCH FIRST 分页;COALESCE 空值;|| 连接。 */
    POSTGRESQL,
    /** MySQL:LIMIT 分页;IFNULL/COALESCE;CONCAT 连接;标识符默认不敏感。 */
    MYSQL,
    /** 通用默认(等同 ORACLE 基线)。 */
    DEFAULT;

    /**
     * 从环境变量 JIAN_SQL_DIALECT 读(对齐规范 §2.4 方式 C);默认 DEFAULT。
     *
     * @return SqlDialect 环境变量/系统属性解析得到的方言;未配置时返回 DEFAULT
     * @throws IllegalArgumentException 环境变量值无法匹配任一枚举常量时抛出
     */
    public static SqlDialect fromEnv() {
        String v = System.getenv("JIAN_SQL_DIALECT");
        if (v == null) v = System.getProperty("jian.sql.dialect");
        if (v == null) return DEFAULT;
        return valueOf(v.toUpperCase());
    }

    /**
     * 标识符是否大小写敏感。
     * 说明:本方法当前未被 L3 解析器接线 —— SqlEngine 的列名匹配
     * 走 df.columnIndex 精确匹配(恒大小写敏感);MySQL 用户期望不区分大小写时,
     * 请确保 SQL 列名与实际列名书写一致,v2 接线计划见 doc/07 分册。
     *
     * @return boolean true 大小写敏感(ORACLE/POSTGRESQL/DEFAULT),false 不敏感(MYSQL)
     */
    public boolean caseSensitive() {
        return this != MYSQL;
    }
}
