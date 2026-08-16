# jian-num

## 基本信息
- **library**: jian-num
- **entryClass**: jian.num.JianNum(顶层静态门面)/ jian.num.Ndarray / jian.num.Matrix / jian.num.Stats / jian.num.Correlation / jian.num.StrOps
- **deps**: Commons Math 3.6.1(统计/相关/线代 RealMatrix);纯 JDK 其余
- **tests**: 59

## 摘要
jian 的数值计算库,对标 numpy 子集:多 dtype Ndarray、Matrix 线代、Stats 描述统计、Correlation 相关系数、StrOps 矢量字符串操作、LinearFit 线性拟合。

## 能力
- Ndarray:5 种 dtype(INT64/FLOAT64/BOOL/DATETIME64/OBJECT);of/zerosInt/zerosFloat/zerosBool;astype;add/sub/mul/div(逐元素 + 标量);and/or/not;切片/索引
- Matrix:基于 Commons Math RealMatrix;of/identity;add/sub/mul/matmul/transpose(T);determinant/inverse
- Stats:mean/sum/min/max/count/std/var(ddof)/median/percentile/quantile/skewness/kurtosis/describe(Summary);NaNPolicy 可控;percentile/quantile 插值为 Commons Math 默认 **R-6**(Q1([1..5])=1.5,与 numpy 'linear'/R-7 的 2.0 在非中位数分位有差异;numpy 口径用 jian-core DataFrameStats.percentile 自写 R-7 —— javadoc 如实声明)
- Correlation:cov / pearson / spearman(配对剔除 NaN,有效样本 <3 抛异常)
- StrOps:upper/lower/strip/repeat/slice/replace/replaceRegex/pad/length/contains/startsWith/endsWith(返回 Ndarray)
- JianNum:顶层静态门面(mean/describe/random 等)+ 全局随机数种子(JianNumRandom)
- LinearFit:最小二乘线性拟合

### 行为细节
- INT64 算术纯 long 精确(除法提升 FLOAT64 对齐 numpy true divide);整数+浮点标量提升 FLOAT64
- randint 支持全范围/[low,high);binomial p=NaN / percentile q=NaN 拒绝;choice k<0 教学 IAE

- Stats.sum/median 遇 ±inf 对齐 numpy(不返 NaN)

### 行为细节(续 1)
- zerosBool 全 false;INT64 sum/mean 纯 long 精度
- StrOps.length 返回 FLOAT64(缺失→NaN);常数列 skew/kurt 返 NaN;sum 遇 inf+(-inf) 返 NaN

## 限制
- Ndarray 一维为主(无完整 n 维 shape/stride;Matrix 承担二维)
- 不对标 numpy 的广播全套、einsum、FFT、linspace/arange 全集(仅统计实用子集)
- OBJECT dtype 字符串是高频路径,但不支持任意 UFUNC 注册

## 快速上手
```java
import jian.num.Ndarray;
import jian.num.Matrix;
import jian.num.Stats;
import jian.num.Correlation;

Ndarray a = Ndarray.of(new double[]{1, 2, 3, Double.NaN});
double m  = Stats.mean(a.toDoubleArray());           // NaN 自动跳过
double sd = Stats.std(a.toDoubleArray());

Matrix x = Matrix.of(new double[][]{{1, 2}, {3, 4}});
Matrix inv = x.inverse();
double det = x.determinant();

double r = Correlation.pearson(xs, ys);   // 配对剔除 NaN
```
