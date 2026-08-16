# jian-sql-bridge

## 基本信息
- **library**: jian-sql
- **entryClass**: jian.sql.bridge.SqlBridge
- **deps**: jian-core;JDBC API;可选 jOOQ(`Result<Record>` 转 DataFrame 时依赖 jian-sql-expr)
- **tests**: 11

## 摘要
SQL 结果 → DataFrame 的单向桥接(规范 §2.4);把 JDBC ResultSet / jOOQ Result 转成 jian 的 DataFrame,使 jian-sql 的查询结果可继续用 jian 分析。

## 能力
- `toDataFrame(Connection, sql, params...)`:PreparedStatement 执行 SELECT → DataFrame
- `toDataFrame(ResultSet)`:从 ResultSetMetaData 取列名,逐行 getObject,Schema 自动推断,null 用 wasNull 区分
- `toDataFrame(jOOQ Result<Record>)`:把 jian-sql-expr 的 jOOQ 查询结果转 DataFrame
- 让 jian-sql-engine / jian-sql-expr 与 jian-core 解耦:核心不依赖 jian,仅此 bridge 依赖

### 行为细节
- toDataFrame JDBC 类型规范化(Timestamp→DATETIME 等)

### 行为细节(续 1)
- 按 ResultSetMetaData 映射 dtype(SMALLINT→INT、BIGINT→LONG 等,而非全 OBJECT)
- 空结果集保留全部列(0 行不丢 Schema)

## 限制
- 仅做结果 → DataFrame 单向转换,不做 DataFrame → 表的反向写入(写表用 jian-io-sql 或 ORM)
- JDBC 特殊大对象(Clob/Blob/BigDecimal)按getObject 原样返回,不规范化(与 jian-io-sql 不同,后者做规范化)
- 调用方负责 Connection / ResultSet 的关闭(try-with-resources)

## 快速上手
```java
import jian.sql.bridge.SqlBridge;

try (Connection conn = engine.connect()) {
    DataFrame df = SqlBridge.toDataFrame(conn, "SELECT * FROM users WHERE age > ?", 18);
}

// jOOQ Result(需 jian-sql-expr)
Result<Record> r = qb.ctx().selectFrom("users").fetch();
DataFrame df = SqlBridge.toDataFrame(r);
```
