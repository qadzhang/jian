package jian.sql.bridge;

import jian.core.DataFrame;
import jian.core.DType;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : SqlBridge —— ResultSet / jOOQ Result → DataFrame 的桥接(对齐规范 05 §2.4 喂给 jian)
// │  Why  : 规范 05 §2.4;jian-sql 核心不依赖 jian,经此 bridge jar 才单向依赖 jian-core
// │  Who  : 用户经 engine.toDataFrame(sql) 或 SqlBridge.toDataFrame(...) 调用
// │  When : SQL 查询结果转 DataFrame 继续分析
// │  Where: jian-sql-bridge/SqlBridge.java
// │  How  : 数据走向:ResultSet → ResultSetMetaData 列名 + JDBC 类型(getColumnType
// │           映射 DType,不对 Java 值推断)→ 逐行取值(normalize + null=缺失)→
// │           Schema(names, dtypes) → DataFrame.of 按 dtype 建列。
// │         关键变量变化:
// │           - jdbcTypes(每列 java.sql.Types)→ dtypes(jian DType,桥内映射不改 jian-core);
// │           - NULL 类型列退回 Schema.infer 单列推断;BOOL 列 Number 0/1 → Boolean;
// │           - 空 jOOQ Result 保留全部列名构造 0 行(不丢列)。
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
     * <p>因为 pgjdbc 等驱动对 SMALLINT/TINYINT 返回 Short、TIME 返回 LocalTime、BLOB 返回 byte[],
     * 对 Java 值跑 Schema.infer 无这些分支会全部落 STRING(元素还是原对象,dtype 与值不匹配),
     * 所以列类型按 {@link ResultSetMetaData#getColumnType(int)}(JDBC SQL 类型)映射 jian DType。
     * 映射表见 {@link #jdbcTypeToDType};
     * 读出 null 即缺失(对齐 jian-core §3.5,由 DataFrame.of 按 dtype 建 nullMask/NaN)。
     *
     * @param rs ResultSet JDBC 结果集,约束:不能为 null;调用方负责关闭;游标从首行前开始遍历
     * @return DataFrame 结果集转换的 DataFrame(列类型按 JDBC 元数据映射)
     * @throws SQLException 当读取元数据或行数据失败时抛出
     */
    public static DataFrame toDataFrame(ResultSet rs) throws SQLException {
        // 伪代码:
        //   1. 读元数据:列名 + JDBC 类型 → 名称表 names 与类型表 jdbcTypes
        //   2. 逐行 getObject + wasNull(读出 null 即缺失)+ normalizeJdbcObject 规范化
        //   3. 定 dtype:jdbcTypes → DType(映射表);JDBC NULL 类型者读完全部行后
        //      退回 Schema.infer 对该列单列推断(无元数据只能看数据)
        //   4. BOOL 列的 0/1 Number(SQLite BOOLEAN)转 Boolean
        //   5. Schema(names, dtypes) + DataFrame.of 按 dtype 建列(缺失语义对齐 §3.5)
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<String> names = new ArrayList<>();
        int[] jdbcTypes = new int[cols];
        for (int c = 1; c <= cols; c++) {
            names.add(meta.getColumnLabel(c));
            jdbcTypes[c - 1] = meta.getColumnType(c);
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[cols];
            for (int c = 1; c <= cols; c++) {
                Object v = rs.getObject(c);
                // 因为 jian-sql 库不依赖 jian 库(无法复用 jian-io-sql 的 Sql.normalizeJdbcObject,§3.6.3 同款),
                // 所以本桥本地复制该规范化逻辑;两处实现互指,修改须同步。
                row[c - 1] = rs.wasNull() ? null : normalizeJdbcObject(v);
            }
            rows.add(row);
        }
        Object[][] data = rows.toArray(new Object[0][]);
        // 按 JDBC 元数据定 dtype;NULL 类型(驱动没给出类型信息)退回数据推断
        List<DType> dtypes = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            DType dt = jdbcTypeToDType(jdbcTypes[c]);
            if (dt == null) {
                // 单列退回 Schema.infer(不改 jian-core;桥内自行动作)
                Object[] col = new Object[data.length];
                for (int r = 0; r < data.length; r++) col[r] = data[r][c];
                dt = Schema.infer(List.of(names.get(c)), new Object[][]{col}).dtypeAt(0);
            }
            dtypes.add(dt);
        }
        // 因为 BOOL 列遇 Number(SQLite BOOLEAN=INTEGER 0/1)时 DataFrame.of 的 BOOL 建列分支
        // 按 String 解析会 ClassCastException,所以先转 Boolean
        for (int c = 0; c < cols; c++) {
            if (dtypes.get(c) != DType.BOOL) continue;
            for (Object[] row : data) {
                if (row[c] instanceof Number num) row[c] = num.intValue() != 0;
            }
        }
        return DataFrame.of(new Schema(names, dtypes), data);
    }

    /**
     * JDBC SQL 类型({@link java.sql.Types})→ jian DType(桥内实现,不改 jian-core Schema)。
     * <p>BIGINT→LONG;INTEGER/SMALLINT/TINYINT→INT;DOUBLE/FLOAT/REAL/DECIMAL/NUMERIC→DOUBLE;
     * BOOLEAN/BIT→BOOL;TIMESTAMP→DATETIME;DATE→DATE;TIME/VARCHAR/CHAR/CLOB 及其它→STRING;
     * NULL 类型返回 null(调用方退回数据推断)。
     *
     * @param jdbcType int java.sql.Types 常量(ResultSetMetaData.getColumnType 的返回值)
     * @return DType 映射结果;jdbcType 为 Types.NULL 时返回 null(表示"无类型信息,需推断")
     */
    private static DType jdbcTypeToDType(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT -> DType.LONG;
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> DType.INT;
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.DECIMAL, Types.NUMERIC -> DType.DOUBLE;
            case Types.BOOLEAN, Types.BIT -> DType.BOOL;
            case Types.TIMESTAMP -> DType.DATETIME;
            case Types.DATE -> DType.DATE;
            default -> DType.STRING;   // TIME/VARCHAR/CHAR/CLOB/LONGVARCHAR/NCHAR/... 及其它
        };
    }

    /**
     * jOOQ Result → DataFrame(对齐规范 §2.4 fetchAsDataFrame)。
     * <p>因为空 Result 若直接返回 0 列 DataFrame 会丢掉全部列名信息(JDBC 空结果尚且保留列),
     * 所以空集保留全部列名构造 0 行,列类型按 jOOQ 字段的 Java 类型轻量映射
     * (无数据可推断,未知类型 STRING 兜底)。
     *
     * @param result Result<Record> jOOQ 查询结果,约束:不能为 null;可为空集(保留列名的 0 行 DataFrame)
     * @return DataFrame 结果转换的 DataFrame(非空集走 Schema.infer;空集保留全部列名)
     */
    public static DataFrame toDataFrame(Result<Record> result) {
        if (result.isEmpty()) {
            // 0 行保列(与 Json/Pickle/Xml 的空结果口径一致)
            List<String> names = new ArrayList<>();
            List<DType> dtypes = new ArrayList<>();
            for (int c = 0; c < result.fields().length; c++) {
                names.add(result.field(c).getName());
                dtypes.add(javaTypeToDType(result.field(c).getType()));
            }
            return DataFrame.of(new Schema(names, dtypes), new Object[0][]);
        }
        List<String> names = new ArrayList<>();
        for (int c = 0; c < result.fields().length; c++) names.add(result.field(c).getName());
        Object[][] rows = new Object[result.size()][names.size()];
        for (int r = 0; r < result.size(); r++) {
            Record rec = result.get(r);
            // jOOQ Record 路径同样规范化(裸 JDBC 类型不进 Schema.infer)
            for (int c = 0; c < names.size(); c++) rows[r][c] = normalizeJdbcObject(rec.get(c));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /**
     * jOOQ 字段 Java 类型 → jian DType(空结果保列时的轻量映射)。
     *
     * @param type Class&lt;?&gt; jOOQ 字段的 Java 类型(result.field(c).getType())
     * @return DType 映射结果;未知类型返回 STRING 兜底(0 行列,dtype 仅作元数据占位)
     */
    private static DType javaTypeToDType(Class<?> type) {
        if (type == Long.class || type == long.class) return DType.LONG;
        if (type == Integer.class || type == int.class || type == Short.class || type == short.class) return DType.INT;
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) return DType.DOUBLE;
        if (type == Boolean.class || type == boolean.class) return DType.BOOL;
        if (type == java.time.LocalDate.class) return DType.DATE;
        if (type == java.time.LocalDateTime.class) return DType.DATETIME;
        return DType.STRING;
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
    /**
     * JDBC 特殊类型规范化(与 jian-io-sql Sql#normalizeJdbcObject 同款,两处互指须同步修改)。
     * <p>Clob→String / Blob→byte[] / BigDecimal→Double / java.sql.Date→LocalDate /
     * Timestamp→LocalDateTime / Time→LocalTime;其余原样返回(交 Schema.infer)。
     * @param v Object JDBC getObject 原始值,可为 null
     * @return Object 规范化后的 Java 标准类型;null 原样返回
     */
    private static Object normalizeJdbcObject(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Clob clob) {
            try {
                long len = clob.length();
                return len > Integer.MAX_VALUE ? clob.getSubString(1, Integer.MAX_VALUE) : clob.getSubString(1, (int) len);
            } catch (java.sql.SQLException e) {
                return clob.toString();
            }
        }
        if (v instanceof java.sql.Blob blob) {
            try {
                long len = blob.length();
                return blob.getBytes(1, len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) len);
            } catch (java.sql.SQLException e) {
                return null;
            }
        }
        if (v instanceof java.math.BigDecimal bd) return bd.doubleValue();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Time t) return t.toLocalTime();
        return v;
    }

}
