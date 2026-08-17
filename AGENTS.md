# Agent 行为规范(jian 项目专用)

> **本文件凌驾于本目录所有其它文档。** 与上级目录的通用 `AGENTS.md`(通用规范)冲突时,以本文件为准;本文件未覆盖处,沿用通用规范的对应条款。
>
> **jian 的特殊性**:本目录产出的是**对外分发的通用 Java 库**(jian / jian-sql / jian-num),不是脚本工具集,也不是插件包。因此对通用规范做了四处重大调整:① **只用 Java 编写,不引入其它 JVM 语言**(§1);② **破例引入 Maven 多模块**(§2);③ **子模块零整合、顶层三库可选 fat**(子模块的 jar 一律按 `groupId:artifactId:version` 精细引用、严禁 shade;顶层 jian/jian-num/jian-sql 经 `-Pfat` 允许出 `*-all.jar`,见 §2.5);④ **不适用插件开发标准**(通用规范第 0、6 章整章不适用,见 §0 说明)。

---

## 0. 总纲(四条红线)

### 0.1 项目定位:对外分发的通用 Java 库

jian 是 **JVM 上对标 pandas / sqlalchemy / numpy 子集**的轻量数据栈,**产物是可对外分发的 jar 包**,必须能在任意一台未做特殊准备的开发机上开箱即用。所有编码、文档、依赖决策都服务于这一目标。

- 三个库(jian / jian-sql / jian-num)相互独立,按需引用,引哪个用哪个。
- 详见 `doc/00-overview.md`。

### 0.2 零本机绑定(强制 · 凌驾全章)

**严禁把任何本机的路径、配置、软件环境、用户名、凭据、硬件特征硬编码到任何源码、脚本、文档、注释里。**

- ❌ 不得出现:任何本机绝对路径(家目录/工具目录/用户名/盘符)、本机已装版本号、数据库连接串、API Key、内网域名。
- ✅ 必须做到:运行时探测(`System.getProperty` / `System.getenv` / `command -v`)、环境变量 + 平台无关回退默认值(如 `${XDG_CACHE_HOME:-~/.cache}`)、敏感信息走 `.env`、依赖缺失时优雅降级给安装提示而非崩溃。
- **跨平台**:Linux / macOS / Windows 三平台通用;调用外部程序按目标平台动态确认可执行文件名与后缀(详见 §7)。

### 0.3 文档四轨同时维护(强制 · 红线)

**本项目的"文档"包括四类,全部必须与源代码保持同步,不允许任何一轨滞后:**

| 轨道 | 内容 | 位置 | 同步要求 |
|---|---|---|---|
| ① md 分册(**事实来源**) | 需求/接口/API 说明 | `doc/00-overview.md` ~ `07-jian-dsl.md`、`doc/api-counts.md` | 改代码事实 → 先改 md |
| ①+ 图片资产 | 架构图(README + doc/index.html 依赖全景区共同引用) | `doc/architecture.svg`(文本 SVG,可直改;index.html 经 `<img src>` 引用,**不内联副本**,避免两处漂移) | 模块结构/依赖关系/制品形态变化 → 只改 architecture.svg 一处(单一事实来源);门户分发时两文件同目录携带 |
| ② html 门户 | 可视化门户(数据驱动渲染) | `doc/index.html` | md 改后同步 html 对应区块/数据 |
| ③ ai-doc 模块文档 | 每模块能力/限制/快速上手 | `<module>/src/main/ai-doc/module.md`(22 份) | 模块能力/签名变化 → 同一次提交改 |
| ④ jar 内 AI 文档 | fat jar 总索引 + 场景集(简版预期值)**+ 场景完整标准答案源码** | `<顶层>/.../META-INF/ai/aggregated.md`、`jian-facade/.../META-INF/ai/scenarios.md`;**完整断言源码经 facade pom 资源配置打进 `META-INF/ai/scenarios-src/`** | 新增场景 → scenarios.md 与 scenarios-src/ 同步(jar 消费者拿到的就是完整可执行答案);见 §2.5.3 第 4 条 |

- **md 是事实来源**,html/ai-doc 与 md 冲突时以 md 为准,改 html/ai-doc 对齐 md。
- **改需求/接口事实内容** → 先改 md 分册 → 再同步 html + ai-doc + jar 内 AI 文档。
- **新增真实场景测试**(如 `scenario` 测试类)→ 必须同步登记进 `META-INF/ai/scenarios.md`(AI 拿 jar 可见)。
- **模块能力/公开 API/测试数变化** → module.md 的"能力/限制/tests 数"字段同次更新;`doc/api-counts.md` 刷新(它是数字的唯一事实来源)。

### 0.4 与通用规范的关系(删/留清单)

| 通用规范章节 | 在 jian 的适用性 | 处理 |
|---|---|---|
| §0 AI 工具使用规范 | ❌ 不适用(jian 是独立 Java 库) | **整章删除** |
| §1 编程语言与运行环境 | ⚠️ **只用 Java**(不引入其它 JVM 语言)、JDK 改 17 | §1 重写 |
| §2 依赖与 jar 包管理 | ⚠️ 破例用 Maven、**子模块零整合**(禁 shade),顶层三库可选 fat | §2 重写 |
| §3 编码与文档规范 | ✅ 完全适用 | §3 保留(示例改 Java) |
| §4 脚本与临时文件管理 | ✅ 适用 | §6 保留 |
| §5 工作流程检查清单 | ✅ 适用 | §8 重写为本项目流程 |
| §6 插件开发标准 | ❌ 不适用 | **整章删除** |

---

### 0.5 pandas 对照测试(强制 · 凌驾全章 · 红线)

> **jian 借鉴自 Python pandas,凡是与 pandas 功能对齐的部分,都必须以 pandas 为 oracle(老师)做对照测试,拉齐差异。** 这是本项目正确性的最终判据,凌驾所有其它测试方法之上。

#### 为什么必须用 pandas 当老师

- jian 自我对拍(Java jqwik ↔ Python Hypothesis,或 fast path ↔ generic path)只能验证"内部一致性",**无法发现"与行业标准不一致"的 bug**——比如稳定排序在所有键相同时的行序,jian 与 pandas 不同,jian 内部自洽但行为偏离标准。
- **pandas 是 30 年积累的事实标准**。任何 jian 与 pandas 在同输入下的结果差异,要么是 jian 的 bug(应修),要么是有意的设计差异(必须在文档显式声明 + 测试中标注)。
- 没有 oracle 的测试都是"学生自己批自己的作业"——必须让 pandas 老师批改。

#### 强制要求(凡新增/修改算子时必做)

1. **凡对标 pandas 的算子**(sortBy/sort_values、merge、groupBy/agg、head/tail、filter/query、concat、dropDuplicates、fillna/dropna/ffill/bfill、astype、select/drop、slice/iloc、nlargest/nsmallest、colAdd/colMul、assign 等),**必须有 pandas 对照测试**。
2. 对照测试放在 `tests-pbt/properties/test_pandas_diff.py`,用 Hypothesis 生成同一种输入:
   - 一份发给 jian(通过 JPype 直调 `JianJpypeBridge`,JVM 嵌入 pytest 进程)
   - 一份发给 pandas(直接 `import pandas as pd`)
   - 逐行逐列比对结果(允许浮点容差、缺失值等价)
3. **失败时让 Hypothesis shrink 到最小失败用例**——直接定位 jian 与 pandas 的具体行为差异点。
4. **发现差异必须做出决策**(二选一,不许含糊):
   - **方案 A**:把 jian 行为对齐 pandas(修 jian 代码)
   - **方案 B**:声明这是有意的设计差异(在 `doc/00-overview.md` 显式记录 + 测试中加 `@pytest.mark.xfail` 注释原因)
5. **绝对禁止**:发现差异后既不对齐也不声明,让 jian 与 pandas 行为悄悄不一致(这是测试偷懒,违反本红线)。

> 对照测试的实际覆盖范围(已覆盖哪些算子)以 `tests-pbt/properties/test_pandas_diff.py` 的 `test_d*` 函数清单为准;每次 jian 新增/修改算子,对应的 pandas 对照测试必须同步增加。详见 `doc/00-overview.md §10.12`。

#### 跑法

```bash
# 跑全部 pandas 对照测试(需要本机已装 pandas)
python3 -m pytest tests-pbt/properties/test_pandas_diff.py -v
```

> **本机要求**:需要 pandas(由 `pip install pandas` 或系统包提供)。本机未装 pandas 时,本测试套件会 skip 而非 fail(优雅降级,符合 §0.2 零本机绑定的精神)。

---

## 1. 编程语言与运行环境

### 1.1 只用 Java

jian 项目**只用 Java 编写,不引入任何其它 JVM 语言**。库源码、测试代码、一次性验证 / demo / 冒烟脚本,全部用 Java。

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

### 2.4 jar 由 Maven 管理(无共享 jar 仓库)

jian 的所有外部依赖都**声明在各模块的 `pom.xml` 里**,由 Maven 从阿里云镜像(§2.3)自动拉取到本地仓库(`~/.m2/repository`),**不维护任何"共享 jar 仓库"**(通用规范的共享仓库约定在 jian 不适用)。

- 开发/测试:一律走 Maven(`mvn compile` / `mvn test`),classpath 由 Maven 自动解析,无需手工拼 jar。
- 一次性验证脚本需要手工 classpath 时,从 Maven 本地仓库取已拉取的 jar,见 §2.7。
- **严禁把任何本机绝对路径(含 `~/.m2` 的具体路径)写进源码、pom、注释、对外文档**;封装进库后必须替换为运行时探测(见 §0.2 / §7)。

### 2.5 引用与打包原则(强制 · 子模块禁整合,顶层三库允许 fat)

**核心立场:子模块对外发布的 jar 一律按其具体 `groupId:artifactId:version` 精细声明、绝不整合;但顶层三库(jian / jian-num / jian-sql)允许通过 `-Pfat` 出可选的 `*-all.jar`(fat jar),作为"单文件即可上手"的 AI 友好补充制品;另有列存附加制品 `jian-columnar-all`(第 4 个 fat,见下)。**

> **列存例外**:`jian-io-parquet` / `jian-io-orc` 自带 ~45MB Hadoop 生态依赖,**默认构建不含**(`./mvnw install` 只编 21 模块);`-Pcolumnar` 显式构建;`-Pfat` 时它们**不进 jian-all**(30M,无列存),而是单独聚合成 `jian-columnar-all`(68M,与 jian-all 叠加使用)。facade 对列存编译期解耦(反射 + `ModuleNotLoadedException` 指引,§4.2 按需加载)。详见 doc/02-jian-io.md §9.7。

#### 2.5.1 子模块:精细引用(强制,红线)

- ❌ **子模块的产物 jar 禁止任何形式的"包整合"**:`jian-core` / `jian-io-*` / `jian-viz` / `jian-export` / `jian-dsl` / `jian-sql-*` 等**叶子模块**,其 jar 只含本模块自写代码,**严禁**把外部依赖塞进去。
- ❌ **子模块严禁使用 maven-shade-plugin / maven-assembly-plugin**:子模块 jar 不得出现 `*-uber`、`*-all`、`*-bundle`、`*-fat`、`*-shaded` 后缀。
- ✅ **子模块必须精细引用**:每个外部库引其**原生的、单一职责的 artifact**。例如要 Excel 能力,引 POI 就明确写出:
  ```xml
  <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>   <!-- 不是 poi-ooxml-uber -->
      <version>5.5.1</version>
  </dependency>
  ```
  POI 自身需要的传递依赖(`poi`、`xmlbeans`、`commons-compress`、`commons-collections4` 等)由 Maven 自动拉取,**不手动整合、不在子模块 pom 里显式塞这些传递依赖**(除非要做版本仲裁)。
- ✅ jian-dsl 的 DSL 引擎自写 Pratt parser + 正则(零运行时依赖),**不用 ANTLR4**(自写版功能完整,ANTLR4 已弃用)。

> **为什么子模块禁止整合**:① 版本仲裁由 Maven 依赖中和机制统一处理,手动整合反而易锁死旧版本;② 用户能看到完整的依赖树,便于排障与升级;③ 与"每个子模块一个细粒度 jar"的按需加载目标一致(见 §4.2)——`jian-core` 一旦塞进 POI,所有引 core 的模块都被迫带上 POI。

#### 2.5.2 顶层三库:可选 fat jar(`-Pfat` 激活)

为了**让 AI / 用户拿到一个 jar 就能跑全功能**(不必再手工拼 22 个子模块),顶层聚合模块 jian / jian-num / jian-sql **允许**通过 `-Pfat` profile 出可选的 fat jar:

- ✅ **允许**:在顶层三库的 pom 用 `maven-shade-plugin` 把全部依赖 shade 进单 jar,产物命名 `jian-all-x.y.z.jar` / `jian-num-all-x.y.z.jar` / `jian-sql-all-x.y.z.jar`(`*-all` 后缀)。
- ✅ **激活方式**:根 pom 的 `<profiles>` 里有 `<id>fat</id>`,默认**关闭**;只有 `./mvnw -Pfat package` 时才聚合进这三个顶层模块、触发 shade。默认 `./mvnw install` 仍只出 22 个细粒度 thin jar(子模块形态不变)。
- ✅ **fat jar 是补充形态,不是主形态**:thin jar 是事实来源(版本仲裁、按需加载靠它),fat jar 仅是"开箱即用"的便利制品。两者并存,用户按场景选。

#### 2.5.3 fat jar 的强制元数据要求(红线)

顶层 fat jar **必须**满足以下四条,否则视为不合格:

1. **shade 必须配 `ServicesResourceTransformer`** —— 合并 `META-INF/services/*`(SPI 注册文件),否则 jian-dsl / jian-num-bridge 的 ServiceLoader 在 fat jar 里会因文件被覆盖而失效(SPI 静默退化)。
2. **shade 必须排除签名文件** —— `<excludes>` 里排 `META-INF/*.SF`、`META-INF/*.DSA`、`META-INF/*.RSA`,否则把签名过的依赖(如 BouncyCastle)的签名块打进新 jar 后,JVM 运行时会因签名校验失败抛 `SecurityException`。
3. **MANIFEST.MF 必须带 AI 标记** —— 用 `ManifestResourceTransformer` 加 AI 可识别字段,让 AI 拿到 jar 能一眼判别"这是聚合 jar,不是单一 artifact",并顺着 `Ai-Modules` 直接跳到总索引:
   ```
   Ai-Aggregated: true
   Ai-Library: jian        # 或 jian-num / jian-sql
   Ai-Modules: META-INF/ai/aggregated.md
   ```
4. **AI 文档必须全量聚合,不得同名丢失(红线)** —— 各子模块的 `module.md` 在 fat jar 内路径唯一,AI 才能看到全部模块的能力说明:
   - **排除** thin 形态的 `META-INF/ai/module.md`(`shade excludes` 加 `<exclude>META-INF/ai/module.md</exclude>`)—— 多模块同名只留一份会误导 AI;
   - **保留** `META-INF/ai/modules/<artifactId>/module.md`(由根 pom 资源插件统一复制,路径唯一,一模块一份);
   - **提供** `META-INF/ai/aggregated.md` 总索引(顶层模块 `src/main/resources/META-INF/ai/aggregated.md`):库定位 + 30 秒可跑示例(**示例 API 必须逐个核实真实存在,禁止凭记忆编写**)+ 模块清单表;MANIFEST 的 `Ai-Modules` 指向它;
   - AI 发现闭环:MANIFEST(`Ai-Aggregated` 识别聚合)→ `Ai-Modules` 跳总索引 → 索引逐模块链到 `modules/<artifactId>/module.md`。

> 顶层三库(jian / jian-num / jian-sql)的 pom.xml 已按上述四条配好,新增 fat jar 制品时必须照此模板,不得省略任一项。

> **取舍记录**:本规则与通用规范 §2.2"uber/fat jar 优先"在**子模块层面**相反(jian 子模块一律禁整合),在**顶层三库层面**对齐(允许并优先提供 fat jar)。整体立场是"子模块零整合、顶层可选 fat",取舍理由见 `doc/00-overview.md` §8 决策与 §10.15(AI 友好的 jar 制品设计)。

### 2.6 版本选择规则

- **始终下载最新稳定版**(可抓 `maven-metadata.xml` 取 `<latest>`)。
- **冲突仲裁**:多包依赖同一传递依赖的不同版本时,由 Maven 的 `<dependencyManagement>` 统一锁版本,保留一个全兼容的最新版;无法确定时取最新版并在 `doc/00-overview.md` §2.3 记录取舍理由。
- 同一制品只保留一个版本。
- **例外:有意选旧版**——当最新版是 beta/不稳定时,可刻意选上一稳定版并在 pom 注释说明。例如 jian-num 用 `commons-math3` **3.6.1**(稳定多年,jian-num 用途避开已知问题),不用 4.0 beta。

### 2.7 一次性验证脚本的 classpath(Java 单文件)

jian 的依赖都在 Maven(§2.4),写一次性 Java 验证脚本(`java Demo.java`)需要带外部 jar 时,**优先走 Maven,不要手工维护 jar 目录**:
```bash
# 方式 A(推荐):写进对应子模块的 JUnit 测试,Maven 自动解析全部 classpath
mvn -pl jian-io-excel -am test

# 方式 B:用 Maven 导出完整 classpath,交给 java 单文件源码启动
CP=$(mvn -pl jian-io-excel -am dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)
java -cp "$CP" Demo.java
```
> 补充:`java` 启动器支持 `-cp "目录/*"` 形式的通配符(Java 6+ 的 JVM 内置特性,JVM 启动时展开该目录下所有 `.jar`/`.zip`,`*` 必须用引号包裹)。但 Maven 本地仓库(`~/.m2/repository`)按 `group/artifact/version` 分层,无法用单个 `*` 一次取齐一组依赖,故 jian 场景下**优先用上面的 Maven 方式**,不手工拼路径。
>
> 一次性脚本任务结束按 §6 清理;长期验证沉淀为 JUnit 测试入库。

### 2.8 AI 友好元数据规范(强制)

> 让 AI 拿到 jar 就能彻底理解接口用法、适用范围、真实示例,而不必逆向 jar 字节码或翻源码猜意图。

#### 2.8.1 每模块 module.md

- **位置**:`<module>/src/main/ai-doc/module.md`(每个子模块一份,共 22 份)。
- **打包(双路径)**:根 pom 的 `maven-resources-plugin` 复制两份——
  1. `META-INF/ai/module.md`(thin jar 规范路径,单模块 jar 内唯一);
  2. `META-INF/ai/modules/<artifactId>/module.md`(fat jar 聚合时路径唯一,shade 不覆盖,见 §2.5.3 第 4 条)。
- **必填字段**:
  - `library` —— 归属库(jian / jian-sql / jian-num)
  - `entryClass` —— 入口类全限定名(如 `jian.Jian`、`jian.core.DataFrame`)
  - `deps` —— 需要配合的其它 jar(依赖方向)
  - **摘要** —— 一句话讲清本模块做什么
  - **能力** —— 列出关键能力/方法清单
  - **限制** —— 不能做的事、已知约束、缺失时如何降级
  - **快速上手** —— 3~5 行真实可跑的 Java 示例
- **维护同步(红线)**:模块新增/删除/改名、能力清单变化 → `module.md` 与 fat jar 的 `aggregated.md` 总索引(§2.5.3 第 4 条)**同一次提交内一起改**,两者不一致视为文档欠账。

#### 2.8.2 sources + javadoc jar

- 每个 thin jar 必须同时出 **`-sources.jar`**(含全部源码 + 全量 `@param/@return/@throws`)与 **`-javadoc.jar`**(HTML API 文档)。
- 根 pom 的 `<pluginManagement>` + `<plugins>` 已激活 `maven-source-plugin`(`attach-sources`)与 `maven-javadoc-plugin`(`attach-javadocs`),`./mvnw install` 自动产出。
- AI 可优先读 `-sources.jar` 里的源码与注释,辅以 `-javadoc.jar` 的 HTML,实现"零逆向理解 API"。

#### 2.8.3 @param/@return/@throws(全量覆盖)

- **全项目所有带参数的 public 方法必须有 `@param`**。
- 有返回值的 public 方法必须有 `@return`;可能抛检查异常的必须有 `@throws`。
- **每次新增 public 方法必须同步补 `@param`**,不允许出现"先写代码后补注释"的欠账 —— 这是 AI 理解 API 的关键元数据,缺失即视为不合规。

#### 2.8.4 fat jar 元数据(配合 §2.5.2/§2.5.3)

顶层三库的 fat jar(由 `-Pfat` 激活)在 shade 时**必须**满足:

- shade 必须配 **`ServicesResourceTransformer`**(合并 `META-INF/services/*` 的 SPI 注册,防 ServiceLoader 失效)。
- shade 必须排 **`META-INF/*.SF` / `*.DSA` / `*.RSA`**(签名文件,防运行时签名校验失败)。
- `MANIFEST.MF` 必须加 **`Ai-Aggregated: true`** + **`Ai-Library: <lib>`**(lib = jian / jian-num / jian-sql),让 AI 一眼识别这是聚合 jar。
- **AI 文档全量聚合**(详见 §2.5.3 第 4 条):排 `META-INF/ai/module.md`、留 `META-INF/ai/modules/<artifactId>/module.md`、供 `META-INF/ai/aggregated.md` 总索引,`Ai-Modules` 属性指向总索引。

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

### 3.1.1 文件命名:看名知意(强制)

> **铁律**:文件名是给后来者(含 Agent 自己)看的"目录索引"——看到文件名就要能大体知道里面装了什么功能。**禁止任何形式的"懒人命名"**:UtilA / UtilB / Helper1 / Misc / Common / Tools / XxxUtil(无修饰)/ A B C D 之类的顺序字母后缀。

#### 合规命名模板

| 场景 | 模板 | 示例 |
|---|---|---|
| DataFrame 按职能拆分 | `DataFrame<职能>` | `DataFrameSort` / `DataFrameStats` / `DataFrameMissing` / `DataFrameReshape` / `DataFrameMerge` |
| 列运算类 | `<主语><动词>` 或 `<主语>Ops` | `ColumnarHashMap` / `StrOps`(Series.str 实现) |
| 时序算子 | `<能力名>` | `Resampler` / `Frequency` / `DatetimeIndex` |
| 单一职责工具 | `<动词><对象>` | `SimpleQueryParser`(非 `QueryUtil`)/ `SortIndexBuilder` |
| SQL 翻译层 | `Sql<动作>To<目标>` | `SqlToDataFrameExecutor`(非 `SqlExecutor`/`SqlUtil`) |

#### 反例(禁止)

- ❌ `DataFrameUtilA.java` / `DataFrameUtilB.java` —— "A/B" 不传达任何信息,后来者必须打开文件才知道
- ❌ `Misc.java` / `Commons.java` / `Tools.java` —— "杂项"会无限膨胀,最终变垃圾桶
- ❌ `Helper.java` —— 帮什么的 helper?完全无信息量
- ❌ `Utils.java`(无修饰)—— 同上;真要工具类,加修饰:`CsvEscapeUtils` / `TypeCastUtils`
- ❌ `Main2.java` / `OldXxx.java` / `XxxV2.java` —— 历史版本不该用文件名留档,git 已记录

#### 3.1.1.1 相似功能必须内聚到同一个类(强制 · 凌驾"文件命名")

> **铁律**:**相同或相似功能的函数,必须放进同一个类**。**禁止**因为"今天补了一批功能"就新建一堆类,而既有类里早有同类功能散落别处 —— 这是"目录碎片化"反模式,让后来者每加一个功能都要先全仓搜索一遍才知道有没有现成归宿。

##### 决策流程(新增函数时必走)

新加一个函数前,**先扫既有伴生类**,按"功能相似度"找归宿:

```
新功能 X
  ↓
扫既有 DataFrame*.java / Series*.java / ...
  ↓
有同类?─── 是 ──→ 并入该类(哪怕该类已 500 行,只要不破 §3.1 的 600 行红线,就内聚进去)
  │
  否 ──→ 评估:是否真的"新职能"?
           ├─ 是 → 新建类(按 §3.1.1 看名知意命名)
           └─ 否 → 找最接近的既有类并入,不要为"新"而新
```

##### 既有类归属速查(jian-core)

| 伴生类 | 装什么 |
|---|---|
| `DataFrame` 主类 | 链式入口(eval/sql/pipe/sample/applyRow)、Index 操作(loc/iloc/resetIndex/setIndex)、元信息(info/dtypes/describe/selectDtypes) |
| `DataFrameSort` | 排序 + TopN + 极值位置(sortBy/sortIndex/nlargest/nsmallest/idxmax/idxmin) |
| `DataFrameMissing` | 缺失值与条件填充(isna/dropna/fillna/ffill/bfill/isin/where/mask/interpolate) |
| `DataFrameReshape` | 长宽转换 + 判重(pivotTable/melt/transpose/dropDuplicates/duplicated/stack/unstack/explode) |
| `DataFrameMerge` | 表连接(merge/join/merge_asof/concat) |
| `DataFrameStats` | 单列与全表统计(sum/mean/std/min/max/median/percentile/corr/cov/skew/kurt/cumsum/diff/quantile/rank/value_counts) |
| `DataFrameArith` | 列级算术与比较(add/sub/mul/div/colLt/colGt/...) |
| `Resampler` | 时间序列重采样(resample 返回值,sum/mean/count/ohlc/...) |
| `Frequency` / `DatetimeIndex` | 时间频率与时间索引(纯数据结构类,非 DataFrame 伴生) |

**判定标准**:加新方法前对照此表;功能落入既有类范围 → 直接并入。**只有出现真正新职能**(如"时间序列重采样"是 Stats/Missing 都覆盖不了的新概念)才允许新建类。

#### 判定标准

如果后来者看到文件名,需要在 IDE 里"打开看一眼才知道干嘛",这个命名就是失败的。优秀的命名应该让人扫一眼 `ls` 就能定位到要找的功能在哪。

> **例外**:若某文件确实是无法再拆的单一职责,长度合规(§3.1),则用类名直配文件名即可(`Frequency.java` 装 `class Frequency`),不要画蛇添足加修饰后缀。

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

### 3.4 文本块优先(多行文本与引号规避)

处理多行文本、或含大量双引号/特殊字符的文本时,**优先使用 Java 文本块(`"""..."""`),不要用 `+` 拼接或大量 `\"` 转义**。典型场景:SQL、JSON、HTML、XML、OOXML 片段、长提示词、多行日志模板。

**版本依据**:Java 文本块自 **JDK 15** 起为正式标准特性(JEP 378;13/14 曾为 preview),**JDK 17 完整支持**——jian 基线就是 JDK 17(§1.2),可直接用,既非 JDK 17 才有,更非 JDK 21 才有。

**核心好处**:文本块**内部的单个 `"` 无需转义**,直接规避 JSON/SQL/HTML 的引号地狱;多行结构清晰可读;JDK 15+ 自动去除相对闭合 `"""` 的公共缩进。

**Java 示例(合规)**:
```java
// 文本块:内部 " 不转义;用 %s 占位 + .formatted() 代入变量(Java 15 新增实例方法)
String name = "jian";
String json = """
        {
            "name": "%s",
            "desc": "含"双引号"也无需转义"
        }
        """.formatted(name);
System.out.print(json);
```

> **变量代入**:Java 文本块**没有 `${}` 插值**,用 `%s`/`%d` 占位 + `.formatted(...)` / `String.format(...)` 在末尾代入(已实测可用)。典型错误:在文本块里写 `"name": "${name}"`——编译不报错但**插值不生效**,输出的是字面量 `${name}`。JDK 21/22 曾 preview 过 `${name}` 式字符串模板(`STR`),但 JEP 465 在 **JDK 23 已撤回移除**(Java 史上首个未能转正的 preview 特性),**切勿依赖**。

**反例(不合规)**:用 `+` 与 `\"` 硬拼——引号多时极易出错、可读性差:
```java
// 反例:引号地狱,禁止
String bad = "{ \"name\": \"" + name + "\", \"desc\": \"含\\\"双引号\\\"需转义\" }";
```

### 3.5 缺失值语义规范(强制 · 红线)

> ⚠️ **修改本契约前必读**:LONG/INT 用 `Long.MIN_VALUE` 哨兵、DOUBLE 用 NaN 表示缺失是**项目核心契约**,与 pandas 的差异已在 `doc/00-overview.md §10.16`(与 pandas 的已知设计差异)显式声明,对照测试已锁定。动契约 = 动全项目 + 全部对照测试,必须先读 §10.16 与 §0.5 红线再决策。

> jian 的缺失值处理遵循**"内部不失真、边界做转换"**原则。计算精度优先于一切——NaN 在内部传递时不能变成 null。

#### 3.5.1 统一规则(全 Column 子类一致)

| API | 缺失行返回值 | 说明 |
|---|---|---|
| `isNull(i)` | `true` | **权威判断**,始终可用 |
| `getDouble(i)` | `Double.NaN` | 数值缺失的统一占位标记(全类型一致) |
| `getLong(i)` | `Long.MIN_VALUE` | long 无 NaN,用最小值作缺失标记;下游可 `== Long.MIN_VALUE` 识别 |
| `get(i)` | DoubleColumn 返 `Double.NaN`(不失真);其它返 `null` | DoubleColumn 内部传递 NaN 不失真 |
| `getRow(i)` / `iterRows()` | `null` | **IO 边界安全网**:缺失行统一转 null,供 CSV/JSON/SQL 写出 |
| `toObjectArray()` | `null` | IO 导出安全网:NaN→null |

#### 3.5.2 下游调用规则(强制)

- **判断缺失一律用 `isNull(i)`**,不得用 `get(i) == null`——因为 DoubleColumn.get(NaN) 现在返回 Double.NaN 不是 null
- **IO 写出(CSV/JSON/SQL)一律通过 `getRow(i)` 或 `toObjectArray()` 取值**——它们在边界把 NaN 转成 null
- **export 显示(各格式默认 naRep)**:
  - **HTML** 默认 `<NA>`(对齐 pandas `to_html` 默认;`HtmlRenderer.naRep` 可改)
  - **Markdown/LaTeX/Excel/控制台** 默认空字符串
  - **不得**输出裸 `NaN` 字样(数值 NaN 经 `getRow(i)` 已转 null,渲染层不再看到 NaN)

#### 3.5.3 为什么不沿用 pandas 的"NaN==null"模型

pandas 把 NaN 和 null 在数值列里等价处理(NaN 表示缺失,null 也变成 NaN),这是历史包袱。jian 区分:
- **NaN** = 计算产生的非数结果(如 0/0),是**有效值的一种**
- **null/缺失** = 原始数据没有值
- 两者在 `isNull` 层面统一为"缺失",但在 `get` 层面**NaN 不失真**(返回 Double.NaN 不是 null)

### 3.6 SQL 跨库类型映射规范(强制)

> jian-io-sql 的 `Sql.java` 必须按数据库方言自适应 SQL 类型名,不能用硬编码。

#### 3.6.1 类型映射表

> **SQLite 列说明**:SQLite 是动态类型系统,列声明的类型名只是**"类型亲和"(advisory)**,不强制 —— 实际存储按值的类型走。
> `Sql.java dtypeToSqlType()` 当前**无 isSqlite 专设分支**,SQLite 落到默认分支(与 PG/H2 共用):`LONG→BIGINT / DOUBLE→DOUBLE PRECISION / BOOL→BOOLEAN / DATETIME→TIMESTAMP / DATE→DATE`。
> 这与下表"理想映射"(SQLite 列写 INTEGER/REAL/TEXT)在**字面量上不同,但 SQLite 都能接受**(类型亲和容忍),不发错 SQL,数据往返正确。
> 若用户严格需要 SQLite 风格类型名,可在 v2 加 isSqlite 分支(ROI 低,见 doc/02 §9.5)。

| jian DType | PostgreSQL | MySQL | SQLite(理想/类型亲和) | H2 | SQL Server | Oracle |
|---|---|---|---|---|---|---|
| INT | INTEGER | INT | INTEGER | INTEGER | INT | INTEGER |
| LONG | BIGINT | BIGINT | INTEGER(代码默认 BIGINT) | BIGINT | BIGINT | NUMBER(19) |
| DOUBLE | DOUBLE PRECISION | DOUBLE | REAL(代码默认 DOUBLE PRECISION) | DOUBLE PRECISION | **FLOAT(53)** | FLOAT(126) |
| BOOL | BOOLEAN | BOOLEAN | INTEGER(代码默认 BOOLEAN) | BOOLEAN | **BIT** | **NUMBER(1)** |
| STRING(≤4000) | VARCHAR(n) | VARCHAR(n) | TEXT | VARCHAR(n) | VARCHAR(n) | VARCHAR2(n) |
| STRING(>4000) | **TEXT** | **LONGTEXT** | TEXT | **CLOB** | **VARCHAR(MAX)** | **CLOB** |
| DATETIME | TIMESTAMP | TIMESTAMP | TEXT(代码默认 TIMESTAMP) | TIMESTAMP | DATETIME2 | TIMESTAMP |
| DATE | DATE | DATE | TEXT(代码默认 DATE) | DATE | DATE | DATE(含时间!) |

**加粗** = 该数据库与默认不同,需方言适配。阈值 4000 = Oracle VARCHAR2 上限(所有库的公共安全上限)。
**SQLite 列括号注** = 代码默认分支产出(类型亲和容忍,与理想映射等价)。

#### 3.6.2 STRING 自适应长度

建表时扫该列实际数据取 maxLen:
- maxLen ≤ 4000 → VARCHAR(maxLen)(向上取整到 4 的倍数)
- maxLen > 4000 → 大文本类型(各库不同,见上表)

#### 3.6.3 JDBC 读回类型规范化

`resultSetToDataFrame` 必须把 JDBC 特殊对象转成 Java 标准类型:

| JDBC 返回 | 转成 | 方法 |
|---|---|---|
| `java.sql.Clob` | `String` | `clob.getSubString(1, len)` |
| `java.sql.Blob` | `byte[]` | `blob.getBytes(1, len)` |
| `java.math.BigDecimal` | `Double` | `bd.doubleValue()` |
| `java.sql.Date` | `LocalDate` | `d.toLocalDate()` |
| `java.sql.Timestamp` | `LocalDateTime` | `ts.toLocalDateTime()` |

#### 3.6.4 跨库测试要求(红线)

凡 jian-io-sql 支持的数据库,**必须有真实数据库测试**(不只是 H2 模拟方言):
- H2 in-memory:默认跑(无外部依赖)
- SQLite in-memory:默认跑(自带 native)
- PostgreSQL:用 `-Dtest.pg=true` 激活(需本机 PG 运行)
- 覆盖:全 dtype 往返 / 参数化查询 / 4 种写入模式 / 缺失值 / 大文本(短/长/混合)/ SQL 注入防护

### 3.7 Web 环境安全规范(强制)

> jian 可能被用于 Tomcat/Spring Boot 等 Web 服务器环境,对外提供数据分析能力。以下安全要求强制执行。

#### 3.7.1 ServiceLoader 不缓存(防 Tomcat redeploy 内存泄漏)

- ❌ **禁止**:把 `ServiceLoader.load(...)` 存在 static final 字段里(ServiceLoader 内部缓存引用 WebappClassLoader,Tomcat redeploy 时 ClassLoader 无法 GC,经典内存泄漏)
- ✅ **必须**:每次 `current()` 调用时新建 `ServiceLoader.load(...)`,由 GC 自动回收
- 适用:`DslEngine.current()` / `StatsProvider.current()`

#### 3.7.2 只读模式必须生效(防 SQL 写操作)

- `Engine.checkReadOnly(sql)` **必须**在 `engine.sql()` 入口调用
- 拦截:DROP/DELETE/TRUNCATE/ALTER/CREATE/GRANT/INSERT/UPDATE(剥前导注释后整词匹配)
- `readOnly=true` 时,任何写操作抛 `SecurityException`

#### 3.7.3 Excel/CSV 公式注入防护(一致)

- CSV 和 Excel 写出都**必须**对 `= + - @` 开头的单元格加单引号前缀(OWASP CSV Injection 规范)

#### 3.7.4 Process 超时与流关闭(Clipboard)

- `ProcessBuilder.start()` 后的 `getInputStream()`/`getOutputStream()` 必须 try-with-resources 关闭
- `waitFor()` 必须带超时(默认 5 秒),超时后 `destroyForcibly()`
- 防 native FD 泄漏 + 子进程挂死

#### 3.7.5 HikariCP 连接池生命周期(Web 集成方负责)

- `Engine` 实现 `AutoCloseable`(close 时关 HikariDataSource)
- Spring Boot 集成:`@Bean` 返回 Engine 类型,Spring 会自动调 close
- 非 Spring 集成:必须手动 `engine.close()` 或注册 shutdown hook
- **jian 库不提供自动 shutdown 机制**(避免对容器生命周期做假设)

#### 3.7.6 内存管理(Java GC 语义)

- **DataFrame 是纯内存数据**,不持有文件句柄/连接,不需要 close()/dispose()
- `df = null` 只断开引用,**不会立即释放内存**;GC 自动回收(JVM 决定时机)
- 大数据量处理完立刻 `df = null` 断引用,必要时 `System.gc()` 建议 GC(不保证立即)
- ❌ **禁止**:用 static 字段缓存大 DataFrame(GC 无法回收 → OOM)
- ✅ 如需缓存用 `WeakHashMap`(GC 可随时回收)
- ✅ 最佳实践:在方法内用局部变量持有 DataFrame,方法结束后自动断引用

#### 3.7.7 安全的方面(无需改)

- ✅ **反序列化安全**:Jackson 未开 `enableDefaultTyping`;Pickle(.jpk)走自定义容器 + CRC + JSON,无 `ObjectInputStream`
- ✅ **SQL 参数化**:SqlBridge/SqlBuilder 全用 PreparedStatement + ? 占位符
- ✅ **Connection/Statement/ResultSet**:全 try-with-resources
- ✅ **文件流**:全 try-with-resources 或 Files 工具方法
- ✅ **DataFrame 不可变**:构造后无 mutator;`dataInPlace()` 仅内部 hot path
- ✅ **ofColumnArraysSafe**(Web 安全版本):防御性 clone 所有入参数组;`ofColumnArrays`(零拷贝)首次调用时 stderr 提醒改用 Safe 版本
- ⚠️ **ThreadLocal 仅一处**:`jian.dsl.SqlEngines`(多请求引擎隔离,正当用途)。
  容器(Tomcat/Spring Boot)线程复用下 `useCustom` 未 `reset` 会跨请求泄漏引擎选择 —— 必须
  try-finally `reset()`,详见其 javadoc 警示与 `SecurityAuditTest.ThreadLocal引擎跨调用泄漏与reset修复`
- ✅ **无其它 ThreadLocal / 无静态可变状态**(除上条 SqlEngines 的显式设计):全 `static final` 或 static 方法

#### 3.7.8 两种部署形态的安全指引

| 形态 | 主要威胁面 | jian 的防护(测试锁定) |
|---|---|---|
| **本地 jar**(java -cp 单文件/脚本) | 公式注入(CSV/Excel 打开即执行)、XXE/zip bomb、慢 URL 挂起 | `= + - @` 前缀转义(§3.7.3,CsvEdgeCase/ExcelEdgeCase 实测);Jackson 默认配置无外部实体;POI 自带 zip bomb 检测;readUrl 10s 超时 + 8MB 上限 + 仅 http/https |
| **Tomcat / Spring Boot** | 存储型 XSS(报表 toHtml)、SQL 注入(标识符/值)、SSRF(readUrl)、ThreadLocal 跨请求泄漏、redeploy 类卸载受阻 | toHtml 五字符转义;标识符按需引号包裹+双写转义(jian-io-sql/ORM 的 JDBC 路径,简单 ASCII 原样放行、中文保真,注入元字符被引号化为字面量,控制字符硬拒);值参数化 `Jian.query(df, expr, Params)`(占位字面量化 + '' 翻倍);`Engine.checkReadOnly` 拦写;SqlEngines 警示;ServiceLoader 不缓存 |
| 通用(SCA) | 依赖 CVE | hadoop-common 3.3.6(columnar 附加 jar,本地 IO 子集使用、无网络面,风险缓解;升级随 orc/parquet 兼容矩阵);jackson/POI/jsoup/HikariCP 均为较新稳定版 |

> 用户可控值**一律** `Jian.query(df, "列 == ${名}", Params.of(...))` 参数化,禁止拼进表达式/SQL(场景示例见 `META-INF/ai/scenarios.md` 安全写法节)。

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
| `<module>/src/main/ai-doc/module.md` | AI 文档 | 每模块能力/限制/快速上手(22 份,打包进 jar,见 §2.8.1) |
| `META-INF/ai/aggregated.md` / `scenarios.md` | AI 文档 | fat jar 总索引 / 真实场景集(见 §2.5.3 第 4 条;scenarios 随场景测试同步) |

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

数据数组已外置为独立 js 文件(`doc/modules.js`、`doc/api-quick.js`、`doc/api-ref.js`),`doc/index.html` 末尾以 `<script src>` 引用之,由 `doc/render.js` 模板化渲染:

- `MODULES`(8 项)→ 模块卡片
- `API_QUICK`(4 组)→ 顶层 API 速查表
- `API_REF`(约 28 项)→ 方法目录卡(日后扩展的核心区)

**新增内容只往对应 js 数据数组里加一项,不动 HTML 结构。** md 与 html 数据冲突时,**以 md 为准,改 html 对齐**。

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
- [ ] **公共 API/能力/测试数变化 → 对应 module.md 的"能力/tests"字段改了吗?api-counts.md 刷新了吗?**
- [ ] **新增场景测试 → META-INF/ai/scenarios.md 登记了吗?**(AI 拿 jar 须能看到场景集)。
- [ ] **模块增删/改名 → module.md 与对应 aggregated.md 总索引同次更新了吗?**(§2.5.3 第 4 条)。

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
- **保留交付物**:用户要求的产出、Maven 管理的依赖(`pom.xml` 声明,本地仓库由 Maven 维护)、`AGENTS.md` / `doc/` 规范与需求文档不清理。
- 清理后简要说明删了什么。

---

## 7. 跨平台零本机绑定(强制 · 红线)

> 与通用规范 §6.7 等价(本节是该红线在 jian 项目的重申)。jian 是要对外分发的库,**任何与本地配置/目录/硬件绑定的写死内容都不可接受**。

### 7.1 禁止写死本地路径/目录/盘符

- ❌ 本机绝对路径(家目录、/opt、/usr/local、Windows 盘符等)、用户名、硬件路径。
- ❌ 写死用户名/家目录前缀。
- ✅ Java 用 `System.getProperty("user.home")` / `System.getenv()` / `File.pathSeparator` / `new File(".").absolutePath`。
- ✅ 需要缓存目录时走环境变量(`${XDG_CACHE_HOME}`),平台无关回退默认值(如 `~/.cache`),默认值本身不含盘符/用户名。

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
3. **依赖查找**:依赖都在各模块 `pom.xml` 里声明(见 §2.4),由 Maven 从阿里云镜像自动拉取;新增依赖直接在对应模块 pom 加 `<dependency>`,无需手工查共享仓库。
4. **编码**:Java + UTF-8 + 中文注释(见 §3)。源码放对应子模块 `src/main/java/...`。检查两项硬指标:
   - **行数**:每个 `.java` 文件不含注释尽量 ≤ 600 行,超了优先拆分(见 §3.1)。
   - **5W1H 的 How**:非平凡函数的 How 必须详写关键变量数值变化 + 逻辑路线 + 数据走向(见 §3.3.1),不能只写一句话。
5. **构建验证**:`mvn -pl <子模块> -am compile`(或单文件 `java MyApp.java` 仅限 demo)。
6. **文档同步**(强制 · 红线):改了需求/接口事实内容 → **先改 md 分册,再同步 html**(见 §5)。
7. **清理现场**:删除一次性验证脚本、临时数据、调试产物(见 §6);保留库源码、pom、通用工具、文档。
8. **如实汇报**:报告改动模块、依赖变化、文档双轨同步情况、清理情况。

---

## 附录 A:本文件与通用规范的差异速查

| 维度 | 通用 `AGENTS.md`(上级目录) | 本文件 `jian/AGENTS.md` |
|---|---|---|
| 主语言 | 允许多种 JVM 语言 | **只用 Java**(不引入其它 JVM 语言) |
| JDK | 21 | **17 LTS** |
| 构建工具 | 禁用 Maven/Gradle | **破例用 Maven 多模块** |
| 包整合 | uber/fat jar 优先 | **子模块禁 shade,精细引用;顶层三库可选 fat**(`-Pfat`,见 §2.5) |
| Skills 规范 | 第 0、6 章强制 | **不适用**(整章删除) |
| 文档维护 | 无特殊要求 | **md + html 双轨同步(红线)** |
| 零本机绑定 | 仅插件强制 | **全项目强制**(库要对外分发) |
| UTF-8 + 5W1H + 伪代码 | 强制 | **强制**,且 **How 必须详写**(变量变化/逻辑路线/数据走向,见 §3.3.1) |
| 单文件行数 | 无限制 | **不含注释 ≤ 600 行**,超了优先拆分(见 §3.1) |
| 文本块(多行文本) | 多语言示例 | **只用 Java 文本块**(§3.4) |

---

*本文件为 jian 项目专用规范,凌驾于本目录所有其它文档。*
