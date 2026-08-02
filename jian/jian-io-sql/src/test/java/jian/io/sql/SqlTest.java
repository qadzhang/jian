package jian.io.sql;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTest {

    private Connection h2() throws Exception {
        // H2 内存库(每个测试唯一名,避免冲突)
        String name = "jian_test_" + System.nanoTime();
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void 写出后读回一致() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                    new Object[][]{
                            {1L, "alice", 90.5},
                            {2L, "bob", 85.0},
                            {3L, "carol", 76.5}
                    });
            Sql.write(df, conn, "users");
            DataFrame r = Sql.readTable(conn, "users");
            assertThat(r.rowCount()).isEqualTo(3);
            assertThat(r.columnNames()).containsExactly("ID", "NAME", "SCORE");  // H2 默认大写
            assertThat(r.getStringColumn("NAME").get(0)).isEqualTo("alice");
            assertThat(r.getDoubleColumn("SCORE").getDouble(1)).isEqualTo(85.0);
        }
    }

    @Test
    void 参数化查询() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(
                    Schema.of("id", DType.LONG, "age", DType.LONG),
                    new Object[][]{{1L, 30L}, {2L, 25L}, {3L, 40L}});
            Sql.write(df, conn, "people");
            DataFrame r = Sql.readQuery(conn, "SELECT * FROM people WHERE age > ?", 28);
            assertThat(r.rowCount()).isEqualTo(2);  // age=30, 40
        }
    }

    @Test
    void APPEND模式累加() throws Exception {
        try (Connection conn = h2()) {
            DataFrame a = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
            DataFrame b = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{2L}, {3L}});
            Sql.write(a, conn, "nums", Sql.Mode.CREATE_OR_REPLACE);
            Sql.write(b, conn, "nums", Sql.Mode.APPEND);
            DataFrame r = Sql.readTable(conn, "nums");
            assertThat(r.rowCount()).isEqualTo(3);
        }
    }

    @Test
    void FAIL_IF_EXISTS抛异常() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}});
            Sql.write(df, conn, "t", Sql.Mode.CREATE_OR_REPLACE);
            try {
                Sql.write(df, conn, "t", Sql.Mode.FAIL_IF_EXISTS);
                org.assertj.core.api.Assertions.fail("应抛异常");
            } catch (java.sql.SQLException e) {
                assertThat(e.getMessage()).contains("已存在");
            }
        }
    }

    @Test
    void 缺失值往返() throws Exception {
        try (Connection conn = h2()) {
            DataFrame df = DataFrame.of(
                    Schema.of("v", DType.DOUBLE),
                    new Object[][]{{1.0}, {null}, {3.0}});
            Sql.write(df, conn, "na");
            DataFrame r = Sql.readTable(conn, "na");
            assertThat(r.rowCount()).isEqualTo(3);
            // NULL 经 JDBC 读回 null,Schema 推断 + 中间列保留
        }
    }
}
