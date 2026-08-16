# jian-dsl

## 基本信息
- **library**: jian
- **entryClass**: jian.dsl.Dsl(静态门面)/ jian.dsl.JianDslEngine(SPI 实现)
- **deps**: jian-core;纯 JDK(零运行时依赖,Pratt parser 与 SQL 子集均自写)
- **version**: 1.0.1
- **tests**: 149(含 EngineConformanceTest 19 双引擎与 core 兜底 SimpleQueryParser 语法矩阵互证)

## 摘要
jian 的三档表达式/SQL 引擎:L1 布尔过滤 query、L2 派生列 eval、L3 SQL(可插拔引擎接口)sql;经 SPI 自动接管 core 的 DslEngine。L3 支持完整 DQL + DML(INSERT/UPDATE/DELETE)。

## 能力

### 双引擎语法矩阵
- PrattEngine(本模块,df.query/eval 主路径)与 jian-core SimpleQueryParser(兜底)语法矩阵一致,由 EngineConformanceTest(19 用例)互证
- 支持:not in/notin/not like/not between、算术 + - * / %、反引号标识符、'' 与反斜杠转义、is [not] true/false、like \% \_ 转义;数值不再隐式当布尔(双引擎同步 fail-fast)


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
- **列名/别名/占位名支持中文等 Unicode 标识符,含 CJK 扩展B/emoji 等增补平面字符**
- **count(*) AS 别名**(输出 LONG);CTE(静态入口 Jian.sql 亦支持占位宽容绑定)
- 聚合函数:mean/sum/count/nunique/min/max/median/std/var/first/last(11 种)
- WHERE 完整运算符(**含 SQL 标准 `=` / `<>` 与反引号 `` `列名` ``**)+ CASE WHEN(SELECT 列表 + WHERE)
- GROUP BY / HAVING / ORDER BY(ASC/DESC,多列)
- **AS 别名真重命名**(HAVING/ORDER BY 可引用别名);**ORDER BY 可引用未选中列**(投影前排序)
- LIMIT / OFFSET / FETCH FIRST / ROWNUM(方言)
- JOIN:INNER/LEFT/RIGHT/FULL OUTER,链式多表 + CROSS JOIN(笛卡尔积)
- UNION ALL / UNION 去重 / INTERSECT / EXCEPT / MINUS
- 子查询(WHERE IN/标量比较,≤ 2 层)
- CTE WITH name AS (subquery)
- 派生表 FROM (SELECT ...) AS t
- USING(col1, col2, ...) 多列
- INSERT INTO ... VALUES / UPDATE ... SET ... WHERE / DELETE FROM ... WHERE
- 三方言:ORACLE/POSTGRESQL/MYSQL/DEFAULT

### 行为细节
- 裸名 CTE 引用(WITH t AS ... SELECT FROM t);嵌套 CASE 递归展开;字符串含 UNION 不误判
- DML 数值列当布尔抛 IAE(与 query 三入口一致);UPDATE SET 支持表达式(SET c = c * 2)
- 无 GROUP BY 的非聚合列抛 IAE(对齐 SQLite,不静默丢列)

- SELECT 重复列保留(c2_2 后缀);空表聚合恒 1 行;UNION ALL 列数校验教学 IAE
- SELECT 表达式列(无括号)与未知列报错(不静默跳过);JOIN ON 多条件 + USING 多列;UNION/派生表括号感知;OFFSET 三方言分页;科学计数法字面量;引擎线程安全

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

// L3 SQL(经 df.sql 实例方法,this 引用主表;中文列名/SQL 标准 = 都支持)
DataFrame r2 = df.sql("SELECT 类别, sum(金额) AS 合计 FROM this GROUP BY 类别 HAVING 合计 > 10 ORDER BY 合计 DESC LIMIT 5");

// CTE
DataFrame r3 = df.sql("WITH rd AS (SELECT * FROM this WHERE dept == 'RD') SELECT name FROM ${rd}");

// DML(返回新 DataFrame)
SqlEngines.current().update(df, "INSERT INTO ${t} (id, name) VALUES (4, 'dave')", Map.of("t", df), SqlDialect.DEFAULT);

// 接入自定义引擎
SqlEngines.useCustom(new MyEngine());
SqlEngines.reset();  // 切回默认
```
