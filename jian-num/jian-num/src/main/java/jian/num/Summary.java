package jian.num;

// ┌─ What : 描述统计摘要(对齐 pandas Series.describe() / numpy 的基本统计概览)
// │  Why  : describe() 需要一次返回 count/mean/std/min/Q1/median/Q3/max 八个量,
// │        用 record 封装不可变,避免散落的数组或多次重复计算
// │  Who  : 由 Stats.describe() 构造返回;用户代码消费
// │  When : 用户调 JianNum.describe(data) 时
// │  Where: jian-num/Summary.java
// │  How  : 数据走向:double[] → Stats.describe() 计算 8 个量 → Summary 实例 → 用户读取字段。
// │         关键变量变化:8 个字段一旦构造不可变(record 特性)。
// │         逻辑路线:仅构造与读取,无分支。
/**
 * 描述统计摘要,由 {@link Stats#describe} 返回。
 *
 * <p>字段对齐 pandas {@code df.describe()} 的默认输出:count / mean / std / min / 25% / 50% / 75% / max。
 * std 默认样本标准差(ddof=1),与 pandas 一致。
 *
 * @param count  long 非 NaN 值个数
 * @param mean   double 均值
 * @param std    double 样本标准差(ddof=1)
 * @param min    double 最小值
 * @param q1     double 25% 分位数(下四分位)
 * @param median double 50% 分位数(中位数)
 * @param q3     double 75% 分位数(上四分位)
 * @param max    double 最大值
 */
public record Summary(
        long count,      // 非 NaN 值个数
        double mean,     // 均值
        double std,      // 样本标准差(ddof=1)
        double min,      // 最小值
        double q1,       // 25% 分位数(下四分位)
        double median,   // 50% 分位数(中位数)
        double q3,       // 75% 分位数(上四分位)
        double max       // 最大值
) {
    @Override
    public String toString() {
        return String.format(
                "Summary{count=%d, mean=%.6f, std=%.6f, min=%.6f, Q1=%.6f, median=%.6f, Q3=%.6f, max=%.6f}",
                count, mean, std, min, q1, median, q3, max);
    }
}
