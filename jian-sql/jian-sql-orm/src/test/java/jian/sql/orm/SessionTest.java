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
    void 恶意Table注解值抛IAE挡住SQL注入() {
        // @Table/@Column 注解值直接拼入 SQL(标识符无参数化形式),必须过白名单
        @Table("x; DROP TABLE users; --")
        class EvilTable {}
        assertThatThrownBy(() -> new Session<>(null, (Class<EvilTable>) EvilTable.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法表名");

        @Table("ok_tbl")
        class EvilCol {
            @Id @Column("id); DROP TABLE users; --") public Long id;
        }
        assertThatThrownBy(() -> new Session<>(null, (Class<EvilCol>) EvilCol.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法列名");
    }
}
