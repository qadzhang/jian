package jian.io.sql;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SqlIoRegressionTest —— SQL 读写回归测试集
// │  Why  : 固化 jian-io-sql 行为(因为事务回滚、列宽不足指引、schema 点号表名、
// │         中文列名报错文案、含下划线表名的存在性判定、Oracle VARCHAR2 字符语义
// │         等边界行为一旦回归会丢数据或误判,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-sql);全用 H2 in-memory,无外部依赖
// │  When : 改动 Sql.write / tableExists / dtypeToSqlType / dropTableIfExists 后必须跑
// │  Where: jian-io-sql/src/test/java/jian/io/sql/SqlIoRegressionTest.java
// │  How  : 数据走向:H2 内存连接 → Sql.write/readTable → 断言行数/异常文案;
// │         Oracle 方言分支无 Oracle 环境,直接对 dtypeToSqlType(包私有)做 DDL 字符串断言。
class SqlIoRegressionTest {

    private Connection h2() throws SQLException {
        String name = "jian_sql_io_test_" + System.nanoTime();
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private DataFrame df(Object... pairs) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Object[]) pairs[i + 1]);
        return DataFrame.ofColumns(m);
    }

    private DataFrame oneRow() {
        return DataFrame.of(Schema.of("v", DType.LONG, "s", DType.STRING),
                new Object[][]{{1L, "x"}});
    }

    // ======================== 事务:写入失败自动回滚 ========================

    @Test
    void 写入失败自动回滚无悬挂行() throws Exception {
        try (Connection conn = h2()) {
            conn.setAutoCommit(false);
            DataFrame ok = df("s", new Object[]{"ab"});
            Sql.write(ok, conn, "t_rb", Sql.Mode.CREATE_OR_REPLACE);   // VARCHAR(4)
            conn.commit();
            // 再写超长 → insertBatch 失败;已 executeBatch 的批次必须被 rollback
            DataFrame bad = df("s", new Object[]{"ok1", "0123456789ABCDEF", "ok3"});
            assertThatThrownBy(() -> Sql.write(bad, conn, "t_rb", Sql.Mode.APPEND))
                    .isInstanceOf(SQLException.class);
            // 悬挂批次已被 rollback;显式 commit 不应落库任何行
            conn.commit();
            DataFrame read = Sql.readTable(conn, "t_rb");
            assertThat(read.rowCount()).isEqualTo(1);   // 只有首次写入的 "ab"
        }
    }

    // ======================== APPEND 列宽不足:报错带指引 ========================

    @Test
    void APPEND列宽不足报错带指引() throws Exception {
        try (Connection conn = h2()) {
            conn.setAutoCommit(true);
            Sql.write(df("s", new Object[]{"ab"}), conn, "t_w", Sql.Mode.CREATE_OR_REPLACE);
            assertThatThrownBy(() -> Sql.write(df("s", new Object[]{"0123456789ABCDEF"}), conn, "t_w", Sql.Mode.APPEND))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CREATE_OR_REPLACE");   // 指引重建
        }
    }

    // ======================== schema 点号表名 ========================

    @Test
    void schema点号表名APPEND可用() throws Exception {
        try (Connection conn = h2()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS S1");
            }
            DataFrame d = df("s", new Object[]{"x"});
            Sql.write(d, conn, "S1.T5", Sql.Mode.CREATE_OR_REPLACE);
            // tableExists 必须把 "S1.T5" 按 schema.表 精确匹配,不能误判不存在
            Sql.write(df("s", new Object[]{"y"}), conn, "S1.T5", Sql.Mode.APPEND);
            DataFrame r = Sql.readTable(conn, "S1.T5");
            assertThat(r.rowCount()).isEqualTo(2);
        }
    }

    // ======================== 中文列名:报错指向真实 API ========================

    @Test
    void 中文列名经引号包裹真实往返() {
        // 修复回归:原先对中文列名一刀切抛 IAE(提示 renameColumns 改 ASCII)是缺陷 ——
        // 主流库都支持中文标识符;现按需以库引号符包裹,中文/大小写混合列名原样建列、往返一致
        DataFrame d = DataFrame.of(Schema.of("中文列", DType.LONG, "AA_a啊", DType.STRING),
                new Object[][]{{1L, "v"}});
        try (Connection conn = h2()) {
            Sql.write(d, conn, "中文表", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame back = Sql.readTable(conn, "中文表");
            assertThat(back.columnNames()).containsExactly("中文列", "AA_a啊");   // 大小写+中文逐字保真
            assertThat(((Number) back.getColumn("中文列").get(0)).longValue()).isEqualTo(1L);
            assertThat(back.getColumn("AA_a啊").get(0)).isEqualTo("v");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== tableExists:JDBC 通配符误判防护 ========================

    @Test
    void 下划线表名不误匹配同长单字符差异表() throws Exception {
        // 因为 JDBC getTables 的 pattern 把 `_` 当单字符通配符,库里存在 DATAAX 时
        // pattern "data_x" 会误命中 → FAIL_IF_EXISTS 误抛"已存在",所以存在性判定
        // 必须精确比对,不走 pattern 匹配
        try (Connection conn = h2()) {
            Sql.write(oneRow(), conn, "DATAAX");
            // FAIL_IF_EXISTS 对不存在的 data_x 正常建表,不抛"已存在"
            assertThatCode(() -> Sql.write(oneRow(), conn, "data_x", Sql.Mode.FAIL_IF_EXISTS))
                    .as("data_x 与 DATAAX 仅一个字符之差,不得被 _ 通配误判为存在")
                    .doesNotThrowAnyException();
            // 两表独立共存
            assertThat(Sql.readTable(conn, "DATAAX").rowCount()).isEqualTo(1);
            assertThat(Sql.readTable(conn, "data_x").rowCount()).isEqualTo(1);
        }
    }

    @Test
    void APPEND模式含下划线表名不误判存在() throws Exception {
        // 锁正向行为:APPEND 对已存在的 _ 表正常追加(存在性判定精确 → 只 INSERT)
        try (Connection conn = h2()) {
            Sql.write(oneRow(), conn, "user_x", Sql.Mode.CREATE_OR_REPLACE);
            Sql.write(oneRow(), conn, "user_x", Sql.Mode.APPEND);   // 表存在 → 只 INSERT
            assertThat(Sql.readTable(conn, "user_x").rowCount()).isEqualTo(2);
        }
    }

    @Test
    void 含下划线表名存在时FAIL_IF_EXISTS仍正确抛() throws Exception {
        // 精确比对不回归:表真存在(同名)时 FAIL_IF_EXISTS 必须抛
        try (Connection conn = h2()) {
            Sql.write(oneRow(), conn, "data_x");
            assertThatThrownBy(() -> Sql.write(oneRow(), conn, "data_x", Sql.Mode.FAIL_IF_EXISTS))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("已存在");
        }
    }

    @Test
    void CREATE_OR_REPLACE重建含下划线表() throws Exception {
        try (Connection conn = h2()) {
            Sql.write(oneRow(), conn, "data_x", Sql.Mode.CREATE_OR_REPLACE);
            Sql.write(oneRow(), conn, "data_x", Sql.Mode.CREATE_OR_REPLACE);   // DROP + CREATE
            assertThat(Sql.readTable(conn, "data_x").rowCount()).isEqualTo(1);
        }
    }

    // ======================== Oracle VARCHAR2(n CHAR) 字符语义 ========================

    @Test
    void Oracle字符串列生成VARCHAR2字符语义() {
        // Oracle 分支:显式 CHAR(字符)语义 —— 因为 byte 语义下 String.length() 定宽的
        // 中文列会 ORA-12899,所以用 VARCHAR2(n CHAR)。dtypeToSqlType 为包私有,
        // 同包测试直接断言 DDL 字符串。
        String ddl = Sql.dtypeToSqlType(DType.STRING, "Oracle", 5);
        assertThat(ddl).as("Oracle 短文本应为 VARCHAR2(n CHAR)").isEqualTo("VARCHAR2(8 CHAR)");
        // 长文本走 CLOB,不受影响
        assertThat(Sql.dtypeToSqlType(DType.STRING, "Oracle", 5000)).isEqualTo("CLOB");
    }

    @Test
    void 非Oracle库VARCHAR不带CHAR后缀() {
        // 其它库行为不变:H2/PG/MySQL 用 VARCHAR(n)
        assertThat(Sql.dtypeToSqlType(DType.STRING, "H2", 5)).isEqualTo("VARCHAR(8)");
        assertThat(Sql.dtypeToSqlType(DType.STRING, "PostgreSQL", 5)).isEqualTo("VARCHAR(8)");
        assertThat(Sql.dtypeToSqlType(DType.STRING, "MySQL", 5)).isEqualTo("VARCHAR(8)");
    }
}
