# 07 · jian-dsl 需求说明书

> 版本:v0.4 → **实现版(自写 Pratt+正则,弃用 ANTLR4)** · 日期:2026-08-02 · 依赖:jian-core(纯 JDK,零运行时依赖)
>

---

## 1. 模块定位

### 1.1 一句话定位

jian-dsl 提供 **自写的 DataFrame 表达式 + 自写 SQL 子集引擎(L3 支持 Oracle/PG/MySQL 三方言分页)**,作为 pandas `df.query()` / `df.eval()` 的**功能对齐 + 适度增强**。**完全自包含 —— 自写解析器,不挂任何外部脚本引擎**,这是 pandas / polars / Spark SQL 的通行做法。方言通过 `SqlDialect` 变量切换(Oracle/PostgreSQL/MySQL)。

### 1.2 为什么自写解析器

经核实,主流 DataFrame 库**都自写解析器,无一嵌入外部脚本引擎**:

| 库 | query/eval 引擎 |
|---|---|
| pandas | 自写 numexpr(Cython)+ Python 后备 |
| polars | 完全自写 Rust 解析器 |
| Spark SQL | 自写 Catalyst(parser 自生成) |
| DataFusion | 自写(sqlparser-rs) |
| jOOQ | 自写 SQL parser |

**自写的理由(也正是本项目的约束)**:

1. **自包含**:本项目的目标是产出**可分发的通用 jar 包**。嵌入外部脚本引擎要拉数 MB 的引擎 jar 及其传递依赖,与"通用 jar"目标冲突。
2. **语义对不上**:脚本引擎是通用语言(能 `Runtime.exec`、能反射),DataFrame 操作只是它的极小子集,**沙箱化通用引擎比自己写还麻烦**。
3. **安全可控**:自写的受限语法不能调用任意 Java 方法、不能反射、不能执行系统命令,**天然安全,无需沙箱化**(见 §3.3)。

**结论**:像 pandas 那样**自己写解析器**。L1+L2 手写,L3 用自写正则子句切分(零运行时依赖,无需 ANTLR4)。

### 1.3 两条自写路线的分工(经核实行业标准)

| 路线 | 体积 | 用在哪 | 行业先例 |
|---|---|---|---|
| **手写递归下降(Pratt parser)** | 0 依赖 | L1 布尔 + L2 算术表达式 | DataFusion / Crafting Interpreters |

> jian-dsl **零运行时依赖**(纯 JDK),不用 ANTLR4。L3 SQL 用自写正则子句切分,功能完整(含子查询/OUTER JOIN/UNION ALL)。
> Apache Calcite 太重(数 MB + 优化器用不上)——不选。

### 1.4 职责边界

**做(三档能力)**:

| 档位 | 对齐/超越 | 引擎 | 示例 |
|---|---|---|---|
| **L1 布尔表达式过滤** | = pandas `df.query()` | 手写 Pratt | `df.query("age > 18 && city == 'SH'")` |
| **L2 表达式求值** | = pandas `df.eval()` | 手写 Pratt | `df.eval("total = price * qty")` |
| **L3 类 SQL 子集** | **超越 pandas** | ANTLR4 | `df.sql("SELECT city, avg(salary) FROM this GROUP BY city ORDER BY 2 DESC")` |

**不做**:
- 不替代 core 的强类型 Java API(Java API 仍是主接口)。
- 不做完整数据库 SQL 方言(只做"DataFrame 上的 SQL 子集")。
- 不做 numexpr 那种向量化高性能引擎(用户已明确不追求极致性能)。
- 不做存储过程/触发器/CTE/窗口函数 over SQL(SQL 子集够用,复杂分析走 core 的 window API)。

### 1.5 依赖关系

```
jian-core
     ▲
     │
jian-dsl
     │
     ├── L1/L2: 手写解析器(纯 JDK,零运行时依赖)
     │
     └── L3:    纯 JDK(自写正则子句切分,零运行时依赖)
```

> **完全可选模块**:用户不引 jian-dsl,core 的 `df.query()` 走内置极简解析器(L1 子集);引了 jian-dsl,自动通过 SPI 升级到完整 L1+L2+L3。

---

## 2. 三档 DSL API

### 2.1 L1 —— 布尔过滤(对齐 `df.query`)

```java
DataFrame adults = df.query("age > 18 && city == 'SH'");
DataFrame rich   = df.query("salary > 10000 && (dept == 'RD' || dept == 'PM')");
DataFrame f      = df.query("age between 18 and 60 && name like 'A%'");  // 比 pandas 多:between/like/in
DataFrame inSet  = df.query("city in ('SH','BJ','SZ')");
```

**语法(L1 子集,够用且明确)**:
- 比较:`> < >= <= == !=`
- 逻辑:`&& || !`(也兼容 `and / or / not`)
- 算术:`+ - * / %`
- 谓词:`between X and Y` / `like 'pattern'`(% 通配)/ `in (...)` / `is null` / `is not null`
- 字面量:数字、字符串(单/双引号)、布尔、null
- 列名:直接当标识符(底层从 binding 取值)
- 括号:任意嵌套

**与 pandas query 的差异**:
- 不支持方法调用(`name.startsWith(...)` 这种)—— 那需要通用引擎,违反自包含原则;改用谓词(`name like 'A%'`)。
- 不支持 Python 风格的 `@var` 引用 —— 改用 `Params.of("var", value)` 显式传参。

### 2.2 L2 —— 表达式求值(对齐 `df.eval`)

```java
// 派生新列
df.eval("total = price * qty");
df.eval("discounted = price * (1 - rate)");
df.eval("grade = score >= 90 ? 'A' : score >= 80 ? 'B' : 'C'");  // 三元

// 多列同时(用分号或文本块)
df.eval("""
    total = price * qty;
    tax = total * 0.13;
    grand = total + tax
""");

// 带参数
df.eval("flag = score > ${threshold}", Params.of("threshold", 85));
```

### 2.3 L3 —— 类 SQL 子集(超越 pandas)

```java
DataFrame result = df.sql("""
    SELECT city, avg(salary) AS avg_sal, count(*) AS cnt
    FROM this
    WHERE age > 18
    GROUP BY city
    HAVING cnt > 10
    ORDER BY avg_sal DESC
    LIMIT 20
""");

// JOIN 多 DataFrame
DataFrame joined = Jian.sql("""
    SELECT a.name, b.dept_name
    FROM ${df1} AS a JOIN ${df2} AS b ON a.dept_id = b.id
    WHERE a.age > 20
""");

Jian.sql("SELECT * FROM ${df1} UNION ALL SELECT * FROM ${df2}");
```

**支持的 SQL 子集**:`SELECT`(含 `distinct` / `*` / 别名)/ `FROM ${df}`(占位符绑定 DataFrame)/ `JOIN ... ON`(inner/left/right)/ `WHERE` / `GROUP BY` / `HAVING` / `ORDER BY`(asc/desc)/ `LIMIT n [OFFSET m]` / `UNION ALL`。
**不支持**:`WITH`(CTE)/ 窗口函数 over SQL(走 core window API)/ 子查询嵌套(v2)/ `INSERT/UPDATE/DELETE`(只读)。

### 2.4 多方言兼容矩阵(**Oracle 基线** + PG/MySQL 兼容)

>
> **关于 grammar 选型的术语澄清(重要)**:
> - Oracle 的 SQL 方言和 PL/SQL 是**两个不同概念**:Oracle SQL 指 Oracle 的 DML/DDL 查询语言(`SELECT`/`INSERT`/...);PL/SQL 是其**过程化扩展**(`DECLARE/BEGIN/END`、存储过程、游标)。
> - 本项目**只用其中的 SELECT 分支**——把 visitor 入口限定为 `select_statement` 规则,PL/SQL 过程化部分**visitor 不实现 = 等于不存在**,不引入过程化能力。
> - 选 Oracle 基线的理由:语法风格覆盖 Oracle/PG/MySQL 用户的最大公约数(`ROWNUM` / `DUAL` / `NVL` / `||` / `FETCH FIRST` / `TO_*`),通过 `dialect` 变量自适应。

#### 各方言语法差异 → jian-dsl 的兼容方式(以 Oracle 为视角)

| 特性 | **Oracle(基线)** | PG 写法 | MySQL 写法 | jian-dsl 处理 |
|---|---|---|---|---|
| 分页 | `ROWNUM <= 20` / `FETCH FIRST 20 ROWS ONLY` | `LIMIT 20` / `FETCH FIRST 20 ROWS ONLY` | `LIMIT 20` | **三种都认**,统一翻成 `.head(20)` |
| 占位表 | `SELECT 1 FROM DUAL`(必需) | `SELECT 1`(无 DUAL) | `SELECT 1`(无 DUAL) | **`FROM DUAL` 等价 `FROM this`** |
| 空值替换 | `NVL(a,b)` | `COALESCE(a,b)` | `IFNULL(a,b)` / `COALESCE` | **三种都认**:函数调用,返回第一个非 null 参数(逐行求值,参数可引用列) |
| 字符串连接 | `a \|\| b` | `a \|\| b` | `CONCAT(a,b)` | **两种都认**,统一翻成 `str_concat(a,b)` |
| 当前日期 | `SYSDATE` | `CURRENT_DATE` / `NOW()` | `CURDATE()` / `NOW()` | **三种都认**,统一翻成 `LocalDate.now()` |
| 当前时间戳 | `SYSTIMESTAMP` | `CURRENT_TIMESTAMP` / `NOW()` | `NOW()` | **三种都认** |
| 取子串 | `SUBSTR(s,1,3)` | `SUBSTRING(s,1,3)` / `SUBSTR` | `SUBSTRING` / `SUBSTR` | **三种都认** |
| 类型转换 | `TO_NUMBER(x)` / `TO_CHAR(x)` / `TO_DATE(x,fmt)` | `CAST(x AS type)` | `CAST` | **都认**,`TO_*` 与 `CAST` 都翻译为内部 cast |
| 模糊匹配 | `LIKE 'A%'` | `LIKE 'A%'` | `LIKE 'A%'` | 统一(`%` 通配) |
| 标识符引号 | `"col"`(双引号) | `"col"`(双引号) | `` `col` ``(反引号) | **三种都认**,Lexer 同时接受 `"`、`` ` `` |
| 大小写 | 默认敏感 | 默认敏感 | 默认不敏感(列名) | DSL 列名匹配**大小写不敏感**(可配) |
| 序列(写场景) | `SEQUENCE.NEXTVAL` | `SERIAL` | `AUTO_INCREMENT` | DSL 只读,无需处理 |

> 注:`DUAL` 在 Oracle 是真实表;在本 DSL 里 `FROM DUAL` 视作 `FROM this`(对当前 DataFrame 操作),让 Oracle 用户的写法无缝迁移。

#### 用变量指定方言(核心 API)

**方言通过 `SqlDialect` 枚举变量传入,影响默认行为(如未加引号标识符大小写、默认分页关键字、空值函数优先级等)。**

```java
// 方式 A:静态入口带方言参数(分页写法按方言识别,ROWNUM / LIMIT / FETCH FIRST 都认)
Dsl.sql("SELECT * FROM ${t} WHERE ROWNUM <= 10", SqlDialect.ORACLE, df);
Dsl.sql("SELECT * FROM ${t} LIMIT 10", SqlDialect.POSTGRESQL, df);
Dsl.sql("SELECT * FROM ${t} LIMIT 10", SqlDialect.MYSQL, df);

// 方式 B:从环境变量读(便于不同环境不同方言,符合 AGENTS.md §7.3 配置走 env 的原则)
//   环境变量 JIAN_SQL_DIALECT=oracle|postgresql|mysql|default
SqlDialect d = SqlDialect.fromEnv();

// 方式 C:df.sql()(接收者即主表)固定用默认方言 DEFAULT;三种分页写法一律兼容识别
df.sql("SELECT * FROM this WHERE ROWNUM <= 10");   // Oracle 写法
df.sql("SELECT * FROM this LIMIT 10");             // PG/MySQL 写法
```

#### `SqlDialect` 变量的影响范围

| 配置项 | ORACLE | POSTGRESQL | MYSQL |
|---|---|---|---|
| 默认分页关键字(用户不写时) | 不自动加(Oracle 用 ROWNUM) | 不自动加 | 不自动加 |
| 未加引号的列名匹配 | 大小写敏感 | 大小写敏感 | 大小写**不**敏感 |
| 空值函数优先级(同名冲突时) | `NVL` 优先 | `COALESCE` 优先 | `IFNULL` 优先 |
| 字符串连接默认操作符 | `\|\|` | `\|\|` | `CONCAT()` |
| 日期默认格式 | `TO_DATE` 默认 `YYYY-MM-DD HH24:MI:SS` | ISO-8601 | ISO-8601 |
| 语法宽容度 | 严格(Oracle 风格) | 中 | 中 |

> **覆盖度**:90%+ 的常用 SELECT 查询,三种方言写出来都能直接跑。极少数方言专属特性(Oracle 的 `MODEL` 子句、PG 的 `WITH RECURSIVE`、MySQL 的 `GROUP_CONCAT`)在 v1 不实现,文档明确列出。
> **核心原则**:grammar 用 Oracle PL/SQL 单份;方言差异在 visitor 翻译层归一化,不维护三份 grammar。

---

## 3. 实现要点

### 3.1 L1/L2 手写 Pratt parser(零依赖)

```
// ┌─ What : 手写递归下降 + Pratt 优先级解析器,处理布尔/算术表达式
// │  Why  : ① 零运行时依赖(纯 JDK),保 jar 自包含;
// │        ② 表达式语法简单,手写比 ANTLR 更轻;
// │        ③ 行业先例:DataFusion/Crafting Interpreters 都这么干
// │  How  : 经典两阶段
// │   阶段1 Lexer:把字符串切成 Token(数字/字符串/标识符/运算符/关键字)
// │   阶段2 Parser(Pratt):按运算符优先级建 AST
// │        优先级(低→高): || → && → ! → 比较 → between/like/in → +/- → */% → 一元 → 字面量/标识符
// │   阶段3 Evaluator:遍历 AST,对每行的 binding 求值
// │   优化:AST 编译一次,缓存复用;每行只走 evaluate
// 伪代码(Pratt 核心循环):
//   Expr parseExpr(int minPrec) {
//     Expr left = parseUnary();
//     while (nextToken is infix && prec(infix) >= minPrec) {
//         op = consume(); right = parseExpr(prec(op) + 1);
//         left = new BinaryOp(op, left, right);
//     }
//     return left;
//   }
```

**AST 节点**:`Literal` / `ColumnRef` / `BinaryOp` / `UnaryOp` / `Ternary` / `Between` / `Like` / `In` / `IsNull`。
**求值**:visitor 模式遍历 AST,`ColumnRef` 从当前行的 binding 取值。

### 3.3 安全(天然安全,无需沙箱)

- **L1/L2/L3 都是受限语法**,不能调用任意 Java 方法、不能反射、不能执行系统命令。
- 自写解析器天然受限,**根本不需要沙箱化**——这是不嵌入外部脚本引擎的额外好处。
- 唯一注意:`like` 的 pattern 不要让用户写 ReDoS 正则(用简单 `%` 通配,不暴露 regex)。

### 3.4 性能

- **AST/parse tree 编译一次,缓存复用**(按表达式字符串做 key)。
- 大表场景:DSL 比 Java API 慢 1.5-3x(每行求值的解释开销),但用户已明确不追求极致性能。
- 文档明确:"DSL 适合脚本/快速探索;百万行以上且要极致性能,用 core 的 Java API"。

---

## 4. 与 core 的解耦(SPI)

core 定义接口:

```java
public interface DslEngine {  // jian-core 中定义
    DataFrame query(DataFrame df, String expr, Params params);
    void eval(DataFrame df, String expr, Params params);
}
```

- core 内置 `SimpleDslEngine`:L1 极简子集(无 between/like/in),纯 JDK。
- jian-dsl 提供 `JianDslEngine`:完整 L1+L2+L3,实现 `DslEngine` + 提供 `sql()` 扩展方法。
- core 通过 `ServiceLoader<DslEngine>` 加载,找到就用,找不到用内置兜底。

| 用户引的 jar | query/eval 走 | sql() 可用 |
|---|---|---|
| 仅 core | 内置 SimpleDslEngine(L1 子集) | 否 |
| core + jian-dsl | 完整 L1+L2 | 是(L3) |

---

## 5. 边界与异常

| 场景 | 处理 |
|---|---|
| 列名不存在 | `IllegalArgumentException("列 'xxx' 不存在,现有列:[...]")`(带现有列提示) |
| 类型不匹配(String 用 `>`) | `IllegalArgumentException("运算符 > 不支持 String 类型")` |
| 语法错误 | Lexer/Parser 报错 → 中文提示带错误字符位置 |
| L3 SQL 语法错 | `IllegalArgumentException`(自写正则引擎报错,非 ANTLR) |
| `${df}` 占位符未绑定 | `IllegalArgumentException("绑定 ${xxx} 未提供")` |
| `${}` 与 this/DUAL 混用 | `IllegalArgumentException`(明确提示改用 df.sql(),而非 NPE) |
| 数据源无法识别 | `IllegalArgumentException("无法识别的数据源 'xxx'")` |

---

## 6. 工作量

- **代码量**:自写约 5,000 行
  - L1/L2 手写 parser(Lexer 500 + Pratt Parser 800 + AST 节点 400 + Evaluator 500)= 2,200 行
  - SPI 集成 + 缓存 + 测试工具 300 行
  - 测试约 2,700 行(其中方言兼容用例 ~1,000 行,§2.4 矩阵每种写法三方言对照)
- **测试要求**:
  - L1:与 pandas 同输入的 query 输出对比(每种运算符/谓词覆盖)。
  - L2:多列 eval、三元、文本块、参数绑定。
  - L3:每条 SQL 子句翻译正确,与等价 Java 链式调用结果一致。
  - **方言兼容矩阵全用例**:§2.4 每一行的 PG/MySQL/Oracle 三种写法,产出结果必须一致。
  - **PL/SQL 隔离测试**:传入 `BEGIN ... END;` 等过程化语法,必须被入口限定拦截(报"本 DSL 仅支持 SELECT")。
  - 错误信息:语法错误带正确行列号。
  - 性能:AST 缓存命中后大表过滤吞吐曲线。

---

## 7. 验收标准

1. L1 `df.query()` 支持 §2.1 全部语法(比较/逻辑/算术/between/like/in/is null/括号)。
2. L2 `df.eval()` 支持赋值派生新列(单列、多列文本块、参数绑定、三元)。
3. L3 `df.sql()` 支持 SELECT/WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/JOIN/UNION ALL。
4. **多方言**:同一查询用 Oracle `ROWNUM<=10`、PG `LIMIT 10`、MySQL `LIMIT 10` 三种写法,**结果完全一致**(§2.4 矩阵全行通过)。
5. **方言变量**:`SqlDialect.ORACLE/POSTGRESQL/MYSQL` 三态可切换(单次调用参数 / 全局默认 / 环境变量 `JIAN_SQL_DIALECT`)。
6. **PL/SQL 隔离**:传入 `BEGIN...END;`/存储过程等过程化语法,被入口限定拦截,报"本 DSL 仅支持 SELECT"。
7. **不引入任何外部脚本引擎**——jar 自包含。
8. L3 用自写 Pratt + 正则(零运行时依赖,不用 ANTLR4);功能完整(子查询2层/JOIN 4种/UNION ALL/三方言分页)。
9. 不引 jian-dsl 时 core 的 query/eval 仍可用(L1 子集兜底)。
10. 语法错误信息带行列号、中文友好。
11. AST 缓存生效,大表场景吞吐可接受。

---

## 8. 开发过程版本变更记录(发布时统一为 v1.0)

| 项 | v0.1 | v0.2 | v0.3 | v0.4(开发末期,Oracle 基线) | 变更原因 |
|---|---|---|---|---|---|
| L1/L2 引擎 | 外部脚本引擎 | 手写 Pratt | 手写 Pratt | 手写 Pratt | 与"自包含 jar"目标一致 |
| **方言兼容** | 仅 MySQL | 仅 MySQL | MySQL/PG/Oracle | **Oracle 基线 + PG/MySQL 兼容** | 需求方指定 Oracle 风味 |
| **方言切换** | — | — | enum 参数 | **enum + 环境变量** | 需求方"用变量说明用哪种方言" |
| **PL/SQL 隔离** | — | — | — | **入口限定 select_statement** | Oracle grammar 合体了过程化,需隔离 |
| 安全 | 沙箱 | 受限语法 | 受限语法 | 受限语法 + 入口限定 | 自然 + 主动双保险 |


---

*本分册独立,与 02-06 无耦合,只单向依赖 core。完全可选,缺失时 core 兜底。jar 完全自包含。*

---

## 9. 实现说明(M6,2026-08-01)


### 9.1 已实现

| 文件 | 内容 |
|---|---|
| `PrattEngine.java` | L1/L2 手写 Pratt parser(Lexer + Pratt Parser + AST + Evaluator;支持三元/参数/${var}/多语句) |
| `SqlEngine.java` | L3 SQL 子集(正则切子句 + 翻译为 core 调用链;SELECT/WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/JOIN/UNION ALL)|
| `Params.java` | 命名参数绑定(替代 pandas 的 @var 引用)|
| `SqlDialect.java` | 方言枚举(ORACLE/POSTGRESQL/MYSQL/DEFAULT)+ fromEnv() |
| `Dsl.java` | 顶层门面(query/eval/sql 三档)|
| `JianDslEngine.java` + SPI 注册 | 实现 core 的 DslEngine SPI,经 ServiceLoader 自动接管 df.query/eval |

### 9.2 与需求的偏差

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| 多方言(ROWNUM/LIMIT/FETCH FIRST / NVL/COALESCE/IFNULL 等) | **分页三方言都认**(LIMIT n [OFFSET m] / FETCH FIRST n ROWS ONLY / OFFSET m ROWS FETCH FIRST / ROWNUM <= n);**空值函数已实现**(nvl/coalesce/ifnull → 第一个非 null) | 分页与空值函数均已在 L1/L3 落地 |
| `df.eval(...)` / `df.sql(...)` 接收者形态 | **已实现**:`df.eval("total = price * qty")`、`df.sql("SELECT ... FROM this")`(经 DslEngine SPI) | 与规范 §2.2 一致;core 未引 jian-dsl 时抛 ModuleNotLoadedException 提示 |
| `SELECT DISTINCT` / `LIMIT ... OFFSET` | **已实现**:DISTINCT 去重、`LIMIT n OFFSET m` 与 Oracle `OFFSET m ROWS FETCH FIRST n` | 规范 §2.3 补全 |
| like 模式语义 | **除 `%` `_` 外全部按字面量**(正则元字符转义,防正则注入) | 安全修复(2026-08-02 审查) |
| 方言变量 `SqlDialect.caseSensitive()` | 已定义但 v1.0 未接线:列名匹配一律精确匹配 | 大小写归一列 v2 规划(见 §9.5) |
| `Jian.setDefaultDialect(SqlDialect)` 全局默认方言 | 不提供(全局可变状态);方言只经 `Dsl.sql(sql, SqlDialect, dfs...)` 显式传,或 `SqlDialect.fromEnv()` 读环境变量 | API 风格:显式优于全局可变状态 |

### 9.3 SPI 自动接管(规范 07 §4 落地)

- core 定义 `DslEngine` SPI + 内置 `SimpleDslEngine`(L1 子集兜底)
- jian-dsl 实现 `JianDslEngine`,经 `META-INF/services/jian.core.DslEngine` 注册
- **用户引了 jian-dsl jar 后,`df.query/eval` 自动升级到完整 L1+L2;未引则用 core 内置兜底**(L1 子集)

### 9.4 设计决策(非 TODO)

以下为**有意不做**的设计选择(非遗留 TODO):
- **ANTLR4 已弃用**:L3 SQL 自写 Pratt + 正则版功能完整(SELECT/WHERE/GROUP/HAVING/ORDER/LIMIT/JOIN 4种/UNION ALL/子查询2层),无需 ANTLR4 的额外复杂度和依赖。
- **窗口函数 over SQL**:规范明确不做(走 core 的 Window API,规范 §1.4)。
- **CTE(WITH)/存储过程**:规范明确不做(§2.3)。
- **多方言空值函数归一化**(NVL/COALESCE/IFNULL):**已实现**(2026-08-02),见 §9.5。

---

*本分册独立,与 02-06 无耦合,只单向依赖 core。完全可选,缺失时 core 兜底。jar 完全自包含。*
### 9.5 2026-08-02 全项目审查修复

- **LIKE 正则注入修复**:`like` 模式除 `%`(任意串)、`_`(单字符)外,其余字符(含 `.` `*` `(` `)` 等正则元字符)一律按字面量转义匹配。
- **`in` 谓词补全**(core 兜底 `SimpleQueryParser`):`col in (v1, v2)` / `not in`,数值跨类型相等(Long 30 == Double 30.0)。
- **NVL/COALESCE/IFNULL 实现**:Pratt 新增函数调用节点,返回第一个非 null 参数(参数可引用列,逐行求值)。
- **L3 补全**:`SELECT DISTINCT`(去重)、`LIMIT n OFFSET m` / `OFFSET m ROWS FETCH FIRST n ROWS ONLY` / 独立 `OFFSET m`。
- **NPE 防护**:`${}` 占位与 `FROM this`/`DUAL` 混用 → 明确报错(提示改用 `df.sql()`);未知数据源不再静默当 this。
- **静态入口收窄**:`Dsl.sql(sql, dfs...)` 必须含 `${}` 表名占位(无占位时没有主表可指,直接报错);`FROM this`/`DUAL` 只挂在 `df.sql()`(接收者即主表)上。未发布项目无"兼容旧写法",此前的隐式绑定已移除。
- **绑定数量校验**:`Dsl.sql(sql, dialect, dfs...)` 与 `df.sql(sql, binds...)` 均校验占位数 = 参数数(防静默少绑)。
- **`df.eval()` / `df.sql()` 落地**:`DslEngine` SPI 增加默认 `sql()` 方法;core 兜底抛 `ModuleNotLoadedException`(带安装提示),jian-dsl 覆盖为完整实现。

---

*本分册独立,与 02-06 无耦合,只单向依赖 core。完全可选,缺失时 core 兜底。jar 完全自包含。*
*M6 实现(L1/L2/L3 + SPI 集成)完成于 2026-08-01;2026-08-02 全项目审查后 36 测试全过。*
