package jian.sql.bridge;

import jian.core.DataFrame;
import jian.core.DType;
import jian.sql.engine.DbType;
import jian.sql.engine.Engine;
import jian.sql.engine.EngineConfig;
import jian.sql.expr.SqlBuilder;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : SqlBridgeRegressionTest —— SqlBridge 回归测试集(固化 JDBC 元数据映射与空结果保列行为)
// │  Why  : 因为 dtype 须按 ResultSetMetaData 映射而非对 Java 值推断(否则 pgjdbc 的
// │         Short(SMALLINT)/LocalTime(TIME)/byte[](BLOB) 会全落 STRING,下游时间运算失效),
// │         且空结果必须保留全部列名,所以用回归测试固化,防未来退化
// │  Who  : JUnit 5 自动执行
// │  When : mvn test(jian-sql-bridge 模块)
// │  Where: jian-sql-bridge/src/test/java/jian/sql/bridge/SqlBridgeRegressionTest.java
// │  How  : H2 建含 SMALLINT/TIME/DECIMAL/DATE/TIMESTAMP/BOOLEAN 的宽表,断言各列 dtype
// │         按 ResultSetMetaData 映射(BIGINT→LONG、SMALLINT→INT、TIME→STRING 等);
// │         另断 Timestamp 规范化、读出 null 即缺失、空结果(ResultSet 与 jOOQ Result)保列
class SqlBridgeRegressionTest {

    private Engine h2Engine() throws Exception {
        Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:bridge_r9_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
        try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE wide (id BIGINT, s SMALLINT, price DECIMAL(20,2),"
                    + " active BOOLEAN, tm TIME, bday DATE, ts TIMESTAMP)");
            st.execute("INSERT INTO wide VALUES (1, 3, 19.99, TRUE,"
                    + " TIME '10:15:30', DATE '2026-08-16', TIMESTAMP '2026-08-16 10:15:30')");
            st.execute("INSERT INTO wide VALUES (2, 7, 0.50, FALSE,"
                    + " TIME '23:59:59', DATE '2026-01-01', TIMESTAMP '2026-01-01 00:00:00')");
        }
        return engine;
    }

    @Test
    void timestamp列经桥规范化为DATETIME() throws Exception {
        // 因为 PG/Oracle 的 getObject 返回 java.sql.Timestamp,若不规范化会被 Schema.infer 归 STRING,
        // 下游 astype(DATETIME)/时间运算失效,所以桥内先规范化再建列
        try (Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:r6_bridge_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build())) {
            engine.sql("CREATE TABLE t(id BIGINT, ts TIMESTAMP)").execute();
            engine.sql("INSERT INTO t VALUES (1, TIMESTAMP '2026-01-01 10:00:00')").execute();
            DataFrame df;
            try (Connection conn = engine.connect();
                 Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT * FROM t")) {
                df = SqlBridge.toDataFrame(rs);
            }
            assertThat(df.getColumn("TS").dtype())
                    .as("Timestamp 应规范化为 DATETIME(不落 STRING)")
                    .isEqualTo(DType.DATETIME);
        }
    }

    @Test
    void SMALLINT与TIME列读回不落STRING() throws Exception {
        // SMALLINT → INT(dtype 按元数据映射,不依赖驱动返回的 Java 类型 —— pgjdbc 对
        // SMALLINT 返回 Short,若按值推断会落 STRING)
        try (Engine engine = h2Engine()) {
            DataFrame df = SqlBridge.fetchAsDataFrame(engine, "SELECT * FROM wide ORDER BY id");
            assertThat(df.columnNames())
                    .containsExactly("ID", "S", "PRICE", "ACTIVE", "TM", "BDAY", "TS");
            // 逐列断言 dtype:BIGINT→LONG / SMALLINT→INT / DECIMAL→DOUBLE / BOOLEAN→BOOL
            // TIME→STRING / DATE→DATE / TIMESTAMP→DATETIME
            assertThat(df.dtypes()).containsExactly(
                    DType.LONG, DType.INT, DType.DOUBLE, DType.BOOL,
                    DType.STRING, DType.DATE, DType.DATETIME);
            // 值随 dtype 正确装箱(SMALLINT 3 经 INT 列回读为 Integer 3)
            assertThat(df.getColumn("S").get(0)).isEqualTo(3);
            assertThat(df.getColumn("S").get(1)).isEqualTo(7);
            assertThat(df.getColumn("PRICE").getDouble(0)).isEqualTo(19.99);
            assertThat(df.getColumn("ACTIVE").get(0)).isEqualTo(Boolean.TRUE);
            // TIME 列是 STRING,值为 LocalTime 的字符串形态(HH:mm:ss)
            assertThat(df.getColumn("TM").get(0)).isEqualTo("10:15:30");
            assertThat(df.getColumn("BDAY").get(0)).isEqualTo(LocalDate.of(2026, 8, 16));
            assertThat(df.getColumn("TS").get(0)).isEqualTo(LocalDateTime.of(2026, 8, 16, 10, 15, 30));
        }
    }

    @Test
    void 读出null即缺失() throws Exception {
        // 缺失行为对齐 jian-core §3.5 —— 读出 null 即缺失,由 DataFrame.of 按 dtype 建掩码
        try (Engine engine = h2Engine()) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO wide VALUES (9, NULL, NULL, NULL, NULL, NULL, NULL)");
            }
            DataFrame df = SqlBridge.fetchAsDataFrame(engine, "SELECT * FROM wide WHERE id = 9");
            // ID 是定位条件本身(=9,非 null),断具体值;其余 6 列全部读出 NULL → 应为缺失
            assertThat(df.getColumn("ID").getLong(0)).isEqualTo(9L);
            for (String col : new String[]{"S", "PRICE", "ACTIVE", "TM", "BDAY", "TS"}) {
                assertThat(df.getColumn(col).isNull(0)).as("列 %s 应为缺失", col).isTrue();
            }
        }
    }

    @Test
    void 空JDBC结果保留全部列名与dtype() throws Exception {
        // 0 行结果列名保留(与 JDBC 语义一致,不丢列);dtype 按元数据映射(有元数据,不退化 STRING)
        try (Engine engine = h2Engine()) {
            DataFrame df = SqlBridge.fetchAsDataFrame(engine, "SELECT * FROM wide WHERE id > 999");
            assertThat(df.rowCount()).isZero();
            assertThat(df.columnCount()).isEqualTo(7);
            assertThat(df.columnNames())
                    .containsExactly("ID", "S", "PRICE", "ACTIVE", "TM", "BDAY", "TS");
            assertThat(df.dtypes()).containsExactly(
                    DType.LONG, DType.INT, DType.DOUBLE, DType.BOOL,
                    DType.STRING, DType.DATE, DType.DATETIME);
        }
    }

    @Test
    void 空jOOQResult保留全部列名构造0行() throws Exception {
        // 因为空 jOOQ Result 若直接返回 0 列 DataFrame 会丢全部列名,所以保留列名构造 0 行
        try (Engine engine = h2Engine();
             SqlBuilder qb = SqlBuilder.create(engine.dataSource(), SqlBuilder.Dialect.H2)) {
            Result<Record> r = qb.query("SELECT * FROM wide WHERE id > 999").fetch();
            assertThat(r.isEmpty()).isTrue();
            DataFrame df = SqlBridge.toDataFrame(r);
            assertThat(df.rowCount()).isZero();
            assertThat(df.columnCount()).isEqualTo(7);
            assertThat(df.columnNames())
                    .containsExactly("ID", "S", "PRICE", "ACTIVE", "TM", "BDAY", "TS");
        }
    }

    @Test
    void 非空jOOQResult行为不变() throws Exception {
        // 回归锁定:jOOQ 非空路径仍走 Schema.infer(行数/列名/值不变)
        try (Engine engine = h2Engine();
             SqlBuilder qb = SqlBuilder.create(engine.dataSource(), SqlBuilder.Dialect.H2)) {
            Result<Record> r = qb.query("SELECT id, s FROM wide ORDER BY id").fetch();
            DataFrame df = SqlBridge.toDataFrame(r);
            assertThat(df.rowCount()).isEqualTo(2);
            assertThat(df.columnNames()).containsExactly("ID", "S");
            assertThat(df.getColumn("ID").get(1)).isEqualTo(2L);
        }
    }

    // ======================== Clob/Blob 失败对称性 ========================

    @Test
    void Clob与Blob读取失败路径对称_统一为缺失() throws Exception {
        // length() 抛 SQLException 的坏 Clob/Blob:两路都应返回 null(缺失)
        // 修复前:Clob 失败返回 clob.toString()(非缺失垃圾串),Blob 失败返 null,语义不对称
        // 动态代理实现"任何调用都失败"的 Clob/Blob(接口方法可声明 SQLException)
        java.sql.Clob badClob = (java.sql.Clob) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Clob.class.getClassLoader(), new Class<?>[]{java.sql.Clob.class},
                (proxy, method, args) -> { throw new java.sql.SQLException("broken"); });
        java.sql.Blob badBlob = (java.sql.Blob) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Blob.class.getClassLoader(), new Class<?>[]{java.sql.Blob.class},
                (proxy, method, args) -> { throw new java.sql.SQLException("broken"); });
        java.lang.reflect.Method m = jian.sql.bridge.SqlBridge.class
                .getDeclaredMethod("normalizeJdbcObject", Object.class);
        m.setAccessible(true);
        assertThat(m.invoke(null, badClob)).isNull();
        assertThat(m.invoke(null, badBlob)).isNull();
    }
}
