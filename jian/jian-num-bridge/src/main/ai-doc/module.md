# jian-num-bridge

## 基本信息
- **library**: jian
- **entryClass**: jian.num.bridge.NumStatsProvider
- **deps**: jian-core(StatsProvider SPI);jian-num(Stats / Correlation,基于 Commons Math 3.6.1)

## 摘要
jian-num → jian-core 的 StatsProvider SPI 桥接实现;引此 jar 后,core 的相关统计从内置简单实现自动升级为 Commons Math 精确实现。

## 能力
- 实现 `jian.core.StatsProvider`,经 ServiceLoader 自动注册(META-INF/services/jian.core.StatsProvider)
- pearson / spearman 相关系数:委托 `jian.num.Correlation`(配对剔除 NaN)
- covariance:委托 `Correlation.cov`(样本,ddof=1)
- percentile:委托 `Stats.percentile`(Commons Math)
- skewness / kurtosis:委托 `Stats`(Commons Math 矩法)
- 引擎名 `jian-num-commons-math`

## 限制
- 仅升级 core 已定义的 SPI 方法集;新增统计能力请直接用 jian-num
- percentile 在 SPI 要求 R-7(对齐 numpy)与 jian-num Stats 的 R-6 之间有小差异(v2 可切换)
- 需保证 classpath 同时有 jian-num 与 commons-math3,否则 ServiceLoader 加载失败

## 快速上手
```java
// 用户代码无需感知本桥:
// 只要 classpath 含 jian-num-bridge,DataFrame.corr() 自动走 Commons Math 精确实现
import jian.core.DataFrame;

double r = df.col("x").corr(df.col("y"));   // 自动用 pearson(委托 jian-num)
double q = df.col("score").percentile(0.95);
```
