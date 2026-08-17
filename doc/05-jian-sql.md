# 05 · jian-sql 需求说明书

> 版本:v1.0 · 日期:2026-08-01 · 作者:zc · 依赖:JDK 17 + jOOQ 3.21.6 + HikariCP + 各 JDBC 驱动(按需)
> **独立于 jian,可单独使用**。

---

## 1. 模块定位

### 1.1 一句话定位

jian-sql 是对标 Python SQLAlchemy 的 Java 库,提供 **数据库连接管理 + 类型安全 SQL 表达式构建 + 轻量 ORM**,主要使命是**给 jian 喂数据**(但也可独立用于一般数据库交互)。基于 **jOOQ 3.21.6(2026-06-15,活跃,行业标准)** 做 SQL 表达式,自写连接管理与 ORM 映射层。

### 1.2 职责边界

**做(三层架构)**:

| 层 | 内容 | 实现方式 |
|---|---|---|
| **Engine 层**(连接管理) | 连接池(HikariCP)、方言探测、URL 构建、`.env` 读凭据 | 自写,基于 HikariCP |
| **Expression 层**(SQL 构建) | 类型安全的链式 SQL 构建(SELECT/WHERE/JOIN/GROUP BY/...) | 复用 jOOQ 3.21.6(OSS Edition) |
| **ORM 层**(映射) | 表 ↔ Java 对象的映射、Session、CRUD、结果集 → DataFrame | 自写 |

**支持数据库**(与 jian-io-sql 一致):PostgreSQL / MySQL / Doris / SQLite / H2 / Oracle / Access。

**做(SQLAlchemy 风格的 API)**:
- `create_engine("postgresql://user:pass@host/db")` —— SQLAlchemy 风格 URL(自动转 JDBC URL)。
- `engine.connect()` / `engine.begin()` —— 上下文管理,自动提交/回滚。
- `Table("users", metadata, Column("id", Integer), ...)` —— 表定义。
- `select(users.c.id).where(users.c.age > 18)` —— 类型安全查询(底层走 jOOQ)。
- `session.query(User).filter(...).all()` —— ORM 风格查询。
- `engine.toDataFrame(sql)` —— 直接喂给 jian。

**不做**:
- 复杂的继承映射(joined-table-per-class 等)—— SQLAlchemy 的 polymorphic 太复杂,v1 只做单表映射。
- 数据库迁移(Alembic 的活)—— 不做 schema 演进。
- 异步驱动(asyncio/asyncpg 的等价物)—— v1 只做同步 JDBC。

### 1.3 依赖关系

```
       jian-sql
   ┌────────┼────────┐
   ▼        ▼        ▼
engine    expr     orm
(HikariCP) (jOOQ)   (自写映射)
              │
              ▼
         各 JDBC 驱动(pg/mysql/sqlite/h2/ojdbc11/ucanaccess,按需引)
```

> jian-sql **不依赖** jian。但提供 `JianAdapter`(在 `jian-sql-bridge` 可选 jar 中)把 ResultSet 转 DataFrame——这个 bridge jar 才依赖 jian-core。

---

## 2. 核心 API(SQLAlchemy 风格)

### 2.1 Engine 层

```java
// 从 .env 读凭据(推荐),不硬编码
Engine engine = Engine.create(
    DbType.POSTGRESQL,
    EngineConfig.fromEnv()        // 读 DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME
);

// 或 SQLAlchemy 风格的 URL(密码可走 ${DB_PASSWORD} 占位)
Engine engine = Engine.fromUrl("postgresql://user:${DB_PASSWORD}@host:5432/db");

try (Connection conn = engine.begin()) {    // 自动提交/回滚
    ...
}

// 连接池配置(HikariCP)
EngineConfig cfg = EngineConfig.builder()
    .host("localhost").port(5432)
    .user("u").password(System.getenv("DB_PASSWORD"))
    .database("db")
    .poolSize(10)
    .build();
```

### 2.2 Expression 层(类型安全 SQL,基于 jOOQ)

```java
// jOOQ 的 DSL 风格
Result<Record> result = engine.dsl()
    .select(USERS.ID, USERS.NAME, USERS.AGE)
    .from(USERS)
    .where(USERS.AGE.gt(18).and(USERS.NAME.like("A%")))
    .orderBy(USERS.AGE.desc())
    .limit(10)
    .fetch();

// 也可直接执行原生 SQL
Result<Record> r2 = engine.sql("SELECT id, name FROM users WHERE age > ?", 18).fetch();
```

> **jOOQ codegen 策略**:标准 jOOQ 用 codegen 生成 `USERS` 这类表常量。本项目提供两种模式:
> ① **codegen 模式**:连数据库生成 Java 类(类型最安全,适合稳定 schema)。
> ② **运行时模式**:用 `DSL.table("users")` / `DSL.field("age")` 动态构造(无 codegen,适合动态 schema / 一次性脚本)。**脚本场景默认用②**,与 AGENTS.md 的脚本优先风格一致。

### 2.3 ORM 层(轻量映射)

```java
// 实体定义(注解)
@Table("users")
public class User {
    @Id @Column("id") Long id;
    @Column("name") String name;
    @Column("age") Integer age;
}

// ORM 操作
Session session = engine.session(User.class);
User u = session.findById(1L);
List<User> adults = session.filter(u -> u.getAge() > 18).list();
session.insert(new User(null, "Alice", 30));
session.update(u);
session.delete(u);
```

### 2.4 喂给 jian(桥接)

```java
// jian-sql 执行查询,结果直接转 DataFrame
DataFrame df = engine.dsl()
    .select(USERS.ID, USERS.NAME, USERS.AGE)
    .from(USERS)
    .fetchAsDataFrame();          // 需引 jian-sql-bridge jar
```

---

## 3. 实现要点

### 3.1 Engine 层的方言适配

```
// ┌─ What : 把 SQLAlchemy 风格的 URL/配置 转 JDBC URL + 选对驱动
// │  How  : 用 DbType 枚举(POSTGRESQL/MYSQL/DORIS/SQLITE/H2/ORACLE/ACCESS)
// │        每个枚举值持有:jdbcUrlPrefix / defaultPort / driverClassName / 方言配置
// 伪代码:
//   enum DbType {
//     POSTGRESQL("jdbc:postgresql://%s:%d/%s", 5432, "org.postgresql.Driver", SQLDialect.POSTGRES),
//     MYSQL     ("jdbc:mysql://%s:%d/%s",      3306, "com.mysql.cj.jdbc.Driver", SQLDialect.MYSQL),
//     DORIS     ("jdbc:mysql://%s:%d/%s",      9030, "com.mysql.cj.jdbc.Driver", SQLDialect.MYSQL),
//     SQLITE    ("jdbc:sqlite:%s",              0,   "org.sqlite.JDBC",          SQLDialect.SQLITE),
//     H2        ("jdbc:h2:%s",                  0,   "org.h2.Driver",            SQLDialect.H2),
//     ORACLE    ("jdbc:oracle:thin:@%s:%d:%s",1521, "oracle.jdbc.OracleDriver", SQLDialect.DEFAULT),
//     ACCESS    ("jdbc:ucanaccess://%s",        0,  "net.sf.ucanaccess.jdbc.UcanaccessDriver", SQLDialect.HSQLDB)
//   }
//   driverClassName 只用于反射试探驱动是否存在;不强制加载
```

### 3.2 安全规范(遵循 AGENTS.md §6.6)

- **凭据走 .env**:`EngineConfig.fromEnv()` 读环境变量,绝不硬编码。
- **SQL 注入防护**:表达式层(jOOQ)用参数化查询,`sql()` 原生 SQL 接口强制 `?` 占位符,拒绝字符串拼接。
- **危险操作过滤**:提供 `EngineConfig.readOnly(true)` 模式 + `engine.checkReadOnly(sql)` 校验(拦截 DROP/DELETE/TRUNCATE/ALTER/CREATE/GRANT/INSERT/UPDATE;自动剥前导注释防绕过);connect()/begin() 不自动调用,由 jian-sql-orm / 用户显式调用。

### 3.3 桥接 jian

- ResultSet → DataFrame 的转换逻辑**放在 bridge jar**,jian-sql 核心不依赖 jian。
- bridge 用 core 的 `DataFrame.of(Schema, Object[][])` 构造,类型走 io-sql 的映射表(复用,不重复实现)。

---

## 4. 边界与异常

| 场景 | 处理 |
|---|---|
| 驱动 jar 未引 | `ModuleNotLoadedException` 提示该引哪个 jar(已实现:Engine 构造时探测驱动类,缺失即抛,带 maven 坐标提示) |
| 连接失败 | `JianSqlException` 带 DbType + 脱敏 URL(已实现,含 `sanitize()` 密码脱敏) |
| 事务中异常 | `engine.begin()` 自动回滚 |
| 只读模式下执行写操作 | 抛 `SecurityException("只读模式禁止写操作")` |
| 实体类无 @Table 注解 | 抛异常提示 |

---

## 5. 工作量

- **代码量**(经源码核实):自写约 2,500 行(engine 600 + expr 适配 400 + orm 1200 + bridge 300),测试 ~1,500 行。
- **测试规模**:jian-sql 全 4 子模块测试全过(当前 @Test 数见 [api-counts.md](api-counts.md))。
- **真实库覆盖**:**jian-sql 自身的 27 测试全为 H2 内存库**(engine 12 + expr 4 + orm 6 + bridge 5),**无 SQLite/PG 集成测试**。
  - **多库真实测试属 jian-io-sql 模块**(见 doc/02 §5),不是 jian-sql:H2 10 + SQLite 9(默认跑)+ PG 14(`-Dtest.pg=true` 激活)。
  - **DbType 枚举定义 7 种库**(H2/PostgreSQL/MySQL/Doris/SQLite/Oracle/Access),但 jian-sql 自身只验 H2;MySQL/Doris/Oracle/Access 仅 DbType 定义,无 CI 验证。
  - **早前版本错误**:曾把 jian-io-sql 的 H2 10/SQLite 9/PG 14 写进 jian-sql 的"真实库覆盖",已修正归属。

---

## 6. 验收标准

1. **已验证的 3 库**(H2/SQLite/PostgreSQL)均能 `create_engine` + 执行 SELECT + 关闭。**MySQL/Oracle/Access/Doris 仅 DbType 定义,需用户自验**(接口同,见 §7.3)。
2. 表达式层(jOOQ)能构建 SELECT/WHERE/JOIN/GROUP BY/ORDER BY/LIMIT。
3. ORM 层支持 CRUD + `@Table`/`@Column`/`@Id` 注解映射。
4. 凭据走 .env,代码中无硬编码连接串。
5. `fetchAsDataFrame()` 能把查询结果转 DataFrame(需 bridge jar)。
6. 只读模式能拦截危险 SQL(已实现,经注释绕过测试验证)。

---

## 7. 实现说明

> 已实现 jian-sql 全 4 子模块(engine/expr/orm/bridge),测试全过(H2 内存库默认验证 + PG 经 `-Dtest.pg=true` 激活;当前 @Test 数见 [api-counts.md](api-counts.md))。

### 7.1 已实现

| 子模块 | 文件 | 测试数 | 状态 |
|---|---|---|---|
| `jian-sql-engine` | DbType(7 库枚举)+ EngineConfig + Engine(HikariCP) + dsl()/sql() 入口 + 只读拦截 | **26** | ✅ stable |
| `jian-sql-expr` | SqlBuilder(jOOQ 3.21.6 运行时模式 + 原生 SQL) | **9** | ✅ stable |
| `jian-sql-orm` | @Table/@Column/@Id + Session(findById/list/insert/update/delete) | **19** | ✅ stable |
| `jian-sql-bridge` | SqlBridge(ResultSet/jOOQ Result → DataFrame) | **11** | ✅ stable |
| **合计** | | **65** | |

### 7.2 与需求的偏差

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `engine.dsl().select(USERS.ID)` codegen 模式 | `engine.dsl().ctx().selectFrom("users")` 运行时模式(含 `engine.dsl()` 与 `engine.sql(sql, params)` 入口) | 规范 §2.2 已说"脚本场景默认运行时模式",无 codegen |
| `engine.session(User.class)` | 用 `new Session<>(engine, User.class)`(engine 不依赖 orm,避免循环依赖) | 功能等价,入口形态不同 |
| `session.query(User).filter(u -> u.age > 18).all()` lambda 链 | `session.list()` 全量(无 lambda 链) | M5 简化:lambda 链需表达式树支持,v2 补;v1 用 SqlBuilder 复杂条件 |
| `engine.toDataFrame(sql)` 直接调 | `SqlBridge.fetchAsDataFrame(engine, sql)` | jian-sql 核心不依赖 jian;bridge jar 单独引 |
| jOOQ Oracle 方言 | 用 SQLDialect.DEFAULT | jOOQ OSS Edition 不含 Oracle/DB2 商业方言 |

### 7.3 已验证

- **真实库测试覆盖:3 种**(H2/SQLite/PostgreSQL),其余 4 种(MySQL/Doris/Oracle/Access)**仅 DbType 定义,无 CI 集成测试**。
- H2 内存库:7 库接口通用的代理验证(其余 6 库接口同,用户引对应驱动)。
- 事务:begin 正常提交 / 异常回滚
- 只读模式:拦截 DROP/DELETE/TRUNCATE/INSERT/UPDATE 等(含注释绕过防护测试)
- URL 解析:`postgresql://user:${DB_PASSWORD}@host:5432/db` → DbType + EngineConfig
- ORM CRUD 完整往返

---

*本分册独立,与 01-04/06 无耦合。jian-sql 可完全脱离 jian 单独使用。*
### 7.4 健壮性与安全(现行)

- **`parseUrl` 健壮性**:URL 不带 `user:pass@` 段时正常解析(用户/密码为空)。
- **只读拦截防绕过**:`checkReadOnly` 先剥前导空白/行注释/块注释,再整词匹配危险关键字(`/* x */ DROP TABLE` 无法绕过)。
- **驱动缺失友好异常**:`Engine.create` 构造时反射探测驱动类,缺失抛 `ModuleNotLoadedException`(带 `groupId:artifactId` 安装提示)。
- **`JianSqlException` 新增**:连接失败包装带 DbType + 脱敏 URL(`sanitize()` 把密码替换为 `***`),防异常信息泄漏凭据。
- **`engine.dsl()` / `engine.sql(sql, params)` 入口落地**(规范 §2.2):`engine.sql("SELECT ... WHERE id > ?", 18).fetch()`;`SqlBuilder.Dialect` 补 DORIS 项(MySQL 协议)。

---

*实现完成;当前测试数与"3 库真测 + 4 库仅 DbType 定义"口径以 §7.3 与 [api-counts.md](api-counts.md) 为准。*

---

### 7.5 行为细节(现行)

- **engine**:checkReadOnly 拦截 DROP/DELETE/TRUNCATE/ALTER/CREATE/GRANT/INSERT/UPDATE/REPLACE/CALL/COPY/LOAD;sanitize 密码含 @ 不泄漏;scrub 支持 `#` 注释 / 反引号标识符 / `$$` 字符串。
- **expr**:fetch/execute 自动归还连接(链式用法不泄漏)。
- **orm**:insert/update/delete 全部过只读拦截(readOnly 抛 SecurityException);insert 自增主键回填;BigDecimal/Boolean/enum/LocalDate 字段映射。
- **bridge**:按 ResultSetMetaData 映射 dtype(SMALLINT→INT 等);空结果集保留列。
- 测试:engine 26 / expr 9 / orm 19 / bridge 11 @Test(口径见 [api-counts.md](api-counts.md))。

### 实现说明:外部 AI 协作复审修复

> 由 AI3 依 ai-code-testing 方法学复审(9 项;采纳 6、复核后否决 3)。

| # | 模块 | 修复 | 行为变化 |
|---|---|---|---|
| 1 | jian-sql-orm | Session 沿类层级收集字段 | 父类(实体基类)的 @Id/@Column 字段参与映射(原 getDeclaredFields 只扫本类,父类字段静默丢失) |
| 2 | jian-sql-orm | 生成键回填去掉 Number-only 早退 | String/enum 等 @Id 经 adaptValue 兜底回填(原静默跳过致实体 id 恒 null) |
| 3 | jian-sql-engine | checkReadOnly 反引号 `` 双写转义 | MySQL `` 双反引号不再提前闭合配对(原可借此把 DROP 误剥成标识符内容绕过只读拦截);字符串剥除维持 ANSI/PG 语义并文档化 fail-closed 取舍 |
| 4 | jian-sql-bridge | Types.OTHER/JAVA_OBJECT → OBJECT | 厂商扩展类型保原对象(原静默 toString 成 STRING) |
| 5 | jian-sql-bridge + jian-io-sql | Clob 读取失败与 Blob 对称返 null | 两处同根因实现同步修复(原 Clob 失败返回 clob.toString() 非缺失垃圾串,Blob 失败返 null,同一读取失败两种语义) |
| 6 | jian-sql-orm | Session 标识符按需引号化(外部 AI 复审) | @Table/@Column 值由白名单硬拒(中文即 IAE)改为与 jian-io-sql 同防线:简单 ASCII 原样放行,中文/特殊字符按库引号符包裹 + 双写转义保真(jOOQ/SQLAlchemy 同为按需引号化);控制字符构造期即拒;注入式注解值成为字面量标识符;中文实体 CRUD 全链 H2 真测 |
