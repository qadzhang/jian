package jian.sql.orm;

import jian.sql.engine.DbType;
import jian.sql.engine.Engine;
import jian.sql.engine.EngineConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Table("users")
class User {
    @Id @Column("id") public Long id;
    @Column("name") public String name;
    @Column("age") public Integer age;

    public User() {}
    public User(Long id, String name, Integer age) { this.id = id; this.name = name; this.age = age; }
}

@Table("用户")
class 中文用户 {
    @Id @Column("编号") public Long 编号;
    @Column("姓名") public String 姓名;
    @Column("余额") public Long 余额;
    public 中文用户() {}
    public 中文用户(Long id, String name, Long bal) { this.编号 = id; this.姓名 = name; this.余额 = bal; }
}

class SessionTest {

    private Engine h2Engine() throws Exception {
        Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:orm_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
        try (Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100), age INT)");
        }
        return engine;
    }

    @Test
    void insert和findById() throws Exception {
        try (Engine engine = h2Engine()) {
            Session<User> s = new Session<>(engine, User.class);
            s.insert(new User(1L, "alice", 30));
            s.insert(new User(2L, "bob", 25));
            User u = s.findById(1L);
            assertThat(u).isNotNull();
            assertThat(u.name).isEqualTo("alice");
            assertThat(u.age).isEqualTo(30);
        }
    }

    @Test
    void list返回全部() throws Exception {
        try (Engine engine = h2Engine()) {
            Session<User> s = new Session<>(engine, User.class);
            s.insert(new User(1L, "a", 1));
            s.insert(new User(2L, "b", 2));
            s.insert(new User(3L, "c", 3));
            List<User> all = s.list();
            assertThat(all).hasSize(3);
        }
    }

    @Test
    void update修改() throws Exception {
        try (Engine engine = h2Engine()) {
            Session<User> s = new Session<>(engine, User.class);
            s.insert(new User(1L, "alice", 30));
            User u = s.findById(1L);
            u.age = 31;
            s.update(u);
            User r = s.findById(1L);
            assertThat(r.age).isEqualTo(31);
        }
    }

    @Test
    void delete删除() throws Exception {
        try (Engine engine = h2Engine()) {
            Session<User> s = new Session<>(engine, User.class);
            s.insert(new User(1L, "a", 1));
            s.insert(new User(2L, "b", 2));
            User u = s.findById(1L);
            s.delete(u);
            assertThat(s.list()).hasSize(1);
        }
    }

    @Test
    void findById不存在返回null() throws Exception {
        try (Engine engine = h2Engine()) {
            Session<User> s = new Session<>(engine, User.class);
            assertThat(s.findById(99L)).isNull();
        }
    }

    @Test
    void 无Table注解抛异常() {
        class NoTable {}
        try {
            new Session<>(null, (Class<NoTable>) NoTable.class);
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("@Table");
        }
    }

    @Test
    void 注入式标识符构造期放行_控制字符构造期硬拒() {
        // 新语义(与 jian-io-sql 同防线):注入元字符表名/列名构造期不再白名单硬拒 ——
        // 真正防线在 SQL 拼接期的 quoteTable/quoteIdentifier 按需引号包裹+双写转义,
        // 注入串整体成为字面量标识符(引号内 ; -- 无语法效力);控制字符构造期即拒。
        @Table("x; DROP TABLE users; --")
        class EvilTable { @Id @Column("id") public Long id; }
        org.assertj.core.api.Assertions.assertThatCode(() -> new Session<>(null, (Class<EvilTable>) EvilTable.class))
                .as("注入式表名构造期放行(防线后移到引号化)").doesNotThrowAnyException();

        @Table("bad\tname")
        class CtlTable { @Id @Column("id") public Long id; }
        assertThatThrownBy(() -> new Session<>(null, (Class<CtlTable>) CtlTable.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制字符");

        @Table("ok_tbl")
        class CtlCol { @Id @Column("a\tb") public Long id; }
        assertThatThrownBy(() -> new Session<>(null, (Class<CtlCol>) CtlCol.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制字符");
    }

    // ======================== 中文标识符(引号保真)========================

    @Test
    void 中文表名列名引号保真CRUD全链() throws Exception {
        Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:orm_cn_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
        try (engine; Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"用户\" (\"编号\" BIGINT PRIMARY KEY, \"姓名\" VARCHAR(100), \"余额\" BIGINT)");
            Session<中文用户> s = new Session<>(engine, 中文用户.class);
            s.insert(new 中文用户(1L, "张三", 100L));
            s.insert(new 中文用户(2L, "李四", 200L));
            中文用户 u = s.findById(1L);
            assertThat(u.姓名).isEqualTo("张三");
            assertThat(u.余额).isEqualTo(100L);
            assertThat(s.list()).hasSize(2);
            u.余额 = 300L;
            s.update(u);
            assertThat(s.findById(1L).余额).isEqualTo(300L);
            assertThat(s.delete(u)).isEqualTo(1);
            assertThat(s.list()).hasSize(1);
        }
    }

    // ======================== 类层级字段 / String 主键回填 ========================

    @Table("base_users")
    static class BaseUser {
        @Id public Long id;
        @Column("created_at") public java.time.LocalDateTime createdAt;
    }

    @Table("base_users")
    static class SubUser extends BaseUser {
        @Column("name") public String name;
    }

    @Test
    void 父类字段参与映射_不再静默丢弃() throws Exception {
        try (Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:orm_hier_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
             Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE base_users (id BIGINT PRIMARY KEY, created_at TIMESTAMP, name VARCHAR(100))");
            SubUser u = new SubUser();
            u.id = 7L;
            u.createdAt = java.time.LocalDateTime.of(2026, 1, 1, 12, 0);
            u.name = "alice";
            Session<SubUser> s = new Session<>(engine, SubUser.class);
            s.insert(u);
            // 修复前:父类 id/created_at 被静默丢弃,insert 抛"实体无 @Id 字段"
            SubUser back = s.findById(7L);
            assertThat(back.name).isEqualTo("alice");
            assertThat(back.createdAt).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 12, 0));
        }
    }

    @Table("strid_users")
    static class StrIdUser {
        @Id public String id;
        @Column("name") public String name;
    }

    @Test
    void String主键生成键回填_不再静默跳过() throws Exception {
        try (Engine engine = Engine.create(DbType.H2, EngineConfig.builder()
                .path("mem:orm_strid_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .user("sa").password("").build());
             Connection conn = engine.connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE strid_users (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
            StrIdUser u = new StrIdUser();
            u.name = "bob";
            Session<StrIdUser> s = new Session<>(engine, StrIdUser.class);
            s.insert(u);
            // 修复前:!Number 早退,u.id 静默保持 null;修复后经 adaptValue 字符串化回填
            assertThat(u.id).isEqualTo("1");
        }
    }
}
