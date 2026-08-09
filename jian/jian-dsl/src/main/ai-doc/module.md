# jian-dsl

## 基本信息
- **library**: jian
- **entryClass**: jian.dsl.Dsl(静态门面)/ jian.dsl.JianDslEngine(SPI 实现)
- **deps**: jian-core;纯 JDK(零运行时依赖,Pratt parser 与 SQL 子集均自写)
- **version**: 1.0.0
- **tests**: 83

## 摘要
jian 的三档表达式/SQL 引擎:L1 布尔过滤 query、L2 派生列 eval、L3 SQL(可插拔引擎接口)sql;经 SPI 自动接管 core 的 DslEngine。L3 支持完整 DQL + DML(INSERT/UPDATE/DELETE)。

## 能力

### L1 query(布尔过滤)
- 比较/逻辑/算术/三元/谓词(between/like/in/is null/not in)
- 支持 `${name}` 命名参数(Params)

### L2 eval(派生列)
- `col = expr` 派生列,分号分隔多列,支持嵌套三元
- nvl/coalesce/ifnull 空值函数

### L3 SQL(可插拔引擎接口)
**架构**:SqlEngineInterface(通用接口,库无关)+ SqlEngines(注册中心,可切换)+ SqlRegexEngine(默认,纯 JDK)
- **切换引擎**:`SqlEngines.useRegex()` / `useCustom(impl)`

**SqlRegexEngine 默认引擎支持**:
- SELECT * / SELECT col / SELECT col AS alias / SELECT DISTINCT
- 聚合函数:mean/sum/count/nunique/min/max/median/std/var/first/last(11 种)
- WHERE 完整运算符 + CASE WHEN(SELECT 列表 + WHERE)
- GROUP BY / HAVING / ORDER BY(ASC/DESC,多列)
- LIMIT / OFFSET / FETCH FIRST / ROWNUM(方言)
- JOIN:INNER/LEFT/RIGHT/FULL OUTER,链式多表 + CROSS JOIN(笛卡尔积)
- UNION ALL / UNION 去重 / INTERSECT / EXCEPT / MINUS
- 子查询(WHERE IN/标量比较,≤ 2 层)
- CTE WITH name AS (subquery)
- 派生表 FROM (SELECT ...) AS t
- USING(col1, col2, ...) 多列
- INSERT INTO ... VALUES / UPDATE ... SET ... WHERE / DELETE FROM ... WHERE
- 三方言:ORACLE/POSTGRESQL/MYSQL/DEFAULT

## 限制
- WINDOW FUNCTIONS(OVER PARTITION BY)不支持 → 用 jian Resampler/colRank/Series.rolling 替代,或经 SqlEngines.useCustom() 接入外部引擎
- CATEGORY dtype 不支持
- L3 聚合函数限定 jian 内置(不支持任意 UDF)

## 快速上手
```java
import jian.core.DataFrame;
import jian.dsl.Dsl;
import jian.dsl.SqlEngines;

// L1 过滤
DataFrame r1 = df.query("age > 18 && city in ('SH','BJ')");

// L3 SQL(经 df.sql 实例方法,this 引用主表)
DataFrame r2 = df.sql("SELECT dept, mean(salary) AS avg_sal FROM this GROUP BY dept ORDER BY salary_mean DESC LIMIT 5");

// CTE
DataFrame r3 = df.sql("WITH rd AS (SELECT * FROM this WHERE dept == 'RD') SELECT name FROM ${rd}");

// DML(返回新 DataFrame)
SqlEngines.current().update(df, "INSERT INTO ${t} (id, name) VALUES (4, 'dave')", Map.of("t", df), SqlDialect.DEFAULT);

// 接入自定义引擎
SqlEngines.useCustom(new MyEngine());
SqlEngines.reset();  // 切回默认
```
