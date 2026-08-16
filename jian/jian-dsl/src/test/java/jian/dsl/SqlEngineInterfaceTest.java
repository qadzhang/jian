package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SqlEngineInterface 通用接口测试 —— 验证可插拔引擎架构
// │  Why  : 接口设计的回归守护(能力探测/线程安全/DQL-DML 分离/向后兼容 execute)
// │  Who  : jian-dsl 模块测试套件
// │  When : mvn test(jian-dsl 模块)
// │  Where: jian-dsl/src/test/java/jian/dsl/SqlEngineInterfaceTest.java
class SqlEngineInterfaceTest {

    private DataFrame df() {
        return DataFrame.of(
            Schema.of("id", DType.LONG, "age", DType.LONG),
            new Object[][]{{1L, 20L}, {2L, 30L}, {3L, 25L}});
    }

    // ======================== 默认引擎元信息 ========================

    @Test
    void 默认引擎_是_SqlRegexEngine() {
        SqlEngines.reset();
        assertThat(SqlEngines.current().name()).isEqualTo("regex");
        assertThat(SqlEngines.current().version()).isEqualTo("1.0");
    }

    @Test
    void 默认引擎_线程安全_为_true() {
        assertThat(SqlEngines.current().isThreadSafe()).isTrue();
    }

    @Test
    void 默认引擎_描述非空() {
        assertThat(SqlEngines.current().description()).isNotEmpty();
        assertThat(SqlEngines.current().description()).containsAnyOf("regex", "jian", "正则");
    }

    @Test
    void 默认引擎_支持_DEFAULT方言() {
        assertThat(SqlEngines.current().supportedDialects()).contains(SqlDialect.DEFAULT);
    }

    // ======================== 能力探测 ========================

    @Test
    void SqlRegexEngine_能力面_精确报告() {
        SqlRegexEngine e = new SqlRegexEngine();
        // 应支持
        assertThat(e.supports(SqlEngineInterface.Capability.SELECT_BASIC)).isTrue();
        assertThat(e.supports(SqlEngineInterface.Capability.GROUP_HAVING)).isTrue();
        assertThat(e.supports(SqlEngineInterface.Capability.JOIN)).isTrue();
        assertThat(e.supports(SqlEngineInterface.Capability.UNION)).isTrue();
        assertThat(e.supports(SqlEngineInterface.Capability.CTE)).isTrue();
        assertThat(e.supports(SqlEngineInterface.Capability.CASE_WHEN)).isTrue();
        assertThat(e.supports(SqlEngineInterface.Capability.INSERT)).isTrue();
        // 不支持
        assertThat(e.supports(SqlEngineInterface.Capability.WINDOW_FUNCTIONS)).isFalse();
        assertThat(e.supports(SqlEngineInterface.Capability.CAST)).isFalse();
    }

    @Test
    void capabilities_方法返回完整能力集合() {
        Set<SqlEngineInterface.Capability> caps = SqlEngines.currentCapabilities();
        assertThat(caps).contains(SqlEngineInterface.Capability.SELECT_BASIC);
        assertThat(caps).doesNotContain(SqlEngineInterface.Capability.WINDOW_FUNCTIONS);
    }

    @Test
    void currentSupports_快捷方法() {
        assertThat(SqlEngines.currentSupports(SqlEngineInterface.Capability.GROUP_HAVING)).isTrue();
        assertThat(SqlEngines.currentSupports(SqlEngineInterface.Capability.WINDOW_FUNCTIONS)).isFalse();
    }

    // ======================== DQL/DML 分离 ========================

    @Test
    void query_执行SELECT() {
        DataFrame r = SqlEngines.current().query(df(), "SELECT * FROM this WHERE age > 22", Map.of());
        assertThat(r.rowCount()).isEqualTo(2);  // 30, 25 > 22
    }

    @Test
    void update_INSERT() {
        Map<String, DataFrame> bindings = new java.util.HashMap<>();
        DataFrame df = df();
        int affected = SqlEngines.current().update(df, "INSERT INTO ${t} (id, age) VALUES (4, 40)",
            new java.util.HashMap<>(Map.of("t", df)), SqlDialect.DEFAULT);
        assertThat(affected).isEqualTo(1);
    }

    @Test
    void update_DELETE() {
        DataFrame df = df();
        Map<String, DataFrame> b = new java.util.HashMap<>(Map.of("t", df));
        int affected = SqlEngines.current().update(df, "DELETE FROM ${t} WHERE age < 25", b, SqlDialect.DEFAULT);
        assertThat(affected).isEqualTo(1);  // id=1 age=20 < 25
    }

    @Test
    void update_UPDATE() {
        DataFrame df = df();
        Map<String, DataFrame> b = new java.util.HashMap<>(Map.of("t", df));
        int affected = SqlEngines.current().update(df, "UPDATE ${t} SET age = 99 WHERE id == 1", b, SqlDialect.DEFAULT);
        assertThat(affected).isEqualTo(1);  // id=1 命中
    }

    // ======================== 向后兼容(execute 委托 query)========================

    @Test
    void execute_SELECT走query() {
        DataFrame r = SqlEngines.current().execute(df(), "SELECT * FROM this WHERE age > 22", Map.of(), SqlDialect.DEFAULT);
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void execute_非SELECT_INSERT_UPDATE_DELETE抛IAE() {
        assertThatThrownBy(() -> SqlEngines.current().execute(df(), "BEGIN INSERT INTO x VALUES(1); END;", Map.of(), SqlDialect.DEFAULT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_WITH_走query路径() {
        // WITH 在最前,execute 应识别为 DQL 走 query
        DataFrame r = SqlEngines.current().execute(
            df(), "WITH young AS (SELECT * FROM this WHERE age < 28) SELECT * FROM ${young}", Map.of(),
            SqlDialect.DEFAULT);
        assertThat(r.rowCount()).isEqualTo(2);  // 20, 25 < 28
    }

    // ======================== 引擎切换 ========================

    @Test
    void useRegex_切换默认引擎() {
        SqlEngines.useRegex();
        assertThat(SqlEngines.current().name()).isEqualTo("regex");
    }

    @Test
    void useCustom_接入自定义引擎() {
        final DataFrame marker = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{99L}});
        SqlEngines.useCustom(new SqlEngineInterface() {
            @Override public String name() { return "test-custom"; }
            @Override public DataFrame query(DataFrame defaultDf, String sql,
                                              Map<String, DataFrame> bindings, SqlDialect dialect) {
                return marker;
            }
        });
        try {
            assertThat(SqlEngines.current().name()).isEqualTo("test-custom");
            DataFrame r = Dsl.sql("SELECT * FROM ${t}",
                DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}}));
            assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(99L);
        } finally {
            SqlEngines.reset();
        }
    }

    @Test
    void useCustom_null抛IAE() {
        assertThatThrownBy(() -> SqlEngines.useCustom(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reset_恢复默认() {
        SqlEngines.useRegex();
        SqlEngines.reset();
        assertThat(SqlEngines.current().name()).isEqualTo("regex");
    }

    @Test
    void ThreadLocal_不同线程独立() throws Exception {
        // 引擎选择应线程隔离:主线程改了,子线程仍是默认
        SqlEngines.useCustom(new SqlEngineInterface() {
            @Override public String name() { return "main-only"; }
            @Override public DataFrame query(DataFrame df, String sql,
                                              Map<String, DataFrame> b, SqlDialect d) { return df; }
        });
        Thread t = new Thread(() -> {
            // 子线程应是默认 regex,不受主线程 useCustom 影响
            assertThat(SqlEngines.current().name()).isEqualTo("regex");
        });
        t.start();
        t.join();
        SqlEngines.reset();
    }
}
