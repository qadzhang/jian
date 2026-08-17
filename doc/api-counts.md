# jian API 与测试数 —— 单一事实来源

> **用途**:本文件是 jian 项目"实测 API 数 + 测试数"的**唯一事实来源**。所有其它文档(README.md / doc/00-07.md / ai-doc/module.md / api-ref.js / modules.js)涉及数字时,**只链接本文件,不要重复抄写数字**(避免多处漂移)。
>
> **维护规则**:因为数字必须与代码保持同步(AGENTS.md §0.3 文档同步红线),所以每次增删测试/公开方法后都要运行下方"统计命令"刷新本表;如数字变化,本文件是权威,其它文档以本文件为准。

---

## 统计口径(重要 —— 不明确口径会导致数字歧义)

- **public 方法数**:grep `^\s+public ` 计数,**包含**构造器、静态工厂方法、@Override 方法、方法重载(每个重载算 1 个);**不包含** `private` / `protected` / 包级私有。
- **@Test 测试数**:grep `^\s*@Test` 计数(只数方法前注解,排除注释里的 @Test 字样);**不包含** `@RepeatedTest`(展开为多次)和 jqwik `@Property`(单独列)。
- **两口径声明**:**@Test 方法数**(grep 统计)与 **surefire 执行数**(`@RepeatedTest`/参数化测试多轮执行)是两个口径,**本文以 @Test 方法数为准**;surefire 执行数仅在表尾附注,不作为主口径。
- **@Property 测试数**:jqwik 属性测试,单独统计。
- **Python PBT**:Hypothesis `@given` 函数计数。
- 统计日期:**2026-08-17**;下次刷新:每次增删测试后。

---

## 实测数据

### 测试数(22 模块)

| 维度 | 数量 | 统计命令 |
|---|---|---|
| Java `@Test`(全仓) | **1246** | `find jian jian-num jian-sql -path "*/src/test/java/*" -name "*.java" -exec grep -hE "^\s*@Test\b" {} + \| wc -l`(surefire 执行数随 @RepeatedTest/参数化多轮与 jqwik @Property 展开 —— 两个口径,以 @Test 方法数为准) |
| ├ jian-core | **572** | `find jian/jian-core/src/test -name "*.java" -exec grep -hE "^\s*@Test\b" {} + \| wc -l`(含蜕变/差分/PBT/边界/回归与 AI 复审回归 AuditRegressionTest 等专项套件) |
| ├ jian-dsl | **156** | 同上,换模块路径(含 EngineConformanceTest 19 双引擎矩阵;+3 PrattLiteralOverflowTest;+1 多行SELECT聚合回归) |
| ├ jian-facade | **93** | 同上(含 scenario 真实场景套件:S1~S16 第一轮 + S17~S46 第二轮抽象场景 30 类;+30 为场景扩充) |
| ├ jian-io-csv | **48** | 同上(含 CsvAdversarialFuzzTest 对抗模糊) |
| ├ jian-io-sql | **47** | 同上(+2:SQLite/PG 中文标识符保真) |
| ├ jian-export | **36** | 同上 |
| ├ jian-io-excel | **32** | 同上 |
| ├ jian-io-json | **28** | 同上 |
| ├ jian-viz | **31** | 同上 |
| ├ jian-num | **59** | 同上 |
| ├ jian-sql-engine | **28** | 同上 |
| ├ jian-sql-orm | **22** | 同上 |
| ├ jian-io-xml | **15** | 同上 |
| ├ jian-num-bridge | **11** | 同上 |
| ├ jian-sql-bridge | **12** | 同上 |
| ├ jian-io-html | **10** | 同上 |
| ├ jian-io-clipboard | **10** | 同上 |
| ├ jian-io-orc | **8** | 同上(`-Pcolumnar` 模块) |
| ├ jian-io-parquet | **6** | 同上(`-Pcolumnar` 模块) |
| ├ jian-io-pickle | **7** | 同上 |
| ├ jian-io-latex | **6** | 同上 |
| └ jian-sql-expr | **9** | 同上 |
| Python 测试 | **124** | `pytest tests-pbt -q`(**80 pandas 对照 d1~d80** + 24 Hypothesis + 16 fuzz + 4 robustness;+7 为 AI 复审对照 d74~d80) |

> surefire 执行数(`@RepeatedTest`/参数化/jqwik `@Property` 展开后)与源码 @Test 方法数 **1246** 是两个口径,**以源码 @Test 方法数为准**。

### API 方法数(jian-core)

| 类 | public 方法数 | 统计命令 |
|---|---|---|
| `DataFrame` | **195** | `grep -cE "^\s+public " jian/jian-core/src/main/java/jian/core/DataFrame.java` |
| `Series` | **52** | 同上,换文件 |
| `Index` | **12** | 同上 |
| `GroupBy` | — | (按需补充) |
| `Window` | — | (按需补充) |

> 注:`DataFrame 195` 含构造器/静态工厂/重载/Override。如需"unique 方法名数",用 `grep -oE "public \w+ \w+\(" | sort -u | wc -l`(约 180 unique 名)。

### dtype 支持(astype / convertColumn)

`DataFrame.astype(colName, dtype)` 支持以下 **8 种** dtype:

| dtype | 说明 |
|---|---|
| `DOUBLE` | double,缺失用 NaN |
| `LONG` | long,缺失用 Long.MIN_VALUE 哨兵 |
| `INT` | int,升位为 LONG 存储 |
| `STRING` | String |
| `BOOL` | boolean |
| `DATETIME` | LocalDateTime |
| `DATE` | LocalDate |
| `OBJECT` | 任意 Object[](兜底) |

**`CATEGORY` 抛 `IllegalArgumentException`**(未实现;pandas 的 category dtype 是稀疏存储优化,jian 当前用 StringColumn 覆盖大部分场景,v2 再评估)。
