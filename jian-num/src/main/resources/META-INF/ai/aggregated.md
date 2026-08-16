# jian-num-all 聚合 jar · AI 总索引

> 本 jar 是**聚合 jar**(MANIFEST `Ai-Aggregated: true`),内含 jian-num 数值库 1 个子模块。
> 模块文档:`META-INF/ai/modules/jian-num/module.md`;thin jar 的文档在 `META-INF/ai/module.md`。

## 这是什么库

JVM 上对标 numpy 子集的独立数值计算库(不依赖 jian):**多 dtype Ndarray + Matrix 线代 + Stats 统计 + 线性拟合 + 随机数**,经 SPI(`StatsProvider`)可被 jian-core 可选加载以加速统计。

## 30 秒上手

```java
import jian.num.JianNum;
import jian.num.Matrix;
import jian.num.Ndarray;
import jian.num.LinearFit;

Ndarray a = Ndarray.of(new double[]{1, 2, 3, 4});
double m = JianNum.mean(new double[]{1.5, 2.5, 3.5});            // 统计
Matrix mm = Matrix.of(new double[][]{{1, 2}, {3, 4}}).matmul(Matrix.identity(2));  // 矩阵乘
LinearFit fit = LinearFit.fit(new double[]{1, 2, 3}, new double[]{2, 4, 6});      // 最小二乘(record: slope/intercept/rSquared)
double y = fit.predict(4);
```

## 模块清单

| 模块 | 干什么 | 关键外部依赖 |
|---|---|---|
| jian-num | 数值计算:Ndarray(多 dtype)/Matrix(线代)/Stats/LinearFit/Random | commons-math3 3.6.1(有意选稳定版) |

## 相关库

- `jian-all`(数据栈:DataFrame + IO + SQL DSL,可经 jian-num-bridge 自动发现本库)
- `jian-sql-all`(数据库引擎栈)
