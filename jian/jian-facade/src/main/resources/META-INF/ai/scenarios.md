# jian 真实场景集(46 个,AI 可直接照抄起步)

> 每个场景 = 真实业务背景 + jian 能力组合 + **可手算的预期结果**;本表是简版速查。
> **双实现口径**:第二轮(S17~S46)每个场景在断言源码里都有两份实现——**链式版(pandas 风格)** 与
> **『SQL 对照版』段(Jian.sql 一条语句)**,并做差分断言(两版逐行相等);第一轮 S1~S16 的 SQL 写法见下方 SP 区。
> SQL 版覆盖数据加工(过滤/分组/聚合/JOIN/TopN/透视=条件列+GROUP BY);导出样式与窗口类
> (Styler/多 sheet/rolling/resample)属链式侧,SQL 版以全局聚合交叉验证等价锁定。
> SQL 子集注意:同一源列多聚合须拆多条 SQL;`fn(CASE...)` 聚合不支持(条件列 assign 预置);
> 表限定名(`a.x`)仅 ON 子句可用(JOIN 后重名列走 `_x`/`_y`)。
> **完整可执行标准答案**(构造数据的代码 + 逐条断言)在本 jar 的 `META-INF/ai/scenarios-src/`
> 13 个 JUnit 源文件里(ScenarioSalesFinanceTest / ScenarioCleanReshapeTest / ScenarioAnalyticsOpsTest /
> ScenarioSqlShowcaseTest / ScenarioStyledExportTest / SecurityAuditTest / ScenarioFileDatabaseTest /
> ScenarioReportExportTest / ScenarioReconcileQualityTest / ScenarioOpsMonitoringTest /
> ScenarioTimeSeriesTest / ScenarioStatsAnalysisTest / ScenarioAuditComplianceTest),
> 每次 `mvn test` 都会用它们验证下表预期值 —— 拿不准 API 用法时,优先读这些文件。
> 场景来源:第一轮(S1~S16)基于 pandas 真实用例的网络调研;第二轮(S17~S46)由四个独立 AI
> 各自网搜 10~15 个真实案例(共 52 例,来源含 Stack Overflow/Kaggle 工程仓库/运维与科研博客),
> 汇总去重后**按抽象口径合并为 30 类通用需求**(表格⇄数据库/报表产出/对账质量/日志监控/
> 时序处理/统计分析/审计合规),便于跨行业照抄起步。

| # | 场景 | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S1 | 门店×品类销售月度汇总 | readCsv → groupBy(品类).agg(sum) → sortBy → head | 食品 700 / 饮料 300,双店各 500 |
| S2 | 银行流水 vs 公司账对账 | merge("outer") → query(单边/金额不等) | 差异 3 行(T003/T004/T006),总额差 151 |
| S3 | 问卷缺失值清洗 | isna → dropna → fillna → colValueCounts | 缺失 年龄2/城市1;完整样本 3 行;均值 26.25 |
| S4 | API 日志按小时重采样 | resample("1h").mean()/count() | 10点 mean=150 / 11点 200 / 13点 250;计数守恒 6 |
| S5 | 电商客户 RFM 分层 | groupBy(客户).agg(min/sum) + query 计次 + 规则打分 | 高价值 2 人(555)、流失 2 人(111) |
| S6 | 落地页 A/B 测试 | groupBy(组).agg(sum/count) + colStd | A 0.6 / B 0.8,相对提升 33.33%,std≈1.5811 |
| S7 | 仓库安全库存预警 | query(现<安全) + assign(可售天数) + sortBy | 预警 S2/S3/S6;优先级 S3(0.4天)→S2→S6 |
| S8 | 成绩长宽透视与排名 | pivotTable + melt(roundtrip) + assign 总分 | 数学均 77.5 / 英语 80;melt 回 8 行无损 |
| S9 | HR 员工-部门多表合并 | merge inner/left 对比 + isna 找孤儿 | inner 5 行;left 6 行含 1 孤儿(钱) |
| S10 | 支付重试重复订单去重 | duplicated(subset) + dropDuplicates(keep=last) | 重复 3 条;1400→1000,虚增 400 |
| S11 | 嵌套 JSON 订单拍平 | jsonNormalize(customer.name/items.0.sku 全拍平) | 2 行;A1 GMV=50、A2=30,总 80 |
| S12 | 总表按区域拆分导出 | groupBy 遍历 + toExcel/readExcel 回读校验 | 3 文件、行守恒 6、金额守恒 920 |
| S13 | SQL 直查内存表 | Jian.sql(GROUP BY+HAVING+ORDER BY);写语句被拒 | HAVING 后仅 食品 700;A 店 4 单 |
| S14 | 汇率按生效日就近折算 | mergeAsof(backward) + assign 行级乘积 | 汇率 7.1/7.2/7.3/**7.3**/7.4,合计 3173 |
| S15 | 用户行为统计月报 | colCorr ±1 + colCumsum + colDiff 首行 NA(§3.5) | corr ±1.0;累计 [10..210];diff 首行缺失 |
| S16 | 财务月报样式导出 | Styler 全链:rowBackgroundIf(亏损整行红底)+format 原生千分位+backgroundGradient+boldIf+columnBackground+自动列宽 | POI 读回:亏损行整格红底/营收 NUMERIC 且 "#,##0" 可求和/明星店加粗/列宽>默认 |

## 第二轮:抽象场景(S17~S46,跨行业通用口径)

> 写法刻意**抽象**(不绑具体行业数据):每类 = 一个通用需求 + jian 能力链 + 可手算预期。
> 完整断言源码见 `scenarios-src/` 对应测试类(括号标注)。

### 文件 ⇄ 数据库(ScenarioFileDatabaseTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S17 | 表格文件批量导入数据库(校验分流+错误回执) | toExcel/readExcel → assign 规则校验 → query 分流 → toSql(H2)→ readSqlTable | 合法 4 行入库、余额和 14000;回执 2 行(缺失/长度) |
| S18 | 数据库分析后导出带行列颜色的 Excel | readSqlTable → groupBy.agg → Styler(整行红底/千分位/加粗)→ POI 读回 | 滞销行红底、头部行加粗、数值可求和 1500 |
| S19 | 库间迁移一致性校验 | toSql×2 → readSqlTable×2 → merge(outer)→ query(单边/不等) | 差异 2 行(改坏 30 + 丢失 500)= 530;好行不误报 |
| S20 | 多源取数合并即席分析 | readSqlTable×2 → merge(inner)→ groupBy.agg | 按维度汇总 600/150 |

### 报表产出(ScenarioReportExportTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S21 | 定时多 sheet 报表 | groupBy/sortBy/head 三路加工 → Excel.writer 多 sheet → sheetNames+逐 sheet 读回 | 3 个 sheet 顺序正确;汇总和 1500;排行首行 400 |
| S22 | 接口导出数据加工管道(过滤+脱敏) | query → assign(敏感字段打星)→ select → toCsv/readCsv | 2 行;138****5678;原号不落盘 |

### 对账与数据质量(ScenarioReconcileQualityTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S23 | 多系统三方对账 | query(\|\| 组合三类差异条件) | 差异 2 单:金额差 30 + 未付 500 = 530 |
| S24 | 数据质量画像 | nullCount/colNunique/colMin/colMax/colQuantile/dropna | b 缺 1、唯一 2;c 唯一 4;完整行 4/5 |
| S25 | 中文脏数据清洗(NFKC/百分号/特殊值) | assign(自定义清洗函数返回 Double) | １２３→123;85%→0.85;New→0;" 42 "→42 |
| S26 | 增量数据保最新去重(CDC 语义) | sortBy(ts 倒序)→ dropDuplicates(subset,keep=first) | 9→3 行、全为 ts=3 版本、值和 69 |

### 日志与监控(ScenarioOpsMonitoringTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S27 | 访问日志 TopN 排行 | groupBy(IP).agg(count/sum)→ sortBy → head | Top1:3 次、带宽 600 |
| S28 | 错误风暴统计 | query(状态≥400)→ groupBy.count → sortBy | 集中报错路径 2 次居首;3xx 不入榜 |
| S29 | 性能分位数版本对比 | query + colQuantile(0.95)+ groupBy(mean) | v1 p95=385 / v2=405,回归 +20ms |
| S30 | 时序指标周期报表 | groupBy.agg(mean/max/min) | cpu 60/80/40;mem 55/60/50 |
| S31 | 慢查询排行 | sortBy(平均耗时,desc)→ head | Q1→Q4→Q2;快查询不入榜 |
| S32 | 安全日志统计 | query(FAILED)→ groupBy.count | 暴破 IP 4 次居首且唯一 |
| S33 | 可用性 SLA 月报 | assign(正常位 1/0)→ groupBy(mean/count) | 1 月 80%、2 月 100% |

### 时序处理(ScenarioTimeSeriesTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S34 | 高频数据降采样落库 | resample(5min).mean/count → toSql → readSqlTable | 桶均 3/8;计数守恒 10;落库 2 行 |
| S35 | 时序异常点识别(z 分数) | colMean/colStd → assign(z)→ query(z>3) | mean 24.5、var 4205;尖峰 z≈4.25 唯一命中 |
| S36 | 周期规律透视 | pivotTable(小时×星期) | 早高峰 90/100 > 晚高峰 60/70 |
| S37 | 滚动窗口指标 | Window.Rolling(3).mean/sum | [NaN,NaN,2,3,4];末位窗口和 12 |

### 统计分析(ScenarioStatsAnalysisTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S38 | 分组统计与组间对比 | groupBy(mean/std)+ t 统计量 | 均值 12/18、std 2、t≈3.674 |
| S39 | 指标相关性分析 | colCorr | 完全线性 1.0;混合 0.5 |
| S40 | 透视与排名 | pivotTable → assign(行合计)→ rank(min) | 500/450 → 名次 2/1 |
| S41 | 留存队列分析 | pivotTable → assign(D1/D7 留存率) | 一月 0.6/0.3;二月 0.7/0.24 |
| S42 | 漏斗转化分析 | assign(逐层转化率,首层 NaN §3.5) | 30%→40%→50%→60%;全链 3.6% |
| S43 | 增长趋势追踪 | colCumsum + assign(环比) | 累计 10/30/45/70;环比 2.0/0.75/5/3 |
| S44 | 嵌套结构拍平 | explode → groupBy.count | 2+2+3→7 行;高频词 3 次;计数守恒 |

### 审计合规(ScenarioAuditComplianceTest)

| # | 场景(抽象) | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S45 | 依赖版本漏洞审计 | Xml.read(rowName)→ merge(CVE 库)→ query(分数≥7) | 仅高危 1 服务(10.0);低分不误报 |
| S46 | 配置合规批量校验 | query(\|\| 越界组合条件)→ toMarkdown | 违规 3/4 服务;合规服务不落报告 |

## 30 秏上手(与场景同款风格)

```java
DataFrame df = Jian.readCsv("销售明细.csv");                          // S1
DataFrame 报表 = df.groupBy("品类").agg(Map.of("销售额", "sum"))
                   .sortBy("销售额_sum", false).head(2);
DataFrame 预警 = Jian.readExcel("库存.xlsx").query("现库存 < 安全库存")   // S7
                   .assign("可售天数", r -> ...);
```

## SQL 优势展示(SP 区:双写法差分,链式版 vs SQL 版逐行相等)

| # | 场景 | 链式版 | SQL 版(一条语句) |
|---|---|---|---|
| SP1 | 聚合报表 + TopN | groupBy→agg→sortBy→head(4 步 3 中间变量) | `SELECT 品类,sum(销售额) AS 合计 FROM ${t} GROUP BY 品类 HAVING 合计>400 ORDER BY 合计 DESC LIMIT 1` |
| SP2 | 复杂过滤 | query(AND + IN) + select | `SELECT 门店,品类 FROM ${t} WHERE 门店 IN ('A','B') AND 销售额 > 100` |
| SP3 | 连接 + 条件分层 | merge(inner) + assign 三元 | `SELECT 姓名,部门名,CASE WHEN 薪资>=10000 THEN '高' ELSE '常规' END AS 薪档 FROM ${e} JOIN ${d} ON e.部门ID=d.部门ID` |
| SP4 | 多步管道 | query → groupBy(2 个中间变量) | `WITH 大单 AS (SELECT * FROM ${t} WHERE 销售额>=100) SELECT 品类,count(*) AS 单数 FROM ${大单} GROUP BY 品类` |

每对结果**逐行断言相等**(差分测试):任一侧回归都会被另一方抓住。

## 安全写法(重要)

**用户可控值一律参数化,不要拼进表达式/SQL**:`Jian.query(df, "类别 == ${c}", Params.of("c", userInput))`
(引擎对占位值做字面量化 + `''` 翻倍转义);测试里的 `WHERE 类别 = '食品'` 拼接均为自控常量示范。
其它安全行为(XSS 转义 / SQL 标识符白名单 / readUrl 仅 http/https / ThreadLocal 容器警示)
见 `META-INF/ai/scenarios-src/jian/scenario/SecurityAuditTest.java`。

## 能力域覆盖

IO(CSV/Excel/JSON/**XML/SQL 库表**,**Excel 多 sheet**)· groupBy/agg · merge(inner/left/outer/asof)· pivot/melt ·
缺失值(isna/dropna/fillna)· resample · SQL(df.sql/Jian.sql)· 去重/排序/TopN · 派生列/统计(corr/cumsum/diff/**rank**)·
**explode 拍平** · **滚动窗口(Window.Rolling)** · **Styler 条件样式(整行着色/渐变/加粗/原生数字格式)** ·
**文件⇄数据库(toSql/readSqlTable,含校验分流与错误回执)**
