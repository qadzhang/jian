package jian.sql.bridge;

import jian.core.DataFrame;
import jian.core.Schema;
import jian.sql.engine.Engine;
import jian.sql.expr.SqlBuilder;
import org.jooq.Record;
import org.jooq.Result;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : SqlBridge —— ResultSet / jOOQ Result → DataFrame 的桥接(对齐规范 05 §2.4 喂给 jian)
// │  Why  : 规范 05 §2.4;jian-sql 核心不依赖 jian,经此 bridge jar 才单向依赖 jian-core
// │  Who  : 用户经 engine.toDataFrame(sql) 或 SqlBridge.toDataFrame(...) 调用
// │  When : SQL 查询结果转 DataFrame 继续分析
// │  Where: jian-sql-bridge/SqlBridge.java
// │  How  : 数据走向:ResultSet → ResultSetMetaData 列名 → 逐行取值 → Object[][] → Schema.infer → DataFrame。
// │         关键变量变化:
// │           - JDBC 类型 → 经 getObject 取 Java 值 → Schema 推断 jian DType;
// │           - null 用 wasNull() 区分。
/**
 * SQL 结果 → DataFrame 桥接(规范 §2.4)。
 *
 * <p>用法:
 * <pre>{@code
 * try (Connection conn = engine.connect()) {
 *     DataFrame df = SqlBridge.toDataFrame(conn, "SELECT * FROM users WHERE age &gt; ?", 18);
 * }
 *
 * // jOOQ Result 转
 * Result<Record> r = qb.ctx().selectFrom("users").fetch();
 * DataFrame df = SqlBridge.toDataFrame(r);
 * }</pre>
 */
public final class SqlBridge {

    private SqlBridge() {}

    /**
     * Connection + 原生 SQL → DataFrame(对齐 pandas.read_sql_query)。
     *
     * @param conn   Connection JDBC 连接,约束:不能为 null;调用方负责关闭
     * @param sql    String SELECT SQL 模板,约束:不能为 null;值用 ? 占位
     * @param params Object... 绑定到 ? 的参数值,顺序与 SQL 中的 ? 一致;可省略
     * @return DataFrame 查询结果转换的 DataFrame(Schema 自动推断)
     * @throws SQLException 当执行查询失败时抛出
     */
    public static DataFrame toDataFrame(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return toDataFrame(rs);
            }
        }
    }

    /**
     * ResultSet → DataFrame。
     *
     * @param rs ResultSet JDBC 结果集,约束:不能为 null;调用方负责关闭;游标从首行前开始遍历
     * @return DataFrame 结果集转换的 DataFrame(Schema 自动推断)
     * @throws SQLException 当读取元数据或行数据失败时抛出
     */
    public static DataFrame toDataFrame(ResultSet rs) throws SQLException {
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

    /**
     * jOOQ Result → DataFrame(对齐规范 §2.4 fetchAsDataFrame)。
     *
     * @param result Result<Record> jOOQ 查询结果,约束:不能为 null;可为空集(返回空 DataFrame)
     * @return DataFrame 结果转换的 DataFrame(Schema 自动推断)
     */
    public static DataFrame toDataFrame(Result<Record> result) {
        if (result.isEmpty()) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        List<String> names = new ArrayList<>();
        for (int c = 0; c < result.fields().length; c++) names.add(result.field(c).getName());
        Object[][] rows = new Object[result.size()][names.size()];
        for (int r = 0; r < result.size(); r++) {
            Record rec = result.get(r);
            for (int c = 0; c < names.size(); c++) rows[r][c] = rec.get(c);
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /**
     * Engine 的便捷扩展:engine.toDataFrame(sql, params) → DataFrame。
     *
     * @param engine Engine 数据库引擎,约束:不能为 null;内部借连接并自动归还
     * @param sql    String SELECT SQL 模板,约束:不能为 null;值用 ? 占位
     * @param params Object... 绑定到 ? 的参数值,顺序与 SQL 中的 ? 一致
     * @return DataFrame 查询结果转换的 DataFrame
     * @throws SQLException 当借连接或执行查询失败时抛出
     */
    public static DataFrame fetchAsDataFrame(Engine engine, String sql, Object... params) throws SQLException {
        try (Connection conn = engine.connect()) {
            return toDataFrame(conn, sql, params);
        }
    }
}
