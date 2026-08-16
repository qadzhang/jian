# 06 · jian-num 需求说明书

> 版本:v1.0 · 日期:2026-08-01 · 作者:zc · 依赖:JDK 17 + Apache Commons Math 3.6.1
> **完全独立,不依赖 jian / jian-sql**。

---

## 1. 模块定位

### 1.1 一句话定位

jian-num 是对标 Python NumPy **子集**的 Java 库,**只实现 pandas/统计分析会用到的基础数值与统计能力**。复用 **Apache Commons Math 3.6.1**(2016 发布,稳定 10 年,已核实无影响 BUG),自写 numpy 风格的薄封装。**最小够用,不做大而全**。

### 1.2 选型核实结论(Commons Math 3.6.1,2026-08-01)

- ✅ **可用**:自 2016 年发布至今 10 年,没有"会导致数据错误"的重大未修复 BUG;Red Hat 维护有企业级 backport 版 `3.6.1.redhat-00001`。
- ✅ **jian-num 的用途避开所有已知问题**:mean/sum/std/variance/percentile/median/quantile/covariance/correlation 全是 3.6.1 最成熟、最被反复测试的部分。
- ⚠️ **已知问题与规避**:
  - `MATH-1502`(K-S 检验 exactP 算错)—— **不在 jian-num 范围**,不暴露此方法。
  - `MATH-1457`(`FastMath.exp` 极端值越界)—— jian-num 内部统一用 `java.lang.Math` 规避。
- ❌ **不用 4.0**:至今 beta(2022 年 beta1,未 GA),且把单 jar 拆成 5 个(rng/numbers/statistics/geometry/math4-legacy),引入成本反而高。

### 1.3 职责边界

**做(最小够用子集)**:

| 类别 | 内容 | 复用 Commons Math |
|---|---|---|
| **Ndarray**(基础) | 一维/二维数组容器 + 算术运算 + 切片 + 广播(简化版) | 否(自写,薄) |
| **描述统计** | mean/sum/min/max/variance/std/median/percentile/quantile/skewness/kurtosis | 是(`DescriptiveStatistics` / `StatUtils`) |
| **相关与协方差** | covariance matrix / pearson correlation / spearman correlation | 是(`Covariance` / `PearsonsCorrelation` / `SpearmansCorrelation`) |
| **简单线性代数** | 矩阵乘、转置、解线性方程组、最小二乘 | 是(`RealMatrix` / `LUDecomposition` / `OLSMultipleLinearRegression`) |
| **随机数** | uniform/normal/binomial 等 + 设置种子(可复现) | 是(`RandomDataGenerator`,统一指定种子) |
| **数值方法**(可选) | 简单曲线拟合(最小二乘)、插值(线性) | 是(`OLSMultipleLinearRegression` / 简单自写) |

**不做**:
- ❌ FFT、信号处理、图像处理 —— 不在数据分析常用范围。
- ❌ 多维(N>2)ndarray —— 二维够用。
- ❌ 完整广播规则 —— 只实现最常用情形(标量与数组、向量与矩阵按行/列)。
- ❌ 完整 ufunc 体系 —— 只提供常用算术与统计函数。
- ❌ 稀疏矩阵 —— 不需要。
- ❌ GPU/并行加速 —— 不追求极致性能。

### 1.4 依赖关系

```
jian-num  (单 jar)
   │
   └── commons-math3-3.6.1.jar (org.apache.commons:commons-math3)
```

> jian-num 完全独立。jian-core 通过 SPI(`StatsProvider` 接口,见 01-core §6.3)可选加载 jian-num,找不到则用 core 内置的简单实现——**core 不强依赖 jian-num**。

---

## 2. 核心 API

> **类名说明**:本节示例中的 `JianNum` 实际类名是 `jian.num.JianNum`(驼峰,**无连字符** —— Java 标识符不能含 `-`)。`import jian.num.JianNum;` 后即可使用下方所有静态方法。早期文档误写 `Jian-num.xxx`(连字符)会导致编译失败,已统一修正。

### 2.1 Ndarray 基础

```java
// 一维
Ndarray a = Ndarray.of(new double[]{1, 2, 3, 4, 5});
Ndarray b = Ndarray.of(new double[]{10, 20, 30, 40, 50});

a.add(b);           // 逐元素加,返回新 Ndarray
a.mul(2.0);         // 标量乘
a.slice(1, 4);      // 切片 [1,4) → [2,3,4]
a.sum();            // 求和
a.mean();           // 均值

// 二维
Ndarray m = Ndarray.of2d(new double[][]{{1,2},{3,4}});
m.T();              // 转置
m.shape();          // [2,2]
m.matmul(other);    // 矩阵乘
m.row(0);           // 取第 0 行
```

### 2.2 描述统计

```java
double[] data = ...;
JianNum.mean(data);           // 均值
JianNum.std(data);            // 标准差(样本,ddof=1)
JianNum.var(data, ddof=0);    // 方差(可选自由度修正)
JianNum.median(data);
JianNum.percentile(data, 25); // Q1
JianNum.quantile(data, 0.95);
JianNum.describe(data);       // 返回 Summary(count/mean/std/min/Q1/median/Q3/max)
```

### 2.3 相关与协方差

```java
double[] x = ..., y = ...;
JianNum.cov(x, y);                  // 协方差
JianNum.pearsonCorr(x, y);          // 皮尔逊相关系数
JianNum.spearmanCorr(x, y);         // 斯皮尔曼
JianNum.covarianceMatrix(matrix2d); // 协方差矩阵
JianNum.correlationMatrix(matrix2d);// 相关矩阵
```

### 2.4 简单线性代数

```java
Matrix A = Matrix.of(new double[][]{{1,2},{3,4}});
Matrix B = Matrix.of(new double[][]{{5,6},{7,8}});
A.mul(B);              // AB
A.transpose();
A.solve(b);            // 解 Ax=b(LU 分解)
A.leastSquares(b);     // 最小二乘解(超定方程)
double det = A.determinant();
```

### 2.5 随机数(可复现)

```java
JianNum.setSeed(42);     // 全局种子,确保结果可复现
JianNum.rand(10);        // 10 个 uniform[0,1)
JianNum.randn(10);       // 10 个标准正态
JianNum.randint(0, 100, 10);  // 10 个 [0,100) 整数
```

### 2.6 曲线拟合(可选)

```java
// y = a*x + b 的最小二乘拟合
double[] x = ..., y = ...;
LinearFit fit = JianNum.linearFit(x, y);
fit.slope(); fit.intercept(); fit.rSquared();
```

---

## 3. 实现要点

### 3.1 Ndarray 内部存储

- 一维:`double[]` + `shape`(同 pandas 的 Series)。
- 二维:`double[][]` 或 `double[]` + `strides`(选 row-major;性能差异对分析场景可忽略,选可读性优先的 `double[][]`)。
- NaN 处理:统计方法默认 skip NaN(与 `np.nanmean` 等一致);可配置为报错。

### 3.2 规避 FastMath 问题

```
// ┌─ What : 不使用 Commons Math 的 FastMath,统一用 java.lang.Math
// │  Why  : 规避 MATH-1457(FastMath.exp 在极端大输入时数组越界)
// │        对数据分析场景,Math 与 FastMath 的性能差异可忽略
// │  How  : jian-num 内部所有数学运算调 Math.*;
// │        直接调 Commons Math 的统计 API 时,这些 API 内部虽用 FastMath,
// │        但统计场景输入范围有限,不会触发越界(MATH-1457 只在 exp(极大值)触发)
```

### 3.3 与 jian 的桥接(可选)

- 实现 jian-core 的 `StatsProvider` SPI 接口,打成 `jian-num-bridge.jar`。
- 该 bridge 才依赖 jian-core;jian-num 核心不依赖 jian。
- 找不到 bridge 时,jian-core 的 describe() 等降级为内置简单实现(精度略低但够用)。

---

## 4. 边界与异常

| 场景 | 处理 |
|---|---|
| 空数组调统计 | 抛 `IllegalArgumentException("数据为空")` |
| 维度不匹配(矩阵乘) | 抛异常并提示期望与实际维度 |
| 奇异矩阵求逆 | 抛 `SingularMatrixException`,提示用 leastSquares |
| NaN 输入 | 默认 skip;`NaNPolicy.ERROR` 模式抛异常 |
| 浮点精度 | 结果与 numpy 同输入下差异 < 1e-10 |

---

## 5. 工作量

- **代码量**:自写约 2,000 行(Ndarray 500 + 统计封装 800 + 线代 500 + 随机数/拟合 200),测试 1,000 行。
- **测试基准**:与 numpy 同输入的输出对比(差异 < 1e-10);随机数种子可复现。

---

## 6. 验收标准

1. Ndarray 一维/二维的算术、切片、矩阵乘可用。
2. 描述统计 7 项(mean/std/var/median/percentile/quantile/describe)结果与 numpy 差异 < 1e-10。
3. 协方差、皮尔逊/斯皮尔曼相关可用。
4. 解线性方程组、最小二乘可用。
5. 随机数种子可复现(同种子同输出)。
6. 不使用 FastMath(规避 MATH-1457);不暴露 K-S 检验(规避 MATH-1502)。
7. jian-num 单独引可用,不强依赖 jian。

---

## 7. 实现说明

> 本节是 M0 阶段实际实现后的回填,记录"代码事实",与上文需求清单的偏差在此显式标注。

### 7.1 已实现的类与文件

| 文件 | 职责 | 行数(含注释) |
|---|---|---|
| `DType.java` | 5 种 dtype 枚举(INT64/FLOAT64/BOOL/DATETIME64/OBJECT)+ promote 类型提升规则 | ~50 |
| `Ndarray.java` | 多 dtype 一维数组引擎(算术/逻辑/比较/切片/astype/isna) | ~370 |
| `StrOps.java` | 字符串批量操作(upper/lower/strip/slice/replace/length/contains/startsWith/cat 等) | ~190 |
| `Stats.java` | 描述统计(mean/sum/min/max/count/std/var/percentile/quantile/median/skewness/kurtosis/describe) | ~200 |
| `Correlation.java` | 协方差/相关(cov/pearson/spearman/covarianceMatrix/correlationMatrix) | ~110 |
| `Matrix.java` | 矩阵与线代(of/mul/add/sub/transpose/solve/determinant/inverse/leastSquares) | ~190 |
| `JianNumRandom.java` | 可复现随机数(rand/randn/randint/binomial/choice) | ~100 |
| `LinearFit.java` | 简单线性最小二乘拟合(基于 OLSMultipleLinearRegression) | ~70 |
| `Summary.java` | describe() 返回的 record | ~25 |
| `NaNPolicy.java` | SKIP/ERROR/PROPAGATE 三态 | ~25 |
| `JianNum.java` | 顶层门面(静态方法聚合) | ~90 |
| 测试套件(Ndarray/Stats/Matrix 等) | 覆盖整数精度/字符串/NaN/统计/线代/矩阵运算/随机复现;数量以 [api-counts.md](api-counts.md) 为准 | ~280+ |

**编译/测试状态**:`mvn -pl jian-num/jian-num test` 全过,BUILD SUCCESS(当前 @Test 数见 [api-counts.md](api-counts.md))。

### 7.2 与需求的偏差(已实现部分)

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| 顶层 API `JianNum.mean(...)` | `JianNum.mean(...)` | Java 标识符不能含连字符,改为驼峰 `JianNum` |
| Ndarray 内部 `double[]` 单一存储 | 5 种 dtype 多存储(long[]/double[]/Boolean[]/Object[]) | 用户明确要求整数独立保留精度,字符串/日期/布尔各有别于浮点;对齐 numpy dtype 体系 |
| Ndarray 一维/二维 | **当前仅一维**(二维运算下沉到 `Matrix` 类) | 一维 Ndarray + 独立 Matrix 类职责更清晰;若 M2 发现 core 需要二维 Ndarray 再补 |
| 字符串走 object dtype | OBJECT dtype + 独立 `StrOps` 入口(对齐 pandas .str accessor) | 字符串使用频率最高,提供批量操作避免逐元素循环 |
| `Matrix.of(double[][])` 直接行向量 | 同 | 一致 |
| Commons Math `Percentile` 默认 | 实测与 numpy 'linear' 在非中位数有差异(R-6:Q1([1..5])=1.5,numpy R-7=2.0) | jian-core 的 `DataFrameStats.percentile` 已用自写 R-7 线性插值对齐 numpy;jian-num 保留 R-6(薄封装定位,javadoc 如实声明并钉精确值) |

### 7.3 实现状态(全部已落地)

- **已实现**:`jian-num-bridge` 实现 `StatsProvider` SPI(经 ServiceLoader 加载 jian-num 精确统计)。
- **已实现**:jian-core `DataFrameStats.percentile` 用 R-7 线性插值,对齐 numpy 'linear'(绕过 Commons Math 默认 R-6 差异)。
- **Ndarray INT64 缺失**:long[] 原生不支持 null,设计标注(需缺失用 FLOAT64 NaN 或 OBJECT null;DataFrame 层的 IntColumn/LongColumn 有 nullMask 完整支持)。

### 7.4 验证基准(测试中已覆盖)

- `np.mean([1,2,3,4,5])` = 3.0 ✓
- `np.std([1,2,3,4,5])`(ddof=0)= √2 ≈ 1.4142 ✓
- pandas `Series.std`(ddof=1)= 1.5811 ✓
- `pearsonr([1,2,3],[2,4,6])` = 1.0 ✓ / `([1,2,3],[3,2,1])` = -1.0 ✓
- `np.linalg.solve([[2,1],[1,3]],[3,2])` = [1.4, 0.2] ✓
- `np.linalg.det([[1,2],[3,4]])` = -2 ✓
- 大整数 `9_000_000_000_000_000_001L` 在 INT64 精确保留 ✓
- 10M 字符串在 OBJECT dtype 正常存取 ✓

---

*本分册独立,与 01-05 无耦合。完全独立可单独使用。*
*M0 实现完成,v1.0 发布;当前测试数以 [api-counts.md](api-counts.md) 为准。*

---

### 7.5 行为细节(现行)

- **jian-num**:zerosBool 全 false;INT64 sum/mean 纯 long 精度;StrOps.length 返回 FLOAT64(缺失→NaN);常数列 skew/kurt 返 NaN;sum 遇 inf+(-inf) 返 NaN。
- **jian-num-bridge**:percentile 对齐 SPI 契约的 R-7 插值(与 pandas/numpy 'linear' 同口径)。
- 测试:num 59 / num-bridge 11 @Test(口径见 [api-counts.md](api-counts.md))。
