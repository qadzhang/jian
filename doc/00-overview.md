# jian / jian-sql / jian-num 需求说明书 · 总览

> 版本:v1.0.1(发布版,与 pom.xml `<version>` 同步) · 日期:2026-08-16
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
| `jian-io-parquet` | Parquet 读写(**默认不构建**,`-Pcolumnar` 激活;~45M Hadoop 依赖见 doc/02 §9.7) | core | parquet-avro |
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
| jian-core | 3,500 | 3,500 | **7,000** | DataFrame 主体 195 public 方法(实测,见 api-counts.md)+ Series 52 + GroupBy + Window + Resampler 18 方法(经源码核实;Resampler 已实现,见 §9) |
| jian-io(12 格式 + 7 数据库) | 7,000 | 4,000 | **11,000** | CSV/Excel/JSON/HTML/XML/SQL/Parquet/ORC/Pickle/Clipboard/LaTeX/Markdown |
| jian-viz(13 种图) | 2,500 | 1,000 | **3,500** | 10 plot + 3 plotting(高维图 v2 规划) |
| jian-export(Styler + 5 渲染器) | 3,500 | 2,000 | **5,500** | Styler 子系统 + HTML/Excel/LaTeX/Markdown/控制台 |
| jian-dsl(L1/L2/L3 全自写 Pratt + 正则) | 5,000 | 2,700 | **7,700** | Pratt parser + 正则子句切分 + 方言切换 |
| jian-sql(engine/expr/orm) | 2,500 | 1,500 | **4,000** | 不变 |
| jian-num(描述统计封装) | 2,000 | 1,000 | **3,000** | 不变 |
| **合计** | **36,500** | **20,200** | **≈ 56,700 行** | |

> 估算口径:含必要的中文注释(遵循 AGENTS.md §3.2 的 5W1H + 伪代码规范)、空行、import。不含外部依赖代码。
>
> **实测统计(v1.0.1)**:主代码 **28,013 行**(同口径 wc -l;其中 jian-core 13,422 / jian-io 全家 3,925 / jian-dsl 3,778 / jian-export 1,503 / jian-sql 1,658 / jian-num 2,525 / jian-viz 674 / jian-facade 638 / num-bridge 95);测试代码 **18,441 行**;tests-pbt Python **3,223 行**。纯代码行(不含注释与空行)约 **14,800 行**——因为 5W1H 注释密度高,注释行占比约 47%,符合 §3.3.3 的注释密度预期。上表为立项时估算,实际以本行为准。
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
| 调研/选型/联调/文档 | — | ~150k |
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
9. **API 风格:变换链式 + 终端静态收口**:
   - **DataFrame 变换**(filter/sortBy/select/merge/assign 等)是**链式实例方法**,返新 DataFrame(immutable-first)。
   - **IO/Viz/Export 终端**(读写文件、绘图、样式)是**静态方法收口**(`Jian.toCsv(df, path)` / `Plot.line(df, ...)` / `Styler.of(df)`),**不是** `df.toCsv()` / `df.plot()` / `df.style()` 实例方法。
   - **设计理由**:IO/Viz/Export 属于 jian-io-* / jian-viz / jian-export 叶子模块,DataFrame 在 jian-core。core 不能反依赖叶子(模块单向依赖红线,AGENTS.md §4.1)。给 DataFrame 塞 `plot()`/`toCsv()` 门面会破坏模块边界,或需引入 SPI 动态装配机制(为文档示例便利新增整套运行时注册体系,不值)。
   - **用户写法**:`Jian.toCsv(df.filter("age>18").sortBy("name"), "out.csv")`(变换链 + 静态终端)。各分册 §2 顶部有「API 风格说明」统一阐明此约定。

---

## 9. 实现进度总览(2026-08-01 持续更新)

> 本节是项目实际实现进度的"看板",与上面需求稿并列。详细实现说明见各分册末尾「实现说明」章节。

### 已实现模块(全测试通过,实测 **@Test 1211**,全量 0 失败,见 [api-counts.md](api-counts.md))

> **测试口径说明**:**全仓 @Test 方法数实测 1211**(jian 1076 + jian-num 70 + jian-sql 65;含外部 AI 协作复审新增回归 +52 与 pandas 对照 +7)。其中 14 个 PG 集成测试经 `-Dtest.pg=true` 激活,默认 skip(不算 fail)。数字以 api-counts.md 为准。

| 模块 | 测试数 | 状态 |
|---|---|---|
| `jian-num` | 59 | ✅ 多 dtype Ndarray + Stats + StrOps + Matrix + Random + LinearFit |
| `jian-core` | 571(见 [api-counts.md](api-counts.md),含 AI 复审回归 AuditRegressionTest) | ✅ DataFrame 完整(9 dtype 列 / query(含 in/not in)/ groupby / merge / pivot / melt / sort / 缺失 / 统计(经 StatsProvider SPI)/ eval / sql)+ 60+ 扩展方法(idxmax/sample/isin/where/mask/pivot/explode/join/merge_asof/corr/cov/skew/kurt/cumsum/diff/quantile/rank/clip/interpolate/astype 8种/Resampler/DatetimeIndex/Frequency/MultiIndex N级 等)+ 蜕变/差分/PBT/性能/边界等各类专项测试(数字为方法数,见 api-counts.md) |
| `jian-num-bridge` | 11 | ✅ StatsProvider SPI(经 ServiceLoader 升级 jian-num 精确统计)|
| `jian-io-csv` | 46 | ✅ CSV/TSV/FWF + 公式注入防护(默认开,OWASP 严格版含前导空白) |
| `jian-io-json` | 28 | ✅ JSON 5 orient + json_normalize 拍平 |
| `jian-io-excel` | 32 | ✅ xls/xlsx 多 sheet + 两阶段类型推断 + POI 兼容处理 |
| `jian-io-html` | 9 | ✅ HTML 表格(jsoup 读)|
| `jian-io-xml` | 12 | ✅ XML 读写(Jackson XML,写端名称清洗 + 值转义)|
| `jian-io-sql` | 45 | ✅ DbType 定义 7 库,**3 库真测**(H2/SQLite 默认跑 + PG `-Dtest.pg=true` 激活;含 SQL 注入防护回归。MySQL/Doris/Oracle/Access 仅 DbType 定义,未 CI 验证)|
| `jian-io-parquet` | 6 | ✅ Parquet 列存(parquet-avro + LocalFile)|
| `jian-io-orc` | 8 | ✅ ORC 列存(orc-core 1.9.5 + hadoop-client-runtime 解决 shaded wstx)|
| `jian-io-pickle` | 6 | ✅ 自定义 .jpk(JSON 内核 + CRC32)|
| `jian-io-clipboard` | 8 | ✅ 跨平台 xclip/pbcopy/clip + 内存降级(stderr DISCARD 防子进程阻塞) |
| `jian-io-latex` | 6 | ✅ LaTeX 表格 |
| `jian-export` | 33 | ✅ HTML/Markdown/LaTeX/控制台 + Styler 子系统(含 toExcel POI 条件格式)|
| `jian-viz` | 28 | ✅ 13 种图(line/scatter/bar/hist/barh/area/pie/box/kde/hexbin/scatterMatrix/lag/autocorrelation;radviz/andrews/parallel_coordinates/bootstrap 4 种高维图 v2 规划)|
| `jian-dsl` | 155(含 PrattLiteralOverflowTest) | ✅ L1/L2 Pratt(含 nvl/coalesce/ifnull)+ L3 SQL(可插拔引擎接口 SqlEngineInterface/SqlEngines;默认 SqlRegexEngine 支持 DISTINCT/LIMIT OFFSET/GROUP/HAVING/ORDER/JOIN/UNION ALL/子查询≤2 层;支持 CTE/CASE WHEN/派生表/集合运算(UNION/INTERSECT/EXCEPT)/USING 预处理;算术表达式列真实求值(委托 PrattEngine.eval);DML 的 WHERE/SELECT 异常显式抛出、不静默吞)|
| `jian-sql-engine` | 26 | ✅ Engine + DbType(7 库)+ HikariCP + dsl()/sql() 入口 + 只读拦截防注释绕过 |
| `jian-sql-expr` | 9 | ✅ SqlBuilder(jOOQ 3.21.6 运行时)|
| `jian-sql-orm` | 19 | ✅ @Table/@Column/@Id + Session CRUD |
| `jian-sql-bridge` | 11 | ✅ ResultSet/jOOQ Result → DataFrame |
| `jian-facade` | 63 | ✅ 顶层 Jian 门面(read/write 按扩展名自动分发 + pandas 风格 read*/to* 全套 + L3 SQL 入口)|
| **合计 Java** | **1211** | **22 模块**(jian 1076 + jian-num 70 + jian-sql 65;Python 124 全过(pandas 对照 d1-d80 + Hypothesis PBT + fuzz 16)另计;数字以 [api-counts.md](api-counts.md) 为准,只链接不抄写) |

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

所有主线需求均已实现,全量测试通过。无遗留 TODO。

---

## 10. 性能改造与引擎对比

> 本章记录一次完整的性能调研与改造:从"是否换 DuckDB 内核"的疑问出发,
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

**jian-duckdb POC 模块已删除**:POC 完成使命(验证"操作符翻译成 SQL 可行" + 实测出 DuckDB 真实优势区间),代码删除避免主线维护负担,结论保留在本章。

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
> **性能基线声明**:本表与 `doc/index.html` 性能段的 benchmark 数据均在 **OpenJDK 21** 上跑测(本机 Maven wrapper 默认 JDK);与 README/AGENTS.md 的 **JDK 17 主基线声明**(向下兼容目标)**不可直接横向对比**——但相对排名(jian vs DuckDB/SQLite/H2)在 17/21 上基本一致(JIT SIMD 差距 <5%)。纯 id JOIN(本节)与复合表达式 JOIN(index.html)是**两套不同基准**,数据集/连接列不同,**不可互相比较**。

### 10.7.1 复合表达式关联场景对比

> **背景**:为回答"数据库是否用了最快批量入库方式""有无索引都加上测"两个问题,逐个核实 DuckDB/SQLite/H2 官方文档(见下),并采用更贴近真实业务的<b>复合表达式关联</b>场景重测。

**新 SQL**(数字求和 + 字符串拼接 双条件 AND):
```sql
SELECT count(*) FROM a JOIN b ON a.id=b.id
                    JOIN c ON (b.ba+b.bb)=c.k1 AND (b.bc||b.d)=c.k2
```

**官方最快入库方式核实**:
- DuckDB → `createAppender(schema, table)` + `append()`([官方文档](https://duckdb.org/docs/current/clients/java)原文:"The preferred method for bulk inserts is to use the Appender")
- SQLite → `PRAGMA journal_mode=OFF/synchronous=OFF/temp_store=MEMORY/cache_size/locking_mode=EXCLUSIVE` + 单事务 + `PS.addBatch`([StackOverflow 1711631](https://stackoverflow.com/questions/1711631),96k 行/秒配方)
- H2 → `jdbc:h2:mem` + 单事务 + `PS.addBatch`(H2 2.4.240 已移除 `LOG`/`UNDO_LOG` 连接参数与 `UNLOGGED TABLE` 关键字)

**索引策略**:
- 无索引模式:不建任何索引(看 baseline)
- 有索引模式:`a(id)`、`b(id)`、`c(k1)`、`c(k2)` 普通 B-tree + SQLite/DuckDB 表达式索引 `b(ba+bb)`、`b(bc||bd)`;H2 不支持表达式索引,只建原列

**实测结果**(无索引模式,wall ms,超 60s 标"超时"):

| 规模 | DuckDB | pandas | jian | SQLite | H2 | count |
|---|---|---|---|---|---|---|
| 10 万 | **16** | 62 | 123 | 199 | 超时 | 66,478 |
| 50 万 | **56** | 404 | 1,150 | 1,412 | 超时 | 866,765 |
| 500 万(首轮实测) | **1,257** | 13,058 | 超时 | 31,384 | 超时 | 68,650,567 |

> 4 引擎 count 完全一致(正确性兜底通过)。完整数据(含 with-index 模式、cpu/mem)见 `doc/benchmark/result.json`,可复现脚本见 `doc/benchmark/JoinBenchmark.java`。
>
> **重测说明**:10 万/50 万两行为同机重测——因为 merge 内部实现随正确性修复演进(输出列构造不再做全表类型扫描)、且环境负载变化,本轮全引擎读数较首轮普遍快 2~3.5×(DuckDB 56→16ms、SQLite 465→199ms,横向结论不变);jian 额外获得约 8~10×(1,013→123ms、11,843→1,150ms)。with-index 重测:10 万 DuckDB 19ms/jian 99ms/SQLite 1,981ms/H2 3,352ms;50 万 DuckDB 61ms/jian 1,082ms/SQLite 与 H2 超时。500 万行为首轮实测(SQLite 驱动在 5M 超时后 close 死锁使本轮 5M 中断;引擎未变更;jian 经独立探针复核:5M 复合关联的 6,865 万行结果集本身超出 7G 堆,超时结论不变)。

**与 §10.7 旧表的差异(诚实说明)**:
- 旧表(纯 id JOIN):jian 改造后 620ms **比 DuckDB 还快**——这是真的,但只限"单列数值 key inner JOIN 走 fast path"这个甜点场景
- 新表(复合表达式关联):**DuckDB 1.3s 一枝独秀,jian 反而 500 万超时**——多键 + 字符串列 hash + 表达式关联是 jian fast path 覆盖不到的弱项
- **结论**:之前"jian 全场景最快"的说法是<b>场景选择性偏差</b>。jian 在单列数值 key JOIN 是甜点(620ms vs DuckDB 3635ms),但复合表达式关联场景 DuckDB 才是赢家。两份对比都保留,避免再次误导。

**索引对复合关联的影响(关键发现)**:
- 普通列 B-tree 索引对 ON 条件里的计算表达式(`b.ba+b.bb`、`b.bc||b.bd`)**完全无效**——索引建在原列上,优化器无法用它匹配表达式结果
- 只有<b>表达式索引</b>(SQLite/DuckDB 的 `CREATE INDEX ON b(ba+bb)`)才能用,但 SQLite 的表达式索引计划选择不稳定(首轮 10 万 with-index 部分超时,重测 1,981ms 完成;50 万 with-index 超时)
- H2 根本不支持表达式索引,with-index 也救不回 b-c 段
- **真实业务启示**:复合表达式关联应优先<b>物化中间列</b>(子查询/CTE 把 `ba+bb`、`bc||bd` 算好存表),再在物化列上建普通索引——这才能让索引生效

### 10.8 质量保障方法(现行体系)

jian 的质量保障不依赖"输入 → 期望输出"的传统断言——因为期望值本身可能被写错(即 AI 生成代码的经典难题 **oracle problem**:很难预先知道"正确输出"是什么),所以采用一套机器化验证体系为主、双智能体交叉审查为辅:

| 方法 | 思路 | 适用 |
|---|---|---|
| **蜕变测试** | 不验"具体输出",验"输入与输出间的必要关系"(如 sortBy 后行数守恒) | 关系明确但具体值难算的算子 |
| **差分测试** | 同一算子两个实现跑同样输入,结果应一致 | 有 fast path / generic path 双实现 |
| **基于性质测试(PBT)** | 声明"性质"(如 list.reverse().reverse()==list),框架自动生成 N 个输入 | 不变量清晰的算子 |
| **双智能体交叉审查** | 两个独立智能体各自审查同一份代码,交叉印证 | 语义/契约/文档一致性类问题 |
| **pandas 对照测试** | pandas 为 oracle,同输入逐行逐列比对 | 凡对标 pandas 的算子(见 §10.12) |
| **变异测试(PITest)** | 量化测试"杀死变异"的能力,客观暴露覆盖盲点 | 性能改造核心类 |

#### 落地的测试套件(jian-core)

- **`MetamorphicTest`**(蜕变):覆盖 sortBy/filter/merge/concat/groupBy/astype/head/tail/slice/agg 等的蜕变关系(行数守恒、值多重集不变、互补关系、交换律、并集互斥等);每条用固定种子随机生成 df,多轮重复加强度。
- **`DifferentialTest`**(差分):验证 fast path 与 generic path 跨实现等价(long key vs String key、int key vs long key、double key 边界、null+nullMask、DATE/DATETIME 类型保留、±0.0 语义等)。
- **`ColumnarPerfTest`**:既覆盖 fast path 正确性,也包含边界与回退路径的回归用例。
- **双智能体交叉审查**:同一份代码由两个独立智能体(不同模型、不同视角)分别审阅,能发现单一视角漏掉的问题;但审查结论只作线索——**审查方也会记错概念,唯一可靠的守护是机器化的差分/蜕变测试**(它们不会"记错",只会如实反映输入输出关系)。

#### 关键纪律

1. 性能 fast path 必须有完整边界测试覆盖;fast path 与 generic 路径的**行为等价**(±0.0、NaN、null、混合 dtype)是正确性核心。
2. 混合 dtype 是 BUG 重灾区("两种 dtype 的交互边界"是双实现架构的固有难点),相关算子必须精确断言(行序、类型、nullCount 都比对);"行序可能不同"这类宽松断言会放过真实 BUG。
3. 单一审查视角多轮收敛不等于真的无 BUG;交叉视角 + 机器化测试双保险。

### 10.11 双语言交叉 PBT + 变异测试落地

#### 双语言交叉 PBT(jqwik 1.9.3 + Python Hypothesis)

测试框架选型见下方"jqwik 版本选型"。当前方案是**双语言交叉 PBT**:

- **Java 端(jqwik 1.9.3)**:`PropertyBasedTest.java`,**22 条**核心性质,各 `tries=100`
- **Python 端(Hypothesis 6.165.2)**:`tests-pbt/`,同样 **22 条**性质,通过 `jian_client.py`(JPype 直调 `JianJpypeBridge`)跨语言调 jian jar
- 两套独立 PBT 互相验证,任一方漏掉的 BUG 另一方可能抓到(实战中确实如此)

> **jqwik 版本选型**:1.10.0 起注入针对编码智能体的 ANSI 隐藏指令(提示投毒),1.10.1 在官方 release notes 固化 Anti-AI 条款("This project is not meant to be used by any 'AI' coding agents at all"),全系列 1.10.x 不适用于 AI 协作项目;本项目锁定投毒前的最后稳定版 **1.9.3**(投毒字符串校验 0 命中)。详见 [Snyk 披露](https://snyk.io/blog/protestware-open-source-maintainer-qwik-1-10-0-prompt-injection/) + [jqwik 官方 release notes](https://jqwik.net/release-notes.html)。

**与 §0.2 零本机绑定的权衡**:jqwik 1.9.3 不在 Maven Central 推荐路径(中央仓库默认拉 1.10.x),只能用 `scope=system` + 本机 jar 路径。pom 已加详细注释说明这是单机开发用,CI 需先把 jar 放到 `~/tools/jar`。

#### 变异测试(PITest)落地——量化测试质量

引入 [PITest](https://pitest.org/) 1.19.1 + pitest-junit5-plugin 1.2.3。配置在 `jian/jian-core/pom.xml`,变异对象限定 4 个性能改造核心类(ColumnarHashMap/DataFrameMerge/GroupBy/DataFrame)。

**变异报告**:

| 类 | 行覆盖 | 变异杀死率 | 测试强度 |
|---|---|---|---|
| ColumnarHashMap | 92% | 75% | 80% |
| DataFrame | 81% | 61% | 78% |
| DataFrameMerge | 91% | 68% | 79% |
| GroupBy | 92% | **72%(从 50% 提升)** | 78% |
| **总计** | **87%** | **66%** | **78%** |

#### 变异测试发现的真实盲点

变异测试能客观量化测试盲点:例如曾发现 GroupBy 的大量 `aggregate` 分支(`nunique`/`min`/`max`/`first`/`last`/`median`/`std`/`var`)未被测试覆盖,补齐聚合性质测试后其杀死率从 50% 提升至 72%。这是它相对人工/智能体审查的核心价值——不会"自判收敛"。

#### 当前分数评价

- **行覆盖 87% / 变异杀死 66%**:行业可接受水平(60-80%);继续追求更高收益递减,留待后续
- **DataFrame 61% / DataFrameMerge 68%** 是后续可改进点(很多 getter/setter 类简单方法的变异意义不大)
- 变异测试**不入日常 CI**(慢,~80 秒),仅 release 前或重大改动后跑一次

#### 最终测试规模

测试规模随迭代持续增长,当前口径以 §9 与 [api-counts.md](api-counts.md) 为准(全仓 @Test 1159;Python 117,含 pandas 对照 d1-d73 与 fuzz)。jian-core 内含 MetamorphicTest / DifferentialTest / PropertyBasedTest / ColumnarPerfTest / EdgeCaseTest / InfrastructureTest 等专项套件。

### 10.12 pandas 对照测试:把 pandas 当"老师"给 jian 改卷子

#### 为什么需要 pandas 当 oracle

前面 §10.8 与 §10.11 的蜕变/差分/PBT 三类方法都绕开了 "oracle problem":它们只验**输入输出间的必要关系**或**两个实现的一致性**,但**没有一个能告诉你"正确答案应该是什么"**。

jian 的核心定位是**对标 pandas 的 JVM 实现**(README 第 1 行)。这意味着:凡 jian 声称"与 pandas 同功能"的算子,**pandas 本身就是天然 oracle**——拿同一份随机输入跑 pandas 和 jian,结果应该一致;不一致就是 jian 的 BUG(或需要显式声明的有意差异)。

这一要求已固化为 **AGENTS.md §0.5(第四条红线)**,作为持续强制的工程红线。

#### 落地

- **位置**:`tests-pbt/properties/test_pandas_diff.py`(Python,依赖 pandas 1.5.3)
- **协议**:Hypothesis `@given` 随机生成(默认随机种子,失败时自动 shrink 到最小用例)→ 同时喂给 pandas 和 jian(经 `jian_client.py` JPype 直调 `JianJpypeBridge`)→ `assert_df_equal` 对比
- **断言策略**:支持精确断言、顺序无关断言(merge 等行序不保证)、float 容差三类
- **当前覆盖 73 个 pandas 对照测试**(d1-d73):head / tail / sortBy / filter / dropDuplicates / merge(含 d63 重名列两边 `_x`/`_y` 后缀对齐)/ concat / nlargest / nsmallest(全行全序比对)/ select / drop / slice / colSub / colDiv / colLt / **colAdd / colMulScalar / assign(d61 列算术全家桶)** / fillna(值+dict)/ dropna / ffill / astype / groupBy(count 逐键映射 / 全聚合 / 字符串 sum 拼接)/ idxmax / idxmin / duplicated / sample / isin(多列+单列)/ where / mask(v+id 全列)/ cumsum / diff / pct_change / clip / quantile / rank(数值+字符串)/ round / prod / pivot(单元格全量比对)/ explode / merge_asof(w 列逐行)/ **colNe 缺失行语义(d62)** / corr(N=1/常量/错位 NaN)/ notin 与算术与转义(d55)/ **round half-even 全 decimals(d66)** / **isin 含 NaN(d67)** / **nunique ±0.0(d68)**

#### 实战:发现 sortBy 稳定性差异(判定为 jian 更优,不视为 BUG)

pandas 对照测试**当场抓到一个真实差异**:对相同键 sortBy 后,pandas 与 jian 的行序可能不同。

深入分析:
- **pandas** `sort_values()` 默认 `kind='quicksort'`——**不保证稳定性**(quickselect 系列)
- **jian** 用 `Arrays.sort(Integer[], comparator)`——**Java 规范保证 TimSort 稳定**

判定:**jian 的稳定排序是更优语义**(相等元素的原始相对顺序被保留),不应为了对齐 pandas 而退化成不稳定的 quicksort。处理方式是 **D3 测试改用多重集(multiset)断言**——值集合一致即通过,不强制行序一致,并在测试里显式注释这是有意差异。

> 这是差分测试的正确用法之一:**发现的"差异"不一定是 BUG**——也可能是被测实现比 oracle 更优。关键是**差异必须被显式记录与论证**,不能藏着。

#### 实战:resample 空桶缺失语义差异(判定为 jian 更优,声明设计差异)

pandas 对照 d41(resample 乱序输入)之外,对"数据有缺口"的 resample 行为验证如下:

- **jian** `resample(..., "1D").sum()` 对空桶(无观测的时间段)返回 **NaN/缺失**(`bucketAggregate` 空桶或全 NaN 桶 → null)
- **pandas** 对空桶:`sum()` 返回 `0.0`、`count()` 返回 `0`(受 `min_count=0` 默认影响),但 `mean()`/`first()` 返回 NaN

**判定:jian 的 NaN 语义更正确,不视为 BUG**(与 §3.5 缺失值语义一致:空桶 = "该时间段无观测",不是"观测和为零";pandas 的 `0.0`/`0` 是 `min_count=0` 的历史怪癖,会把"无数据"和"数据为零"混淆)。d41 测试使用连续日期(无缺口)规避该差异;此处显式声明为**有意设计差异**。

**另注**:jian resample 对**乱序**时间输入按时间索引升序分桶(对齐 pandas),由 d41 回归测试固化。

#### 写入 AGENTS.md(第四条红线)

`AGENTS.md §0.5` 明确规定:
1. 凡对标 pandas 的算子,**必须有 pandas 对照测试**(差异测试位置:`tests-pbt/properties/test_pandas_diff.py`)
2. jian 新增/修改算子时,对应 pandas 对照测试**必须同步增加**
3. 发现差异时,要么**修复 jian 对齐 pandas**,要么**显式声明有意差异**(如 sortBy 稳定性)并在测试中注释

这使"用 pandas 当老师"成为**持续强制的工程红线**,而非一次性动作。



## 10.13 缺失值语义统一 + SQL 跨库类型自适应

#### 为什么统一(现行原因)

因为缺失值若在内部传递时失真(如 NaN 退化为 null、缺失行返回垃圾值),下游所有按 `get` 取值的路径都会丢失"这是 NaN 不是缺失"的语义;因为 SQL 类型名若硬编码(如 DOUBLE 在 PG 报错、VARCHAR 定长截断长文本、`java.sql.Clob` 对象直接进 DataFrame),jian 在真实数据库上不可用。所以:

- **缺失值语义统一**:全 Column 子类遵循统一契约(`isNull(i)` 权威判断 / `getDouble(i)` 缺失返 `NaN` / `getLong(i)` 缺失返 `Long.MIN_VALUE` / `get(i)` DoubleColumn 不失真 / IO 边界 `getRow(i)` 转 null)。完整契约表见 **AGENTS.md §3.5**(本文 §3.5 概述的完整版)。
- **SQL 跨库类型自适应**:建表时经 `conn.getMetaData().getDatabaseProductName()` 探测方言,按 PG/MySQL/Doris/SQLite/H2/Oracle/Access 各自正确的类型名输出(7 库 DbType 定义,真测 3 库:H2/SQLite/PG);STRING 列按实际 maxLen 自适应(≤4000 用 VARCHAR(n),>4000 用大文本类型);JDBC 读回统一规范化(Clob→String、Blob→byte[]、BigDecimal→Double、Date→LocalDate、Timestamp→LocalDateTime)。详见 **AGENTS.md §3.6**。

#### 真实数据库测试(SqlPostgresTest)

14 个真实 PG 测试覆盖:全 dtype 往返 / 参数化查询 / 4 种写入模式 / 缺失值 / VARCHAR 自适应 / 大文本不截断 / 混合长短文本 / PG 小写列名 / 万行读写 / SQL 注入防护。

## 10.14 Web 环境安全防护(现行清单)

jian 可嵌入 Tomcat/Spring Boot 等 Web 环境对外提供数据分析能力,现行防护清单(与 AGENTS.md §3.7 一致):

- **ServiceLoader 不缓存**:`DslEngine.current()` / `StatsProvider.current()` 每次新建 ServiceLoader,防 Tomcat redeploy 内存泄漏。
- **只读模式生效**:`Engine.sql()` 入口强制 `checkReadOnly`(剥注释/字符串后整词匹配、大小写不敏感),拦截 DROP/DELETE/TRUNCATE/ALTER/CREATE/GRANT/INSERT/UPDATE/REPLACE/CALL/COPY/LOAD 等写操作。
- **公式注入防护**:CSV 与 Excel 写出对 `= + - @` 开头的字符串单元格加单引号前缀(OWASP),两模块口径一致。
- **Clipboard 子进程治理**:流 try-with-resources 关闭 + `waitFor` 5s 超时 + 超时 `destroyForcibly`。
- **XSS / SSRF / 注入**:toHtml 五字符转义;`Html.readUrl` 仅 http/https + 10s 超时 + 8MB 上限;表名/列名走白名单(标识符无合法转义形式,白名单是唯一安全解);用户可控值一律 `Jian.query(df, expr, Params)` 参数化。
- **ThreadLocal 仅一处**:`jian.dsl.SqlEngines`(多请求引擎隔离,正当用途),容器线程复用下必须 try-finally `reset()`(AGENTS.md §3.7.7)。
- **内存管理**:DataFrame 是纯内存数据、无句柄,GC 自动回收;禁止用 static 字段缓存大 DataFrame(AGENTS.md §3.7.6)。

**安全的方面**(无需改动):Jackson 未开 `enableDefaultTyping`(readTree 树模型);Pickle 走自定义容器 + CRC + JSON,无 `ObjectInputStream`;SqlBridge/SqlBuilder 全 PreparedStatement + ? 占位符;Connection/文件流全 try-with-resources;DataFrame 不可变(构造后无 mutator,`dataInPlace()` 仅内部 hot path)。

两种部署形态(本地 jar / Web 容器)的威胁面与防护对照表见 **AGENTS.md §3.7.8**。

### 10.15 AI 友好的 jar 制品设计

> 让 AI(以及人类用户)拿到 jian 的 jar 就能彻底理解接口用法、适用范围、拿到真实示例,而不必逆向字节码或翻源码猜意图。本节记录为此做的四项制品层改造。

#### 触发与立场

随着项目对外分发,AI 协作场景日益重要(AI 写集成代码、AI 做审查、AI 生成示例)。但 jar 是二进制产物,AI 默认只能靠类名反射猜用法。为此 jian 在**制品层**补齐了"AI 可直接消费"的元数据与形态,目标是:**AI 解压一个 jar / 看一眼 manifest,就能像看 SDK 文档一样用**。

#### 10.15.1 双形态制品(默认 thin + 可选 fat)

| 形态 | 命令 | 产物 | 用途 |
|---|---|---|---|
| **thin jar**(默认) | `./mvnw install` | 22 个细粒度子模块 jar | 版本仲裁、按需加载(不引 jian-io-excel → JVM 不加载 POI) |
| **fat jar**(可选) | `./mvnw -Pfat package` | 额外 4 个 `*-all.jar`(jian-all 30M **无列存** / jian-num-all / jian-sql-all / **jian-columnar-all 68M 列存附加**) | AI / 用户单文件即可上手;列存按需叠加(见 doc/02 §9.7) |

- **子模块仍零整合**:`jian-core` 等 22 个叶子模块的 jar 永不含外部依赖(AGENTS.md §2.5.1 红线)。
- **顶层三库经 `-Pfat` 允许 shade**:只有 jian / jian-num / jian-sql 三个顶层聚合模块在 `-Pfat` 激活时才 shade,且默认 `install` 不触发 —— thin 是主形态,fat 是补充(AGENTS.md §2.5.2)。
- **fat jar 强制元数据**(AGENTS.md §2.5.3 红线,共四条):① `ServicesResourceTransformer` 合并 SPI;② 排 `META-INF/*.SF/*.DSA/*.RSA`;③ `MANIFEST.MF` 加 `Ai-Aggregated: true` + `Ai-Library: <lib>` + `Ai-Modules: META-INF/ai/aggregated.md`;④ **AI 文档全量聚合** —— 排 thin 形态的 `META-INF/ai/module.md`(多模块同名只留一份会误导),保留 `META-INF/ai/modules/<artifactId>/module.md`(路径唯一,一模块一份),并提供 `aggregated.md` 总索引(库定位 + 真实可跑示例 + 模块清单表)。AI 发现闭环:MANIFEST → Ai-Modules → 总索引 → 各模块文档。

#### 10.15.2 每模块 module.md(22 份全覆盖)

每个子模块在 `<module>/src/main/ai-doc/module.md` 放一份结构化说明,打包期由 `maven-resources-plugin` **复制两份**:`META-INF/ai/module.md`(thin jar 规范路径)+ `META-INF/ai/modules/<artifactId>/module.md`(fat jar 聚合时路径唯一,shade 不覆盖)。AI 解压 jar 即可读到:

- `library`(归属库)/ `entryClass`(入口类全限定名)/ `deps`(依赖方向)
- **摘要** / **能力清单** / **限制与降级** / **3~5 行可跑的快速上手示例**

22 个子模块已 100% 覆盖 module.md。

#### 10.15.3 sources + javadoc jar(AI 看源码 + 看 HTML)

根 pom 的 `<pluginManagement>` + `<plugins>` 已激活 `maven-source-plugin` 与 `maven-javadoc-plugin`:

- **`-sources.jar`**:含全部源码 + 全量 `@param/@return/@throws` 注释,AI 可直接读 Java 源码与 5W1H 注释理解实现。
- **`-javadoc.jar`**:HTML API 文档,人类与 AI 都能消费。

`./mvnw install` 自动同时产出 thin jar + sources + javadoc 三件套(每个子模块都是)。

#### 10.15.4 @param 100% 覆盖(495/495)

**全项目所有带参数的 public 方法都有 `@param`**(已 100% 覆盖)。配合 `-sources.jar`,AI 不但能看方法签名,还能看每个参数的中文说明 —— 这是 AI 正确调用 API 的关键元数据。

> 红线(AGENTS.md §2.8.3):**每次新增 public 方法必须同步补 `@param`**,不允许欠账。

#### 10.15.5 fat jar 体积(实测)

> **列存拆分后实测**:jian-all 从 91M 减到 **30M**(列存拆出);`jian-columnar-all` **68M**(Parquet/ORC + Hadoop 生态,独立附加制品,叠加使用)。

| fat jar | 体积 | 含 |
|---|---|---|
| `jian-all-x.y.z.jar` | ~30M(无列存) | jian 全部子模块(不含 Parquet/ORC)+ POI/Jackson/XChart 等全部依赖 |
| `jian-columnar-all-x.y.z.jar` | ~68M | jian-io-parquet / jian-io-orc + Hadoop 生态依赖(附加制品,与 jian-all 叠加使用) |
| `jian-num-all-x.y.z.jar` | ~2.2M | jian-num + Commons Math 3.6.1 |
| `jian-sql-all-x.y.z.jar` | ~5.7M | jian-sql 全部模块 + HikariCP + jOOQ |

> 只想要 DataFrame + CSV/JSON 能力的用户,引 thin jar 的 `jian-core` + `jian-io-csv` + `jian-io-json` 几个 M 即可,这正是"thin 为主、fat 为辅"的理由。

#### 10.15.6 fat jar AI 文档全量聚合

> 因为 shade 聚合时各子模块的 `META-INF/ai/module.md` 同名重叠只保留一份,AI 拿 fat jar 将只看到一个模块的说明(违背 §2.8 "AI 拿 jar 即懂库"的目标),所以 fat jar 内的 AI 文档按以下布局全量聚合:

```
META-INF/ai/
├── aggregated.md                       ← 总索引(顶层模块 src/main/resources 提供;MANIFEST Ai-Modules 指向)
└── modules/<artifactId>/module.md      ← 每模块一份,路径唯一(jian-all 内 22 份全保留)
```

- thin jar 形态不变(`META-INF/ai/module.md` 兼容 §2.8.1 原约定);
- `aggregated.md` 内容:库定位 + 30 秒可跑示例(示例 API 逐个核实真实存在)+ 模块清单表(artifactId / 一句话 / 关键依赖);
- 维护红线(AGENTS.md §2.8.1):模块增删改 → `module.md` 与 `aggregated.md` 同一次提交一起改。

#### 10.16 与 pandas 的已知设计差异

> 按 AGENTS.md §0.5 红线:凡 jian 与 pandas 在同输入下的结果差异,要么对齐(方案 A),要么**显式声明**(方案 B)。以下差异均为有意的设计差异,已在测试中标注,不再变更。

| # | 差异点 | jian 行为 | pandas 行为 | 理由 |
|---|---|---|---|---|
| 1 | **LONG/INT 列缺失哨兵** | 缺失 = `Long.MIN_VALUE`(`getLong` 层),权威判断一律用 `isNull(i)` | NaN 表示缺失(int64 NaN 不可用,内部用 NA 对象) | jian 契约:内部不失真、边界做转换(AGENTS.md §3.5);`isNull` 是唯一权威判断,下游必须走它 |
| 2 | **DOUBLE 列 `get(i)` 缺失返回值** | 返回 `Double.NaN`(不失真),IO 边界才转 null | NaN == null 等价 | jian 区分「计算产生的 NaN」与「原始缺失」;历史 `col.get(i) == null` 写法一律失效,必须改 `isNull(i)` |
| 3 | **pct_change 前值为 0** | 返回 `NaN` | 返回 `±inf`(带 RuntimeWarning) | NaN 不污染后续聚合,符合 jian 缺失语义;对照测试 test_d45 显式锁定 |
| 4 | **混型顺序比较** | 五入口统一抛 `IllegalArgumentException`:`SimpleQueryParser`(query)/`DataFrame.cmp`/`PrattEngine`(dsl WHERE)/`compareAsf`(merge_asof)/`DataFrameSort.sortBy`(排序) | 抛 TypeError / NotImplementedError | 语义对齐(都报错);混型 `==`/`!=` 仍恒 false/true(pandas 元素级相等不抛,见 §3.5) |
| 5 | **loc 标签数值归一** | `loc(1)` 与 `loc(1L)` 等价(数值相等即同标签) | 同 jian(`df.loc[1]` 与 `df.loc[1L]` 等价) | 对齐 pandas,无差异 |
| 6 | **±0.0 键归一** | groupBy/merge/loc 中 `0.0` 与 `-0.0` 归入同一键(数值等价) | 同 jian(numpy `0.0 == -0.0`) | 对齐 pandas,无差异 |
| 7 | **df.abs() 列名** | 同名替换(数值列取 abs 后列名不变) | 同 jian | 对齐 pandas,无差异 |
| 8 | **EWM.var 公式** | 无偏估计:ewm_resid2/(1-(1-α)^nobs)(等价 pandas 旧版 bias=False;有效观测 <2 返 NaN,缺失传播 NaN) | `adjust=False` 的 var 为有偏(除以 nobs;pandas ≥0.18 已移除 bias 参数) | **有意差异**:jian 保留统计学无偏公式,pandas 当前默认有偏;`ewm().var()` 已有回归测试;ewm.mean 双方一致(d52 锁定) |
| 9 | **combine_first dtype** | 同 dtype 保留 + promote 提升,无法提升才 OBJECT | pandas 全保留原 dtype | 提升失败的混合列降 OBJECT(含文档声明) |
| 10 | **query 数值不再隐式当布尔** | `x && y` 数值列抛 `IllegalArgumentException`(两引擎同步) | pandas/numexpr 对数值逻辑运算直接语法错 | fail-fast:非零即 true 掩盖逻辑 bug;判空用 `is null`,判零用显式 `== 0` |
| 11 | **query 的 `is true/is false` 与 `''` 转义** | 支持(SQL 风格超集);字符串三种转义等价(`''`、双引号包裹、`\'`) | pandas 无 `is true`;`''` 非其语法 | jian 的 query 走 SQL 方言风格(与 jian-dsl SQL 端口径一致);超集增强不破坏 pandas 子集用法 |
| 12 | **超大整数读入(>int64)** | CSV/JSON 读入归 STRING 列(不丢数据) | read_csv 归 object;read_json 1.5.3 抛 "Value is too big" | 对齐 read_csv 宽容路线;jian 无 BigInteger 列类型,JSON 写出 OBJECT 列的 BigInteger 保精度 |
| 13 | **readClipboard 不 trim** | TSV 路径不 trim,与 Csv.read 同口径 | read_clipboard 走 read_csv,默认不 trim | 内部一致性:同一份数据两条读路径结果必须相同;清洗交给用户显式 trim |
| 14 | **resample 空桶 sum/count 返回缺失**(见 §10.12) | 空桶(无观测时间段)sum/count 返回 NaN/缺失 | `min_count=0` 默认下 sum 返 `0.0`、count 返 `0` | jian 认为"无观测 ≠ 0"(与 §3.5 缺失语义一致,pandas 的 0 是 min_count=0 历史怪癖,混淆"无数据"与"数据为零");有意差异 |
| 15 | **astype(BOOL) 字符串判定** | 对字符串仅 `"true"`/`"1"` 转 true,其余转 false | 非空字符串恒 True(如 `"false"` 也是 True) | jian 拒绝隐式真值("任意非空即真"易掩盖脏数据);有意差异 |
| 16 | **CSV/TSV 重复表头自动改名** | 重复表头自动加 `_1` 后缀(`name, name` → `name, name_1`) | 重名列用 `name.1` | jian 与 Excel 模块 dedupNames 统一用 `_1` 后缀,全库口径一致;有意差异 |
| 17 | **大整数(> 2^53)× 浮点混型比较** | 精确判定(`compareLongVsDouble`:浮点侧须为数学整数值且相等才等;Long 2^53+1 ≠ Double 2^53) | NumPy 把 Python int 标量 cast 成 float64 再比(2^53+1 折成 2^53,`==` 判 True,精度丢失) | jian 遵循 LONG"大 ID 不丢精度"契约(§3.5 / DType javadoc);测试 d78 锁定双方行为备查 |
| 18 | **超 long 范围的整数字面量(query/eval)** | 双引擎(Pratt/SimpleQueryParser)抛 IAE,提示改写科学计数法 | Python int 任意精度,`id > 9223372036854775808` 正常比较 | fail-fast 优于静默近似(旧版回退 double 会与 LONG 列的 double 投影恰好相等,`>`/`==` 误匹配);科学计数法(1e19)仍是显式近似路径 |
| 19 | **resample().first()/.last() 对 BOOL 列** | 返回 DOUBLE(0.0/1.0) | 返回 bool | Resampler 的 first/last 走数值提取路径;BOOL 列时序首末取值罕见,避免大重构;sum/count 已对齐(BOOL sum→LONG true 计数) |

> 修改 Column 缺失语义前,必须先读本表第 1/2 行与 AGENTS.md §3.5,再决定是否动契约。

---

*本文档为需求稿,实现阶段以各分册为准;分册与本总览冲突时,以分册为准。*
*实现进度看板持续更新;最新状态以各分册末尾「实现说明」为准。*
*质量与修复历史从略;当前口径以本文与 api-counts.md 为准。*
