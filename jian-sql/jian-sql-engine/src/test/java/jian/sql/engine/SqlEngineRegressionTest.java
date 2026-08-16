package jian.sql.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SqlEngineRegressionTest —— Engine 回归测试集(固化连接/URL 解析/只读拦截/连接池归还行为)
// │  Why  : 因为这些行为是安全与资源管理的底线(密码脱敏、只读拦截、链式调用不泄漏池连接),
// │         所以用回归测试固化,防未来退化
// │  Who  : JUnit 5 自动执行
// │  When : mvn test(jian-sql-engine 模块)
// │  Where: jian-sql-engine/src/test/java/jian/sql/engine/SqlEngineRegressionTest.java
// │  How  : 覆盖四块行为:
// │           ①URL 解析与脱敏:畸形 URL 异常消息不含密码段;sanitize '@' 定位与 parseUrl 同口径;
// │           ②只读拦截:readOnly 引擎 dsl() 抛 SecurityException 且 Hikari 只读;
// │             写关键字整词 + 大小写不敏感全拦截;注释/字符串/反引号/$$ 内的关键字不误拦;
// │           ③注释语义:行注释内的写关键字不拦截(注释不执行),注释外的仍拦截;
// │           ④连接归还:engine.sql(...).fetch()/execute() 链式循环不耗尽默认池(10)。
class SqlEngineRegressionTest {

    private Engine h2Engine(String tag) {
        return Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:" + tag + "_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
    }

    private Engine readOnlyEngine(String tag) {
        return Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:" + tag + "_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").readOnly(true).build());
    }

    // ======================== URL 解析与脱敏 ========================

    @Test
    void 畸形URL异常不回显密码() {
        // 因为异常消息若直接拼接原文会把密码段明文泄露,所以过 sanitize
        String mal = "postgresql://user:SECRET_PASSWORD@host:5432/db";
        assertThatThrownBy(() -> Engine.parseUrl("postgresql:user:SECRET_PASSWORD@host:5432/db"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET_PASSWORD"))
                .satisfies(e -> assertThat(e.getMessage()).contains("***"));
        assertThatThrownBy(() -> DbType.fromUrl("unknownscheme://user:SECRET@host/db"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
    }

    @Test
    void 密码含at时脱敏不残留尾段() {
        // 若取第一个 '@' 会把 "ss" 明文残留在掩码外,所以取 lastIndexOf('@')
        String s = JianSqlException.sanitize("postgresql://user:p@ss@host:5432/db");
        assertThat(s).isEqualTo("postgresql://user:***@host:5432/db");
        assertThat(s).doesNotContain("p@ss");
        assertThat(s).doesNotContain("ss@");
    }

    @Test
    void 密码含多个at与含冒号仍全脱敏() {
        // lastIndexOf 取最后一个 '@'(host/db 不含 @),密码里的 @ 与 : 都在掩码段内
        String s = JianSqlException.sanitize("mysql://root:p@ss:word@host:3306/db");
        assertThat(s).isEqualTo("mysql://root:***@host:3306/db");
        assertThat(s).doesNotContain("word");
    }

    @Test
    void OracleThinURL无userinfo段不误替换() {
        // "jdbc:oracle:thin:@host:1521:db" 的 ':' 紧贴 '@'(无密码段),不应被误脱敏;
        // 无 "://" 的 URL 没有 SQLAlchemy user:pass@ 段,原样返回
        String u = "jdbc:oracle:thin:@host:1521:db";
        assertThat(JianSqlException.sanitize(u)).isEqualTo(u);
        // 无 '@' 的 URL 原样返回
        assertThat(JianSqlException.sanitize("jdbc:sqlite:/tmp/x.db")).isEqualTo("jdbc:sqlite:/tmp/x.db");
        // 普通密码 URL 全脱敏
        assertThat(JianSqlException.sanitize("jdbc:postgresql://user:secret@host:5432/db"))
                .isEqualTo("jdbc:postgresql://user:***@host:5432/db");
        // 只有 user 无密码 → 原样返回
        assertThat(JianSqlException.sanitize("postgresql://user@host:5432/db"))
                .isEqualTo("postgresql://user@host:5432/db");
    }

    // ======================== 只读拦截 ========================

    @Test
    void 只读引擎dsl入口被拒绝() {
        // 因为 SqlBuilder 可执行 DDL/DML 而入口无法逐语句校验,所以 readOnly 时 dsl() 抛 SecurityException
        try (Engine ro = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:r6_ro_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").readOnly(true).build())) {
            assertThatThrownBy(ro::dsl)
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("只读");
            // sql() 写操作仍被拦(原有防线回归)
            assertThatThrownBy(() -> ro.sql("DROP TABLE IF EXISTS x"))
                    .isInstanceOf(SecurityException.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 只读拦截REPLACE_CALL_COPY_LOAD写关键字() throws Exception {
        // REPLACE INTO / CALL proc / COPY FROM / LOAD DATA 都是写操作,必须整词拦截
        try (Engine ro = readOnlyEngine("r9_kw")) {
            for (String sql : new String[]{
                    "REPLACE INTO t VALUES (1)",
                    "replace into t values(1)",               // 大小写不敏感
                    "CALL write_proc()",
                    "call write_proc(1, 2)",
                    "COPY t FROM '/f.csv'",
                    "copy t from stdin",
                    "LOAD DATA INFILE '/f' INTO TABLE t",
                    "load data infile '/f' into table t"}) {
                assertThatThrownBy(() -> ro.sql(sql))
                        .as("写关键字 SQL 应被拦截:%s", sql)
                        .isInstanceOf(SecurityException.class);
            }
            // MERGE(既有 10 词)行为不回归
            assertThatThrownBy(() -> ro.sql("MERGE INTO t USING s ON (1=1)"))
                    .isInstanceOf(SecurityException.class);
            // SELECT 仍放行(只读引擎上真执行)
            try (Connection c = ro.connect(); Statement st = c.createStatement()) {
                st.execute("SELECT 1");
            }
        }
    }

    @Test
    void 只读拦截大小写不敏感() {
        // 因为关键字模式带 (?i),小写/混合大小写的写操作全拦截
        try (Engine ro = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:r8_ci_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").readOnly(true).build())) {
            for (String kw : new String[]{"drop table t", "delete from t", "truncate table t",
                    "alter table t add c int", "create table t(x int)", "grant select on t to u",
                    "insert into t values(1)", "update t set x=1", "DeLeTe FROM t", "MeRgE INTO t"}) {
                assertThatThrownBy(() -> ro.sql(kw).fetch())
                        .as("小写/混合 %s 应被拦截", kw)
                        .isInstanceOf(SecurityException.class);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 反引号标识符的SELECT不误拦() throws Exception {
        // `delete` 是列名不是关键字,scrub 按字面量剥除反引号段,合法 SELECT 放行
        try (Engine ro = readOnlyEngine("r9_bt")) {
            assertThatCode(() -> ro.sql("SELECT `delete` FROM t"))
                    .as("反引号标识符按字面量剥除,SELECT 应放行")
                    .doesNotThrowAnyException();
            assertThatCode(() -> ro.sql("SELECT `update`, `insert` FROM t")).doesNotThrowAnyException();
            // 反引号列名后跟真正的写语句仍拦截(剥除不等于放行一切)
            assertThatThrownBy(() -> ro.sql("SELECT `delete` FROM t; DROP TABLE t"))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void 井号行注释被剥除_注释后的DROP仍拦截() throws Exception {
        // MySQL '#' 行注释 —— 注释剥掉后,注释外的整词照常匹配
        try (Engine ro = readOnlyEngine("r9_hash")) {
            assertThatThrownBy(() -> ro.sql("SELECT 1 # 备注\nDROP TABLE t"))
                    .as("'#' 后换行再 DROP:DROP 在注释外,应拦截")
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> ro.sql("DELETE FROM t # 删数据"))
                    .as("'#' 注释在语句尾:前面的 DELETE 仍拦截")
                    .isInstanceOf(SecurityException.class);
            // 注释里的写关键字不误报(注释不执行)
            assertThatCode(() -> ro.sql("SELECT 1 # DROP TABLE t")).doesNotThrowAnyException();
        }
    }

    @Test
    void PG美元引号字符串不误拦() throws Exception {
        // $$...$$ 是 PG 字符串(函数体常用),内容按字面量剥除
        try (Engine ro = readOnlyEngine("r9_dollar")) {
            assertThatCode(() -> ro.sql("SELECT $$DROP TABLE x$$ AS s"))
                    .doesNotThrowAnyException();
            // $$ 串外的真写语句仍拦
            assertThatThrownBy(() -> ro.sql("SELECT 1; SELECT $$a$$; TRUNCATE TABLE t"))
                    .isInstanceOf(SecurityException.class);
        }
    }

    // ======================== 注释语义 ========================

    @Test
    void 行注释内写关键字不拦截_注释外拦截() {
        // 注释里的 DROP 不执行 → 不拦截是正确语义;同一 SQL 注释外的 DROP 仍拦截。
        // 防未来 scrub 逻辑退化
        try (Engine ro = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:r6_ro2_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").readOnly(true).build())) {
            // 注释内 DROP:不抛(注释不执行)
            ro.sql("SELECT 1 -- DROP TABLE t");
            // 注释外 DROP:拦截
            assertThatThrownBy(() -> ro.sql("SELECT 1; DROP TABLE t"))
                    .isInstanceOf(SecurityException.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== 连接归还(链式调用不泄漏池连接) ========================

    @Test
    void 链式sqlFetch循环15次不耗尽默认10连接池() throws Exception {
        // SqlBuilder.fetch 完成后自动归还连接,循环 15 次(超过默认池 10)全部正常返回
        try (Engine engine = h2Engine("r9_leak")) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE u (id BIGINT, name VARCHAR(50))");
                st.execute("INSERT INTO u VALUES (1, 'alice')");
            }
            for (int i = 0; i < 15; i++) {
                org.jooq.Result<org.jooq.Record> r = engine.sql("SELECT name FROM u WHERE id = ?", 1).fetch();
                assertThat(r.size()).as("第 %d 次循环", i).isEqualTo(1);
            }
        }
    }

    @Test
    void 链式execute后连接同样归还() throws Exception {
        // execute() 路径与 fetch() 同款归还;后续仍能正常借连接写读
        try (Engine engine = h2Engine("r9_leak2")) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE u (id BIGINT, name VARCHAR(50))");
            }
            engine.sql("INSERT INTO u VALUES (?, ?)", 1, "alice").execute();
            engine.sql("INSERT INTO u VALUES (?, ?)", 2, "bob").execute();
            assertThat(engine.sql("SELECT COUNT(*) FROM u").fetch().get(0).get(0))
                    .isEqualTo(2L);
        }
    }
}
