# jian API 与测试数 —— 单一事实来源

> **用途**:本文件是 jian 项目"实测 API 数 + 测试数"的**唯一事实来源**。所有其它文档(README.md / doc/00-07.md / ai-doc/module.md / api-ref.js / modules.js)涉及数字时,**只链接本文件,不要重复抄写数字**(避免多处漂移)。
>
> **维护规则**:每次合并 PR 前,运行下方"统计命令"刷新本文件;如数字变化,本文件是权威,其它文档以本文件为准。

---

## 统计口径(重要 —— 不明确口径会导致数字歧义)

- **public 方法数**:grep `^\s+public ` 计数,**包含**构造器、静态工厂方法、@Override 方法、方法重载(每个重载算 1 个);**不包含** `private` / `protected` / 包级私有。
- **@Test 测试数**:grep `^\s*@Test` 计数(只数方法前注解,排除注释里的 @Test 字样);**不包含** `@RepeatedTest`(展开为多次)和 jqwik `@Property`(单独列)。
- **@Property 测试数**:jqwik 属性测试,单独统计。
- **Python PBT**:Hypothesis `@given` 函数计数。
- 统计日期:**2026-08-09**;下次刷新:每次 PR 合并前。

---

## 实测数据(2026-08-09,L8 修复后刷新)

### 测试数

| 维度 | 数量 | 统计命令 |
|---|---|---|
| Java `@Test`(全仓) | **656** | `find jian -path "*/src/test/java/*" -name "*.java" -exec grep -hE "^\s*@Test\b" {} + \| wc -l` |
| ├ jian-core | **423** | `find jian/jian-core/src/test -name "*.java" -exec grep -hE "^\s*@Test\b" {} + \| wc -l`(含 Round2AuditFixTest 11) |
| ├ jian-dsl | **76** | 同上,换模块路径(含 Round2AuditFixTest 8) |
| ├ jian-io-sql | **33** | 同上 |
| ├ jian-export | **23** | 同上 |
| ├ jian-viz | **16** | 同上 |
| ├ jian-facade | **17** | 同上 |
| ├ jian-num-bridge | **6** | 同上 |
| └ 其余 io/num/sql 模块 | 各 1-16 | 同上 |
| Python PBT(`@given`) | **62** | `grep -cE "^def test_" tests-pbt/properties/*.py` |

> Surefire 报告数(经 @RepeatedTest/jqwik @Property 展开后约 846)与源码 @Test 数(656)不一致,因为 `@RepeatedTest` 展开为多次 + jqwik `@Property(tries=N)` 单源方法对应多次尝试。**以源码 @Test 数为准**。

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

---

## 历史漂移(已修正 —— 记录避免重蹈)

以下数字曾出现在多个文档且互相矛盾,现已统一到本文件:

| 项 | 曾出现的错误数字 | 实测真值(本文件) |
|---|---|---|
| jian-core 测试数 | 327 / 498 / 526 | **412** |
| DataFrame 方法数 | 85 / 143 / 165+ | **195**(含重载)/ ~180(unique 名) |
| Series 方法数 | 40 / 45 | **52** |
| astype dtype 数 | 5 / 7 | **8** |

**教训**:不要在多个文档重复抄写数字,一律链接本文件。
