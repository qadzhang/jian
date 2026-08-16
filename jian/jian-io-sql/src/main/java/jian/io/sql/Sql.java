package jian.io.sql;

import jian.core.DataFrame;
import jian.core.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Sql —— 7 数据库通用读写(对齐 pandas.read_sql / to_sql,基于 JDBC)
// │  Why  : 规范 02 §3.6;一套代码适配 PG/MySQL/Doris/SQLite/H2/Oracle/Access,反射探测驱动
// │  Who  : 用户经 Jian.readSql 或 Sql.read 调用
// │  When : 数据库读写
// │  Where: jian-io-sql/Sql.java
// │  How  : 数据走向:
// │           读:Connection → PreparedStatement → ResultSet → ResultSetMetaData 列名+类型 → Object[][] + 推断 → DataFrame;
// │           写:DataFrame → CREATE TABLE(按 dtype→SQL 列类型)→ PreparedStatement 批量 INSERT。
// │         关键变量变化:
// │           - JDBC 类型 → jian DType 的映射表;
// │           - batchSize:批量插入,默认 1000。
// │         逻辑路线:
// │           路径 A(readSqlQuery)→ SQL 查询 → ResultSet → DataFrame;
// │           路径 B(readSqlTable)→ SELECT * FROM table → 同上;
// │           路径 C(toSql)→ CREATE + 批量 INSERT;
// │           路径 D(驱动未引)→ ClassNotFoundException(由 JDBC 抛,带中文提示)。
/**
 * 7 数据库通用读写,对齐 pandas.read_sql / to_sql(基于 JDBC)。
 *
 * <p>支持数据库:PostgreSQL / MySQL / Doris / SQLite / H2 / Oracle / Access(用户按需引对应驱动 jar)。
 *
 * <p>用法:
 * <pre>{@code
 * try (Connection conn = DriverManager.getConnection(url, user, pass)) {
 *     DataFrame df = Sql.readQuery(conn, "SELECT * FROM users WHERE age > ?", 18);
 *     DataFrame all = Sql.readTable(conn, "users");
 *     Sql.write(df, conn, "users", Sql.Mode.CREATE_OR_REPLACE);
 * }
 * }</pre>
 */
public final class Sql {

    private Sql() {}

    /** 写出模式(对齐规范 02 §1.3)。 */
    public enum Mode {
        OVERWRITE,             // DROP + CREATE + INSERT
        APPEND,                // 仅 INSERT(表须已存在)
        CREATE_OR_REPLACE,     // DROP IF EXISTS + CREATE + INSERT
        FAIL_IF_EXISTS         // 表存在则抛
    }

    // ======================== 读 ========================

    /**
     * 执行 SQL 查询,返回 DataFrame(对齐 pandas.read_sql_query)。
     * @param conn   java.sql.Connection 数据库连接,非 null
     * @param sql    String SQL 查询(支持 ? 占位符),非 null
     * @param params Object... 占位符参数,按顺序对应 ?
     * @return DataFrame 查询结果;JDBC 特殊类型(Clob/Blob/BigDecimal/Date/Timestamp)自动规范化
     * @throws SQLException SQL 执行异常
     */
    public static DataFrame readQuery(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // fetchSize hint(默认无 hint 时 MySQL 全量缓冲易 OOM)。
            // 注意:DataFrame 是全内存模型(对齐 pandas),fetchSize 只降低驱动侧缓冲峰值,
            // 不改变最终整表驻留内存的固有边界(大表请分页/流式处理,见 doc/02 §9.5)。
            try { ps.setFetchSize(1000); } catch (SQLException ignore) { /* 驱动不支持 hint 则忽略 */ }
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return resultSetToDataFrame(rs);
            }
        }
    }

    /**
     * 读整张表(对齐 pandas.read_sql_table)。
     * @param conn  java.sql.Connection,非 null
     * @param table String 表名,非 null。**只允许 `[A-Za-z_][A-Za-z0-9_.]*`
     *             白名单字符**(防 SQL 注入,见 AGENTS.md §3.7「安全规范」);schema.table
     *             的点号允许;含其它字符(如分号、空格、引号)直接抛 IAE。
     * @return DataFrame 整表数据
     * @throws SQLException SQL 异常
     * @throws IllegalArgumentException 表名非法(含注入风险字符)
     */
    public static DataFrame readTable(Connection conn, String table) throws SQLException {
        // SQL 注入防护:JDBC PreparedStatement 不支持表名占位符(只支持值占位符),
        // 所以表名只能用「白名单正则」校验(见 assertSafeTableName)。
        // 不主动加 quote:不同数据库对 quoted identifier 的大小写敏感性不同
        // (H2/Oracle 带 " 后变大小写敏感,与无 quote 建表大写不一致),保留默认折叠行为更稳。
        assertSafeTableName(table);
        return readQuery(conn, "SELECT * FROM " + table);
    }

    // ┌─ What : SQL 标识符白名单校验(表名/列名)
    // │  Why  : 因为 write/createTable/insertBatch 要把用户传入的表名/列名拼接进
    // │         DROP TABLE / CREATE TABLE / INSERT INTO,而标识符没有合法的"转义形式"
    // │         (不像字符串字面量),所以用白名单校验防 SQL 注入 —— 比转义更安全、
    // │         更简单,Java API 对入参有完全控制权。
    // │  Who  : readTable / write / createTable / insertBatch 所有拼接标识符的入口
    // │  When : 任何表名/列名进入 SQL 拼接前
    // │  Where: jian-io-sql/Sql.java
    // │  How  : 关键变量变化:table(原始入参)→ 正则匹配 → 合法则原样放行 / 非法则抛 IAE。
    // │         逻辑路线(两条路径):
    // │           路径 A(匹配白名单)→ 返回,继续拼接 SQL;
    // │           路径 B(null 或不匹配)→ 抛 IllegalArgumentException(带"非法表名/列名"提示)。
    // │         数据走向:Java 入参(table/colName)→ assertSafe* → 拼接进 DROP/CREATE/INSERT 语句。
    private static final java.util.regex.Pattern TABLE_NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final java.util.regex.Pattern COLUMN_NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 表名白名单校验:只允许 [A-Za-z_][A-Za-z0-9_.]*(含 schema.table 的点号),
     * 排除分号、引号、空格、-- 等所有 SQL 注入元字符。
     * @param table String 表名,非 null
     * @throws IllegalArgumentException 表名非法(含注入风险字符)
     */
    private static void assertSafeTableName(String table) {
        if (table == null || !TABLE_NAME_PATTERN.matcher(table).matches()) {
            throw new IllegalArgumentException("非法表名(只允许 [A-Za-z_][A-Za-z0-9_.]*): " + table);
        }
    }

    /**
     * 列名白名单校验:只允许 [A-Za-z_][A-Za-z0-9_]*,
     * 排除分号、引号、空格、-- 等所有 SQL 注入元字符。
     * @param col String 列名,非 null
     * @throws IllegalArgumentException 列名非法(含注入风险字符)
     */
    private static void assertSafeColumnName(String col) {
        if (col == null || !COLUMN_NAME_PATTERN.matcher(col).matches()) {
            // 报错教学化 —— 因为中文等 Unicode 列名不加 quote 在多数数据库本就非法
            // (需 quoted identifier,v2 功能),所以拒绝是安全正确行为;提示先 rename
            throw new IllegalArgumentException("非法列名(只允许 [A-Za-z_][A-Za-z0-9_]*,排除 ; ' -- 空格等注入元字符): "
                    + col + ";中文列名请先 df.renameColumns(Map.of(旧名, ascii名)) 改为 ASCII 再写入(quoted identifier 为 v2 规划)");
        }
    }

    /**
     * ResultSet → DataFrame(列名 + 类型从 ResultSetMetaData 推断)。
     *
     * <p><b>JDBC 类型规范化</b>:不同数据库的 getObject 返回类型不统一(参考
     * <a href="https://download.java.net/java/early_access/loom/docs/api/java.sql/java/sql/ResultSet.html">JDK ResultSet 官方规范</a>):
     * <ul>
     *   <li>CLOB(H2/Oracle/PG 大文本)→ java.sql.Clob,需转 String</li>
     *   <li>BLOB(二进制)→ java.sql.Blob,需转 byte[]</li>
     *   <li>DECIMAL/NUMERIC(Oracle/PG 精确数值)→ java.math.BigDecimal,需转 Double</li>
     *   <li>DATE → java.sql.Date,需转 LocalDate</li>
     *   <li>TIMESTAMP → java.sql.Timestamp,需转 LocalDateTime</li>
     * </ul>
     * 不规范化会导致 jian 的 Schema.infer 把这些 JDBC 特殊对象识别为 OBJECT 列,
     * 后续 getStringColumn/getDoubleColumn 抛 ClassCastException。
     */
    private static DataFrame resultSetToDataFrame(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<String> names = new ArrayList<>();
        for (int c = 1; c <= cols; c++) names.add(meta.getColumnLabel(c));

        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[cols];
            for (int c = 1; c <= cols; c++) {
                Object v = rs.getObject(c);
                row[c - 1] = rs.wasNull() ? null : normalizeJdbcObject(v);
            }
            rows.add(row);
        }
        Object[][] data = rows.toArray(new Object[0][]);
        return DataFrame.of(Schema.infer(names, data), data);
    }

    /**
     * 把 JDBC 特殊类型(java.sql.Clob/Blob/Date/Timestamp/BigDecimal)规范化为 jian 期望的 Java 标准类型。
     * 参考 <a href="https://download.java.net/java/early_access/loom/docs/api/java.sql/java/sql/ResultSet.html">JDBC ResultSet 规范</a>的 getObject 映射表。
     *
     * @param v ResultSet.getObject 返回的原始对象(可能为 null)
     * @return 规范化后的对象(String/Long/Double/Boolean/LocalDate/LocalDateTime/byte[]/null);
     *         不在已知特殊类型内的原样返回(交给 Schema.infer 处理)
     */
    private static Object normalizeJdbcObject(Object v) {
        if (v == null) return null;
        // CLOB → String(H2/Oracle/PG 大文本返回 java.sql.Clob)
        if (v instanceof java.sql.Clob clob) {
            try {
                long len = clob.length();
                return len > Integer.MAX_VALUE ? clob.getSubString(1, Integer.MAX_VALUE) : clob.getSubString(1, (int) len);
            } catch (SQLException e) {
                // 与 SqlBridge.normalizeJdbcObject 同步(两处同根因,须同步改)—— Clob 失败
                // 统一返 null(缺失),不再 toString 出驱动内部对象字符串(两处同根因,须同步改)
                return null;
            }
        }
        // BLOB → byte[](二进制大对象)
        if (v instanceof java.sql.Blob blob) {
            try {
                long len = blob.length();
                return blob.getBytes(1, len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) len);
            } catch (SQLException e) {
                return null;
            }
        }
        // BigDecimal → Double(Oracle NUMERIC/PG DECIMAL 返回 BigDecimal,精确但 jian 用 double)
        if (v instanceof java.math.BigDecimal bd) {
            return bd.doubleValue();
        }
        // java.sql.Date → LocalDate(jian DATE 列)
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        // java.sql.Timestamp → LocalDateTime(jian DATETIME 列)
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        // java.sql.Time → LocalTime(罕见,转 String 兜底)
        if (v instanceof java.sql.Time) {
            return v.toString();
        }
        // java.sql.Array → Object[](SQL 数组,罕见)
        if (v instanceof java.sql.Array arr) {
            try {
                return arr.getArray();
            } catch (SQLException e) {
                return null;
            }
        }
        // 其它(String/Integer/Long/Double/Boolean/Float 等):原样返回,Schema.infer 处理
        return v;
    }

    // ======================== 写 ========================

    /**
     * 把 DataFrame 写入数据库表(对齐 pandas.to_sql)。
     *
     * @param df 数据
     * @param conn JDBC 连接
     * @param table 目标表名
     * @param mode 写出模式
     */
    public static void write(DataFrame df, Connection conn, String table, Mode mode) throws SQLException {
        // SQL 注入防护:DROP/CREATE/INSERT 的表名都来自本参数,先白名单校验
        assertSafeTableName(table);
        try {
            // 表存在检查
            boolean exists = tableExists(conn, table);
            if (exists && mode == Mode.FAIL_IF_EXISTS) {
                throw new SQLException("表 " + table + " 已存在,且模式为 FAIL_IF_EXISTS");
            }
            if (mode == Mode.OVERWRITE || mode == Mode.CREATE_OR_REPLACE) {
                dropTableIfExists(conn, table);
                exists = false;
            }
            if (!exists) {
                createTable(df, conn, table);
            }
            // 批量 INSERT
            insertBatch(df, conn, table, 1000);
        } catch (SQLException e) {
            // 因为 autoCommit=false 时若半程失败直接上抛,已 executeBatch 的批次会
            // 悬挂在事务里,调用方后续误 commit 即落库部分数据,所以失败必须回滚。
            if (!conn.getAutoCommit()) {
                try {
                    conn.rollback();
                } catch (SQLException re) {
                    e.addSuppressed(re);
                }
            }
            // APPEND 模式列宽不足的典型失败附指引
            // (建表时按首个 df 定 VARCHAR 宽度,APPEND 写更长数据触发驱动原生错)
            if (mode == Mode.APPEND) {
                throw new SQLException("APPEND 写入失败(已回滚):" + e.getMessage()
                    + ";若因目标表列宽不足(APPEND 不扩列宽),建议 Mode.CREATE_OR_REPLACE 重建表", e.getSQLState(), e.getErrorCode(), e);
            }
            throw e;
        }
        if (!conn.getAutoCommit()) conn.commit();
    }

    /**
     * 默认 CREATE_OR_REPLACE。
     * @param df    DataFrame,非 null
     * @param conn  java.sql.Connection,非 null
     * @param table String 目标表名,非 null
     * @throws SQLException SQL 异常
     */
    public static void write(DataFrame df, Connection conn, String table) throws SQLException {
        write(df, conn, table, Mode.CREATE_OR_REPLACE);
    }

    // ======================== pandas 风格别名(对齐 read_sql / to_sql)========================

    /**
     * 对齐 pandas.read_sql:执行 SQL 查询返回 DataFrame。
     * @param conn   java.sql.Connection,非 null
     * @param sql    String SQL 查询,非 null
     * @param params Object... 占位符参数
     * @return DataFrame 查询结果
     * @throws SQLException SQL 异常
     */
    public static DataFrame readSql(Connection conn, String sql, Object... params) throws SQLException {
        return readQuery(conn, sql, params);
    }

    /**
     * 对齐 pandas.read_sql_table:读整张表。
     * @param conn  java.sql.Connection,非 null
     * @param table String 表名,非 null
     * @return DataFrame 整表数据
     * @throws SQLException SQL 异常
     */
    public static DataFrame readSqlTable(Connection conn, String table) throws SQLException {
        return readTable(conn, table);
    }

    /**
     * 对齐 pandas.to_sql:把 DataFrame 写入数据库表。
     * @param df    DataFrame,非 null
     * @param conn  java.sql.Connection,非 null
     * @param table String 目标表名,非 null
     * @param mode  Sql.Mode 写入模式(OVERWRITE/APPEND/CREATE_OR_REPLACE/FAIL_IF_EXISTS)
     * @throws SQLException SQL 异常
     */
    public static void toSql(DataFrame df, Connection conn, String table, Mode mode) throws SQLException {
        write(df, conn, table, mode);
    }

    /**
     * to_sql 默认 CREATE_OR_REPLACE。
     * @param df    DataFrame,非 null
     * @param conn  java.sql.Connection,非 null
     * @param table String 目标表名,非 null
     * @throws SQLException SQL 异常
     */
    public static void toSql(DataFrame df, Connection conn, String table) throws SQLException {
        write(df, conn, table);
    }

    /**
     * 判断表是否存在(用 meta.getTables,通用,不写写死方言;大小写不敏感)。
     *
     * <p>异常处理策略:**不再静吞所有 SQLException**——只把"表/对象不存在"
     * 这类预期情况(经 {@link #isTableMissing(SQLException)} 判定)视为「不存在」,
     * 其它 SQLException(权限不足、连接断开、SQLState 异常等)**向上抛出**,
     * 避免无权限错误被静默降级(参考 AGENTS.md §3.7.1 错误处理红线)。
     *
     * <p>因为 JDBC pattern 里 `_` 匹配任意单字符、`%` 匹配任意串(候选表名直接作
     * tableNamePattern 传入会误匹配,如 H2 建表 DATAAX 后 pattern "data_x" 误命中,
     * 导致 FAIL_IF_EXISTS 误抛"表已存在"、APPEND 误判存在),所以候选表名不作为
     * tableNamePattern 传给 getTables,改为 pattern 传 null 拉全表,Java 侧
     * String.equalsIgnoreCase 精确比对(等值比较无通配符语义)。
     */
    private static boolean tableExists(Connection conn, String table) throws SQLException {
        // H2 等数据库建表默认大写,匹配时大小写不敏感(全表拉取 + equalsIgnoreCase,
        // 语义等价且不受 JDBC 通配符干扰)
        // 因为把 "S1.T5" 整串当表名模式时 getTables 匹配不到(表在 schema S1 下),
        // APPEND 会误判"表不存在"→ 走 createTable → "Table already exists" 异常,
        // 所以 schema.table 点号形态拆为两参探测。
        String schemaPart = null, tablePart = table;
        int dot = table.indexOf('.');
        if (dot > 0 && dot < table.length() - 1) {
            schemaPart = table.substring(0, dot);
            tablePart = table.substring(dot + 1);
        }
        String[] schemaCands = schemaPart == null
            ? new String[]{ null }   // 无点号:行为与原实现一致(不传 schemaPattern)
            : new String[]{ schemaPart, schemaPart.toUpperCase(), schemaPart.toLowerCase() };
        for (String sc : schemaCands) {
            // tableNamePattern 传 null(拉全表),Java 侧精确比对,
            // 杜绝用户表名里的 _ / % 被当 JDBC 通配符。
            try (ResultSet rs = conn.getMetaData().getTables(null, sc, null, null)) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null && name.equalsIgnoreCase(tablePart)) return true;
                }
            } catch (SQLException e) {
                if (isTableMissing(e)) continue;   // 仅"表不存在"才跳到下个候选名
                throw e;                            // 其它异常向上抛,不静吞
            }
        }
        return false;
    }

    /**
     * 判定一个 SQLException 是否属于"表/对象不存在"类(可安全视为 false 的预期情况)。
     * 常见 SQLState:42S02/42P01(PG)/42X05(H2)/99999(通用 fallback);
     * 同时兼顾 errorCode(H2 用 42122、SQL Server 用 208、Oracle 用 942)。
     */
    private static boolean isTableMissing(SQLException e) {
        String sqlState = e.getSQLState();
        if (sqlState != null) {
            // SQL 标准 + 主流库的"表/视图不存在" SQLState
            if (sqlState.equals("42S02") || sqlState.equals("42P01")
                    || sqlState.equals("42X05") || sqlState.equals("42Y03")) {
                return true;
            }
        }
        // H2 / SQL Server / Oracle / MySQL 特定 errorCode
        // (MySQL 1146 = Table doesn't exist,SQLState 42S02 已覆盖但双保险)
        int ec = e.getErrorCode();
        return ec == 42122 || ec == 208 || ec == 942 || ec == 1146;
    }

    // ┌─ What : dropTableIfExists —— 按数据库方言 DROP 表(OVERWRITE/CREATE_OR_REPLACE 用)
    // │  Why  : 因为 "DROP TABLE IF EXISTS" 在 Oracle 19c 及以下不支持(ORA-00933),
    // │         无条件拼该语句会让 Oracle 上 CREATE_OR_REPLACE 模式必炸,所以按方言二选一。
    // │  Who  : Sql.write(mode=OVERWRITE/CREATE_OR_REPLACE)
    // │  When : 建表前的旧表清理
    // │  Where: jian-io-sql/Sql.java
    // │  How  : 关键变量变化:productName(getDatabaseProductName)→ isOracle 布尔 → 二选一语句。
    // │         逻辑路线(两条路径):
    // │           路径 A(Oracle)→ 先 tableExists 探测,存在才执行 "DROP TABLE <t>"(无 IF EXISTS 后缀);
    // │           路径 B(其它库)→ 直接 "DROP TABLE IF EXISTS <t>"(表不存在也不报错)。
    // │         数据走向:表名(已过 assertSafeTableName 白名单)→ Statement.execute → 数据库删表。
    private static void dropTableIfExists(Connection conn, String table) throws SQLException {
        boolean isOracle = false;
        try {
            String productName = conn.getMetaData().getDatabaseProductName();
            isOracle = productName != null && productName.equalsIgnoreCase("Oracle");
        } catch (SQLException ignore) { /* 元数据不可得时走通用分支,不阻断删除 */ }
        try (Statement st = conn.createStatement()) {
            if (isOracle) {
                // Oracle 19c 及以下不支持 IF EXISTS → 探测后按需 DROP(23c 起支持,保守取兼容写法)
                if (tableExists(conn, table)) st.execute("DROP TABLE " + table);
            } else {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    /** CREATE TABLE(按 DataFrame dtype → SQL 列类型,带方言探测)。
     *  探测:用 conn.getMetaData().getDatabaseProductName() 判断数据库类型,
     *  按 [PostgreSQL 官方数据类型文档](https://www.postgresql.org/docs/current/datatype.html)
     *  + [SQL Server 数据类型](https://learn.microsoft.com/en-us/sql/t-sql/data-types/data-types-transact-sql)
     *  + [Oracle 数据类型](https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/Data-Types.html)
     *  + [MySQL 数据类型](https://dev.mysql.com/doc/refman/8.3/en/floating-point-types.html)
     *  的官方文档,选每个数据库都接受的安全类型名。
     *
     *  <p><b>STRING 列自适应长度</b>:扫该列实际数据取 maxLen,
     *  ≤ VARCHAR_THRESHOLD 用 VARCHAR(各库都能接受的上限),超过用大文本类型。
     *  阈值 4000 = Oracle VARCHAR2 上限(所有库的公共安全上限)。 */
    private static void createTable(DataFrame df, Connection conn, String table) throws SQLException {
        String productName = conn.getMetaData().getDatabaseProductName();
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        List<String> cols = df.columnNames();
        List<jian.core.DType> dtypes = df.dtypes();
        for (int c = 0; c < cols.size(); c++) {
            if (c > 0) sb.append(", ");
            // SQL 注入防护:列名直接拼入 CREATE TABLE,先白名单校验
            assertSafeColumnName(cols.get(c));
            // STRING 列:扫实际数据取 maxLen,按长度选 VARCHAR(n) 或大文本
            int maxLen = -1;
            if (dtypes.get(c) == jian.core.DType.STRING) {
                maxLen = scanMaxStringLength(df.getColumn(cols.get(c)));
            }
            sb.append(cols.get(c)).append(' ').append(dtypeToSqlType(dtypes.get(c), productName, maxLen));
        }
        sb.append(')');
        try (Statement st = conn.createStatement()) {
            st.execute(sb.toString());
        }
    }

    /** VARCHAR vs 大文本的长度阈值。
     * 取 4000 —— 这是 Oracle VARCHAR2 的硬上限(所有数据库的公共安全上限)。
     * ≤ 4000 用 VARCHAR(n),> 4000 用大文本(TEXT/LONGTEXT/CLOB/VARCHAR(MAX))。
     * 参考:Oracle 4000,SQL Server 8000,MySQL 65535,PG 无上限但建议此阈值。 */
    private static final int VARCHAR_THRESHOLD = 4000;

    /** 扫一列取最大字符串长度(非 null 元素的最大 length)。 */
    private static int scanMaxStringLength(jian.core.Column c) {
        int max = 0;
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;  // 缺失行跳过(不参与长度判断)
            Object v = c.get(i);
            int len = v.toString().length();
            if (len > max) max = len;
        }
        return max;
    }

    /**
     * jian DType → SQL 列类型(按数据库方言适配)。
     *
     * <p><b>跨库兼容性</b>:
     * <table>
     *   <tr><th>jian 类型</th><th>PG</th><th>MySQL</th><th>SQLite</th><th>H2</th><th>SQL Server</th><th>Oracle</th></tr>
     *   <tr><td>INT</td><td>INTEGER ✅</td><td>INT ✅</td><td>INTEGER ✅</td><td>INTEGER ✅</td><td>INT ✅</td><td>INTEGER(→NUMBER) ✅</td></tr>
     *   <tr><td>LONG</td><td>BIGINT ✅</td><td>BIGINT ✅</td><td>INTEGER ✅</td><td>BIGINT ✅</td><td>BIGINT ✅</td><td><b>NUMBER(19)</b>(无 BIGINT)</td></tr>
     *   <tr><td>DOUBLE</td><td>DOUBLE PRECISION ✅</td><td>DOUBLE ✅</td><td>REAL ✅</td><td>DOUBLE PRECISION ✅</td><td><b>FLOAT(53)</b>(无 DOUBLE PRECISION)</td><td>FLOAT(126)(非 IEEE754)</td></tr>
     *   <tr><td>BOOL</td><td>BOOLEAN ✅</td><td>BOOLEAN(→TINYINT) ✅</td><td>INTEGER(0/1) ✅</td><td>BOOLEAN ✅</td><td><b>BIT</b>(无 BOOLEAN)</td><td><b>NUMBER(1)</b>(SQL 层无 BOOLEAN)</td></tr>
     *   <tr><td>STRING(短,≤4000)</td><td>VARCHAR(n) ✅</td><td>VARCHAR(n) ✅</td><td>TEXT ✅</td><td>VARCHAR(n) ✅</td><td>VARCHAR(n) ✅</td><td>VARCHAR2(n) ✅</td></tr>
     *   <tr><td>STRING(长,>4000)</td><td>TEXT ✅</td><td>LONGTEXT ✅</td><td>TEXT ✅</td><td>CLOB ✅</td><td>VARCHAR(MAX) ✅</td><td>CLOB ✅</td></tr>
     *   <tr><td>DATETIME</td><td>TIMESTAMP ✅</td><td>TIMESTAMP ✅</td><td>TEXT ✅</td><td>TIMESTAMP ✅</td><td>DATETIME2(推荐)</td><td>TIMESTAMP ✅</td></tr>
     *   <tr><td>DATE</td><td>DATE ✅</td><td>DATE ✅</td><td>TEXT ✅</td><td>DATE ✅</td><td>DATE ✅</td><td>DATE(<b>含时间!</b>)</td></tr>
     * </table>
     *
     * <p><b>STRING 自适应规则</b>:扫该列实际数据取 maxLen。
     * maxLen ≤ {@value #VARCHAR_THRESHOLD} 用 VARCHAR(maxLen)(向上取整到 4 的倍数,留余量);
     * 超过用各库的大文本类型。阈值 4000 = Oracle VARCHAR2 上限。
     *
     * <p><b>已知限制</b>(本机无法测,文档记录):
     * <ul>
     *   <li>Oracle 的 DATE 含时间部分(与标准 SQL 不同),DATE 列读回可能有时间截断</li>
     *   <li>Oracle 的 FLOAT/DOUBLE PRECISION 内部是 NUMBER(十进制),不是 IEEE754,极大数精度行为不同</li>
     *   <li>SQLite 是动态类型,声明的类型名只是"类型亲和"(advisory),不强制</li>
     * </ul>
     *
     * @param dt          jian DType
     * @param productName conn.getMetaData().getDatabaseProductName() 的返回(如 "PostgreSQL"/"Microsoft SQL Server"/"H2"/"SQLite")
     * @param maxLen      STRING 列的实际最大长度(<0 表示非 STRING 列,忽略)
     * @return 该数据库接受的 SQL 类型字符串;Oracle STRING 短文本为 {@code VARCHAR2(n CHAR)}
     *         (显式字符语义;byte 语义下中文列将 ORA-12899)
     */
    // 可见性 private → 包私有:仅为同包单元测试直接断言生成的 DDL 字符串
    // (Oracle 环境本机不可得,静态断言 " VARCHAR2(n CHAR)" 是唯一可机器验证口径),不构成公开 API。
    static String dtypeToSqlType(jian.core.DType dt, String productName, int maxLen) {
        // 探测数据库类型(getDatabaseProductName 返回值参考 JDBC 规范)
        boolean isSqlServer = productName != null && productName.contains("Microsoft SQL Server");
        boolean isOracle    = productName != null && productName.equalsIgnoreCase("Oracle");
        boolean isMySQL     = productName != null && (productName.contains("MySQL") || productName.contains("MariaDB"));
        boolean isH2        = productName != null && productName.equals("H2");

        // STRING 列:按实际 maxLen 决定 VARCHAR(n) vs 大文本
        if (dt == jian.core.DType.STRING) {
            // 向上取整到 4 的倍数(留余量,避免恰好边界);最小 1
            int n = Math.max(1, ((maxLen + 3) / 4) * 4);
            boolean needLargeText = maxLen > VARCHAR_THRESHOLD;
            if (needLargeText) {
                // 大文本:各库不同
                if (isSqlServer) return "VARCHAR(MAX)";
                if (isOracle)    return "CLOB";
                if (isMySQL)     return "LONGTEXT";
                if (isH2)        return "CLOB";
                return "TEXT";    // PG / SQLite
            } else {
                // 短文本:VARCHAR(n),Oracle 用 VARCHAR2(n CHAR)。
                // 因为 "VARCHAR2(n)" 的 n 按默认 **byte** 语义计,而 scanMaxStringLength
                // 用 String.length()(UTF-16 char 数)定宽 —— 中文等多字节字符列在 Oracle
                // 默认 byte 语义下 ORA-12899(值超列宽),所以显式 CHAR 语义让 n 按字符数计,
                // 与 scanMaxStringLength 的度量口径一致。
                if (isOracle)    return "VARCHAR2(" + n + " CHAR)";
                return "VARCHAR(" + n + ")";
            }
        }

        if (isSqlServer) {
            return switch (dt) {
                case INT -> "INT";
                case LONG -> "BIGINT";
                case DOUBLE -> "FLOAT(53)";
                case BOOL -> "BIT";
                case DATETIME -> "DATETIME2";
                case DATE -> "DATE";
                default -> "VARCHAR(MAX)";
            };
        }
        if (isOracle) {
            return switch (dt) {
                case INT -> "INTEGER";
                case LONG -> "NUMBER(19)";
                case DOUBLE -> "FLOAT(126)";
                case BOOL -> "NUMBER(1)";
                case DATETIME -> "TIMESTAMP";
                case DATE -> "DATE";
                default -> "CLOB";
            };
        }
        // 默认:PG / MySQL / SQLite / H2
        return switch (dt) {
            case INT -> "INTEGER";
            case LONG -> "BIGINT";
            case DOUBLE -> "DOUBLE PRECISION";
            case BOOL -> "BOOLEAN";
            case DATETIME -> "TIMESTAMP";
            case DATE -> "DATE";
            default -> isMySQL ? "LONGTEXT" : (isH2 ? "CLOB" : "TEXT");
        };
    }

    /** 批量 INSERT。 */
    private static void insertBatch(DataFrame df, Connection conn, String table, int batchSize) throws SQLException {
        List<String> cols = df.columnNames();
        // SQL 注入防护:列名直接拼入 INSERT INTO 列清单,先白名单校验(表名已在 write 校验)
        for (String col : cols) assertSafeColumnName(col);
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (");
        sb.append(String.join(",", cols));
        sb.append(") VALUES (");
        for (int c = 0; c < cols.size(); c++) sb.append(c == 0 ? "?" : ",?");
        sb.append(')');
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int count = 0;
            for (Object[] row : df.iterRows()) {
                for (int c = 0; c < cols.size(); c++) {
                    Object v = row[c];
                    if (v == null) ps.setNull(c + 1, Types.NULL);
                    else ps.setObject(c + 1, v);
                }
                ps.addBatch();
                if (++count % batchSize == 0) ps.executeBatch();
            }
            if (count % batchSize != 0) ps.executeBatch();
        }
    }
}
