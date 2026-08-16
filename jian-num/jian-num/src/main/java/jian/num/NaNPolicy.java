package jian.num;

// ┌─ What : NaN 处理策略枚举(对齐 numpy 的 nan* 系列函数的可配行为)
// │  Why  : 描述统计在遇到 Double.NaN 时,pandas/numpy 默认 skip(跳过),但用户可能希望报错;
//          用枚举把策略显式化,避免散落的 if 判断,符合规范 §3.3 注释可读性要求
// │  Who  : 由 Stats / Ndarray / Correlation 等统计方法在计算前查询
// │  When : 每个数组进入统计计算前,根据策略决定 NaN 去留
// │  Where: jian-num/NaNPolicy.java
// │  How  : 数据走向:输入 double[] →(按 policy 过滤 NaN)→ 干净的 double[] → 统计算法。
// │         关键变量变化:无(纯策略枚举,不持有状态)。
// │         逻辑路线:
// │           路径 A(SKIP,默认)→ 过滤掉所有 NaN 后计算(等价 np.nanmean);
// │           路径 B(ERROR)→ 检测到 NaN 立即抛 IllegalArgumentException,提示改用 SKIP;
// │           路径 C(PROPAGATE)→ 不处理 NaN,直接交给 Commons Math(结果通常是 NaN)。
public enum NaNPolicy {
    /** 跳过 NaN,只用非 NaN 值计算(对齐 np.nanmean/nansum,默认行为) */
    SKIP,

    /** 遇到 NaN 立即抛 IllegalArgumentException(严格模式,数据质量校验场景) */
    ERROR,

    /** 不处理 NaN,直接计算(结果可能为 NaN,对齐 numpy 非 nan* 函数的默认行为) */
    PROPAGATE;

    /** 默认策略:pandas/numpy 统计场景最常用 */
    // 因为非 final 的 DEFAULT 可被外部重赋值,会破坏全局默认策略的单例契约,
    // 所以声明为 final
    public static final NaNPolicy DEFAULT = SKIP;
}
