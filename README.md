# JIAN · JVM 轻量数据栈

> **jian** = 拼音「简」= **J**ava **I**ntegrated **AN**alysis
>
> 以玉简之器,容数据之变;以简化之桥,渡语言之隙;以简约之道,立长久之基;以吉安之愿,佑用者之祥。

闲来手搓的项目,对标 **pandas / sqlalchemy / numpy** 子集的 JVM 数据栈。三个独立库,按需引用。

---

## 快速开始

```bash
# clone 后仅需 JDK 17+
./mvnw install
```

```java
import jian.Jian;
import jian.core.DataFrame;

// 读(按扩展名自动分发:csv/json/xlsx/html/xml/parquet/orc/pickle 等 12 格式)
DataFrame df = Jian.read("users.csv");

// 变换(链式,对齐 pandas)
DataFrame r = df.query("age > 18")
                .groupBy("dept")
                .agg(Map.of("salary", "mean"))
                .sortBy("salary_mean", false)
                .head(10);

// 内存 SQL(不碰数据库,支持多表 JOIN / 子查询)
Dsl.sql("SELECT city, avg(score) FROM ${t} GROUP BY city", df);
Dsl.sql("SELECT * FROM ${a} LEFT JOIN ${b} ON a.id=b.id", df1, df2);

// 写(按扩展名自动分发)
Jian.write(r, "report.html");
```

---

## 架构图

![](doc/architecture.svg)

**三个独立库:**
- **jian** — DataFrame + 12 格式 IO + 13 种图(PNG/SVG,4 种高维图 v2 规划) + Styler 导出 + DSL(SQL 子集)
- **jian-sql** — Engine(HikariCP) + SqlBuilder(jOOQ) + ORM(Session) + Bridge
- **jian-num** — Ndarray + 描述统计 + 线代 + 随机数(对标 numpy 子集)

**核心设计:**
- 三库完全独立,jian-sql 不依赖 jian,jian-num 不依赖任何
- 按需引 jar,不引不加载(不引 jian-io-excel → JVM 不加载 POI)
- SPI 解耦:core 内置兜底,引了 jian-dsl / jian-num-bridge 自动升级
- 零本机绑定(无硬编码路径/凭据);精细引用(无 uber/shade)

---

## 模块清单(22 个子模块)

| 库 | 子模块 | 说明 |
|---|---|---|
| **jian** | jian-core | DataFrame/Series/GroupBy/Window/MultiIndex(9 dtype 列式) |
| | jian-io-csv | CSV/TSV/FWF |
| | jian-io-excel | xls/xlsx(逐列类型精确推断) |
| | jian-io-json | JSON 5 种 orient |
| | jian-io-html | HTML 表格(jsoup) |
| | jian-io-xml | XML(Jackson) |
| | jian-io-sql | 7 库 DbType(3 库真测:H2/SQLite/PG) |
| | jian-io-parquet | Parquet 列存 |
| | jian-io-orc | ORC 列存 |
| | jian-io-pickle | 自定义 .jpk 序列化 |
| | jian-io-clipboard | 跨平台剪贴板 |
| | jian-io-latex | LaTeX 表格 |
| | jian-viz | 13 种图表(PNG/SVG;4 种高维图 v2 规划) |
| | jian-export | HTML/Markdown/LaTeX/控制台 + Styler |
| | jian-dsl | L1 query + L2 eval + L3 SQL(Pratt+正则,零依赖) |
| | jian-num-bridge | StatsProvider SPI |
| | jian-facade | 顶层 Jian 门面(聚合全部 io) |
| **jian-sql** | jian-sql-engine | Engine + DbType(7库) + HikariCP |
| | jian-sql-expr | SqlBuilder(jOOQ 运行时模式) |
| | jian-sql-orm | Session ORM CRUD(@Table/@Column/@Id) |
| | jian-sql-bridge | ResultSet → DataFrame |
| **jian-num** | jian-num | Ndarray/Stats/Matrix/Random |

---

## 缺失值与 NaN 语义

jian 的缺失值处理遵循**"内部不失真、边界做转换"**原则:

| API | 缺失行返回 | 说明 |
|---|---|---|
| `isNull(i)` | `true` | 权威判断,全类型一致 |
| `getDouble(i)` | `NaN` | 数值缺失统一占位(全类型) |
| `getLong(i)` | `Long.MIN_VALUE` | long 无 NaN,用最小值作缺失标记 |
| `get(i)` | DoubleColumn 返 `Double.NaN`(不失真);其它返 `null` | **NaN 不失真** |
| `getRow(i)` | `null` | IO 边界安全网(CSV/JSON/SQL 写出) |

> **为什么不用 pandas 的"NaN==null"模型**:pandas 把 NaN 和 null 在数值列等价处理是历史包袱。jian 区分:NaN=计算产生的非数(有效值的一种),null=原始缺失。两者在 `isNull` 统一,但在 `get` 层面 NaN 不失真。
>
> **export 层缺失行显示**:HTML 默认 `<NA>`(对齐 pandas `to_html`,naRep 可配);Markdown/LaTeX/Excel/控制台默认空字符串,均不输出裸 "NaN"。详见 `AGENTS.md §3.5`。

## SQL 跨库支持(7 库 DbType,3 库真测)

jian-io-sql 的 `DbType` 枚举定义 **7 种数据库**:PostgreSQL / MySQL / Doris / SQLite / H2 / Oracle / MS Access(Doris 复用 MySQL 协议)。

> **真实库测试覆盖**:**3 库有集成测试**(H2 + SQLite 默认跑,PostgreSQL 经 `-Dtest.pg=true` 激活);**4 库仅 DbType 定义**(MySQL/Doris/Oracle/Access,用户引对应驱动后接口通用,但未经 CI 验证)。

**类型映射自适应**(`Sql.java` 按 `DatabaseMetaData` 探测方言):

| jian 类型 | PG | MySQL | Oracle | SQLite | H2 |
|---|---|---|---|---|---|
| DOUBLE | DOUBLE PRECISION | DOUBLE | FLOAT(126) | REAL | DOUBLE PRECISION |
| STRING(短) | VARCHAR(n) | VARCHAR(n) | **VARCHAR2(n)** | TEXT | VARCHAR(n) |
| STRING(长>4000) | **TEXT** | **LONGTEXT** | **CLOB** | TEXT | **CLOB** |

> VARCHAR 长度自适应:扫实际数据取 maxLen,≤4000 用 VARCHAR(n),>4000 用大文本类型。
> JDBC 读回自动规范化:Clob→String、BigDecimal→Double、Timestamp→LocalDateTime。详见 `AGENTS.md §3.6`。

**真实数据库测试**(不只 H2 模拟方言):
- H2 in-memory + SQLite in-memory:默认跑
- PostgreSQL 18:`-Dtest.pg=true` 激活(14 个测试覆盖全 dtype/大文本/注入防护)

## Web 环境安全(Tomcat/Spring Boot)

jian 可安全用于 Web 服务器环境,现行防护:

| 防护点 | 威胁 | 做法 |
|---|---|---|
| ServiceLoader 缓存导致 Tomcat redeploy 内存泄漏 | 🔴 高 | 每次 `current()` 新建 ServiceLoader,不缓存 |
| 只读模式形同虚设 | 🟠 中 | `engine.sql()` 入口强制调用 `checkReadOnly`(剥注释/字符串后整词、大小写不敏感匹配) |
| Excel 写出无公式注入防护(CSV 有) | 🟠 中 | Excel 加 `= + - @` 单引号前缀(同 CSV) |
| Clipboard 子进程流未关闭 + 无超时 | 🟡 低 | try-with-resources + `waitFor(5s)` + `destroyForcibly()` |

**安全的方面**(无需改):
- 反序列化:Jackson 未开 defaultTyping;Pickle 走自定义容器 + JSON,无 ObjectInputStream
- SQL 参数化:全 PreparedStatement + ? 占位符
- Connection/文件流:全 try-with-resources
- DataFrame 不可变:构造后无 mutator;Web 场景用 `ofColumnArraysSafe`(防御性 clone)替代 `ofColumnArrays`(零拷贝)
- ThreadLocal 仅一处(`jian.dsl.SqlEngines` 多请求引擎隔离,须 try-finally reset,见 `AGENTS.md §3.7.7`);其余无静态可变状态

详见 `AGENTS.md §3.7`。

## 内存管理(Java GC 语义)

jian 的 DataFrame 是**纯内存数据对象**（`long[]`/`double[]`/`String[]`），不持有文件句柄或数据库连接。理解 Java 的 GC 语义对 Web 场景很重要：

### `df = null` 不会立即释放内存

```java
DataFrame df = Jian.read("big.csv");  // 堆上分配了 ~100MB
df = null;                             // 只断开引用,100MB 数据仍在堆里
// → 等 GC 自动运行时才回收(JVM 决定时机,通常几秒内)
```

Java **没有** C 的 `free()` 或 C++ 的 `delete`。开发者只能断开引用，GC 负责回收。

### Web 场景的正确做法

```java
// ✅ 方法内局部变量(最推荐)：方法结束后自动断引用，GC 回收
void handleRequest() {
    DataFrame df = Jian.read("data.csv");
    // ... 处理 ...
    // 方法结束 → df 出栈 → GC 回收
}

// ✅ 大数据量处理完立刻断引用
DataFrame df = Jian.read("huge.csv");
// ... 处理完，后续不再需要 df ...
df = null;           // 断引用
System.gc();         // 建议 JVM 赶紧 GC(不保证立即,但通常有效)

// ❌ 危险：static 缓存持有大 DataFrame
static Map<String, DataFrame> cache = new HashMap<>();
cache.put("big", df);  // 即使 df=null，cache 还引用着，GC 永远不回收 → OOM

// ✅ 如需缓存，用弱引用(GC 可随时回收)
static WeakHashMap<String, DataFrame> cache = new WeakHashMap<>();
```

### 为什么 jian 不提供 close()/dispose()

- DataFrame 是纯内存数据，不持有外部资源
- `AutoCloseable` 是给**持有外部资源**（文件句柄/连接/锁）的对象用的
- 加 close 会误导用户以为"不 close 就泄漏"，实际上 GC 会自动回收
- jian 真正需要 close 的是 `Engine`（HikariCP 连接池）和 `SqlBuilder`（JDBC 连接），它们已实现 `AutoCloseable`

### 不可变语义

DataFrame 是**不可变**的——每个变换返回新 DataFrame，原 DataFrame 不变（类似 Java String / pandas）：

```java
DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}});
DataFrame df2 = df.astype("v", DType.LONG);  // 返回新 df2,原 df 不变

// 想"原地改"？用变量覆盖(语义仍是新对象替换旧引用)
df = df.astype("v", DType.LONG);  // 原 DataFrame 如无人引用,GC 自动回收
```

线程安全：多个请求可并发读同一个 DataFrame，无需加锁。

## 构建

```bash
./mvnw install                    # 默认 thin jar(22 个子模块)
./mvnw -Pfat package              # 额外出 3 个 fat jar(jian-all / jian-num-all / jian-sql-all)
./mvnw -pl jian/jian-core -am compile    # 只构建 core
./mvnw test                       # 跑全部测试
```

**双形态制品**(AGENTS.md §2.5):
- **默认 thin jar**:`./mvnw install` 出 22 个细粒度子模块 jar,按需引用,子模块零整合(无 shade)。
- **可选 fat jar**:`./mvnw -Pfat package` 额外激活顶层三库(jian / jian-num / jian-sql)的 `maven-shade-plugin`,各出一个 `*-all.jar`(含全部依赖,单文件即可上手)。fat jar 是 AI 友好的补充形态,带 `Ai-Aggregated: true` manifest 标记。

依赖管理:Maven 多模块 + 阿里云镜像(配置在 `.mvn/settings.xml`)。

---

## 文档

- `doc/index.html` — 可视化门户(单文件,浏览器打开即用)
- `doc/00-overview.md` ~ `07-jian-dsl.md` — 详细需求与实现说明
- `NAMING.md` — 命名由来(玉简/简化/简约/吉安)
- `AGENTS.md` — 开发规范(凌驾全项目)

---

## 测试方法学(给 AI 协作项目用)

> 本项目大量代码由 AI 辅助生成,采用一套**针对 AI 代码弱点设计的系统化测试方法**。
> 该方法同时装备**人类审查者**和 **AI 审查者**,两边按同一份 checklist 工作。
> 详细论述见 `doc/00-overview.md §10.8`;此处是大纲与使用指南。

### 为什么 AI 代码需要特殊测试方法

AI 生成代码有个核心难题叫 **oracle problem(预言机难题)**:很难预先知道"正确输出"是什么——
因为"期望输出"本身也可能是 AI 生成的(它会把期望写错)。所以传统"输入 → 期望输出"的写法常失效。
解决方案是**绕开 oracle**——用三种方法验证代码"行为符合性质",而不是"输出等于某值"。

### 三种核心方法

| 方法 | 思路 | 例 | 何时用 |
|---|---|---|---|
| **蜕变测试**<br>(Metamorphic) | 不验具体输出,验**输入输出间的必要关系** | `sortBy` 后 `rowCount` 必守恒;<br>`filter(p) ∪ filter(¬p)` 必等于原表 | 关系明确但具体值难算 |
| **差分测试**<br>(Differential) | 同一算子的两个实现跑同输入,**结果应一致** | long key 走 fast path 与 String key 走 generic path 结果应等价 | 有 fast / generic 双实现 |
| **基于性质测试**<br>(Property-Based) | 声明**性质**,框架自动生成 N 个随机输入 | `reverse(reverse(x)) == x`;<br>`sort(x).size() == x.size()` | 不变量清晰的算子 |

辅助方法:

- **变异测试(Mutation Testing)**:用 PITest 主动改坏代码,看测试能否抓到——**测测试本身是否有效**
- **静态分析**:SpotBugs / PMD / `-Xlint` 抓编译期问题
- **多智能体交叉审查**:多个独立 AI 实例(不同模型/视角)互相找茬,**但 AI 审查不能替代机器化差分/蜕变测试**

### 已落地的测试代码

| 测试类 | 数量 | 方法 | 覆盖 |
|---|---|---|---|
| `MetamorphicTest` | 99 断言 / 31 方法 | 蜕变 | sortBy/filter/merge/concat/groupBy/astype/head/tail/slice/agg 等,`@RepeatedTest` 多轮(surefire 展开后 99,源码 24 @Test + 7 @RepeatedTest) |
| `PropertyBasedTest`(jqwik 1.9.3) | 25 | PBT | sortBy/filter/head/concat/dropDuplicates/merge/groupBy/fillna/dropna/ffill/astype/select/drop/slice/nlargest/nsmallest/colAdd/colMul/assign,`tries=100` 自动随机输入 |
| `DifferentialTest` | 38 断言 | 差分 | long/int/double key fast vs generic path 等价 + INT×LONG 混合 + DATE 保留 + ofColumnsDirect vs ofColumnArrays + getIntColumn LONG→INT |
| `NullNaNPropagationTest` | 9 | 蜕变 | **NaN/缺失值全链路不失真**:get 不失真 + getDouble 返 NaN + getLong 返 MIN_VALUE + getRow 边界转 null + ffill/bfill + merge 补 null + 排序 + 算术传播 |
| `ColumnarPerfTest` | 27 | 单元 + 回归 | 边界与回退路径的"重现代码"防回归 |
| `tests-pbt/properties/test_jian_properties.py`(Python Hypothesis) | 24 | PBT 同行评议 | 与 jqwik 同样性质 + colSub/colDiv 双语言交叉验证(**v 含 NaN 边界注入**) |
| `tests-pbt/properties/test_pandas_diff.py`(pandas 1.5.3) | 80 | pandas 对照(d1-d80) | 以 pandas 为 oracle,覆盖 head/tail/sortBy/filter/dropDuplicates/merge/concat/nlargest/nsmallest/select/drop/slice/colSub/colDiv/colLt/fillna/dropna/ffill/astype/groupBy/idxmax/idxmin/duplicated/sample/isin/where/mask/cumsum/diff/pct_change/clip/quantile/rank/round/prod/pivot/explode/merge_asof/resample/统计/Window 等算子(完整清单以该文件 test_d* 函数为准) |
| `SqlPostgresTest`(PostgreSQL 18) | 14 | 真实 PG | 全 dtype 往返 / 参数化 / 4 种写入模式 / 缺失值 / **VARCHAR 自适应** / **大文本不截断** / **PG 小写列名** / 万行 / SQL 注入防护 |
| 其它既有测试 | ~157 | 单元 | 模块正常功能(dsl/export/io/num/sql/viz/facade 各子模块) |
| **合计 jian-core** | **571**(见 [doc/api-counts.md](doc/api-counts.md)) | | 60+ 扩展 DataFrame 方法(idxmax/sample/isin/where/mask/pivot/explode/join/merge_asof/corr/cov/skew/kurt/cumsum/diff/quantile/rank/clip/interpolate/astype 8种/Resampler/DatetimeIndex/Frequency/MultiIndex N级 等) |
| **合计 Java 全量(22 模块)** | **1246**(@Test 方法数;含 jian-facade 真实场景集 46 个:S1~S16 第一轮 + S17~S46 第二轮抽象场景 30 类,断言源码随 jar 分发) | 22 模块 | 实测 @Test 数,两口径以 @Test 方法数为准(见 doc/api-counts.md);另有 jqwik @Property 展开 + PG skip 14 |
| **合计 jian-io-sql** | **47** | H2+SQLite+PG | 3 库真测(H2/SQLite 默认跑;PG `-Dtest.pg=true` 激活,含 SQL 注入防护 + 中文标识符引号保真) |
| **合计 jian-export** | **36** | | 含缺失值显示(空 vs "NaN")验证 |
| **合计 Python 端** | **124** | Hypothesis+pandas | 24(PBT 同行评议) + 80(pandas 对照 d1-d80) + 16(fuzz) + 4(robustness),`pytest tests-pbt -q` |

### 变异测试(PITest)已落地——测"测试本身是否有效"

本项目用 [PITest](https://pitest.org/) 1.19.1 + JUnit 5 plugin 跑变异分析(主动注入 bug 看测试能否抓到)。
配置在 `jian/jian-core/pom.xml`,跑法:

```bash
mvn -pl jian/jian-core -o test-compile org.pitest:pitest-maven:mutationCoverage
# 报告:jian/jian-core/target/pit-reports/<时间戳>/index.html
```

**变异分数**:

| 类 | 行覆盖 | 变异杀死率 | 测试强度 |
|---|---|---|---|
| ColumnarHashMap | 92% | 75% | 80% |
| DataFrame | 81% | 61% | 78% |
| DataFrameMerge | 91% | 68% | 79% |
| GroupBy | 92% | **72%** | 78% |

变异测试能客观量化测试盲点:如 GroupBy 的大量聚合分支曾被测试遗漏,按报告补齐聚合性质测试后其杀死率从 50% 提升到 72%。这是它相对人工/AI 审查的核心价值——不会"自判收敛"。

### 双语言交叉 PBT(jqwik 1.9.3 + Python Hypothesis)

本项目同时用两套独立的 PBT 实现验证核心性质(jqwik 端 25 条),形成"**同行评议**"——任一方出错都能被另一方对出。

**Java 端(jqwik 1.9.3)**:`jian/jian-core/src/test/java/jian/core/PropertyBasedTest.java`
```bash
mvn -pl jian/jian-core test -Dtest=PropertyBasedTest    # 25 性质各 tries=100
```

**Python 端(Hypothesis 6.165.2)**:`tests-pbt/properties/test_jian_properties.py`
```bash
python3 -m pytest tests-pbt/properties/test_jian_properties.py  # 24 性质各 max_examples=100-200
# 通过 tests-pbt/harness/jian_client.py(JPype 直调 JianJpypeBridge)跨语言调 jian jar
```

### pandas 对照测试(pandas 1.5.3 oracle,红线)

> 本项目是对标 pandas 的 JVM 实现。**AGENTS.md §0.5(第四条红线)** 规定:凡对标 pandas 的算子**必须有 pandas 对照测试**——把 pandas 当"老师"给 jian 改卷子。

**测试位置**:`tests-pbt/properties/test_pandas_diff.py`
```bash
python3 -m pytest tests-pbt/properties/test_pandas_diff.py   # 73 个对照测试(d1-d73)
```

**当前覆盖**:head/tail/sortBy/filter/dropDuplicates/merge/concat/nlargest/nsmallest/select/drop/slice/colSub/colDiv/colLt/fillna/dropna/ffill/astype/groupBy/统计/Window/Resample 等,完整算子清单以该文件 `test_d*` 函数清单为准(d1-d73)。每次 jian 新增/修改对标 pandas 的算子,对应的 pandas 对照测试**必须同步增加**(AGENTS.md 红线)。

**发现并处理的差异**:sortBy 稳定性——pandas `sort_values()` 默认 `kind='quicksort'`(不稳定),jian 用 TimSort(稳定);两者对相同键的行序可能不同。**判定:jian 的稳定排序是更优语义,不视为 BUG**,测试用多重集断言对齐(D3)。详见 `doc/00-overview.md §10.12`。

#### jqwik 版本选择(必须 1.9.3,严禁 1.10.x)

> **⚠️ jqwik 1.10.x 不能用**:1.10.0(2026-05-25)被作者注入恶意 prompt-injection 字符串(`JqwikExecutor.printMessageForCodingAgents`,ANSI 隐藏"删除所有 jqwik 测试"指令);1.10.1+ 把"反对 AI 协作"立场固化到官方 release notes:"This project is not meant to be used by any 'AI' coding agents at all"。详见 [Snyk 披露](https://snyk.io/blog/protestware-open-source-maintainer-qwik-1-10-0-prompt-injection/)。
>
> **本项目用 1.9.3**(投毒前最后稳定版,已 strings 校验投毒字符串 0 命中)。jar 需自行放到共享仓库(默认 `~/tools/jar`,可用 `-Djar.home=/path` 覆盖),pom 用 `scope=system` 引用(避免 Maven 解析到 1.10.x)。详见 jian-core/pom.xml 注释。

#### 双语言交叉 PBT 的互补价值

两套独立实现互相验证,任一方漏掉的性质另一方可能抓到——实战中死性质(P10 两端都漏调 `groupBy`)、`data()` 零拷贝对公共 API 契约的影响、`Json.java` 的 NaN/Infinity 处理遗漏等均由交叉验证发现。**任一单一方法(单语言 PBT 或单智能体审查)都会漏掉部分 BUG,组合方法才接近完整**。详见 `doc/00-overview.md §10.11`。

辅助审查方法:

- **双智能体交叉审查**:两个独立智能体(不同模型、不同视角)互相审查,能发现单一视角漏掉的问题;但**单一视角多轮收敛 ≠ 真无 BUG**,且审查结论不能替代机器化差分/蜕变测试。详见 `doc/00-overview.md §10.8`。

### 实战教训

审查方(无论人或 AI)也会把概念记错——例如把 `Double.equals` 对 ±0.0 的语义记反,曾据此把原本正确的实现改坏,而机器化差分测试(`dt_merge_正零负零_与DoubleEquals等价`)当场抓到了这个不一致。

> **核心教训**:
> 1. 审查方也会记错概念;**机器化的差分/蜕变测试**不会"记错",只会如实反映输入输出关系——唯一可靠的守护。
> 2. **单一视角多轮收敛 ≠ 真无 BUG**;交叉视角 + 机器化测试双保险。
> 3. **测试断言要严**——"行序可能不同"这种宽松断言会放过真实 BUG,要尽量精确(行序、类型、nullCount 都比对)。
> 4. **混合 dtype 是 BUG 重灾区**——问题常出现在"两种 dtype 的交互边界"。

### 使用指南(给审查者,无论是人还是 AI)

跑测试:
```bash
./mvnw test                                   # 全项目测试
./mvnw -pl jian/jian-core test                # 仅 jian-core
./mvnw -pl jian/jian-core test -Dtest=MetamorphicTest   # 仅蜕变测试
```

写新测试时,优先选方法:
1. **能写成"关系/不变量"的就别写"具体期望值"**——后者 AI 容易写错
2. **同一算子新加 fast path,必须配差分测试**——验证与 generic path 等价
3. **修复每个 BUG 后,写"重现代码"测试**——防回归

### AI 审查 checklist(给人/AI 共用)

```
[ ] 是否引入了跨实现的不一致?(fast path 与 generic path 行为不等价)
[ ] 边界条件是否完整?(空数组、null key、重复 key、±0.0、NaN、容量溢出)
[ ] 测试是否机器化覆盖?(不能只靠"我看了一遍觉得没问题")
[ ] "期望值"是不是 AI 自己写的?(若是,需要差分/蜕变方法验证)
[ ] 是否漏掉了某个文档化的语义?(如 Double.equals 与 Double.compare 不同)
```

详细论述见 `doc/00-overview.md §10.8`。

---

## 技术选型

| 积木 | 版本 | 用在 |
|---|---|---|
| Apache Commons CSV | 1.12.0 | jian-io-csv |
| Apache POI | 5.5.1 | jian-io-excel |
| Jackson | 2.18.2 | jian-io-json/xml |
| jsoup | 1.18.3 | jian-io-html |
| parquet-avro | 1.14.4 | jian-io-parquet |
| orc-core | 1.9.5 | jian-io-orc |
| XChart | 4.0.3 | jian-viz |
| Commons Math | 3.6.1 | jian-num |
| jOOQ | 3.21.6 | jian-sql-expr |
| HikariCP | 6.2.1 | jian-sql-engine |

---

## 免责声明（必读）

本项目由 AI 工具辅助开发，免费分发，不附带任何形式的担保。

**开发模型**：本系统最初由 **GLM-5.2** 编写,现由 **GLM-5.3** 主要维护,**MiniMax M3** 与 **DeepSeek V4 Flash** 协助开发。

**AI 生成内容**：本项目的需求文档、设计、代码均由 AI 工具辅助生成，可能存在错误、遗漏或未经验证的内容。使用者须自行核实、自行评估、自行测试。

**免费分发，"按现状"提供**：本项目以"按现状"（AS IS）基础免费提供，不收取任何费用，明示不提供任何明示或默示的担保，包括但不限于对适销性、特定用途适用性、非侵权、准确性、可靠性的担保。

**仅用于学习与交流目的**：本项目的核心目的是技术学习与研究交流——验证 JVM 上对标 pandas/numpy/sqlalchemy 的可行性，并非商业产品。不建议直接用于生产环境或关键业务。如需生产级方案，请评估商业支持的官方或第三方产品。

**不保证及时维护**：作者没有义务对本项目进行持续维护、版本跟进、问题修复或安全更新。使用者须做好"无人维护"的心理准备，自行 fork、自行修复。

**使用者自行承担全部安全风险**：本项目涉及数据库连接（凭据走 `.env` 环境变量）、文件读写、批量导入等多个安全敏感面。作者不担保本项目在任何使用场景下的安全性，相关风险包括但不限于：
- **凭据泄露**：示例中可能展示数据库连接方式；如使用者照搬配置、提交到版本库或部署到不可信环境，由此引发的后果由使用者自行承担；
- **数据隐私**：本项目读写各类数据文件（CSV/Excel/JSON/SQL/Parquet 等），作者不担保满足任何特定地区的合规要求（如 GDPR、个人信息保护法等）；
- **第三方依赖**：依赖 POI、Jackson、jOOQ、Commons Math 等第三方库，作者不担保这些依赖无漏洞、无供应链攻击风险；
- **数据精度**：Excel 等格式存在固有限制（如 double 存储导致 >15 位整数精度丢失），作者不担保数据转换的绝对准确性。

**不承担任何后果**：使用者因使用、复制、分发、修改或依赖本项目而产生的任何直接或间接损失——包括但不限于数据丢失、业务中断、凭据泄露、合规违规、第三方索赔——作者概不负责。

**数据无价，自行备份**：本项目涉及数据读写与格式转换，操作前请务必做好完整备份。

下载、安装、使用、复制、分发或以任何方式利用本项目，即视为已阅读、理解并无条件接受本免责声明的全部条款。如不同意，请立即停止使用并删除本项目。

---

## License

MIT
