# jian-sql-orm

## 基本信息
- **library**: jian-sql
- **entryClass**: jian.sql.orm.Session
- **deps**: jian-sql-engine(Engine 提供连接);JDBC API;反射读注解(纯 JDK)

## 摘要
轻量 ORM,对齐规范 §2.3 / SQLAlchemy Session;用 `@Table`/`@Column`/`@Id` 注解映射实体到表,Session 提供 findById/list/insert/update/delete。

## 能力
- 注解:`@Table("name")` 标类对应表、`@Column("name")` 标字段对应列、`@Id` 标主键字段
- Session:`findById(id)` 按 @Id 主键查单条;`list()` 查全表
- Session:`insert(entity)` / `update(entity)` / `delete(entity)`,均用 PreparedStatement 参数化(防注入)
- 构造:`new Session<>(engine, EntityClass.class)`,反射扫描注解建立字段↔列映射
- 字段未标 @Column 时按字段名映射列名

## 限制
- 轻量 ORM:不支持关系映射(一对多/多对多/延迟加载)、二级缓存、复杂级联
- update/delete 必须有 @Id 字段,否则抛 IllegalStateException
- 不支持自定义类型转换器、复杂主键(复合主键)、动态 fetch/Projections
- 不内置事务(用 Engine.begin() 的 try-with-resources 控制事务边界)

## 快速上手
```java
import jian.sql.orm.Session;
import jian.sql.orm.Table;
import jian.sql.orm.Column;
import jian.sql.orm.Id;

@Table("users")
public class User {
    @Id @Column("id")
    public Long id;
    @Column("name")
    public String name;
    @Column("age")
    public int age;
}

try (Session<User> s = new Session<>(engine, User.class)) {
    User u  = s.findById(1L);
    List<User> all = s.list();
    s.insert(new User(2L, "alice", 30));
    u.age = 31;
    s.update(u);
    s.delete(u);
}
```
