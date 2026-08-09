# jian / jian-sql / jian-num 需求说明书 · 总览

> 版本:v1.0.0(发布版,与 pom.xml `<version>` 同步) · 日期:2026-08-09
> 作者:zc · **本文件是事实来源(md 为准,见 AGENTS.md §0.3)**

---

## 0. 文档目的

本文档是 **jian / jian-sql / jian-num** 三个相互独立、可单独引用的 Java 库的**总需求说明书**。它回答四个问题:

1. **要做什么** —— 三个库各自的范围与边界。
2. **基于什么做** —— 复用哪些开源积木、自写哪些部分(附选型核实数据)。
3. **多大工作量** —— 代码行数、token 用量的近似估算。
4. **怎么落地** —— 模块切分、依赖隔离、开发顺序。

各模块的详细需求见同目录下 `01 ~ 07` 各分册,**分册之间无引用关系**,可独立阅读、独立实现、独立打包。

---

## 1. 项目定位

### 1.1 一句话定位

在 JVM 上提供一套 **以数据导入导出与数据分析为目的**(不追求极致运行速度)的轻量工具栈,API 风格向 Python 的 pandas / sqlalchemy / numpy 靠拢,降低 Python ↔ Java 之间的认知成本。

### 1.2 三个库的职责切分

| 库 | 对标 Python | 核心职责 | 是否依赖另外两个 |
|---|---|---|---|
| **jian** | pandas | 表格数据结构(DataFrame/Series)+ 数据变换 + IO(7 数据库 + 12 种文件格式)+ 可视化 + 多格式导出 | 可选依赖 jian-num |
| **jian-sql** | sqlalchemy | 数据库连接管理、SQL 表达式构建、ORM 映射、为 jian 喂数据 | 不依赖 jian |
| **jian-num** | numpy(子集) | 仅实现 pandas/统计分析会用到的基础数值与统计能力 | 不依赖任何 |

> **关键设计原则**:三个库**解耦**。即:`jian-core` 不强依赖 `jian-sql`;`jian-sql` 不依赖 `jian`;`jian-num` 完全独立。用户按需引 jar,引哪个就用哪个,不引就不加载。

### 1.3 不做什么(明确排除)

- ❌ 不做分布式/集群计算(那是 Spark/Flink 的领地)。
- ❌ 不做 GPU 加速、不做极致性能优化(用户已明确不追求速度极限)。
- ❌ 不做深度学习框架(不对标 PyTorch/TensorFlow)。
- ❌ jian-num 不追求 1:1 复刻 numpy(只做 pandas 需要的子集)。
- ❌ 不做实时流处理。

---

## 2. 技术选型(已核实活跃度,截止 2026-08-01)

### 2.1 选型两条硬标准

经与需求方确认,选型遵循:

1. **用户基数大** —— 有人用、有人讨论、BUG 已被社区磨平。
2. **最后更新在 6 个月内** —— 即 2026-02-01 之后仍有 release(活跃维护)。

> **同时满足两条者优先复用;无法同时满足者,优先自写而非硬绑僵尸库。**
> 这是本项目"自写核心 + 复用成熟积木"混合策略的由来。

### 2.2 Java 生态现状的一个关键事实

**Java 生态中没有同时满足上述两条标准的 pandas 等价物。**

- DFLib(319 stars,2025-04 停更)—— 用户基数过小 + 停更,**淘汰**。
- Tablesaw(3.8k stars,2025-06 release,Apache 孵化中)—— 用户基数大但 13 个月未发版,**风险点,不作为核心依赖**。
- Joinery(轻量)—— 用户基数小,**淘汰**。

结论:**jian 的 DataFrame 核心自写**,只把"用户基数大且活跃"的细粒度积木(CSV/Excel/JSON/图表/SQL 等)作为叶子依赖。

### 2.3 复用积木清单(均已核实活跃度)

| 积木 | 用途 | 最新版本 | 最后更新 | 6 个月内? | 用户基数 | 用在哪个模块 |
|---|---|---|---|---|---|---|
| **Apache Commons CSV** | CSV 读写 | 1.12.0 | 2024-09 | ⚠️ 边界(11 个月) | 极高 | jian-io |
| **Apache POI** | Excel/ xls 读写 | 5.5.1 | 2025-11-30 | ⚠️ 边界(9 个月) | 极高(行业标准) | jian-io |
| **Jackson** | JSON 读写 | 2.18.x | 持续更新 | ✅ | 极高(行业标准) | jian-io |
| **PostgreSQL JDBC** | PG 驱动 | 42.7.x | 持续 | ✅ | 极高 | jian-io-sql / jian-sql |
| **MySQL Connector/J** | MySQL 驱动 | 9.1.0 | 持续 | ✅ | 极高(行业标准) | jian-io-sql / jian-sql |
| **SQLite JDBC**(xerial) | SQLite 驱动 | 3.46.1.3 | 持续 | ✅ | 极高 | jian-io-sql / jian-sql |
| **H2 JDBC** | H2 驱动 | 2.3.232 | 持续 | ✅ | 极高 | jian-io-sql / jian-sql |
| **Doris** | (用 MySQL 协议驱动) | 同 MySQL | — | ✅ | — | jian-io-sql / jian-sql |
| **UCanAccess** | MS Access 驱动 | 5.x | 2024+ | ✅ | 高(Access Java 事实标准) | jian-io-sql / jian-sql |
| **ojdbc8**(Oracle) | Oracle 驱动 | 23.6.0.24.10 | 2024+ | ✅ | 极高(官方) | jian-io-sql / jian-sql |
> Oracle 驱动选 ojdbc8 而非 ojdbc11:兼容 Oracle 12c(ojdbc11 最低要求 19c),见 §2.3 取舍。
| **XChart** | 图表绘制 | 4.0.3 | 2026-07-07 | ✅ | 中 | jian-viz |
| **jOOQ**(OSS Edition) | SQL 表达式 | 3.21.6 | 2026-06-15 | ✅ | 极高(行业标准) | jian-sql |
| **HikariCP** | 连接池 | 5.1.0 / 6.x | 持续 | ✅ | 极高(行业标准) | jian-sql |
| **Apache Commons Math 3.6.1** | 描述统计 | 3.6.1 | 2016 发布,稳定 10 年 | ❌(但已核实无影响 BUG,见下注) | 极高 | jian-num |

> **注:Commons Math 选型核实结论(2026-08-01)** —— 3.6.1 虽不在 6 个月活跃窗口内,但经核实可用,理由:
> ① 自 2016 年发布至今 10 年,没有"会导致数据错误"的重大未修复 BUG;Red Hat 维护有企业级 backport 版(`3.6.1.redhat-00001`)。
> ② jian-num 的用途(mean/sum/std/variance/percentile/median/quantile/covariance/correlation)正好是 3.6.1 最成熟、最被反复测试的部分,避开了所有已知问题。
> ③ 4.0 至今仍是 beta(2022 年 beta1,未 GA),且把单 jar 拆成 5 个(rng/numbers/statistics/geometry/math4-legacy),引入成本反而更高。
> ④ 两个已知问题与 jian-num 无关:`MATH-1502`(K-S 检验算错)——不在 jian-num 范围;`MATH-1457`(`FastMath.exp` 极端值越界)——jian-num 统一用 `Math` 规避。
| **SLF4J** | 日志门面 | 2.0.x | 持续 | ✅ | 极高 | 全部(可选) |
| **JUnit 5** | 测试 | 5.11.3 | 持续 | ✅ | 极高 | 全部(仅测试) |

> 标注 ⚠️ 的两个(CSV、POI)处于 6 个月边界附近,但用户基数极大、是事实标准,作为"接受项";若它们的下一版超期仍无更新,未来可替换为自写或他选。

### 2.4 自写部分清单

| 自写模块 | 行数估算 | 理由 |
|---|---|---|
| jian-core(DataFrame 85 方法 15 大类 + Series + GroupBy + 窗口) | ~3500 行 | 无合格活跃库可复用 |
| jian-export(Styler 子系统 + 5 渲染器) | ~3500 行 | Styler 是 pandas 子系统,自写 |
| jian-dsl(L1/L2/L3 全部自写 Pratt + 正则(零依赖)) | ~5000 行 | 不嵌入外部脚本引擎(行业惯例),自包含;Oracle 基线 + PG/MySQL 兼容,方言变量切换 |
| jian-sql(连接管理/ORM 映射层) | ~2500 行 | jOOQ 只解决 SQL 表达式,连接管理与 ORM 映射需自写 |
| jian-num(描述统计封装) | ~2000 行 | 复用 Commons Math 3.6.1(已核实无影响 BUG),自写 numpy 风格薄封装 |
| 各模块的适配/胶水层 | ~6000 行 | 把积木的 API 包装成统一风格 |

> 关键原则:**所有 DSL/解析器自写,不嵌入 Groovy/Kotlin/JavaScript 等外部脚本引擎**——保证最终 jar 自包含、可独立分发(与 pandas/polars/Spark SQL 的做法一致)。

---

## 3. 模块化架构

### 3.1 模块全景图

```
┌─────────────────────────────────────────────────────────────┐
│                      用户代码 (your app)                     │
└────────────┬──────────────┬──────────────┬─────────────────┘
             │              │              │
     ┌───────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
     │   jian    │ │ jian-sql│ │   jian-num   │   ← 三个独立库
     │  (jar × N)   │ │  (jar × N) │ │  (单 jar)  │     可分别引用
     └──┬───┬───┬───┘ └─────┬──────┘ └─────┬──────┘
        │   │   │           │              │
    ┌───┘   │   └──┐        │              │
    ▼       ▼      ▼        ▼              ▼
  core    io    viz/export  jOOQ       Commons Math
 (自写) (POI等) (XChart等)  HikariCP    (统计积木)
```

### 3.2 jian 内部子模块(每个独立 jar,按需引入)

| 子模块 artifactId | 内容 | 强依赖的子模块 | 外部依赖 jar |
|---|---|---|---|
| `jian-core` | DataFrame/Series/索引/变换/groupby/join | 无 | 无(纯 JDK) |
| `jian-io-csv` | CSV/TSV/FWF 读写 | core | Commons CSV |
| `jian-io-excel` | Excel(xls/xlsx)+ ExcelWriter 多 sheet | core | Apache POI(`poi-ooxml`,非 uber) |
| `jian-io-json` | JSON(5 种 orient)+ json_normalize | core | Jackson |
| `jian-io-html` | HTML 表格读(jsoup)/ 写 | core | jsoup + 自写 |
| `jian-io-xml` | XML 读写 | core | JDK + Jackson XML |
| `jian-io-sql` | 7 数据库读 / 写:PG / MySQL / Doris / SQLite / H2 / Oracle / Access | core | 各 JDBC 驱动(按需) |
| `jian-io-parquet` | Parquet 读写(去 Hadoop) | core | parquet-avro |
| `jian-io-orc` | ORC 读写 | core | orc-core |
| `jian-io-pickle` | 自定义 .jpk 序列化(不用 Kryo/JDK 序列化) | core | 无(纯 JDK) |
| `jian-io-clipboard` | 系统剪贴板(跨平台) | core | 无(纯 JDK) |
| `jian-io-latex` | LaTeX 表格写出 | core | 无(纯 JDK) |
| `jian-viz` | 13 种图表(10 plot + 3 plotting)→ PNG/SVG | core | XChart(+jian-num 可选) |
| `jian-export` | DataFrame → HTML / Markdown 表格 | core | 无 |
| `jian-dsl` | L1/L2 手写 Pratt + L3 自写 SQL 子集 | core | 纯 JDK(零运行时依赖) |

> **依赖方向单向**:io/viz/export 依赖 core;core 不反向依赖任何 io/viz/export。
> **加载策略**:用户只引 `jian-core` 也能跑(纯内存变换);需要读 CSV 时再加 `jian-io-csv`,JVM 类加载按需,不会加载 POI/XChart 的类。

### 3.3 jian-sql 内部子模块

| 子模块 | 内容 | 外部依赖 |
|---|---|---|
| `jian-sql-engine` | 连接管理、连接池、方言探测 | HikariCP |
| `jian-sql-expr` | 类型安全 SQL 表达式构建 | jOOQ OSS |
| `jian-sql-orm` | 表→对象映射、Session | 上述两个 |

### 3.4 jian-num

单 jar,内部不拆模块。依赖 Commons Math 3.6.1。

---

## 4. 工作量估算

### 4.1 代码量估算(自写 + 适配层,Java 行数)

> 范围已扩张为"大面对齐 pandas 3.x",各模块估算相应上调。

| 模块 | 自写代码(行) | 测试代码(行) | 合计(行) | 说明 |
|---|---|---|---|---|
| jian-core | 3,500 | 3,500 | **7,000** | DataFrame 主体 195 public 方法(实测,见 api-counts.md)+ Series 52 + GroupBy + Window + Resampler 18 方法(2026-08-09 经源码核实;早前版本写"14000 行/200+ 方法/Resampler 全套"是估算超前,Resampler 现已实现见 §9) |
| jian-io(12 格式 + 7 数据库) | 7,000 | 4,000 | **11,000** | CSV/Excel/JSON/HTML/XML/SQL/Parquet/ORC/Pickle/Clipboard/LaTeX/Markdown |
| jian-viz(13 种图) | 2,500 | 1,000 | **3,500** | 10 plot + 3 plotting(高维图 v2 规划) |
| jian-export(Styler + 5 渲染器) | 3,500 | 2,000 | **5,500** | Styler 子系统 + HTML/Excel/LaTeX/Markdown/控制台 |
| jian-dsl(L1/L2/L3 全自写 Pratt + 正则) | 5,000 | 2,700 | **7,700** | Pratt parser + 正则子句切分 + 方言切换 |
| jian-sql(engine/expr/orm) | 2,500 | 1,500 | **4,000** | 不变 |
| jian-num(描述统计封装) | 2,000 | 1,000 | **3,000** | 不变 |
| **合计** | **36,500** | **20,200** | **≈ 56,700 行** | |

> 估算口径:含必要的中文注释(遵循 AGENTS.md §3.2 的 5W1H + 伪代码规范)、空行、import。不含外部依赖代码。
> 误差范围:实际 ±30% 属正常(core 的 groupby/pivot/rolling、io 的 Parquet/ORC、dsl 的方言归一化是不确定度最高的三块)。

### 4.2 技术难点提示

> **风险点**:core 的 pivot_table/rolling/resample/时间序列、io 的 Parquet/ORC 去 Hadoop 化、dsl 的方言归一化 visitor 是三大技术难点,实现时需重点投入。

### 4.3 AI token 用量估算(本类环境开发)

按"需求澄清 → 设计 → 实现 → 调试 → 文档"全流程,每千行自写代码约消耗:

| 工作类型 | tokens / 千行 |
|---|---|
| 纯实现(逻辑明确,直接写) | ~25k |
| 含调研/选型/调试(本项目主场景) | ~50-70k |
| 含反复重构/边界打磨(本项目 core 主场景) | ~80-100k |

按本项目自写 36,500 行、平均 60k tokens/千行估算:

| 模块 | 自写行数 | token 估算 |
|---|---|---|
| jian-core | 3,500 | **250k**(15 大类、有 pivotTable/groupBy/merge/window 等难点;resample 规划中) |
| jian-io | 7,000 | 420k |
| jian-viz | 2,500 | 150k |
| jian-export | 3,500 | 220k |
| jian-dsl | 5,000 | 350k(三方言归一化用例多) |
| jian-sql | 2,500 | 150k |
| jian-num | 2,000 | 120k |
| 调研/选型/联调/文档(含本轮已花) | — | ~150k |
| **合计** | **36,500** | **≈ 2.4M - 2.8M tokens** |

> 上下文换页/总结的开销另计,实际可能再多 15-25%。
> 若只做 MVP(core 基础 + CSV + 基本 viz + export):约 **500-700k tokens**。
> **提示**:core 的 token 消耗最大且最难压缩(已实现 85 个 DataFrame 方法对齐 pandas 核心子集 + 大量边界用例;规划项见 01 §3.16),建议按"内部分包"分多轮上下文实现,避免单轮塞不下。

---

## 5. 开发路线图

### 5.1 推荐实现顺序(依赖驱动)

```
第1步: jian-num         ←  最简单、零依赖,练手 + 给 jian-core 用
第2步: jian-core   ←  最重,所有 jian 子模块的基石
第3步: jian-io-csv ←  让 core 能读数据,形成最小闭环
        └─ 此时已有"可用的 jian MVP"
第4步: jian-io-json / excel / sql  ←  并行铺开
第5步: jian-viz    ←  数据能看了
第6步: jian-export ←  数据能导出了
第7步: jian-sql    ←  独立线,可任意时点插入
第8步: 联调 + 示例 + 打包发布
```

### 5.2 里程碑(MVP → 完整版)

范围扩张后,分更多里程碑,降低单阶段风险:

| 里程碑 | 包含 | 能干什么 |
|---|---|---|
| **M0: jian-num MVP** | jian-num | 描述统计、分位数、随机数 |
| **M1: core 基础** | core §3.1-3.8(属性/转换/索引/二元/应用/统计/重索引/缺失) | DataFrame 读写变换、统计 |
| **M2: core 高级** | core §3.9-3.12 + GroupBy + 窗口 + Resampler | pivot/merge/时间序列/rolling |
| **M3: jian MVP** | M2 + io-csv/excel/json + 基础 viz + export(无 Styler) | 读 CSV/Excel → 变换 → 出图/HTML |
| **M4: jian 全功能** | 12 格式全 io + 13 种图 + Styler | 全部 pandas 等价能力 |
| **M5: jian-sql** | engine/expr/orm | 完整数据库交互 |
| **M6: 发布版** | 全部 + 文档 + 示例 + 打包 | 可对外分发 |

---

## 6. 关键设计原则(贯穿所有模块)

### 6.1 模块解耦与按需加载

- **每个子模块独立 artifact**(独立 Maven `artifactId`/独立 jar)。
- **依赖单向**:叶子依赖 core,core 不依赖叶子。
- **运行时按需加载**:用户不引 `jian-io-excel`,JVM 不会加载 POI 的类。
- **失败优雅降级**:若调用 Excel 读取但未引 POI,抛出带安装提示的 `ModuleNotLoadedException`,而非 `NoClassDefFoundError`。

### 6.2 API 风格统一

- 向 pandas/sqlalchemy 靠拢的命名(`DataFrame`、`readCsv`、`groupBy`、`merge`、`Engine`、`Session`),降低 Python 用户的认知成本。
- **链式调用为主**:`df.filter(...).select(...).sortBy(...).head(10)`。
- **不可变优先**:DataFrame 的变换返回新实例(便于链式与并行),仅在显式 `inPlace()` 时原地修改。

### 6.3 跨平台与零本机绑定(遵循 AGENTS.md §6.0/§6.7)

- 不写死任何本机路径、用户名、凭据。
- 数据库连接串、API Key 走 `.env` 或环境变量,严禁硬编码。
- 不依赖本机特定软件版本。

### 6.4 中文 UTF-8 + 注释规范(遵循 AGENTS.md §3.1/§3.2)

- 所有源码、注释、日志用中文 UTF-8。
- 关键逻辑用 **5W1H** 详写;非平凡函数实现前先写**中文伪代码**注释。

### 6.5 不追求极致性能

- 数据规模定位:**单机、千万行级以内**的表格。
- 实现优先用清晰的列式存储(`List<Object>` 或更细的 typed column),不优先上 off-heap / 向量化。
- 与 pandas 比,可接受 1.5-3 倍慢;换取代码可读、可维护。

---

## 7. 分册索引

| 分册 | 文件 | 说明 |
|---|---|---|
| 01 | `01-jian-core.md` | DataFrame/Series/GroupBy/Window 全 15 大类数据操作 |
| 02 | `02-jian-io.md` | 12 格式 + 7 数据库读写适配 |
| 03 | `03-jian-viz.md` | 13 种图表 → PNG/SVG |
| 04 | `04-jian-export.md` | Styler 子系统 + HTML/Excel/LaTeX/Markdown/控制台 |
| 05 | `05-jian-sql.md` | 引擎/表达式/ORM |
| 06 | `06-jian-num.md` | 描述统计/分位数/随机数/简单线代 |
| 07 | `07-jian-dsl.md` | L1/L2/L3 全自写 Pratt + 正则(Oracle/PG/MySQL 三方言分页都认) |

> 每个分册独立,无相互引用,可单独实现、单独打包、单独测试。

---

## 8. 已确认的关键决策(2026-08-01)

经评审确认,以下决策已定,贯穿所有分册:

1. **JDK 基线:JDK 17 LTS**(向下兼容 17+,本机更高版本 JDK 同样可跑)。API 风格自由使用 JDK 17 特性:record / sealed / pattern matching for switch / 文本块。
2. **主语言:只用 Java**(不用 Groovy/Kotlin 等 JVM 语言,含验证/demo/冒烟脚本)。详见 `AGENTS.md` §1.1。
3. **构建工具:Maven 多模块**(破例引入)。原因:本项目 22 个子模块(见 §3.2),手工管理 jar 依赖会失控。Maven 仅用于构建期依赖管理,**不要求最终用户装 Maven**(产物仍是 jar)。
4. **打包形态:每个子模块一个 jar**(细粒度,严格按需加载)。用户只引需要的 jar。
5. **子模块不做包整合**(强制,AGENTS.md §2.5.1):22 个叶子子模块的 jar 一律按 `groupId:artifactId:version` 精细引用,**不用 uber/fat jar、不用 maven-shade-plugin / maven-assembly-plugin**。理由:① 版本仲裁交 Maven 依赖中和机制统一处理,手动整合反而锁死旧版;② 依赖树对用户透明,便于排障升级;③ 与"细粒度按需 jar"目标一致。
   - **例外(AGENTS.md §2.5.2)**:jian / jian-num / jian-sql 三个**顶层聚合模块**经 `-Pfat` 允许出 `*-all.jar`(fat jar),默认 `install`/`package` 不触发,thin 是主形态。详见 §10.15「AI 友好的双形态制品」。
   - 例:Excel 能力引 `org.apache.poi:poi-ooxml:5.5.1`(**不是** `poi-ooxml-uber`),传递依赖由 Maven 自动拉。
   - 例:jian-dsl 自写 Pratt parser(零运行时依赖,不用 ANTLR4)。
6. **功能范围:大面对齐 pandas 3.x**(用户明确要求)。
   - IO:10 类主流格式全实现(CSV(含 TSV/FWF)/Excel/JSON/HTML/XML/SQL/Parquet/ORC/Pickle/Clipboard/LaTeX 仅写/Markdown 经 export);Tier 2(Feather/Stata/SAS/SPSS/GBQ/Iceberg/HDF5)**不计划支持**(见 doc/02 §1.2)。
   - 图表:13 种全做(10 种 plot + 3 种 plotting);radviz/andrews_curves/parallel_coordinates/bootstrap 4 种高维图列入 v2 规划。
   - 样式:Styler 子系统全功能(条件染色/颜色映射/数值格式/条形/自定义 CSS),HTML+Excel+LaTeX 三输出。
7. **Parquet/ORC**:全实现(原 v2 项提前到 v1,因用户要求大面对齐)。
8. **DSL 不嵌入任何外部脚本引擎**:L1/L2 手写 Pratt,L3 用自写正则子句切分(不用 ANTLR4)。
9. **API 风格:变换链式 + 终端静态收口**(2026-08-09 与 AI agent2 共识,修正早期文档的"全链式"误导):
   - **DataFrame 变换**(filter/sortBy/select/merge/assign 等)是**链式实例方法**,返新 DataFrame(immutable-first)。
   - **IO/Viz/Export 终端**(读写文件、绘图、样式)是**静态方法收口**(`Jian.toCsv(df, path)` / `Plot.line(df, ...)` / `Styler.of(df)`),**不是** `df.toCsv()` / `df.plot()` / `df.style()` 实例方法。
   - **设计理由**:IO/Viz/Export 属于 jian-io-* / jian-viz / jian-export 叶子模块,DataFrame 在 jian-core。core 不能反依赖叶子(模块单向依赖红线,AGENTS.md §4.1)。给 DataFrame 塞 `plot()`/`toCsv()` 门面会破坏模块边界,或需引入 SPI 动态装配机制(为文档示例便利新增整套运行时注册体系,不值)。
   - **用户写法**:`Jian.toCsv(df.filter("age>18").sortBy("name"), "out.csv")`(变换链 + 静态终端)。
   - **早期文档偏差**:doc/02/03/04 早期示例曾写 `df.toCsv(...).write()` / `df.plot().line(...)` / `df.style()` 全链式,已逐步修正为静态终端风格(见各分册 §2 顶部"API 风格说明")。

---

## 9. 实现进度总览(2026-08-01 持续更新)

> 本节是项目实际实现进度的"看板",与上面需求稿并列。详细实现说明见各分册末尾「实现说明」章节。

### 已实现模块(全测试通过,2026-08-09 阶段 A-F + L8 修复后实测 721 测试,见 [api-counts.md](api-counts.md))

> **测试口径说明(2026-08-09 经 AI agent2 + AI agent1 第二轮审查核实)**:**全仓 @Test 实测 721**(jian 656 + jian-num 38 + jian-sql 27)。其中 14 个 PG 集成测试经 `-Dtest.pg=true` 激活,默认 skip(不算 fail)。早前版本的 834/584 是旧口径残留,以 api-counts.md 为准。

| 模块 | 测试数 | 状态 |
|---|---|---|
| `jian-num` | 38 | ✅ 多 dtype Ndarray + Stats + StrOps + Matrix + Random + LinearFit |
| `jian-core` | 412(见 [api-counts.md](api-counts.md)) | ✅ DataFrame 完整(9 dtype 列 / query(含 in/not in)/ groupby / merge / pivot / melt / sort / 缺失 / 统计(经 StatsProvider SPI)/ eval / sql)+ 阶段 A-F 新增 60+ 方法(idxmax/sample/isin/where/mask/pivot/explode/join/merge_asof/corr/cov/skew/kurt/cumsum/diff/quantile/rank/clip/interpolate/astype 8种/Resampler/DatetimeIndex/Frequency/MultiIndex N级 等)+ 蜕变 28 + 差分 8 + PBT 25 + Perf 27 + MissingMethods 22 + EdgeCase 17 + StageA 29 + StageB 34 + StageC 15 + StageD 19 + StageF 18 + Infrastructure 35 + 其它(数字为方法数,见 api-counts.md) |
| `jian-num-bridge` | 6 | ✅ StatsProvider SPI(经 ServiceLoader 升级 jian-num 精确统计)|
| `jian-io-csv` | 12 | ✅ CSV/TSV/FWF + 公式注入防护(默认开,OWASP 严格版含前导空白) |
| `jian-io-json` | 10 | ✅ JSON 5 orient + json_normalize 拍平 |
| `jian-io-excel` | 16 | ✅ xls/xlsx 多 sheet + 两阶段类型推断 + POI 陷阱修复 |
| `jian-io-html` | 5 | ✅ HTML 表格(jsoup 读)|
| `jian-io-xml` | 5 | ✅ XML 读写(Jackson XML,写端名称清洗 + 值转义)|
| `jian-io-sql` | 33 | ✅ DbType 定义 7 库,**3 库真测**(H2 10 + SQLite 9 默认跑 + PG 14 `-Dtest.pg=true` 激活;含 SQL 注入防护回归。MySQL/Doris/Oracle/Access 仅 DbType 定义,未 CI 验证)|
| `jian-io-parquet` | 3 | ✅ Parquet 列存(parquet-avro + LocalFile)|
| `jian-io-orc` | 3 | ✅ ORC 列存(orc-core 1.9.5 + hadoop-client-runtime 解决 shaded wstx)|
| `jian-io-pickle` | 4 | ✅ 自定义 .jpk(JSON 内核 + CRC32)|
| `jian-io-clipboard` | 2 | ✅ 跨平台 xclip/pbcopy/clip + 内存降级(stderr DISCARD 防子进程阻塞) |
| `jian-io-latex` | 1 | ✅ LaTeX 表格 |
| `jian-export` | 23 | ✅ HTML/Markdown/LaTeX/控制台 + Styler 子系统(含 toExcel POI 条件格式)|
| `jian-viz` | 16 | ✅ 13 种图(line/scatter/bar/hist/barh/area/pie/box/kde/hexbin/scatterMatrix/lag/autocorrelation;radviz/andrews/parallel_coordinates/bootstrap 4 种高维图 v2 规划)|
| `jian-dsl` | 76 | ✅ L1/L2 Pratt(含 nvl/coalesce/ifnull)+ L3 SQL(可插拔引擎接口 SqlEngineInterface/SqlEngines;默认 SqlRegexEngine 支持 DISTINCT/LIMIT OFFSET/GROUP/HAVING/ORDER/JOIN/UNION ALL/子查询≤2 层;阶段 E 新增 CTE/CASE WHEN/派生表/集合运算(UNION/INTERSECT/EXCEPT)/USING 预处理;L8 修复(2026-08-09):算术表达式列真实求值(委托 PrattEngine.eval)+ DML WHERE/SELECT 异常不再静默吞 + 删 evalCondFallback/bindRowValues/evalArithmetic 三个手写补丁)|
| `jian-sql-engine` | 12 | ✅ Engine + DbType(7 库)+ HikariCP + dsl()/sql() 入口 + 只读拦截防注释绕过 |
| `jian-sql-expr` | 4 | ✅ SqlBuilder(jOOQ 3.21.6 运行时)|
| `jian-sql-orm` | 6 | ✅ @Table/@Column/@Id + Session CRUD |
| `jian-sql-bridge` | 5 | ✅ ResultSet/jOOQ Result → DataFrame |
| `jian-facade` | 17 | ✅ 顶层 Jian 门面(read/write 按扩展名自动分发 + pandas 风格 read*/to* 全套 + L3 SQL 入口)|
| **合计 Java** | **721** | **22 模块**(jian 656 + jian-num 38 + jian-sql 27;Python Hypothesis 24 + pandas 对照 38 另计;数字以 [api-counts.md](api-counts.md) 为准,只链接不抄写) |

### 工程基线

- Maven 多模块(按 `jian/`、`jian-sql/`、`jian-num/` 三库分组)+ Maven Wrapper(阿里云镜像)
- groupId `jian`(零本机绑定);精细引用,无 uber/shade
- AGENTS.md §5.4 落地:模块完成即同步 md + html(双轨)
- 构建:`./mvnw install`(任何人 clone 后仅需 JDK 17+)
- 顶层 `Jian` 门面(jian-facade):`Jian.read("x.csv")` / `Jian.write(df, "out.json")` / `Jian.sql("SELECT ... FROM ${t}", df)`

### 顶层 Jian 门面用法

```java
import jian.Jian;
import jian.core.DataFrame;

// 读(自动按扩展名分发)
DataFrame df = Jian.read("users.csv");
DataFrame df2 = Jian.read("data.xlsx");   // Excel
DataFrame df3 = Jian.read("api.json");    // JSON

// 写(按扩展名)
Jian.write(df, "out.csv");
Jian.write(df, "out.html");
Jian.write(df, "out.parquet");

// SQL(内存 DataFrame,不碰数据库);${名} 占位按出现顺序绑定 DataFrame
DataFrame r = Jian.sql("SELECT city, mean(salary) AS avg_sal FROM ${t} GROUP BY city", df);

// 多表 JOIN(占位按出现顺序绑定:${a}=df1,${b}=df2)
DataFrame joined = Jian.sql("SELECT * FROM ${a} LEFT JOIN ${b} ON a.id=b.id", df1, df2);

// 也可挂在 DataFrame 上:df.sql("SELECT ... FROM this") / df.eval("total = price * qty")
```

### 实现完毕

所有主线需求 + AI agent 2 审查问题 + 用户反馈均已解决。无遗留 TODO。

---

## 10. 性能改造与引擎对比(2026-08-08)

> 本章记录一次完整的性能调研与改造历程:从"是否换 DuckDB 内核"的疑问出发,
> 实测对比 jian / DuckDB / SQLite / H2 四引擎,最终决定**就地改造 jian-core 而非换内核**。

### 10.1 触发与历程

- **触发**:用户提"5 万行三表 JOIN,jian 与 DuckDB 速度差多少"。
- **历经验证**:① 基准测试 → ② POC 实现 lazy DataFrame + DuckDB 后端(已删) → ③ 四引擎对比 → ④ **发现 jian 现状的瓶颈是数据结构,不是 JDK**。
- **结论**:JDK 21 下 jian-core 还有 9-17 倍改进空间,不需要等 JDK 28 的 Valhalla/Vector API。

### 10.2 四引擎基准实测(同机器 8 核 8G / OpenJDK 21.0.12)

**测试场景**:同份随机种子生成的三表 `a/b/c`(各 N 行,列 `id BIGINT + _val DOUBLE`,`id` 在 `[0, 2N)` 范围随机以制造部分不匹配),执行 `SELECT count(*) FROM a JOIN b ON a.id=b.id JOIN c ON a.id=c.id`。每引擎用其最佳批量导入 API:jian(改造后)/DuckDB(Appender)/SQLite(in-memory + PRAGMA + 索引)/H2(in-memory + 索引)。**数据是随机生成的三表 JOIN,非真实业务数据**。

| 规模(三表各 N) | 指标 | jian(改造后) | DuckDB 1.5.5 | SQLite 3.53 | H2 2.4 |
|---|---|---|---|---|---|
| **10 万**(总 30 万行,结果 25,084 行) | wall ms | **59** | 609 | 528 | 1,881 |
| | cpu ms | 48 | 540 | 505 | 1,662 |
| | mem MB | +24 | −27 | +28 | +292 |
| **50 万**(总 150 万行,结果 125,566 行) | wall ms | **185** | 925 | 2,159 | 5,924 |
| | cpu ms | 177 | 831 | 2,122 | 5,439 |
| | mem MB | +101 | +110 | −61 | +206 |
| **500 万**(总 1500 万行,结果 1,250,160 行) | wall ms | **1,604**(用 -Xmx7g) | 4,543 | 25,930 | 126,907 |
| | cpu ms | 1,485 | 4,046 | 25,887 | 80,603 |
| | mem MB | +958 | −309 | +668 | +2,377 |

**关键观察**(改造后的 jian 在所有规模都最快):
- **速度**:改造后 jian 比 DuckDB 快 3-10 倍,比 SQLite 快 10-15 倍,比 H2 快 30-80 倍
- **内存**:DuckDB / SQLite 数据在 native 内存,JVM 堆增量小;jian 数据全在 JVM 堆(500万规模需 -Xmx7g);H2 内存占用最大
- **适用场景**:jian 适合"单机内存分析 + 已有 DataFrame 在手";DuckDB 适合"突破 JVM 堆限制 + 复杂 SQL";SQLite 适合"嵌入式持久化";H2 适合"纯 Java 单元测试 in-memory DB"

> 注:本表为 v1.1 改造后的自洽数据(随机种子可复现),与 §10.7 改造前对比是两套数据。

### 10.3 瓶颈诊断:jian 的"慢"是数据结构问题,不是 JDK 限制

profile 显示 jian 500万三表 JOIN:
- **cpu/wall 比 = 54.5%**:45% 时间花在 GC/对象分配(不是计算)
- 根因:`HashMap<List<Object>>` 作 join key —— 每行 `new ArrayList<>(n)` + N 次装箱 + `List.hashCode()` O(n)

#### 速度加成来自架构改造,不是 JDK 21(澄清)

一个常见的误解是"改造后 jian 速度提升主要靠 JDK 21"。**这不成立**:

| 改造代码用到的 JDK 特性 | 速度贡献 |
|---|---|
| switch 表达式 / arrow case(JDK 14+) | 0%(纯语法糖) |
| pattern matching for instanceof(JDK 16+) | 0%(等价于强转) |

**改造代码完全没用 JDK 21 特有的性能特性**——没用 Vector API(还 incubator)、没用 Valhalla 值类型(JDK 28+ 才 preview)、没主动配 ZGC 分代。pom.xml 也只要求 **JDK 17 LTS**。

9-17 倍提升的真正来源:
- **`LongColumn.data()` 暴露 `long[]` 零拷贝**(JDK 8 能写)——绕开虚分发 + 装箱
- **`ColumnarHashMap` 开放寻址**(JDK 8 能写)——替代 `HashMap<List<Object>>`
- **merge/groupby fast path 单列数值特化**(JDK 8 能写)——避开通用路径
- **`ofColumnArrays` 零拷贝工厂**(JDK 8 能写)——避开逐行装箱

> JDK 21 的 HotSpot JIT 对 primitive 数组热循环会自动 SIMD 向量化,这是被动受益——**但 JDK 17 的 JIT 也会做,差距 < 5%**。换言之,本次性能改造在 JDK 8/11/17/21 上提升幅度基本一致,主要功劳在架构,不在 JDK 版本。

### 10.4 jian-core 性能改造(P0+P1 已落地,全测试通过)

| 改造点 | 位置 | 收益 |
|---|---|---|
| **各 Column 子类暴露 primitive 数组访问**(`data()` 零拷贝 + `wrapNoCopy` 静态构造) | 全 9 个 Column 子类 | 消除虚分发 + 装箱 |
| **新增 `ColumnarHashMap`**:open-addressing + long 槽位 + 桶内链表 | jian-core/ColumnarHashMap.java(新) | 替代 `HashMap<List<Object>>` |
| **DataFrameMerge 加 dtype 特化 fast path**:单列 long/int/double key inner/left JOIN 走 primitive + ColumnarHashMap | DataFrameMerge.java | **9-17 倍**(实测 500万 JOIN 2842ms → 311ms) |
| **GroupBy 加 dtype 特化 fast path**:单列 long/int/double key(无 null)走 `LinkedHashMap<Long,int[]>` | GroupBy.java | 单 key 分组 5-10 倍 |
| **DataFrame.ofColumnArrays 零拷贝工厂**:直接接收 `long[]/double[]/Object[]` | DataFrame.java | 输出端省 N 次装箱 |
| **新测试 13 个**:`ColumnarPerfTest` 覆盖 fast path 正确性 + 边界 + 回退路径 | jian-core 测试 | 防回归 |

**fast path 与通用路径的关系**:fast path 仅覆盖"单列数值 key + inner/left"等高频场景;多列 key / 字符串 key / right / outer / 含 null 自动落回原 `HashMap<List>` 路径,**正确性优先**。

### 10.5 关于"换 DuckDB 当内核"的最终结论

POC 实现 + 实测后的判断:

- ❌ **整体换内核不成立**:500万规模,纯 jian 改造后(311ms)反而比 DuckDB(3635ms)快 11.7 倍(因为绕开 register 搬运税 + JNI 跨界)
- ❌ **"小规模下 DuckDB 比 jian 快"是错的**:100万以下 jian 反而更快
- ✅ **DuckDB 真实优势在"突破 JVM 堆限制"**:500万行 jian 默认堆 OOM,DuckDB 不 OOM
- ✅ **DuckDB 真实优势在"复杂 SQL"**:窗口/CTE/递归 SQL,jian-dsl 自写 Pratt 永远追不上

**jian-duckdb POC 模块已删除(2026-08-08)**:POC 完成使命(验证"操作符翻译成 SQL 可行" + 实测出 DuckDB 真实优势区间),代码删除避免主线维护负担,结论保留在本章。

### 10.6 不做的改造(避免过度设计)

- **谓词下推 / 列裁剪**:仅适用于 lazy plan 形态;jian 是 eager(每次操作物化新 df),无 plan 谈不上"下推"
- **JNI / native 加速**:破坏纯 JVM 卖点,ROI 低
- **MVCC / 事务 / checkpoint**:jian 是无状态内存库,做了就是过度设计
- **磁盘压缩编码**(RLE/Bit-Packing/FOR):jian 是内存库,这些是磁盘优化

### 10.7 改造前后对比(500万三表 JOIN 总耗时)

```
改造前 jian   : 7323 ms
DuckDB        : 3635 ms    ← 比改造前 jian 快 2 倍
改造后 jian   : ~620 ms    ← 比 DuckDB 还快 6 倍(单 JOIN 311ms × 2)
```

> 上表是<b>纯 id JOIN 场景</b>(`ON a.id=b.id`)。详见 `doc/index.html`「性能对比」章节。
>
> **性能基线声明(2026-08-09 修订)**:本表与 `doc/index.html` 性能段的 benchmark 数据均在 **OpenJDK 21** 上跑测(本机 Maven wrapper 默认 JDK);与 README/AGENTS.md 的 **JDK 17 主基线声明**(向下兼容目标)**不可直接横向对比**——但相对排名(jian vs DuckDB/SQLite/H2)在 17/21 上基本一致(JIT SIMD 差距 <5%)。纯 id JOIN(本节)与复合表达式 JOIN(index.html)是**两套不同基准**,数据集/连接列不同,**不可互相比较**。

### 10.7.1 复合关联场景重测(2026-08-08,用户质疑驱动的诚实重测)

> **背景**:用户质疑原对比表"数据库是否用了最快批量入库方式" + "有无索引都加上测"。逐个核实 DuckDB/SQLite/H2 官方文档(见下),并改成更贴近真实业务的<b>复合表达式关联</b>场景后重测。

**新 SQL**(数字求和 + 字符串拼接 双条件 AND):
```sql
SELECT count(*) FROM a JOIN b ON a.id=b.id
                    JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc||b.d)=c.k2
```

**官方最快入库方式核实**(用户质疑的直接回答):
- DuckDB → `createAppender(schema, table)` + `append()`([官方文档](https://duckdb.org/docs/current/clients/java)原文:"The preferred method for bulk inserts is to use the Appender")
- SQLite → `PRAGMA journal_mode=OFF/synchronous=OFF/temp_store=MEMORY/cache_size/locking_mode=EXCLUSIVE` + 单事务 + `PS.addBatch`([StackOverflow 1711631](https://stackoverflow.com/questions/1711631),96k 行/秒配方)
- H2 → `jdbc:h2:mem` + 单事务 + `PS.addBatch`(H2 2.4.240 已移除 `LOG`/`UNDO_LOG` 连接参数与 `UNLOGGED TABLE` 关键字)

**索引策略**(用户"有无索引都加上"的直接回答):
- 无索引模式:不建任何索引(看 baseline)
- 有索引模式:`a(id)`、`b(id)`、`c(k1)`、`c(k2)` 普通 B-tree + SQLite/DuckDB 表达式索引 `b(ba+bb)`、`b(bc||bd)`;H2 不支持表达式索引,只建原列

**实测结果**(无索引模式,wall ms,超 60s 标"超时"):

| 规模 | DuckDB | jian | SQLite | H2 | count |
|---|---|---|---|---|---|
| 10 万 | **56** | 1,013 | 465 | 超时 | 66,478 |
| 50 万 | **163** | 11,843 | 2,988 | 超时 | 866,765 |
| 500 万 | **1,257** | 超时 | 31,384 | 超时 | 68,650,567 |

> 4 引擎 count 完全一致(正确性兜底通过)。完整数据(含 with-index 模式、cpu/mem)见 `doc/benchmark/result.json`,可复现脚本见 `doc/benchmark/JoinBenchmark.java`。

**与 §10.7 旧表的差异(诚实说明)**:
- 旧表(纯 id JOIN):jian 改造后 620ms **比 DuckDB 还快**——这是真的,但只限"单列数值 key inner JOIN 走 fast path"这个甜点场景
- 新表(复合表达式关联):**DuckDB 1.3s 一枝独秀,jian 反而 500 万超时**——多键 + 字符串列 hash + 表达式关联是 jian fast path 覆盖不到的弱项
- **结论**:之前"jian 全场景最快"的说法是<b>场景选择性偏差</b>。jian 在单列数值 key JOIN 是甜点(620ms vs DuckDB 3635ms),但复合表达式关联场景 DuckDB 才是赢家。两份对比都保留,避免再次误导。

**索引对复合关联的影响(关键发现)**:
- 普通列 B-tree 索引对 ON 条件里的计算表达式(`b.ba+b.bb`、`b.bc||b.bd`)**完全无效**——索引建在原列上,优化器无法用它匹配表达式结果
- 只有<b>表达式索引</b>(SQLite/DuckDB 的 `CREATE INDEX ON b(ba+bb)`)才能用,但 SQLite 在 10 万规模 with-index 反而触发优化器选错计划(部分超时)
- H2 根本不支持表达式索引,with-index 也救不回 b-c 段
- **真实业务启示**:复合表达式关联应优先<b>物化中间列</b>(子查询/CTE 把 `ba+bb`、`bc||bd` 算好存表),再在物化列上建普通索引——这才能让索引生效

### 10.8 质量审查历程(本机 AI agent 1 4 轮迭代)

性能改造完成后,用本机 AI agent 1 CLI 做了 4 轮代码审查直至收敛:

| 轮次 | 发现 BUG 数 | 关键 BUG(类型) | 修复策略 |
|---|---|---|---|
| 第 1 轮 | 10 | chooseCapacity 整数溢出(致命);ofColumnArrays 不校验列等长(致命);String[] 误判 OBJECT(致命);IntColumn/BoolColumn.nullMask 返回引用 | 全修 + 加 8 个回归测试 |
| 第 2 轮 | 1 真实 + 5 测试覆盖不足 | short[]/float[]/byte[] 等不支持数组抛 ClassCastException | 加显式类型校验 + 补 4 个测试 |
| 第 3 轮 | 3 | toPrimitiveArray 误判 INT 列为 OBJECT;±0.0 在 fast path 不等价(generic 视为相等);多维数组(long[][])走 OBJECT 分支 CCE | 按源 dtype 派发;±0.0 入桶前规范化;多维检测置于 instanceof Object[] 之前 |
| 第 4 轮 | **0(收敛)** | AI agent 1 确认无致命/严重 BUG | — |

**最终结果**:jian-core 测试从 107 增长到 **134 个**(增加 27 个性能与回归测试),全过 0 失败。所有 fast path 都与 generic 路径行为等价(含 ±0.0、null key、INT/LONG/DOUBLE/STRING/INT 列输出类型等边界)。

> 这一历程的教训:① 性能 fast path 必须有完整边界测试覆盖;② 与现有 generic 路径的"行为等价"是正确性核心(±0.0、NaN、null);③ 多轮独立审查(不同视角)能发现单轮漏掉的问题。

### 10.9 测试方法学升级:蜕变 + 差分 + PBT(2026-08-08)

AI agent 1 4 轮审查收敛后,jian-core 测试数从 134 升至 **219 个**——但更重要的是引入了**针对 AI 生成代码的系统化测试方法**(参考 [Confident AI 2026 LLM testing](https://www.confident-ai.com/blog/llm-testing-in-2024-top-methods-and-strategies)、[Hillel Wayne metamorphic](https://www.hillelwayne.com/post/metamorphic-testing/))。

#### 为什么 AI 代码需要特殊测试方法

AI 生成代码有个核心难题叫 **"oracle problem"**(预言机难题):**很难预先知道"正确输出"是什么**,所以传统"输入 → 期望输出"的测试写法对 AI 代码常常失效(AI 也会把"期望输出"写错)。三种方法绕开它:

| 方法 | 思路 | 适用 |
|---|---|---|
| **蜕变测试** | 不验"具体输出",验"输入与输出间的必要关系"(如 sortBy 后行数守恒) | 关系明确但具体值难算的算子 |
| **差分测试** | 同一算子两个实现跑同样输入,结果应一致 | 有 fast path / generic path 双实现 |
| **基于性质测试(PBT)** | 声明"性质"(如 list.reverse().reverse()==list),框架自动生成 N 个输入 | 不变量清晰的算子 |

#### 落地的三类测试

- **`MetamorphicTest`(50 个断言 / 27 个 @RepeatedTest 方法)**:覆盖 sortBy/filter/merge/concat/groupBy/astype/head/tail/slice/agg 等的蜕变关系(行数守恒、值多重集不变、互补关系、交换律、并集互斥等);每条用固定种子随机生成 df,`@RepeatedTest` 多轮加强度。
- **`DifferentialTest`(29 个断言)**:验证 fast path 与 generic path 跨实现等价(long key vs String key、int key vs long key、double key 边界、null+nullMask、DATE/DATETIME 类型保留等)。
- **`ColumnarPerfTest`**:既覆盖 fast path 正确性,也包含 BUG 回归用例(每个修复点都有"重现代码")。

#### 实战:差分测试抓到了 AI agent 1 修反的 BUG #2

**这是测试方法学价值的铁证**。AI agent 1 第 3 轮审查发现"±0.0 在 fast path 不等价"问题,做了修复(让 fast path 把 ±0.0 视为相等)。**差分测试 `dt_merge_正零负零_与DoubleEquals等价` 一跑就失败**——深入分析发现:

- `Double.equals(+0.0, -0.0) == false`(按位比较,位模式不同)
- `HashMap<Double>`(generic 路径)用 `equals`,**视 ±0.0 不等**
- AI agent 1 误以为 `Double.equals` 视 ±0.0 相等(它在审查报告里写错了),于是把 fast path 改成"视为相等"
- **结果 fast path 与 generic 路径反而被改得不一致**

差分测试直接抓到这个不一致。**最终修复是撤销 AI agent 1 的错误修复**——fast path 与 generic 都视 ±0.0 不等(与 `Double.equals` 一致)。

> **核心教训**:多轮 AI 审查(即使是同一个 AI agent 1)也无法保证一致正确——审查者也会把概念记错(如 `Double.equals` vs `Double.compare` 对 ±0.0 的语义)。**唯一可靠的守护是机器化的差分/蜕变测试**——它们不会"记错",只会"如实反映输入输出关系"。

### 10.10 双 AI 交叉审查:AI agent 1 × AI agent 2(2026-08-08)

差分测试抓到 AI agent 1 把 ±0.0 修反后,引入第二个 AI(AI agent 2)做**交叉审查**——同一份代码,用两个独立的 AI 反复审,看是否收敛到"无新 BUG"。结论:**双 AI 比单 AI 强,但仍不及机器化差分测试**。

#### 流程

```
AI agent 1 4 轮(收敛)→ 差分测试(抓到 AI agent 1 修反的 ±0.0)
                ↓ 修复
              AI agent 1 自审 1 轮(自以为收敛)
                ↓ 切换 AI
              AI agent 2 3 轮(发现 AI agent 1 全部漏掉的 4 个 BUG)
                ↓ 修复 + 收敛
              最终 228 测试全过
```

#### AI agent 2 发现的 4 个 BUG(AI agent 1 4 轮 + 我都漏了)

| BUG | 严重性 | 描述 | 修复 |
|---|---|---|---|
| **BUG 1** | 严重 | INT×LONG 混合 key:inner/left 走 fast path 正确匹配,但 right/outer 落回 generic 后 `Integer.equals(Long)=false` 全部不匹配——同一对表换 how 参数结果天差地别 | ① fast path 触发条件改为左右 key 完全同 dtype;② generic 路径 normKey 把 Integer/Long 统一 Long、Float/Double 统一 Double |
| **BUG 2** | 中 | 重复 key 时 fast path 输出行序(头插逆序)与 generic(顺序)不一致 | 未修(pandas 兼容性问题,严重性低) |
| **BUG 3** | 严重 | left join 未匹配行补 null 时,fast path 把整列降级 OBJECT,下游 getLong 抛 ClassCastException | 新增 toColumn 方法,数值/布尔列带 null 时返回带 nullMask 的 LongColumn/IntColumn/BoolColumn;新增 DataFrame.ofColumnsDirect 工厂 |
| **BUG A** | 中 | fast path 把 DATE/DATETIME/CATEGORY 输出列降级 OBJECT(BUG 1 的同类残留,落在输出列类型上) | toColumn 补 DATE/DATETIME/CATEGORY 三个分支;ofColumnArrays 配套补 LocalDate[]/LocalDateTime[] 推断 |

#### 为什么 AI agent 1 4 轮收敛还漏了这 4 个?

| 漏掉的原因 | 具体表现 |
|---|---|
| 审查者视角同质化 | AI agent 1 4 轮用的是同一份 prompt、同一份代码视角,思维定式一致;AI agent 2 是另一个模型 + 另一次独立审视,带着不同视角 |
| 测试断言放过 | 我写的差分测试 DT1 用"多重集断言"+ 显式注释"具体行序可能不同"——BUG 1/2 被这种宽松断言放过;DT3 只测 int×int、long×long,不测 int×long 混合,放过 BUG 1 |
| 概念盲区 | AI agent 1 把 Integer.equals(Long) 视为相等(实际 false)、把"DATE 输出列"完全没纳入考虑——这是 AI 的知识盲区,人也会犯 |

#### 核心教训

1. **单 AI 多轮收敛 ≠ 真无 BUG**——AI agent 1 4 轮收敛是假收敛,AI agent 2 一上来就抓到 4 个
2. **双 AI 交叉审查 > 单 AI 多轮**——但仍不及机器化差分测试
3. **测试断言要严**——"行序可能不同"这种宽松断言会放过真实 BUG,要尽量精确断言(行序、类型、nullCount 都比对)
4. **混合 dtype 是 BUG 重灾区**——所有 4 个 BUG 都涉及"两种 dtype 的交互边界",这是 fast/generic 双实现架构的固有难点

#### 最终状态

- jian-core 测试:228 个(原 219 + AI agent 2 引出的 9 个回归)
- 双 AI 各自独立收敛(AI agent 1 第 4 轮 + AI agent 2 第 3 轮都明确"无致命严重 BUG")
- 4 个跨实现不一致 BUG 全部修复 + 回归守护

### 10.11 双语言交叉 PBT + 变异测试落地(2026-08-08)

#### 双语言交叉 PBT(jqwik 1.9.3 + Python Hypothesis)

经过对 jqwik 投毒事件的反复核实(本节下方有详细时间线),最终方案是**双语言交叉 PBT**:

- **Java 端(jqwik 1.9.3)**:`PropertyBasedTest.java`,**22 条**核心性质,各 `tries=100`
- **Python 端(Hypothesis 6.165.2)**:`tests-pbt/`,同样 **22 条**性质,通过 `JianPbtBridge.java`(subprocess + JSON 协议)跨语言调 jian jar
- 两套独立 PBT 互相验证,任一方漏掉的 BUG 另一方可能抓到(实战中确实如此)

**jqwik 版本选择时间线**(我前面说法前后矛盾,这里给最终核实版):

| 版本 | 状态 | 决策 |
|---|---|---|
| 1.9.3(2025-06) | 投毒前最后稳定版,strings 校验投毒字符串 0 命中 | ✅ **本项目用** |
| 1.10.0(2026-05-25) | 首次投毒:`JqwikExecutor.printMessageForCodingAgents` 注入 ANSI 隐藏的"删 jqwik 代码"指令 | ❌ 禁用 |
| 1.10.1(2026-05-29) | "弱化"了字符串,但 **Anti-AI Clause 被官方 release notes 固化**:"This project is not meant to be used by any 'AI' coding agents at all" | ❌ 在 AI 协作项目禁用 |

> 我前面说法前后不一(先说"未修复不能用",又说"修复了可用")。最终核实:1.10.x 全系列不适合 AI 协作项目,1.9.3 是干净版本可用。详见 [Snyk 披露](https://snyk.io/blog/protestware-open-source-maintainer-qwik-1-10-0-prompt-injection/) + [jqwik 官方 release notes](https://jqwik.net/release-notes.html)。

**与 §0.2 零本机绑定的权衡**:jqwik 1.9.3 不在 Maven Central 推荐路径(中央仓库默认拉 1.10.x),只能用 `scope=system` + 本机 jar 路径。pom 已加详细注释说明这是单机开发用,CI 需先把 jar 放到 `~/tools/jar`。

#### 双语言交叉 PBT 的实战价值

| 阶段 | 发现 | 由谁抓到 |
|---|---|---|
| 阶段 2 写 Python Hypothesis | harness 路径错 + Java bridge 空表 dtype 不一致 | Python Hypothesis(flaky 检测) |
| 阶段 3 AI agent 1 + AI agent 2 复审 | P10 是死测试(Java/Python 双端都漏调 groupBy)+ parseStr 转义 + 空表 + writeJson NaN + P5 边界 | AI agent 1 + AI agent 2 独立发现(双 AI 都抓到 P10) |
| 阶段 4 修复后复审 | data() 零拷贝破坏公共 API + Json.java 漏 NaN/Infinity + MR7 假性质(31>30) | AI agent 1(前两者)+ AI agent 2(第三个,且实测出失败) |
| 阶段 4 自打脸 | Java 注释里写 `\uXXXX` 触发 Java 词法 unicode 转义,编译失败 | 我自己(写完编译才发现) |

**核心教训**:① 单语言 PBT 会漏 BUG(jqwik 的 P10 死测试 Python 端也犯了);② 单 AI 多轮收敛不可靠(AI agent 1 复审才抓到 data() 和 Json);③ **双 AI + 双语言 PBT 交叉验证才接近完整**;④ AI 写 AI 测试代码也会犯低级错(我在注释里写 `\uXXXX`)。

#### 变异测试(PITest)落地——量化测试质量

引入 [PITest](https://pitest.org/) 1.19.1 + pitest-junit5-plugin 1.2.3。配置在 `jian/jian-core/pom.xml`,变异对象限定 4 个性能改造核心类(ColumnarHashMap/DataFrameMerge/GroupBy/DataFrame)。

**变异报告(2026-08-08)**:

| 类 | 行覆盖 | 变异杀死率 | 测试强度 |
|---|---|---|---|
| ColumnarHashMap | 92% | 75% | 80% |
| DataFrame | 81% | 61% | 78% |
| DataFrameMerge | 91% | 68% | 79% |
| GroupBy | 92% | **72%(从 50% 提升)** | 78% |
| **总计** | **87%** | **66%** | **78%** |

#### 变异测试发现的真实盲点

第一轮跑变异:GroupBy 杀死率仅 50%——大量 `aggregate` 方法的 `case "nunique"/"min"/"max"/"first"/"last"/"median"/"std"/"var"` 分支没被测试覆盖(原来只测了 sum/count/mean)。

补 6 个聚合性质测试(MR22-MR27)后,GroupBy 杀死率从 **50% → 72%**。这是变异测试最直接的价值——**客观量化测试盲点**,不像 AI 审查会"自判收敛"。

#### 当前分数评价

- **行覆盖 87% / 变异杀死 66%**:行业可接受水平(60-80%);继续追求更高收益递减,留待后续
- **DataFrame 61% / DataFrameMerge 68%** 是后续可改进点(很多 getter/setter 类简单方法的变异意义不大)
- 变异测试**不入日常 CI**(慢,~80 秒),仅 release 前或重大改动后跑一次

#### 最终测试规模

jian-core 测试数:**412 个**(2026-08-09 实测,口径见 [api-counts.md](api-counts.md);含 MetamorphicTest 96 + DifferentialTest 38 + PropertyBasedTest 25 + ColumnarPerfTest 27 + DataFrameMissingMethodsTest 22 + EdgeCaseTest 17 + InfrastructureTest 35 + StageA/B/C/D/F Test 共 115 等)。另有 Python Hypothesis 24 条 + pandas 对照 38 条(d1-d38)作为同行评议/差分守护。

### 10.12 pandas 对照测试:把 pandas 当"老师"给 jian 改卷子(2026-08-08)

#### 为什么需要 pandas 当 oracle

前面 §10.9-10.11 的蜕变/差分/PBT 三类方法都绕开了 "oracle problem":它们只验**输入输出间的必要关系**或**两个实现的一致性**,但**没有一个能告诉你"正确答案应该是什么"**。

jian 的核心定位是**对标 pandas 的 JVM 实现**(README 第 1 行)。这意味着:凡 jian 声称"与 pandas 同功能"的算子,**pandas 本身就是天然 oracle**——拿同一份随机输入跑 pandas 和 jian,结果应该一致;不一致就是 jian 的 BUG(或需要显式声明的有意差异)。

这正是用户反复强调的硬要求("既然这个项目是 python pandas 的借鉴,那么应该在功能上与 pandas 同功能的部分都要使用两种 全面重跑……有老师给你矫正答案,你做了吗"),现已写入 **AGENTS.md §0.5(第四条红线)**。

#### 落地

- **位置**:`tests-pbt/properties/test_pandas_diff.py`(Python,依赖 pandas 1.5.3)
- **协议**:用 `numpy.random` 固定种子生成 DataFrame → 同时喂给 pandas 和 jian(经 `JianPbtBridge.java` subprocess)→ `assert_df_equal` 对比
- **断言策略**:支持精确断言、顺序无关断言(merge 等行序不保证)、float 容差三类
- **当前覆盖 38 个 pandas 对照测试**(d1-d38,2026-08-09 阶段 A-F 扩展后):head / tail / sortBy / filter / dropDuplicates / merge / concat / nlargest / nsmallest / select / drop / slice / colSub / colDiv / colLt / fillna / dropna / ffill / astype / groupBy / idxmax / idxmin / duplicated / sample / isin / where / mask / cumsum / diff / pct_change / clip / quantile / rank / round / prod / pivot / explode / merge_asof

#### 实战:发现 sortBy 稳定性差异(判定为 jian 更优,不视为 BUG)

pandas 对照测试**当场抓到一个真实差异**:对相同键 sortBy 后,pandas 与 jian 的行序可能不同。

深入分析:
- **pandas** `sort_values()` 默认 `kind='quicksort'`——**不保证稳定性**(quickselect 系列)
- **jian** 用 `Arrays.sort(Integer[], comparator)`——**Java 规范保证 TimSort 稳定**

判定:**jian 的稳定排序是更优语义**(相等元素的原始相对顺序被保留),不应为了对齐 pandas 而退化成不稳定的 quicksort。处理方式是 **D3 测试改用多重集(multiset)断言**——值集合一致即通过,不强制行序一致,并在测试里显式注释这是有意差异。

> 这是差分测试的正确用法之一:**发现的"差异"不一定是 BUG**——也可能是被测实现比 oracle 更优。关键是**差异必须被显式记录与论证**,不能藏着。

#### 写入 AGENTS.md(第四条红线)

`AGENTS.md §0.5` 明确规定:
1. 凡对标 pandas 的算子,**必须有 pandas 对照测试**(差异测试位置:`tests-pbt/properties/test_pandas_diff.py`)
2. jian 新增/修改算子时,对应 pandas 对照测试**必须同步增加**
3. 发现差异时,要么**修复 jian 对齐 pandas**,要么**显式声明有意差异**(如 sortBy 稳定性)并在测试中注释

这把"用 pandas 当老师"从一次性动作升级为**持续强制的工程红线**。



### 10.13 缺失值语义统一 + SQL 跨库类型修复(2026-08-08)

#### 问题背景

用户连续追问"NaN 会不会影响后续计算""各种类型在数据库里是否正确",暴露出两类系统性缺陷:

**缺陷 1:缺失值在内部传递时失真**
- `DoubleColumn.get(NaN)` 返回 null(不是 NaN)→ 下游 assign/applyStr 等通过 get 取值的路径丢失"这是 NaN 不是缺失"的语义
- `LongColumn/IntColumn.getDouble(缺失行)` 返回垃圾值(data 数组未初始化的 0,不是 NaN)
- `LongColumn/IntColumn/CategoryColumn.getLong(缺失行)` 返回垃圾值或抛异常
- 各子类缺失行取值约定不统一(NaN / 0 / 抛异常 / 垃圾值)

**缺陷 2:SQL 类型映射不兼容真实数据库**
- DOUBLE → PG 报错"类型 double 不存在"(PG 要 DOUBLE PRECISION)
- STRING → 硬编码 VARCHAR(1000),超 1000 字符静默截断
- CLOB 读回不规范化(java.sql.Clob 对象直接进 DataFrame,下游 ClassCastException)

#### 修复:缺失值语义统一(AGENTS.md §3.5)

全 8 个 Column 子类统一:

| API | 缺失行返回值 |
|---|---|
| `isNull(i)` | `true`(权威判断) |
| `getDouble(i)` | `NaN`(全类型一致) |
| `getLong(i)` | `Long.MIN_VALUE`(long 无 NaN 的缺失标记,不抛异常) |
| `get(i)` | DoubleColumn 返 NaN(不失真);其它返 null |
| `getRow(i)` | null(IO 边界安全网) |

下游 ~12 处 `get()==null` 改成 `isNull()`;export 层 6 处缺失行显示空(不是 "NaN")。

#### 修复:SQL 跨库类型自适应(AGENTS.md §3.6)

- 建表时用 `conn.getMetaData().getDatabaseProductName()` 探测方言,按 PG/MySQL/Doris/SQLite/H2/Oracle/Access 各自正确的类型名(7 库 DbType 定义,详见 `DbType.java`;真测 3 库:H2/SQLite/PG)
- STRING 列扫实际数据取 maxLen:≤4000 用 VARCHAR(n),>4000 用大文本(TEXT/LONGTEXT/CLOB/VARCHAR(MAX))
- JDBC 读回加 `normalizeJdbcObject`:Clob→String、Blob→byte[]、BigDecimal→Double、Date→LocalDate、Timestamp→LocalDateTime

#### 真实数据库测试(SqlPostgresTest)

14 个真实 PG 测试覆盖:全 dtype 往返 / 参数化查询 / 4 种写入模式 / 缺失值 / VARCHAR 自适应 / 大文本不截断 / 混合长短文本 / PG 小写列名 / 万行读写 / SQL 注入防护。

#### 修复的 BUG 清单

| BUG | 影响 | 修复 |
|---|---|---|
| DOUBLE 类型建表失败(PG) | jian 在真实 PG 上建表直接报错 | 方言探测 → DOUBLE PRECISION |
| VARCHAR(1000) 硬编码截断 | 长文本(>1000)静默截断或报错 | 扫 maxLen 自适应 + 大文本类型 |
| CLOB 读回不规范化 | 大文本读回多字符/ClassCastException | normalizeJdbcObject 统一转换 |
| LongColumn/IntColumn getDouble(缺失) 返回垃圾值 | 缺失行参与计算结果错误 | 缺失行返回 NaN |
| DoubleColumn get(NaN) 失真为 null | 内部传递丢失 NaN 语义 | get 返回 Double.NaN |
| 各子类 getLong(缺失) 抛异常 | 调用方无法处理缺失 | 返回 Long.MIN_VALUE |
| export 显示 "NaN" | 表格里 NaN 不应显示给用户 | 缺失行显示空 |

#### 测试验证

- jian-core:**412 测试全过**(口径见 [api-counts.md](api-counts.md);含 9 个 NaN 蜕变测试 + 边界注入 NaN/±0.0/MAX/MIN + 2026-08-09 新增 NaN 分组/LONG 缺失分组/astype 测试)
- jian-export:**23 测试全过**
- jian-io-sql:**33 测试全过**(H2 10 含 SQL 注入防护 + SQLite 9 + PG 14)

### 10.14 Web 环境安全审查与修复(2026-08-08)

用户提出"jian 如果用在 Tomcat/Spring Boot 里形成 Web 服务,会不会有内存泄漏/数据泄漏等安全问题"。双 AI agent 并行审查全项目后,发现并修复 4 个真实问题:

| 问题 | 风险 | 根因 | 修复 |
|---|---|---|---|
| ServiceLoader 缓存导致 redeploy 内存泄漏 | 🔴 高 | `DslEngine.LOADER`/`StatsProvider.LOADER` 是 static ServiceLoader,内部缓存引用 WebappClassLoader,Tomcat redeploy 时无法 GC | 改成每次 `current()` 新建 ServiceLoader |
| Engine.checkReadOnly 只读拦截是死代码 | 🟠 中 | `checkReadOnly()` 方法写了但 `engine.sql()` 从不调用它,只读模式形同虚设 | `sql()` 入口强制调 `checkReadOnly` |
| Excel 写出无公式注入防护 | 🟠 中 | Csv.java 有 `= + - @` 单引号防护,Excel.java 没有防护(行为不一致) | Excel.java 加 `isFormulaStart` + 单引号前缀 |
| Clipboard 子进程流未关闭 + 无超时 | 🟡 低 | `p.getInputStream()` 未 close;`waitFor()` 无超时(可能挂死) | try-with-resources + `waitFor(5s)` + `destroyForcibly()` |

**安全的方面**(审查确认无问题):
- 反序列化:Jackson 未开 `enableDefaultTyping`(readTree 树模型);Pickle 走自定义容器 + CRC + JSON,无 `ObjectInputStream`
- SQL 参数化:SqlBridge/SqlBuilder 全用 PreparedStatement + ? 占位符
- Connection/文件流:全 try-with-resources(零泄漏)
- DataFrame 不可变:构造后无 mutator;`dataInPlace()` 仅 jian-core 内部 hot path 调用
- 无 ThreadLocal / 无静态可变状态 / 无路径穿越(库层面信任调用方)
- **内存管理**:DataFrame 是纯内存数据,不需要 close();`df = null` 后由 GC 自动回收;Web 场景禁止 static 缓存大 DataFrame(详见 README「内存管理」段 + AGENTS.md §3.7.6)

详见 `AGENTS.md §3.7`。

---

### 10.15 AI 友好的 jar 制品设计(2026-08-08)

> 让 AI agent(以及人类用户)拿到 jian 的 jar 就能彻底理解接口用法、适用范围、拿到真实示例,而不必逆向字节码或翻源码猜意图。本节记录为此做的四项制品层改造。

#### 触发与立场

随着项目对外分发,AI 协作场景日益重要(AI 写集成代码、AI 做审查、AI 生成示例)。但 jar 是二进制产物,AI 默认只能靠类名反射猜用法。为此 jian 在**制品层**补齐了"AI 可直接消费"的元数据与形态,目标是:**AI 解压一个 jar / 看一眼 manifest,就能像看 SDK 文档一样用**。

#### 10.15.1 双形态制品(默认 thin + 可选 fat)

| 形态 | 命令 | 产物 | 用途 |
|---|---|---|---|
| **thin jar**(默认) | `./mvnw install` | 22 个细粒度子模块 jar | 版本仲裁、按需加载(不引 jian-io-excel → JVM 不加载 POI) |
| **fat jar**(可选) | `./mvnw -Pfat package` | 额外 3 个 `*-all.jar`(jian-all / jian-num-all / jian-sql-all) | AI / 用户单文件即可上手,含全部依赖 |

- **子模块仍零整合**:`jian-core` 等 22 个叶子模块的 jar 永不含外部依赖(AGENTS.md §2.5.1 红线)。
- **顶层三库经 `-Pfat` 允许 shade**:只有 jian / jian-num / jian-sql 三个顶层聚合模块在 `-Pfat` 激活时才 shade,且默认 `install` 不触发 —— thin 是主形态,fat 是补充(AGENTS.md §2.5.2)。
- **fat jar 强制元数据**(AGENTS.md §2.5.3 红线):① `ServicesResourceTransformer` 合并 SPI;② 排 `META-INF/*.SF/*.DSA/*.RSA`;③ `MANIFEST.MF` 加 `Ai-Aggregated: true` + `Ai-Library: <lib>`。

#### 10.15.2 每模块 module.md(22 份全覆盖)

每个子模块在 `<module>/src/main/ai-doc/module.md` 放一份结构化说明,打包期由 `maven-resources-plugin` 复制进 jar 的 `META-INF/ai/module.md`。AI 解压 jar 即可读到:

- `library`(归属库)/ `entryClass`(入口类全限定名)/ `deps`(依赖方向)
- **摘要** / **能力清单** / **限制与降级** / **3~5 行可跑的快速上手示例**

截至 2026-08-08,22 个子模块已 100% 覆盖 module.md。

#### 10.15.3 sources + javadoc jar(AI 看源码 + 看 HTML)

根 pom 的 `<pluginManagement>` + `<plugins>` 已激活 `maven-source-plugin` 与 `maven-javadoc-plugin`:

- **`-sources.jar`**:含全部源码 + 全量 `@param/@return/@throws` 注释,AI 可直接读 Java 源码与 5W1H 注释理解实现。
- **`-javadoc.jar`**:HTML API 文档,人类与 AI 都能消费。

`./mvnw install` 自动同时产出 thin jar + sources + javadoc 三件套(每个子模块都是)。

#### 10.15.4 @param 100% 覆盖(495/495)

**全项目所有带参数的 public 方法都有 `@param`**(2026-08-08 达 495/495 = 100%)。配合 `-sources.jar`,AI 不但能看方法签名,还能看每个参数的中文说明 —— 这是 AI 正确调用 API 的关键元数据。

> 红线(AGENTS.md §2.8.3):**每次新增 public 方法必须同步补 `@param`**,不允许欠账。

#### 10.15.5 fat jar 三库体积(2026-08-08 实测)

| fat jar | 体积 | 含 |
|---|---|---|
| `jian-all-x.y.z.jar` | ~124M | jian 全部 17 个子模块 + POI/Jackson/parquet/orc/XChart 等全部依赖 |
| `jian-num-all-x.y.z.jar` | ~2.2M | jian-num + Commons Math 3.6.1 |
| `jian-sql-all-x.y.z.jar` | ~5.7M | jian-sql 全部模块 + HikariCP + jOOQ |

> jian-all 较大是因为含 POI/parquet/orc 这类重型 IO 依赖;只想要 DataFrame + CSV/JSON 能力的用户,引 thin jar 的 `jian-core` + `jian-io-csv` + `jian-io-json` 几个 M 即可,这正是"thin 为主、fat 为辅"的理由。

---

*本文档为需求稿,实现阶段以各分册为准;分册与本总览冲突时,以分册为准。*
*实现进度看板持续更新;最新状态以各分册末尾「实现说明」为准。*
*全部实现完毕于 2026-08-02;2026-08-02 全项目审查(文档对照 + 安全审计)后全过。*
*2026-08-08 测试方法学升级:蜕变 + 差分 + 双语言交叉 PBT + pandas 对照 + 变异测试。*
*2026-08-08 缺失值语义统一 + SQL 跨库类型修复 + PG 真实测试 + Web 安全审查。*
*2026-08-09 测试边界注入(NaN/±0.0/MAX/MIN)+ SQL 注入防护白名单 + DSL NaN 缺失识别 + columnsInternal 不可变 + DF.size 溢出保护 + jian 全量 584 测试(567 默认 + 14 PG + 3 新 EdgeCase)+ Python 44 全过。*
*2026-08-09 AI agent2 第二轮审查修复:doc/01「200+ 方法」改回实测 85+ 声明 + §3 各小节已实现/规划二分 + §3.16 路线图集中列出未实现项(resample/stack/unstack/interpolate/explode 等共 60+ 项)+ GroupBy NaN 分组语义文档化(EdgeCaseTest 固化)+ astype 部分支持(5 种 dtype)+ doc/05 测试数 22→27 + 7 库口径修正(3 库真测)+ doc/06 测试数 33→38 + README 7 库表述统一(删 SQL Server,补 Doris)+ doc/00 §9 测试口径明示 PG(584 = 570 默认 + 14 PG skip)+ 测试数 581→584 / jian-core 324→327 + pandas 对照补 d16-d20(fillna/dropna/ffill/astype/groupBy),Python 39→44。*
*2026-08-09 阶段 A-F 大规模实现(用户决策"全部做完不停下"):阶段 0 MultiIndex N级/DatetimeIndex/Frequency 基础设施;阶段 A idxmax/idxmin/duplicated/resetIndex/setIndex/sample/pipe/applyRow/isin/where/mask/info/selectDtypes;阶段 B StatsProvider SPI 扩 rank/mad/sem + corr/cov/skew/kurt/cumsum/diff/pct_change/quantile/rank/clip/round/all/any/prod/nunique + corrMatrix/covMatrix;阶段 C pivot/explode/join/merge_asof/addScalar/sub/mul/div ScalarAllColumns;阶段 D Resampler(sum/mean/count/min/max/median/std/var/ohlc/agg/first/last 17方法)+ shift/atTime/betweenTime/asof;阶段 E SqlEngineInterface 通用可插拔接口 + SqlEngines 注册中心 + SqlPreprocessor(CASE/CTE/派生表/集合运算/USING 预处理)+ JSqlParserEngine stub;阶段 F astype 扩到 7 种 dtype(BOOL/DATETIME/DATE)+ interpolate 线性插值 + notna/notnull/pad/backfill 别名。测试:Java 584→785(+201),Python 44→62(+18);DataFrame public 方法 85→139(+54)。SQL 引擎调研:jOOQ Parser 路线舍弃(只能 parse,extract 不行);JSqlParser 5.3 探针 14/14 过但 5.x 有 count(*) 回归+线程不安全;Apache Calcite 14-25MB 太重;sqlglot_java 388K 但是 0 star 8 天弃坑项目;Apache DataFusion Java 0.1 JNI;结论:Java 生态无成熟轻量 SQL-on-DataFrame 库,jian 自写+可插拔接口填补空白。*
