package jian.sql.orm;

import jian.sql.engine.DbType;
import jian.sql.engine.Engine;
import jian.sql.engine.EngineConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : SessionRegressionTest —— Session 回归测试集(固化 ORM 映射与写路径防线行为)
// │  Why  : 因为实体映射(UPDATE SET 主键、缺列、NULL、富类型适配、自增主键回填)与
// │         只读拦截是 ORM 层的行为底线,所以用回归测试固化,防未来退化;
// │         期望值锚定 SQLAlchemy/pandas 语义
// │  Who  : JUnit 5 自动执行(surefire)
// │  When : mvn test(jian-sql-orm 模块)
// │  Where: jian-sql-orm/src/test/java/jian/sql/orm/SessionRegressionTest.java
// │  How  : H2 内存库建表插入边界数据 → Session 各 API 断言(生成 SQL/缺列/NULL/类型适配/只读拦截)。
// │         两组夹具:静态共享引擎(基础 CRUD 映射)+ 每测试独立引擎(只读/富类型/自增场景)。
class SessionRegressionTest {

    static Engine engine;
    static Session<BasicUser> session;

    /** 基础实体(id/name/age)。 */
    @Table("users")
    public static class BasicUser {
        @Id @Column("id") public Long id;
        @Column("name") public String name;
        @Column("age") public Integer age;
    }

    /** 基本类型 int age 的实体(DB age 列 NULL 映射 int 的 fail-fast 场景)。 */
    @Table("users")
    public static class UserPrim {
        @Id @Column("id") public Long id;
        @Column("age") public int age;
    }

    /** 色彩枚举(enum 字段往返)。 */
    enum Color { RED, GREEN }

    /** 基础实体(id/name/age,带构造器)。 */
    @Table("users")
    static class UserRow {
        @Id @Column("id") public Long id;
        @Column("name") public String name;
        @Column("age") public Integer age;
        public UserRow() {}
        UserRow(Long id, String name, Integer age) { this.id = id; this.name = name; this.age = age; }
    }

    /** 富类型实体(BigDecimal/Boolean/enum/LocalDate/LocalDateTime 字段)。 */
    @Table("rich_rows")
    static class RichRow {
        @Id @Column("id") public Long id;
        @Column("price") public BigDecimal price;         // H2 DECIMAL → BigDecimal(保精度)
        @Column("active") public Boolean active;          // H2 BOOLEAN
        @Column("color") public Color color;              // H2 VARCHAR → enum
        @Column("bday") public LocalDate bday;              // H2 DATE → java.sql.Date → LocalDate
        @Column("ts") public LocalDateTime ts;            // H2 TIMESTAMP → LocalDateTime
    }

    /** TIMESTAMP 列 → LocalDate 字段实体(LocalDateTime 截日期分支)。 */
    @Table("ts_days")
    static class TsDayRow {
        @Id @Column("id") public Long id;
        @Column("ts") public LocalDate ts;                // 列是 TIMESTAMP,字段是 LocalDate
    }

    /** TINYINT 0/1 列 → Boolean 字段实体(模拟 SQLite 的 BOOLEAN 存 INTEGER)。 */
    @Table("tiny_rows")
    static class TinyRow {
        @Id @Column("id") public Long id;
        @Column("flag") public Boolean flag;
    }

    /** 自增主键实体(id=null 插入后回填)。 */
    @Table("auto_users")
    static class AutoUser {
        @Id @Column("id") public Long id;
        @Column("name") public String name;
        public AutoUser() {}
        AutoUser(Long id, String name) { this.id = id; this.name = name; }
    }

    @BeforeAll
    static void setup() throws Exception {
        engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:r6_orm_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
        engine.sql("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR, age INT)").execute();
        engine.sql("INSERT INTO users VALUES (1, 'alice', 30), (2, NULL, NULL)").execute();
        session = new Session<>(engine, BasicUser.class);
    }

    @AfterAll
    static void teardown() { engine.close(); }

    private Engine h2Engine(String name) throws Exception {
        Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:" + name + "_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
        try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100), age INT)");
            st.execute("CREATE TABLE rich_rows (id BIGINT PRIMARY KEY, price DECIMAL(30,18),"
                    + " active BOOLEAN, color VARCHAR(20), bday DATE, ts TIMESTAMP)");
            st.execute("CREATE TABLE ts_days (id BIGINT PRIMARY KEY, ts TIMESTAMP)");
            st.execute("CREATE TABLE tiny_rows (id BIGINT PRIMARY KEY, flag TINYINT)");
            st.execute("CREATE TABLE auto_users (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50))");
        }
        return engine;
    }

    /** 在既有 mem 库上建只读引擎(表由可写引擎先建好;同一 DB_CLOSE_DELAY=-1 库互通)。 */
    private Engine readOnlyEngineOn(String name) {
        return Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:" + name + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").readOnly(true).build());
    }

    // ======================== CRUD 映射语义 ========================

    @Test
    void update的SET子句不含主键() throws Exception {
        // 因为主键同时进 SET 与 WHERE 语义错(对齐 SQLAlchemy 只对非 PK 列 SET),
        // 所以生成 SQL 应为 "UPDATE users SET name=?,age=? WHERE id=?"。
        // 行为验证:改 name 后 id 不变、按 id 能再查到
        BasicUser u = session.findById(1L);
        u.name = "alice2";
        int affected = session.update(u);
        assertThat(affected).isEqualTo(1);
        BasicUser back = session.findById(1L);
        assertThat(back.id).isEqualTo(1L);        // 主键未被 SET 破坏
        assertThat(back.name).isEqualTo("alice2");
    }

    @Test
    void 缺列查询不炸_缺失字段为null() throws Exception {
        // SELECT 未投影实体全部列时缺列字段保持 null,整条查询成功(对齐 SQLAlchemy 只填充已选列)
        List<BasicUser> list = session.list();
        assertThat(list).hasSize(2);
        assertThat(list.get(1).id).isEqualTo(2L);
        assertThat(list.get(1).name).isNull();    // DB NULL → 字段 null 不炸
    }

    @Test
    void 基本类型字段遇NULL_failfast教学() throws Exception {
        // 基本类型 int 字段无法承载 DB NULL,清晰 IllegalStateException 提示改包装类型(不静默填 0)
        Session<UserPrim> sp = new Session<>(engine, UserPrim.class);
        assertThatThrownBy(() -> sp.findById(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("基本类型")
                .hasMessageContaining("包装类型");
    }

    @Test
    void 数值类型容错互转() throws Exception {
        // DB INT → Integer 字段、DB BIGINT → Long 字段:Number 容错互转
        BasicUser u = session.findById(1L);
        assertThat(u.age).isEqualTo(30);          // DB INT → Integer 字段
        assertThat(u.id).isEqualTo(1L);           // DB BIGINT → Long
    }

    // ======================== 只读引擎拦截 Session 写操作 ========================

    @Test
    void 只读引擎insert被SecurityException拦截() throws Exception {
        // 因为只读引擎的所有写入口都必须拦截,Session 的 CRUD 也过 checkReadOnly
        try (Engine rw = h2Engine("r9_ro"); Engine ro = readOnlyEngineOn(
                rw.config().path.substring("mem:".length()))) {
            try (Connection c = rw.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO users VALUES (1, 'alice', 30)");
            }
            Session<UserRow> s = new Session<>(ro, UserRow.class);
            assertThatThrownBy(() -> s.insert(new UserRow(2L, "bob", 25)))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("只读模式禁止写操作")
                    .hasMessageContaining("INSERT INTO users");
            // 读路径不受影响(只读引擎 SELECT 正常)
            assertThat(s.findById(1L).name).isEqualTo("alice");
            assertThat(s.list()).hasSize(1);
        }
    }

    @Test
    void 只读引擎update与delete同样被拦截() throws Exception {
        try (Engine rw = h2Engine("r9_ro2"); Engine ro = readOnlyEngineOn(
                rw.config().path.substring("mem:".length()))) {
            try (Connection c = rw.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO users VALUES (1, 'alice', 30)");
            }
            Session<UserRow> s = new Session<>(ro, UserRow.class);
            UserRow u = s.findById(1L);
            u.age = 31;
            assertThatThrownBy(() -> s.update(u))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("UPDATE users SET");
            assertThatThrownBy(() -> s.delete(u))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("DELETE FROM users");
        }
    }

    // ======================== adaptValue 富类型适配 ========================

    @Test
    void 富类型实体读回_BigDecimal保精度_Boolean_enum_LocalDate() throws Exception {
        // BigDecimal 经 String 构造 round-trip 精度无损(18 位小数 double 接不住);
        // enum/Boolean(0/1)/LocalDate(DATETIME)字段各有适配分支。
        // adaptValue 在读路径(mapRow),故用原生 SQL 写入、Session.findById 读回验证
        try (Engine engine = h2Engine("r9_rich")) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO rich_rows VALUES (1,"
                        + " 123.456789012345678901, TRUE, 'GREEN',"
                        + " DATE '2026-08-16', TIMESTAMP '2026-08-16 10:15:30')");
            }
            Session<RichRow> s = new Session<>(engine, RichRow.class);
            RichRow back = s.findById(1L);
            assertThat(back.price).isNotNull();
            // 18 位小数,double 接不住 —— 经 String 构造 round-trip 精度无损
            assertThat(back.price.compareTo(new BigDecimal("123.456789012345678901"))).isZero();
            assertThat(back.active).isTrue();
            assertThat(back.color).isEqualTo(Color.GREEN);      // VARCHAR → enum
            assertThat(back.bday).isEqualTo(LocalDate.of(2026, 8, 16));
            assertThat(back.ts).isEqualTo(LocalDateTime.of(2026, 8, 16, 10, 15, 30));
        }
    }

    @Test
    void Number的0和1映射Boolean字段_SQLite式存储() throws Exception {
        // SQLite 的 BOOLEAN 列经 JDBC 是 0/1 Number;H2 用 TINYINT 模拟同款输入
        try (Engine engine = h2Engine("r9_tiny")) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO tiny_rows VALUES (1, 1), (2, 0)");
            }
            Session<TinyRow> s = new Session<>(engine, TinyRow.class);
            assertThat(s.findById(1L).flag).isTrue();    // 1 → true
            assertThat(s.findById(2L).flag).isFalse();   // 0 → false
        }
    }

    @Test
    void LocalDateTime列截日期进LocalDate字段() throws Exception {
        // MySQL DATETIME / H2 TIMESTAMP 读回是 LocalDateTime,LocalDate 字段截时间部分
        try (Engine engine = h2Engine("r9_tsday")) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO ts_days VALUES (1, TIMESTAMP '2026-08-16 23:59:59')");
            }
            Session<TsDayRow> s = new Session<>(engine, TsDayRow.class);
            assertThat(s.findById(1L).ts).isEqualTo(LocalDate.of(2026, 8, 16));
        }
    }

    @Test
    void enum字段遇未知常量抛教学型IAE() throws Exception {
        // DB 存了 'BLUE'(无此常量)→ 教学型 IAE(含常量与区分大小写提示)
        try (Engine engine = h2Engine("r9_enum")) {
            try (Connection c = engine.connect(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO rich_rows VALUES (9, 1.0, TRUE, 'BLUE',"
                        + " DATE '2026-01-01', TIMESTAMP '2026-01-01 00:00:00')");
            }
            Session<RichRow> s = new Session<>(engine, RichRow.class);
            assertThatThrownBy(() -> s.findById(9L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("无常量")
                    .hasMessageContaining("BLUE");
        }
    }

    // ======================== 自增主键回填 ========================

    @Test
    void insert的id为null时跳过主键列并回填自增键() throws Exception {
        // id=null 时主键交给库生成(RETURN_GENERATED_KEYS),插入成功后反射回填实体
        try (Engine engine = h2Engine("r9_auto")) {
            Session<AutoUser> s = new Session<>(engine, AutoUser.class);
            AutoUser alice = new AutoUser(null, "alice");   // id=null → 交给库生成
            assertThat(s.insert(alice)).isEqualTo(1);
            assertThat(alice.id).isEqualTo(1L);             // 反射回填(可写 + Number 字段)
            AutoUser bob = new AutoUser(null, "bob");
            s.insert(bob);
            assertThat(bob.id).isEqualTo(2L);
            assertThat(s.list()).hasSize(2);
            // 库里确实按自增写入
            assertThat(s.findById(1L).name).isEqualTo("alice");
        }
    }

    @Test
    void insert的id非null时行为不变() throws Exception {
        // 显式主键照旧写进 INSERT(不进 RETURN_GENERATED_KEYS 分支)
        try (Engine engine = h2Engine("r9_auto2")) {
            Session<AutoUser> s = new Session<>(engine, AutoUser.class);
            AutoUser u = new AutoUser(100L, "carol");
            s.insert(u);
            assertThat(u.id).isEqualTo(100L);               // 不被覆盖
            assertThat(s.findById(100L).name).isEqualTo("carol");
        }
    }
}
