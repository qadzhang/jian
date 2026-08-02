# jian / jian-sql / jian-num 需求说明书 · 总览

> 版本:v1.0(发布版) · 日期:2026-08-01
> 作者:zc · 状态:待评审

---

## 0. 文档目的

本文档是 **jian / jian-sql / jian-num** 三个相互独立、可单独引用的 Java 库的**总需求说明书**。它回答四个问题:

1. **要做什么** —— 三个库各自的范围与边界。
2. **基于什么做** —— 复用哪些开源积木、自写哪些部分(附选型核实数据)。
3. **多大工作量** —— 代码行数、token 用量的近似估算。
4. **怎么落地** —— 模块切分、依赖隔离、开发顺序。

各模块的详细需求见同目录下 `01 ~ 06` 各分册,**分册之间无引用关系**,可独立阅读、独立实现、独立打包。

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
| jian-core(DataFrame 全 15 大类 + Series + GroupBy + 窗口) | ~14000 行 | 无合格活跃库可复用 |
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
| jian-core | 14,000 | 8,000 | **22,000** | 15 大类 DataFrame/Series/GroupBy/Window/Resampler 全套 |
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
| jian-core | 14,000 | **1,000k**(15 大类、有 pivot/rolling/resample 等难点) |
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
> **提示**:core 的 token 消耗最大且最难压缩(pandas 200+ 方法逐个对齐 + 大量边界用例),建议按"内部分包"分多轮上下文实现,避免单轮塞不下。

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
5. **不做包整合**(强制):外部 jar 一律按 `groupId:artifactId:version` 精细引用,**不用 uber/fat jar、不用 maven-shade-plugin / maven-assembly-plugin**。理由:① 版本仲裁交 Maven 依赖中和机制统一处理,手动整合反而锁死旧版;② 依赖树对用户透明,便于排障升级;③ 与"细粒度按需 jar"目标一致。详见 `AGENTS.md` §2.5。
   - 例:Excel 能力引 `org.apache.poi:poi-ooxml:5.5.1`(**不是** `poi-ooxml-uber`),传递依赖由 Maven 自动拉。
   - 例:jian-dsl 自写 Pratt parser(零运行时依赖,不用 ANTLR4)。
6. **功能范围:大面对齐 pandas 3.x**(用户明确要求)。
   - IO:12 类 Tier 1 格式全实现(CSV(含 TSV/FWF)/Excel/JSON/HTML/XML/SQL/Parquet/ORC/Pickle/Clipboard/LaTeX 仅写/Markdown 经 export);Tier 2(Feather/Stata/SAS/SPSS/GBQ/Iceberg/HDF5)留接口不实现。
   - 图表:13 种全做(10 种 plot + 3 种 plotting);radviz/andrews_curves/parallel_coordinates/bootstrap 4 种高维图列入 v2 规划。
   - 样式:Styler 子系统全功能(条件染色/颜色映射/数值格式/条形/自定义 CSS),HTML+Excel+LaTeX 三输出。
7. **Parquet/ORC**:全实现(原 v2 项提前到 v1,因用户要求大面对齐)。
8. **DSL 不嵌入任何外部脚本引擎**:L1/L2 手写 Pratt,L3 用自写正则子句切分(不用 ANTLR4)。

---

## 9. 实现进度总览(2026-08-01 持续更新)

> 本节是项目实际实现进度的"看板",与上面需求稿并列。详细实现说明见各分册末尾「实现说明」章节。

### 已实现模块(全测试通过,342 测试)

| 模块 | 测试数 | 状态 |
|---|---|---|
| `jian-num` | 38 | ✅ 多 dtype Ndarray + Stats + StrOps + Matrix + Random + LinearFit |
| `jian-core` | 107 | ✅ DataFrame 完整(9 dtype 列 / query(含 in/not in)/ groupby / merge / pivot / melt / sort / 缺失 / 统计(经 StatsProvider SPI)/ eval / sql)|
| `jian-num-bridge` | 6 | ✅ StatsProvider SPI(经 ServiceLoader 升级 jian-num 精确统计)|
| `jian-io-csv` | 12 | ✅ CSV/TSV/FWF + 公式注入防护(默认开) |
| `jian-io-json` | 10 | ✅ JSON 5 orient + json_normalize 拍平 |
| `jian-io-excel` | 16 | ✅ xls/xlsx 多 sheet + 两阶段类型推断 + POI 陷阱修复 |
| `jian-io-html` | 5 | ✅ HTML 表格(jsoup 读)|
| `jian-io-xml` | 5 | ✅ XML 读写(Jackson XML,写端名称清洗 + 值转义)|
| `jian-io-sql` | 11 | ✅ 7 数据库通用(H2 验证,PreparedStatement 参数化)|
| `jian-io-parquet` | 3 | ✅ Parquet 列存(parquet-avro + LocalFile)|
| `jian-io-orc` | 3 | ✅ ORC 列存(orc-core 1.9.5 + hadoop-client-runtime 解决 shaded wstx)|
| `jian-io-pickle` | 4 | ✅ 自定义 .jpk(JSON 内核 + CRC32)|
| `jian-io-clipboard` | 2 | ✅ 跨平台 xclip/pbcopy/clip + 内存降级 |
| `jian-io-latex` | 1 | ✅ LaTeX 表格 |
| `jian-export` | 23 | ✅ HTML/Markdown/LaTeX/控制台 + Styler 子系统(含 toExcel POI 条件格式)|
| `jian-viz` | 16 | ✅ 17 图(line/bar/hist/box/kde/area/pie/scatter/hexbin + scatterMatrix/lag/acf 等)|
| `jian-dsl` | 36 | ✅ L1/L2 Pratt(含 nvl/coalesce/ifnull)+ L3 SQL 子集(DISTINCT/LIMIT OFFSET/GROUP/HAVING/ORDER/JOIN 链式/UNION ALL/子查询≤2 层 + SPI)|
| `jian-sql-engine` | 7 | ✅ Engine + DbType(7 库)+ HikariCP + dsl()/sql() 入口 + 只读拦截防注释绕过 |
| `jian-sql-expr` | 4 | ✅ SqlBuilder(jOOQ 3.21.6 运行时)|
| `jian-sql-orm` | 6 | ✅ @Table/@Column/@Id + Session CRUD |
| `jian-sql-bridge` | 5 | ✅ ResultSet/jOOQ Result → DataFrame |
| `jian-facade` | 17 | ✅ 顶层 Jian 门面(read/write 按扩展名自动分发 + pandas 风格 read*/to* 全套 + L3 SQL 入口)|
| **合计** | **342** | **22 模块** |

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

所有主线需求 + opencode 审查问题 + 用户反馈均已解决。无遗留 TODO。

---

*本文档为需求稿,实现阶段以各分册为准;分册与本总览冲突时,以分册为准。*
*实现进度看板持续更新;最新状态以各分册末尾「实现说明」为准。*
*全部实现完毕于 2026-08-02;2026-08-02 全项目审查(文档对照 + 安全审计)后 342 测试全过。*
