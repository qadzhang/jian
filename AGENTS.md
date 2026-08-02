# Agent 行为规范(jian 项目专用)

> **本文件凌驾于本目录所有其它文档。** 与上级目录的通用 `AGENTS.md`(通用规范)冲突时,以本文件为准;本文件未覆盖处,沿用通用规范的对应条款。
>
> **jian 的特殊性**:本目录产出的是**对外分发的通用 Java 库**(jian / jian-sql / jian-num),不是脚本工具集,也不是技能包(Skill)。因此对通用规范做了四处重大调整:① **只用 Java 编写,不用 Groovy/Kotlin 等 JVM 语言**(§1);② **破例引入 Maven 多模块**(§2);③ **不做包整合 —— 不用 uber/fat jar、不用 maven-shade,外部 jar 一律按 `groupId:artifactId:version` 精细引用**(§2.5);④ **不适用 Skill 开发标准**(通用规范第 0、6 章整章不适用,见 §0 说明)。

---

## 0. 总纲(三条红线)

### 0.1 项目定位:对外分发的通用 Java 库

jian 是 **JVM 上对标 pandas / sqlalchemy / numpy 子集**的轻量数据栈,**产物是可对外分发的 jar 包**,必须能在任意一台未做特殊准备的开发机上开箱即用。所有编码、文档、依赖决策都服务于这一目标。

- 三个库(jian / jian-sql / jian-num)相互独立,按需引用,引哪个用哪个。
- 详见 `doc/00-overview.md`。

### 0.2 零本机绑定(强制 · 凌驾全章)

**严禁把任何本机的路径、配置、软件环境、用户名、凭据、硬件特征硬编码到任何源码、脚本、文档、注释里。**

- ❌ 不得出现:任何本机绝对路径(家目录/工具目录/用户名/盘符)、本机已装版本号、数据库连接串、API Key、内网域名。
- ✅ 必须做到:运行时探测(`System.getProperty` / `System.getenv` / `command -v`)、环境变量 + 平台无关回退默认值(如 `${JAR_HOME:-~/tools/jar}`)、敏感信息走 `.env`、依赖缺失时优雅降级给安装提示而非崩溃。
- **跨平台**:Linux / macOS / Windows 三平台通用;调用外部程序按目标平台动态确认可执行文件名与后缀(详见 §7)。

### 0.3 文档双轨同时维护(强制 · 红线)

**`doc/*.md` 与 `doc/index.html` 是同一份需求/接口说明的两种形态,必须同步维护,不允许只改一边。**

- **md 是事实来源**(source of truth):`00-overview.md` ~ `07-jian-dsl.md` 是详细需求分册。
- **html 是可视化门户**:由 `doc/index.html` 单文件承载,数据驱动渲染(模块卡 / API 速查 / 方法卡均由文件末尾 `<script>` 里的 `MODULES` / `API_QUICK` / `API_REF` 数据数组生成)。
- **改文档时的强制流程**(详见 §5):
  - 改需求/接口的**事实内容** → 先改对应 md 分册 → 再同步到 html。
  - html 的数据数组(`MODULES` / `API_QUICK` / `API_REF`)与 md 不一致时,**以 md 为准,改 html 对齐 md**。
- md 与 html 冲突时,**以 md 为准**(这是 `00-overview.md` 已声明的原则)。

### 0.4 与通用规范的关系(删/留清单)

| 通用规范章节 | 在 jian 的适用性 | 处理 |
|---|---|---|
| §0 Skills 使用规范 | ❌ 不适用(jian 不是 skill) | **整章删除** |
| §1 编程语言与运行环境 | ⚠️ **只用 Java**(不用 Groovy/Kotlin 等 JVM 语言)、JDK 改 17 | §1 重写 |
| §2 依赖与 jar 包管理 | ⚠️ 破例用 Maven、**不做包整合**(不用 uber/fat/shade) | §2 重写 |
| §3 编码与文档规范 | ✅ 完全适用 | §3 保留(示例改 Java) |
| §4 脚本与临时文件管理 | ✅ 适用 | §6 保留 |
| §5 工作流程检查清单 | ✅ 适用 | §8 重写为本项目流程 |
| §6 Skill 开发标准 | ❌ 不适用 | **整章删除** |

---

## 1. 编程语言与运行环境

### 1.1 只用 Java

jian 项目**只用 Java 编写,不使用 Groovy、Kotlin 等 JVM 语言**。库源码、测试代码、一次性验证 / demo / 冒烟脚本,全部用 Java。

| 场景 | 语言 | 说明 |
|---|---|---|
| 库的源码(`jian-core` / `jian-io-*` / ...) | **Java**(`.java`) | 严格遵守 JDK 17 特性 |
| 构建脚本(pom.xml) | **XML(Maven)** | 见 §2 |
| 测试代码 | **Java**(JUnit 5) | 一律 Java |
| 一次性验证 / demo / 冒烟脚本 | **Java**(`.java`) | 利用 JDK 17+ 单文件源码启动(`java Demo.java`),任务结束清理(见 §6) |

> 验证某个 jar 行为、跑 demo、冒烟测试,统一写 Java 单文件或 JUnit 测试,长期验证沉淀为 JUnit 测试入库。

### 1.2 JDK 基线:JDK 17 LTS(强制)

- **统一 JDK 17 LTS**(向下兼容 17+)。三个库 + 7 个分册口径完全一致,**不混用 21**。
- API 风格自由使用 JDK 17 特性:`record` / `sealed` / pattern matching for switch / 文本块。
- 编译 Java 时强制 `-encoding UTF-8`(防中文乱码):
  ```bash
  javac -encoding UTF-8 MyApp.java
  ```
- 一次性验证可利用 JDK 17+ 的单文件源码启动特性:`java Demo.java`(需带 classpath 时见 §2.7)。

### 1.3 不自行安装/切换 JDK 版本

直接使用开发机已安装的 JDK,**不自行安装、不切换版本**。运行前确认:
```bash
java -version     # 应为 17 或更高
```

---

## 2. 依赖管理(Maven 多模块 · 破例)

### 2.1 破例引入 Maven(与通用规范 §2.3 相反)

通用规范禁止 Maven/Gradle,但 jian 有 **22 个子模块**(`jian-core` / `jian-io-csv` / `jian-io-excel` / ... / `jian-num`),手工管理 jar 依赖会失控。因此**破例引入 Maven 多模块**。

- Maven 仅用于**构建期依赖管理**,**不要求最终用户装 Maven**(产物仍是 jar)。
- 顶层一个 parent pom,聚合所有子模块;每个子模块独立 jar(细粒度按需加载)。
- 用户最终拿到的产物是**每个子模块一个 jar**,引哪个用哪个,不引不加载。

### 2.2 顶层 API 不写死外部 jar 类名

`jian-io-sql` 用反射 + `ServiceLoader` 探测已加载的 JDBC 驱动,**不写死任何驱动类名**。调用未引 jar 的功能时,抛 `ModuleNotLoadedException`(带"请引 xxx.jar"提示),而非 `NoClassDefFoundError`。

### 2.3 下载源:阿里云镜像

新依赖统一从阿里云公共仓库取(通用规范 §2.4 在 jian 里继续生效):
```
https://maven.aliyun.com/repository/public
```
- Maven 项目的 `pom.xml` / `settings.xml` 配置阿里云镜像。
- 手动下载 jar 时,URL 拼接规则不变:`{group路径}/{artifactId}/{version}/{artifactId}-{version}.jar`。

### 2.4 共享 jar 仓库(本机开发时)

本机开发时,共享 jar 仓库仍走通用规范 §2 约定的目录(本机如 `~/tools/jar`)。但**严禁把绝对路径写进任何源码、文档、pom、注释**;该路径只用于"在本机跑日常验证",一旦封装进库,必须替换为运行时探测(见 §0.2 / §7)。

### 2.5 精细引用原则(强制 · 不做包整合)

**外部 jar 一律按其具体 `groupId:artifactId:version` 精细声明,严禁使用任何形式的"包整合"。**

- ❌ **禁止使用 uber jar / fat jar**:不得引用 `poi-ooxml-5.5.1-uber.jar`、`docx4j-17.0.1.jar` 这类把全部传递依赖打进单 jar 的整合包。
- ❌ **禁止使用 maven-shade-plugin / maven-assembly-plugin 把依赖 shade 进产物 jar**:每个子模块的 jar 只含本模块自写代码,外部依赖通过 `pom.xml` 的 `<dependencies>` 正常声明,由用户的 Maven 依赖解析去拉。
- ❌ **禁止 `-uber`、`-all`、`-bundle`、`-fat`、`-shaded` 后缀的制品**。
- ✅ **必须**:每个外部库引其**原生的、单一职责的 artifact**。例如要 Excel 能力,引 POI 就明确写出:
  ```xml
  <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>   <!-- 不是 poi-ooxml-uber -->
      <version>5.5.1</version>
  </dependency>
  ```
  POI 自身需要的传递依赖(`poi`、`xmlbeans`、`commons-compress`、`commons-collections4` 等)由 Maven 自动拉取,**不手动整合、不在 pom 里显式塞这些传递依赖**(除非要做版本仲裁)。
- ✅ jian-dsl 的 DSL 引擎自写 Pratt parser + 正则(零运行时依赖),**不用 ANTLR4**(自写版功能完整,ANTLR4 已弃用)。

> **为什么禁止整合**:① 版本仲裁由 Maven 依赖中和机制统一处理,手动整合反而易锁死旧版本;② 用户能看到完整的依赖树,便于排障与升级;③ 与"每个子模块一个细粒度 jar"的项目目标一致(见 §4.2)。
> **取舍记录**:本规则与通用规范 §2.2"uber/fat jar 优先"相反,在 jian 项目以本文件为准。取舍理由见 `doc/00-overview.md` §8 决策。

### 2.6 版本选择规则

- **始终下载最新稳定版**(可抓 `maven-metadata.xml` 取 `<latest>`)。
- **冲突仲裁**:多包依赖同一传递依赖的不同版本时,由 Maven 的 `<dependencyManagement>` 统一锁版本,保留一个全兼容的最新版;无法确定时取最新版并在 `doc/00-overview.md` §2.3 记录取舍理由。
- 同一制品只保留一个版本。

### 2.7 一次性验证脚本的 classpath(Java 单文件)

写一次性 Java 验证脚本(`java Demo.java`)需要带外部 jar 时,**不依赖 uber jar**,直接列具体 jar 或用 Maven 拉的真实 jar 路径:
```bash
# 方式 A:逐个列具体 jar(明确、可追溯)
java -cp "${JAR_HOME:-~/tools/jar}/poi-ooxml-5.5.1.jar:${JAR_HOME:-~/tools/jar}/xmlbeans-*.jar" Demo.java

# 方式 B(推荐):写进对应子模块的 JUnit 测试,用 mvn test 跑,Maven 自动解析 classpath
mvn -pl jian-io-excel -am test
```
> 一次性脚本任务结束按 §6 清理;长期验证沉淀为 JUnit 测试入库。

---

## 3. 编码规范(UTF-8 + 5W1H + 伪代码 + 行数约束)

### 3.1 单文件行数约束(强制)

- **每个 `.java` 源码文件,不含注释的代码行数尽量不超过 600 行。**
  - 口径:统计文件内**非空、非纯注释**的代码行;空行不计、注释行(含 5W1H/伪代码块)不计。
  - 超过 600 行 → **优先拆分**为多个内聚的类/文件(按职责拆:主类 + 辅助类 + 常量类 + Builder 等),而不是硬塞。
  - 实在无法拆分(如某些必然长大的语法 visitor、生成的 parser 适配层)→ 在文件头 5W1H 注释中**显式说明不可拆分的理由**,作为破例留档。

- **拆分指引**(超过 600 行时的处理思路):
  1. 先看是否职责过多 → 按职责拆成多个并列类(如 `DataFrame` 主类 + `DataFrameIO` + `DataFrameStats`)。
  2. 再看是否有大量私有辅助方法 → 抽成同包内的 `XxxInternals` 工具类。
  3. 再看是否有大量常量/枚举 → 抽成独立常量类或枚举。
  4. 仍超长 → 评估是否设计问题(类职责过载),回头重构而非加行。

> **与注释密度不冲突**:本条按"不含注释"统计,因此 §3.3.3 鼓励的"注释行数可与代码持平甚至更多"不会触发 600 行红线 —— 注释是给读者的路标,不计入代码体量。

### 3.2 统一中文 UTF-8(强制)

- 所有源码、配置、脚本、文档一律 **UTF-8**。
- 所有**注释、变量命名说明、日志输出、提交说明**均**使用中文**。
- 控制台中文输出:locale 为 `zh_CN.UTF-8` 时无需任何额外处理。

### 3.3 注释规范:5W1H + 伪代码(强制)

由 Agent 编写的程序/脚本,注释必须同时满足两条硬性要求:① 关键逻辑用 **5W1H** 格式详写;② 非平凡函数/算法实现前先写**伪代码**。

#### 3.3.1 5W1H 格式(How 必须详写)

`What`(做什么)/ `Why`(为什么)/ `Who`(谁·对谁)/ `When`(何时)/ `Where`(在哪)/ `How`(怎么做)。适用于:文件头、类/函数块、复杂逻辑段。简单 getter/setter 不强制。

> **How 是 5W1H 的重中之重,必须详写,不允许只列步骤大纲。** 一个合格的 How 至少覆盖以下三点:
>
> 1. **关键变量的数值变化情况** —— 函数内起关键作用的变量(中间变量、累加器、循环计数、状态标记、入参被加工后的形态),从进入函数到返回/抛出,沿途每一步**取值如何变化**。例如:"`sql` 入参为原始字符串 → trim+upper 后变为大写无空白 → 正则匹配后要么原值要么触发拒绝"。
> 2. **逻辑路线(分支与跳转)** —— 把函数里的 if/else、循环、提前 return、异常抛出的**所有路径**都点明,标注每条路径的触发条件与去向。例如:"路径 A:命中危险关键词 → 抛 SecurityException 退出;路径 B:非 SELECT 开头 → 抛另一个异常;路径 C:全部放行 → 返回清理后的 sql"。
> 3. **数据走向(从哪来、流经哪些加工、到哪去)** —— 输入数据从入参进入,经过哪些变换/过滤/聚合,最终落到哪个返回值或副作用(写文件、入池、缓存)。例如:"raw(外部输入)→ sanitizeSql 内部清洗 → 返回 safeSql → 由 Engine.execute() 拿去 PreparedStatement"。

**Java 示例(合规 —— 注意 How 写得有多细)**:
```java
// ┌─ What : 从 .env 加载数据库连接配置并建立 Engine
// │  Why  : 连接串属敏感信息,按 §0.2 零本机绑定红线不得硬编码,必须运行时读 .env
// │  Who  : 由 jian-sql Engine.create() 在首次创建引擎时调用
// │  When : 引擎初始化时调用一次(单例缓存)
// │  Where: jian-sql-engine / EngineConfig.java
// │  How  : 数据走向:文件 .env → Properties → EngineConfig → HikariDataSource。
// │         关键变量变化:
// │           - props(初始为空)→ load() 后填入 DB_HOST/PORT/USER/PASSWORD/NAME 五个键;
// │           - url(初始为空串)→ 由 DbType.jdbcUrlPrefix + host + port + name 拼接为完整 jdbc URL;
// │           - config(初始 null)→ builder 填字段后变为不可变 EngineConfig 实例。
// │         逻辑路线(三条路径,任一失败即抛异常退出,不继续):
// │           路径 A(文件不存在/读失败)→ IOException → 包装为带"请检查 .env 路径"的提示并抛出;
// │           路径 B(键缺失,如 DB_PASSWORD 未设)→ IllegalStateException("缺少必填键:xxx") 抛出;
// │           路径 C(全部齐备)→ 反射试探驱动类是否可加载 → 不可加载抛 ModuleNotLoadedException;
// │                              → 可加载则用 config 建 HikariDataSource 并返回。
// public static EngineConfig fromEnv() { ... }
```

**反例(不合规)**:
- `// 连接数据库` —— 缺 Why/When/How。
- `// │  How : 读 .env 然后建连接` —— How 只写了一句话,缺变量变化、逻辑路线、数据走向三要素,等于没写。

> How 的篇幅可以很长(几行到十几行都正常)。非平凡函数的 How 行数与代码行数持平甚至更多,是预期内的(见 §3.3.3 注释密度)。

#### 3.3.2 实现前先写伪代码

非平凡函数/算法/流程(多步逻辑、条件分支、跨平台探测、危险操作过滤、IO/网络调用),**必须先写中文伪代码,再写真实代码**。伪代码**保留在最终代码里**,不删。

**Java 示例(合规)**:
```java
// 过滤危险 SQL,只放行只读查询
// 伪代码:
//   1. 把 SQL 转大写,去掉前后空白与分号
//   2. 若匹配 DROP / DELETE / TRUNCATE / ALTER / CREATE / GRANT 等关键词 → 拒绝
//   3. 若不是以 SELECT 开头 → 拒绝
//   4. 否则放行,返回清理后的 SQL
public static String sanitizeSql(String raw) {
    String sql = raw.trim().toUpperCase().replaceAll(";+\\s*$", "");
    if (sql.matches("(?s).*\\b(DROP|DELETE|TRUNCATE|ALTER|CREATE|GRANT)\\b.*")) {
        throw new SecurityException("禁止执行修改性 SQL");
    }
    if (!sql.startsWith("SELECT")) {
        throw new SecurityException("仅允许 SELECT 查询");
    }
    return sql;
}
```

#### 3.3.3 注释密度

- **宁可多注释,不可零注释**。复杂/安全/跨平台/核心逻辑,注释行数可与代码持平甚至更多。
- 注释随代码改 —— **改代码不更新注释等同于误导**。
- 临时调试注释(`// TODO 待删`、注释掉的旧代码)**交付前必须清理**。
- 注释行不计入 §3.1 的 600 行代码上限,放心详写。

---

## 4. 模块与依赖方向(技术约束)

### 4.1 单向依赖

- 叶子模块(`jian-io-*` / `jian-viz` / `jian-export`)依赖 `jian-core`;**core 不反向依赖任何叶子**。
- `jian-sql` **不依赖** jian;通过可选的 `jian-sql-bridge` jar 才单向依赖 `jian-core`。
- `jian-num` **完全独立**,不依赖任何 jian 模块;`jian-core` 通过 SPI(`StatsProvider`)可选加载它。
- `jian-dsl` 单向依赖 `jian-core`,完全可选(缺失时 core 内置兜底)。

### 4.2 按需加载

用户只引需要的 jar,未引的不会触发类加载。例如:用户不引 `jian-io-excel`,JVM 不会加载 POI 的类。调用未引 jar 的功能 → `ModuleNotLoadedException`(带安装提示)。

### 4.3 不可变优先 + 链式调用

- DataFrame 的变换返回新实例(便于链式与并行),仅在显式 `inPlace()` 时原地修改。
- 链式调用为主:`df.filter(...).select(...).sortBy(...).head(10)`。
- API 风格向 pandas/sqlalchemy 靠拢,降低 Python 用户认知成本。

---

## 5. 文档维护(双轨同步 · 红线)

### 5.1 文件清单与分工

| 文件 | 角色 | 内容 |
|---|---|---|
| `doc/00-overview.md` | 事实来源 | 总览:三库切分、选型、模块图、路线图、里程碑、决策 |
| `doc/01-jian-core.md` ~ `07-jian-dsl.md` | 事实来源 | 各分册详细需求 |
| `NAMING.md` | 事实来源 | 命名由来(玉简) |
| `doc/index.html` | 可视化门户 | 单文件,数据驱动渲染(见 §5.3) |

### 5.2 改文档时的强制流程

**任何对需求/接口事实内容的修改,都必须先改 md,再同步 html。** 不允许只改 html 数据数组而不改 md。

#### 5.2.1 新增/修改模块信息(定位、依赖、工作量)

1. 改对应的 md 分册(如 `01-jian-core.md`)。
2. 同步改 `doc/index.html` 中 `<script>` 的 `MODULES` 数组对应项。

#### 5.2.2 新增/修改顶层 API 速查

1. 改对应 md 分册的 API 章节。
2. 同步改 `doc/index.html` 的 `API_QUICK` 数组。

#### 5.2.3 新增函数接口(代码实现后补全接口参考)

1. md 分册里若有 API 章节,补全方法签名/参数/示例。
2. 在 `doc/index.html` 的 `API_REF` 数组里加一项,字段契约:
   ```js
   { id, module, since, status, summary,
     params:[{name,type,desc}],
     returns:{type,desc},
     example, throws:[...] }
   ```
   - `status` 取值:`planned`(规划)/ `alpha`(开发)/ `beta`(测试)/ `stable`(稳定)。
3. 无需改 html 结构 —— 模板会自动渲染。

### 5.3 html 数据驱动的渲染机制(认知)

`doc/index.html` 末尾 `<script>` 内有三个数据数组,模板化渲染:

- `MODULES`(7 项)→ 模块卡片
- `API_QUICK`(5 组)→ 顶层 API 速查表
- `API_REF`(6+ 项)→ 方法目录卡(日后扩展的核心区)

**新增内容只往数组里加一项,不动 HTML 结构。** md 与 html 数据冲突时,**以 md 为准,改 html 对齐**。

### 5.4 模块实现完成后的文档同步(强制 · 红线)

> **每个模块开发完成(编译过 + 测试通过)后,必须立即把"实际实现的特性与内容"同步进 html 与 md,不得积压到项目末尾。** 这是与"双轨维护"(§5.2)并列的第二条文档红线,专门约束"代码先行、文档滞后"的常见漏洞。

**"模块开发完成"的判定标准**(同时满足才算完成,才触发本节流程):
1. 模块编译通过(`mvn -pl <模块> compile` 无错)。
2. 模块单元测试通过(`mvn -pl <模块> test` 全绿,核心算子有用例覆盖)。
3. 该模块的对外 API 已稳定(签名不再大改)。

**强制流程**(每完成一个模块依次做完,缺一不可):

1. **html 同步实现内容** —— 在 `doc/index.html` 末尾 `<script>` 的对应数据数组里加/改:
   - `MODULES`:把该模块的状态从 `planned` → `alpha`/`beta`/`stable`;补充"已实现特性"摘要。
   - `API_REF`:把该模块**实际暴露的公开方法**逐个加项(字段契约见 §5.2.3),`status` 按真实程度填。
   - 如该模块有"顶层速查"价值,补 `API_QUICK` 一组。
2. **md 同步实现与偏差** —— 改对应 md 分册:
   - 在分册末尾(或对应章节)加「**实现说明(vX.Y,日期)**」小节,记录:
     - 实际实现的类/方法清单(与需求清单的对应关系);
     - **与需求的偏差**:实现时做的取舍、简化、增强、改名(例如:需求写 `Jian-num.mean`,Java 实现为 `JianNum.mean` —— 标识符不能有连字符);
     - 已知 TODO(M1/M2 阶段补齐的部分,如分位插值对齐 numpy 'linear')。
3. **需求本身随实现演进** —— 若实现中发现了需求的不合理或更优方案,**改 md 的需求描述**(md 是事实来源),html 再对齐 md。不允许"代码与需求长期背离却都不改"。
4. **html 改完用浏览器渲染验证**(见 §5.5 检查清单第 3 条)。

> **为什么强制每个模块完成即同步**:① 文档与代码同步是质量门槛,不是收尾杂活;② 后续模块(如 jian-core 的 DataFrame)会引用前序模块(如 jian-num 的 Ndarray)的真实 API,文档不同步会导致下游引用错误 API;③ 避免项目末期一次性补文档时的"事后编造"。

### 5.5 双轨维护检查清单(每次改文档后过一遍)

- [ ] md 改了 → html 同步改了吗?
- [ ] html 改了数据数组 → 对应 md 的事实内容对齐了吗?
- [ ] html 改完用浏览器打开确认渲染正常(7 模块卡 / 5 速查表 / 方法卡数量正确,无 JS 错)。
- [ ] **模块完成 → §5.4 的 4 步都做了吗?**(实现说明、偏差、TODO、html 状态都更新)。

---

## 6. 临时文件与清理(沿用通用规范 §4)

### 6.1 保留 vs 清理

| 类型 | 处理 |
|---|---|
| 库源码(`src/main/java/...`)、测试(`src/test/java/...`)、pom.xml | **保留** |
| 通用工具脚本(可复用) | 保留,放 `tools/` 或对应子目录 |
| 一次性验证脚本(测某 jar 行为、demo) | **任务结束清理** |
| 编译产物 `target/`(Maven) | `.gitignore`,不入库;本地可保留加速增量编译 |
| 临时 csv/json 测试数据 | 清理(除非作为 JUnit fixture 入库) |
| 调试日志、dump 文件 | 清理 |

### 6.2 清理原则

- **清理前确认**:删除前查看目标,内容与预期不符或非自身创建,**暂停向用户确认**,不擅自删除。
- **保留交付物**:用户要求的产出、共享 jar 仓库中已登记的依赖 jar、`AGENTS.md` / `doc/` 规范与需求文档不清理。
- 清理后简要说明删了什么。

---

## 7. 跨平台零本机绑定(强制 · 红线)

> 与通用规范 §6.7 等价(本节是该红线在 jian 项目的重申)。jian 是要对外分发的库,**任何与本地配置/目录/硬件绑定的写死内容都不可接受**。

### 7.1 禁止写死本地路径/目录/盘符

- ❌ 本机绝对路径(家目录、/opt、/usr/local、Windows 盘符等)、用户名、硬件路径。
- ❌ 写死用户名/家目录前缀。
- ✅ Java 用 `System.getProperty("user.home")` / `System.getenv()` / `File.pathSeparator` / `new File(".").absolutePath`。
- ✅ 需要 jar 仓库/缓存目录时走环境变量(`${JAR_HOME}`、`${XDG_CACHE_HOME}`),平台无关回退默认值(如 `~/tools/jar`),默认值本身不含盘符/用户名。

### 7.2 禁止写死硬件/架构/外部程序路径/可执行文件后缀

- ❌ CPU 架构、GPU 型号、核数、内存;假设某外部程序固定装在某位置。
- ❌ **写死可执行文件名/后缀**:Linux/macOS 通常无后缀(`ffmpeg`),Windows 通常带后缀(`ffmpeg.exe`)。**不要按本机一刀切**。
- ✅ `System.getProperty("os.name")` 判定平台;优先用 PATH 查找让 OS 解析后缀(`new ProcessBuilder("ffmpeg", ...)` 在 Windows 上自动匹配 `ffmpeg.exe`);或显式枚举候选后缀逐个 `new File(dir, cand).exists()` 试探。

> jian 中只有 `jian-io-clipboard`(剪贴板调 xclip/pbcopy/clip)和 `jian-io-html`(URL 抓取)涉及外部程序调用,必须遵守本条。

### 7.3 凭据走 .env(红线)

- ❌ 写死数据库连接串、API Key、Token、内网域名。
- ✅ 一律走 `.env` 或环境变量;`jian-sql` 的 `EngineConfig.fromEnv()` 从环境变量构建。
- `.env` **不入库、不入 jar、不进文档示例的真实凭据**。

### 7.4 三平台可运行

- Linux、macOS、Windows(cmd/PowerShell/MSYS2)三平台通用。
- classpath 分隔符、换行符、临时目录、PATH 分隔符差异,统一用语言内置常量(`File.pathSeparator`、`System.lineSeparator()`、`System.getProperty("java.io.tmpdir")`)或平台分支解决。
- 交付前至少在两类平台验证(条件允许覆盖三类);未能实测的用静态检查兜底(`grep -nE "/home/|/Users/|C:\\\\Users|/usr/local/[a-z]+"` 应无命中)。

---

## 8. 工作流程检查清单(jian 项目专用)

每次在 jian 目录开展工作,按以下顺序执行:

1. **环境确认**:`java -version`(应为 17 或更高)。
2. **需求定位**:明确改的是哪个模块/分册;查 `doc/00-overview.md` 与对应分册。
3. **依赖查找**:先查共享 jar 仓库与 JDK 自带库 → 缺失才从阿里云下载(见 §2)。
4. **编码**:Java + UTF-8 + 中文注释(见 §3)。源码放对应子模块 `src/main/java/...`。检查两项硬指标:
   - **行数**:每个 `.java` 文件不含注释尽量 ≤ 600 行,超了优先拆分(见 §3.1)。
   - **5W1H 的 How**:非平凡函数的 How 必须详写关键变量数值变化 + 逻辑路线 + 数据走向(见 §3.3.1),不能只写一句话。
5. **构建验证**:`mvn -pl <子模块> -am compile`(或单文件 `java MyApp.java` 仅限 demo)。
6. **文档同步**(强制 · 红线):改了需求/接口事实内容 → **先改 md 分册,再同步 html**(见 §5)。
7. **清理现场**:删除一次性验证脚本、临时数据、调试产物(见 §6);保留库源码、pom、通用工具、文档。
8. **如实汇报**:报告改动模块、依赖变化、文档双轨同步情况、清理情况。

---

## 9. 已确认的关键决策(2026-08-01 评审,贯穿全项目)

1. **JDK 基线:JDK 17 LTS**(向下兼容 17+,三库与全分册统一,不混用 21)。
2. **主语言:只用 Java**(不用 Groovy/Kotlin 等 JVM 语言,见 §1.1)。
3. **构建工具:Maven 多模块**(破例,见 §2)。
4. **打包形态:每个子模块一个 jar**(细粒度按需加载)。
5. **不做包整合**:外部 jar 一律按 `groupId:artifactId:version` 精细引用,**不用 uber/fat jar、不用 maven-shade**(见 §2.5)。
6. **功能范围:大面对齐 pandas 3.x**(IO 12 类 Tier 1 + 图表 13 种(10 plot + 3 plotting,高维图 v2 规划)+ Styler 全功能)。
7. **DSL 不嵌入任何外部脚本引擎**:L1/L2 手写 Pratt,L3 用自写正则子句切分(不用 ANTLR4,ANTLR 已整体弃用,见 07 分册 §9.4)。
8. **DSL 方言:Oracle 基线 + PG/MySQL 兼容**,通过 `SqlDialect` 变量切换。
9. **Commons Math 选 3.6.1**(稳定 10 年,jian-num 用途避开已知问题;不用 beta 版 4.0)。
10. **文档双轨**:md(事实来源)+ html(数据驱动门户)同时维护。

> 详细决策见 `doc/00-overview.md` §8 与 `doc/index.html`「关键决策记录」章节(两者同步)。

---

## 附录 A:本文件与通用规范的差异速查

| 维度 | 通用 `AGENTS.md`(上级目录) | 本文件 `jian/AGENTS.md` |
|---|---|---|
| 主语言 | Groovy 脚本优先 | **只用 Java**(不用 Groovy/Kotlin 等 JVM 语言) |
| JDK | 21 | **17 LTS** |
| 构建工具 | 禁用 Maven/Gradle | **破例用 Maven 多模块** |
| 包整合 | uber/fat jar 优先 | **禁止 uber/fat/shade,精细引用**(见 §2.5) |
| Skills 规范 | 第 0、6 章强制 | **不适用**(整章删除) |
| 文档维护 | 无特殊要求 | **md + html 双轨同步(红线)** |
| 零本机绑定 | 仅 Skill 强制 | **全项目强制**(库要对外分发) |
| UTF-8 + 5W1H + 伪代码 | 强制 | **强制**,且 **How 必须详写**(变量变化/逻辑路线/数据走向,见 §3.3.1) |
| 单文件行数 | 无限制 | **不含注释 ≤ 600 行**,超了优先拆分(见 §3.1) |

---

*本文件为 jian 项目专用规范,凌驾于本目录所有其它文档。*
