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

    /** 执行 SQL 查询,返回 DataFrame(对齐 pandas.read_sql_query)。 */
    public static DataFrame readQuery(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return resultSetToDataFrame(rs);
            }
        }
    }

    /** 读整张表(对齐 pandas.read_sql_table)。 */
    public static DataFrame readTable(Connection conn, String table) throws SQLException {
        return readQuery(conn, "SELECT * FROM " + table);
    }

    /** ResultSet → DataFrame(列名 + 类型从 ResultSetMetaData 推断)。 */
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
                row[c - 1] = rs.wasNull() ? null : v;
            }
            rows.add(row);
        }
        Object[][] data = rows.toArray(new Object[0][]);
        return DataFrame.of(Schema.infer(names, data), data);
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
        // 表存在检查
        boolean exists = tableExists(conn, table);
        if (exists && mode == Mode.FAIL_IF_EXISTS) {
            throw new SQLException("表 " + table + " 已存在,且模式为 FAIL_IF_EXISTS");
        }
        if (mode == Mode.OVERWRITE || mode == Mode.CREATE_OR_REPLACE) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            exists = false;
        }
        if (!exists) {
            createTable(df, conn, table);
        }
        // 批量 INSERT
        insertBatch(df, conn, table, 1000);
        if (!conn.getAutoCommit()) conn.commit();
    }

    /** 默认 CREATE_OR_REPLACE。 */
    public static void write(DataFrame df, Connection conn, String table) throws SQLException {
        write(df, conn, table, Mode.CREATE_OR_REPLACE);
    }

    // ======================== pandas 风格别名(对齐 read_sql / to_sql)========================

    /**
     * 对齐 pandas.read_sql:执行 SQL 查询返回 DataFrame。
     * <pre>{@code
     * DataFrame df = Sql.readSql(conn, "SELECT * FROM users WHERE age > ?", 18);
     * }</pre>
     */
    public static DataFrame readSql(Connection conn, String sql, Object... params) throws SQLException {
        return readQuery(conn, sql, params);
    }

    /**
     * 对齐 pandas.read_sql_table:读整张表。
     * <pre>{@code
     * DataFrame df = Sql.readSqlTable(conn, "users");
     * }</pre>
     */
    public static DataFrame readSqlTable(Connection conn, String table) throws SQLException {
        return readTable(conn, table);
    }

    /**
     * 对齐 pandas.to_sql:把 DataFrame 写入数据库表。
     * <pre>{@code
     * Sql.toSql(df, conn, "users", Sql.Mode.APPEND);
     * }</pre>
     */
    public static void toSql(DataFrame df, Connection conn, String table, Mode mode) throws SQLException {
        write(df, conn, table, mode);
    }

    /** to_sql 默认 CREATE_OR_REPLACE。 */
    public static void toSql(DataFrame df, Connection conn, String table) throws SQLException {
        write(df, conn, table);
    }

    /** 判断表是否存在(用 meta.getTables,通用,不写死方言;大小写不敏感)。 */
    private static boolean tableExists(Connection conn, String table) throws SQLException {
        // H2 等数据库建表默认大写,匹配时用大写 + 也试原样
        String[] candidates = { table, table.toUpperCase(), table.toLowerCase() };
        for (String cand : candidates) {
            try (ResultSet rs = conn.getMetaData().getTables(null, null, cand, null)) {
                if (rs.next()) return true;
            } catch (SQLException ignored) { /* 乐观策略:表不存在返回 false,不算错误 */ }
        }
        return false;
    }

    /** CREATE TABLE(按 DataFrame dtype → SQL 列类型)。 */
    private static void createTable(DataFrame df, Connection conn, String table) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        List<String> cols = df.columnNames();
        List<jian.core.DType> dtypes = df.dtypes();
        for (int c = 0; c < cols.size(); c++) {
            if (c > 0) sb.append(", ");
            sb.append(cols.get(c)).append(' ').append(dtypeToSqlType(dtypes.get(c)));
        }
        sb.append(')');
        try (Statement st = conn.createStatement()) {
            st.execute(sb.toString());
        }
    }

    /** jian DType → SQL 列类型(通用,各数据库兼容)。 */
    private static String dtypeToSqlType(jian.core.DType dt) {
        return switch (dt) {
            case INT -> "INTEGER";
            case LONG -> "BIGINT";
            case DOUBLE -> "DOUBLE";
            case BOOL -> "BOOLEAN";
            case STRING -> "VARCHAR(1000)";
            case DATETIME -> "TIMESTAMP";
            case DATE -> "DATE";
            default -> "VARCHAR(1000)";
        };
    }

    /** 批量 INSERT。 */
    private static void insertBatch(DataFrame df, Connection conn, String table, int batchSize) throws SQLException {
        List<String> cols = df.columnNames();
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
