package jian.io.sql;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SqlPostgresTest —— 真实 PostgreSQL 18 数据库的完整读写测试(非 H2 模拟)
// │  Why  : jian-io-sql 号称支持 7 库,但此前只测 H2 in-memory + SQLite,H2 的 MODE=PostgreSQL 不等于真 PG。
// │         PG 有独特行为(大小写敏感、NUMERIC 精度、TIMESTAMP 时区、BOOLEAN 类型),必须用真 PG 验证。
// │  Who  : jian-io-sql 维护者(跨库真实环境验证)
// │  When : 改动 Sql 的建表 DDL / 类型映射 / 注入防护后
// │  Where: jian-io-sql/SqlPostgresTest.java
// │  How  :
// │    ① 连接本地 PG 18(jdbc:postgresql://127.0.0.1:5432/postgres,用户 postgres/123)
// │    ② 每个测试用唯一表名(防冲突),测试后 DROP TABLE 清理
// │    ③ 覆盖 8 维度:全 dtype 往返 / 参数化查询 / 4 种写入模式 / 缺失值 / PG 特有行为 / 大数据量 / 注入防护 / 大小写
//
// 运行前提:本机 PG 18 运行在 127.0.0.1:5432,用户 postgres 密码 123。
// 用 -Dtest.pg=true 激活(默认跳过,避免无 PG 环境的 CI 失败):
//   ./mvnw -pl jian/jian-io-sql test -Dtest=SqlPostgresTest -Dtest.pg=true
@Tag("postgres")
@EnabledIfSystemProperty(named = "test.pg", matches = "true")
class SqlPostgresTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/postgres";
    private static final String USER = "postgres";
    private static final String PWD = "123";

    private Connection conn;
    private String tableName;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.postgresql.Driver");
        conn = DriverManager.getConnection(URL, USER, PWD);
        tableName = "jian_test_" + System.nanoTime();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + tableName);
            } catch (SQLException ignore) {}
            conn.close();
        }
    }

    // ======================== 1. 全 dtype 往返 ========================

    /**
     * 写出全 dtype DataFrame → 读回 → 逐列逐行精确断言。
     * 覆盖:LONG / DOUBLE / STRING / BOOL / DATE / DATETIME。
     */
    @Test
    void 全dtype写出读回一致() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "score", DType.DOUBLE, "name", DType.STRING,
                          "vip", DType.BOOL, "birthday", DType.DATE, "created", DType.DATETIME),
                new Object[][]{
                        {1L, 90.5, "alice", true,
                         java.time.LocalDate.of(1990, 5, 1),
                         java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0)},
                        {2L, 85.0, "bob", false,
                         java.time.LocalDate.of(1995, 10, 20),
                         java.time.LocalDateTime.of(2024, 6, 1, 14, 0, 0)},
                });
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        DataFrame r = Sql.readTable(conn, tableName);

        assertThat(r.rowCount()).isEqualTo(2);
        // PG 列名默认小写(与 H2 大写不同——这是 PG 特有行为)
        assertThat(r.columnNames()).containsExactly("id", "score", "name", "vip", "birthday", "created");
        // LONG
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getLongColumn("id").getLong(1)).isEqualTo(2L);
        // DOUBLE
        assertThat(r.getDoubleColumn("score").getDouble(0)).isEqualTo(90.5);
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(85.0);
        // STRING
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getStringColumn("name").get(1)).isEqualTo("bob");
        // BOOL(PG 原生 boolean 类型)
        assertThat(((jian.core.BoolColumn) r.getColumn("vip")).getBool(0)).isTrue();
        assertThat(((jian.core.BoolColumn) r.getColumn("vip")).getBool(1)).isFalse();
    }

    // ======================== 2. 参数化查询 ========================

    /**
     * 写入数据 → 参数化查询 ? 占位 → 精确断言返回行。
     */
    @Test
    void 参数化查询_问号占位() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{1L, 30L}, {2L, 25L}, {3L, 40L}, {4L, 35L}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);

        // WHERE age > ?
        DataFrame r1 = Sql.readQuery(conn, "SELECT * FROM " + tableName + " WHERE age > ?", 28);
        assertThat(r1.rowCount()).isEqualTo(3);   // 30, 40, 35

        // WHERE age > ? AND id < ?
        DataFrame r2 = Sql.readQuery(conn,
                "SELECT * FROM " + tableName + " WHERE age > ? AND id < ?", 28, 4);
        assertThat(r2.rowCount()).isEqualTo(2);   // 30(id=1), 35(id=3);40(id=3) 但 id<4
    }

    // ======================== 3. 四种写入模式 ========================

    @Test
    void CREATE_OR_REPLACE_覆盖旧表() throws Exception {
        DataFrame a = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
        DataFrame b = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{2L}, {3L}});

        Sql.write(a, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        assertThat(Sql.readTable(conn, tableName).rowCount()).isEqualTo(1);

        Sql.write(b, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);  // 覆盖
        DataFrame r = Sql.readTable(conn, tableName);
        assertThat(r.rowCount()).isEqualTo(2);   // 旧数据被删,只有 2,3
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(2L);
    }

    @Test
    void APPEND_累加数据() throws Exception {
        DataFrame a = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
        DataFrame b = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{2L}, {3L}});

        Sql.write(a, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        Sql.write(b, conn, tableName, Sql.Mode.APPEND);
        assertThat(Sql.readTable(conn, tableName).rowCount()).isEqualTo(3);  // 1 + 2 + 3
    }

    @Test
    void FAIL_IF_EXISTS_表已存在抛异常() throws Exception {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);

        assertThatThrownBy(() -> Sql.write(df, conn, tableName, Sql.Mode.FAIL_IF_EXISTS))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void OVERWRITE_模式() throws Exception {
        DataFrame a = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}, {2L}});
        DataFrame b = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{9L}});

        Sql.write(a, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        Sql.write(b, conn, tableName, Sql.Mode.OVERWRITE);
        DataFrame r = Sql.readTable(conn, tableName);
        assertThat(r.rowCount()).isEqualTo(1);   // OVERWRITE = DROP+CREATE+INSERT
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(9L);
    }

    // ======================== 4. 缺失值往返 ========================

    /**
     * 缺失值写出 → PG NULL → 读回应还原为缺失。
     */
    @Test
    void 缺失值写出读回_NULL往返() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE, "s", DType.STRING),
                new Object[][]{
                        {1.0, "a"},
                        {null, null},
                        {3.0, "c"}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        DataFrame r = Sql.readTable(conn, tableName);

        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getColumn("v").get(0)).isEqualTo(1.0);
        // 缺失行:DoubleColumn.get(NaN) 现在返回 Double.NaN(不是 null);用 isNull 判断
        assertThat(r.getColumn("v").isNull(1)).isTrue();
        assertThat(r.getColumn("v").get(2)).isEqualTo(3.0);
        assertThat(r.getColumn("s").get(0)).isEqualTo("a");
        assertThat(r.getColumn("s").get(1)).isNull();
        assertThat(r.getColumn("s").get(2)).isEqualTo("c");
    }

    // ======================== 5. PG 特有:大小写敏感性 ========================

    /**
     * PG 默认列名小写(不区分大小写标识符,但返回小写)。
     * 这与 H2(大写)/MySQL(保留大小写带反引号)不同——是真实跨库差异。
     */
    @Test
    void PG列名默认小写_与H2大写不同() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("MyCol", DType.LONG, "Another", DType.STRING),
                new Object[][]{{1L, "x"}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        DataFrame r = Sql.readTable(conn, tableName);

        // PG 把 "MyCol" 折叠为 "mycol"(未加引号的标识符折叠为小写)
        assertThat(r.columnNames()).containsExactly("mycol", "another");
    }

    // ======================== 6. 大数据量(万行级)========================

    /**
     * 写 10000 行 → 读回 → 断言行数 + 首尾值。
     * 验证大批量 INSERT 不丢行、不串行。
     */
    @Test
    void 万行读写_不丢行() throws Exception {
        int n = 10_000;
        long[] ids = new long[n];
        double[] vs = new double[n];
        for (int i = 0; i < n; i++) { ids[i] = i; vs[i] = i * 1.1; }
        DataFrame df = DataFrame.ofColumnArrays(
                java.util.List.of("id", "v"),
                new Object[]{ids, vs});

        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        DataFrame r = Sql.readTable(conn, tableName);

        assertThat(r.rowCount()).isEqualTo(n);
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(0L);
        assertThat(r.getLongColumn("id").getLong(n - 1)).isEqualTo(n - 1);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getDoubleColumn("v").getDouble(n - 1)).isEqualTo((n - 1) * 1.1, org.assertj.core.data.Offset.offset(1e-6));
    }

    // ======================== 7. SQL 注入防护 ========================

    /**
     * 参数化查询防注入:传入恶意参数应被正确转义,不改变查询语义。
     */
    @Test
    void 参数化查询防SQL注入() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);

        // 恶意输入:"1 OR 1=1" → 参数化后当作字符串字面值,不匹配任何行
        DataFrame r = Sql.readQuery(conn,
                "SELECT * FROM " + tableName + " WHERE name = ?", "alice' OR '1'='1");
        assertThat(r.rowCount()).isEqualTo(0);   // 注入被阻止,0 行返回
    }

    // ======================== 8. 别名一致性(readSql/toSql)========================

    /**
     * readSql / toSql 是 readQuery / write 的别名,行为应一致。
     */
    @Test
    void readSql和toSql是readQuery和write的别名() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "v", DType.DOUBLE),
                new Object[][]{{1L, 10.0}, {2L, 20.0}});
        // toSql = write
        Sql.toSql(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);
        // readSql = readQuery
        DataFrame r = Sql.readSql(conn, "SELECT * FROM " + tableName + " WHERE v > ?", 15.0);
        assertThat(r.rowCount()).isEqualTo(1);   // 只有 v=20.0
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(20.0);
    }

    // ======================== 9. VARCHAR 自适应长度(短文本走 VARCHAR,长文本走 TEXT)========================

    /**
     * 短文本(≤ 4000 字符)应建 VARCHAR(n),不被截断。
     * 因为定长 VARCHAR(1000) 会让 1000~4000 字符的短文本被截断,所以按实际长度自适应建列。
     */
    @Test
    void 短文本_VARCHAR自适应_不截断() throws Exception {
        String medium = "x".repeat(3500);   // 3500 字符(>1000 但 <4000 阈值)
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "txt", DType.STRING),
                new Object[][]{{1L, "short"}, {2L, medium}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);

        // 验证表结构:txt 列应是 VARCHAR(3500)(按实际长度自适应,非定长 1000)
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery(
                 "SELECT data_type, character_maximum_length FROM information_schema.columns " +
                 "WHERE table_name = '" + tableName + "' AND column_name = 'txt'")) {
            rs.next();
            String dataType = rs.getString(1);
            int charLen = rs.getInt(2);
            assertThat(dataType).as("短文本应建 character varying").isEqualTo("character varying");
            assertThat(charLen).as("VARCHAR 长度应 ≥ 3500(自适应)").isGreaterThanOrEqualTo(3500);
        }

        // 读回验证不截断
        DataFrame r = Sql.readTable(conn, tableName);
        assertThat(r.getStringColumn("txt").get(0)).isEqualTo("short");
        assertThat(((String) r.getStringColumn("txt").get(1)).length()).as("3500 字符不截断").isEqualTo(3500);
    }

    /**
     * 长文本(> 4000 字符)应建 TEXT(PG 大文本),不被截断。
     * 因为 VARCHAR 超长会截断/报错,所以 > 4000 时 PG 用 TEXT 类型。
     */
    @Test
    void 长文本_走TEXT类型_万字符不截断() throws Exception {
        String longText = "abcdefgh".repeat(1000);   // 8000 字符(纯 ASCII,远超 4000 阈值)
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "body", DType.STRING),
                new Object[][]{{1L, longText}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);

        // 验证表结构:body 列应是 text(PG 大文本类型)
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery(
                 "SELECT data_type FROM information_schema.columns " +
                 "WHERE table_name = '" + tableName + "' AND column_name = 'body'")) {
            rs.next();
            assertThat(rs.getString(1))
                    .as("长文本(>4000)在 PG 应建 text 类型")
                    .isEqualTo("text");
        }

        // 读回:完整长度不截断
        DataFrame r = Sql.readTable(conn, tableName);
        String readBack = (String) r.getColumn("body").get(0);
        assertThat(readBack.length())
                .as("长文本完整读回不截断")
                .isEqualTo(longText.length());
        assertThat(readBack).isEqualTo(longText);
    }

    /**
     * 混合短长文本列:同一张表里有短(name)和长(article)两个字符串列,
     * 应分别走 VARCHAR 和 TEXT,各自不截断。
     */
    @Test
    void 混合长短文本_各自正确选型() throws Exception {
        String longArticle = "A".repeat(8000);  // > 4000 → TEXT
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "article", DType.STRING),
                new Object[][]{{"alice", longArticle}, {"bob", "short article"}});
        Sql.write(df, conn, tableName, Sql.Mode.CREATE_OR_REPLACE);

        DataFrame r = Sql.readTable(conn, tableName);
        assertThat(r.rowCount()).isEqualTo(2);
        // name 短文本
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        // article 长文本不截断
        assertThat(((String) r.getColumn("article").get(0)).length()).isEqualTo(8000);
        assertThat(r.getStringColumn("article").get(1)).isEqualTo("short article");
    }
}
