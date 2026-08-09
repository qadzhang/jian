package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第二轮审查(AI agent2 + AI agent2)代码侧修复回归测试。
 *
 * <p>覆盖 jian-core 三处修复:
 * <ul>
 *   <li>#4 {@link DataFrameMerge#compareAsf} —— 混型 Comparable 比较不再抛 CCE</li>
 *   <li>#5 {@link DataFrame#cmp} —— 混型 == 恒 false(对齐 pandas 1.5.3 实测语义)</li>
 *   <li>#6 {@link DataFrame#loc} —— RangeIndex 非数字标签 / 非整数数字标签 / 越界 抛带语义异常</li>
 * </ul>
 *
 * <p>对应 AI 测试方法学指南 铁律:3(修复每个 bug 后写重现代码测试)、5(混合 dtype 边界)。
 * 商议过程见 第二轮审查共识记录(AI agent2 与主 agent 共识)。
 */
class Round2AuditFixTest {

    // ======================== #4 compareAsf 混型 CCE ========================

    /**
     * #4 回归:merge_asof 的 on 列出现 String vs Number 混型时,compareAsf 不应抛 CCE。
     *
     * <p>构造难点:merge_asof 的 on 列通常同型(都是时间或都是数值);这里通过反射直接测 compareAsf
     * 的混型分支(方法私有,用反射访问)。如果方法签名变化,本测试会失败 —— 这是有意的。
     */
    @Test
    void compareAsf_混型不抛CCE走String字典序() throws Exception {
        java.lang.reflect.Method m = DataFrameMerge.class.getDeclaredMethod(
            "compareAsf", Object.class, Object.class);
        m.setAccessible(true);
        // String vs Number —— 原 ca.compareTo(b) 会抛 CCE,现应走 String 字典序
        int r1 = (int) m.invoke(null, "abc", 123);
        int r2 = "abc".compareTo(String.valueOf(123));
        assertThat(r1).isEqualTo(r2);
        // 反向:Number vs String
        int r3 = (int) m.invoke(null, 123, "abc");
        assertThat(r3).isEqualTo(String.valueOf(123).compareTo("abc"));
    }

    /**
     * #4 回归:同型 Number 仍走数值比(不被 String 兜底误伤);
     * 同型 String 仍走 compareTo(不降级为 String.valueOf 字典序 —— 虽然结果一样,但路径要对)。
     */
    @Test
    void compareAsf_同型路径不被混型修复影响() throws Exception {
        java.lang.reflect.Method m = DataFrameMerge.class.getDeclaredMethod(
            "compareAsf", Object.class, Object.class);
        m.setAccessible(true);
        // 同型 Number:1.0 < 2.0
        assertThat((int) m.invoke(null, 1.0, 2.0)).isNegative();
        assertThat((int) m.invoke(null, 2.0, 1.0)).isPositive();
        assertThat((int) m.invoke(null, 1.5, 1.5)).isZero();
        // 同型 String
        assertThat((int) m.invoke(null, "a", "b")).isNegative();
        assertThat((int) m.invoke(null, "b", "a")).isPositive();
        // null 仍按"极小"处理
        assertThat((int) m.invoke(null, null, 1.0)).isNegative();
        assertThat((int) m.invoke(null, 1.0, null)).isPositive();
        assertThat((int) m.invoke(null, null, null)).isZero();
    }

    // ======================== #5 cmp 混型 == 恒 false(pandas 对齐)========================

    /**
     * #5 回归:混型 == 恒 false、!= 恒 true(对齐 pandas 1.5.3 实测)。
     *
     * <p>关键判据(AI agent2 用本机 pandas 1.5.3 实测):
     * <pre>
     * pd.Series(['1','2']) == 1   → [False, False]   # 字符串列 == 数字 恒 False
     * pd.Series([1.0, 2.0]) == '1' → [False, False]  # 数值列 == 字符串 恒 False
     * </pre>
     * 原代码 a.equals(b) 对混型也是恒 false(类型不同),本测试同时锁定新行为不退化为 c==0。
     */
    @Test
    void cmp_混型相等比较恒false对齐pandas() throws Exception {
        java.lang.reflect.Method m = DataFrame.class.getDeclaredMethod(
            "cmp", Object.class, String.class, Object.class);
        m.setAccessible(true);
        // String vs Number(==)
        assertThat((boolean) m.invoke(null, "1", "==", 1)).isFalse();
        assertThat((boolean) m.invoke(null, "1.0", "==", 1.0)).isFalse();
        assertThat((boolean) m.invoke(null, 1, "==", "1")).isFalse();
        // String vs Number(!=)
        assertThat((boolean) m.invoke(null, "1", "!=", 1)).isTrue();
        assertThat((boolean) m.invoke(null, 1, "!=", "1")).isTrue();
    }

    /**
     * #5 回归:同型 Number 用 == 数值比(±0.0 等价、NaN≠NaN,与 IEEE/pandas 一致)。
     */
    @Test
    void cmp_同型Number数值比正零负零等价() throws Exception {
        java.lang.reflect.Method m = DataFrame.class.getDeclaredMethod(
            "cmp", Object.class, String.class, Object.class);
        m.setAccessible(true);
        // +0.0 == -0.0(IEEE 数值相等,原 equals 会判不等 —— 修复后正确)
        assertThat((boolean) m.invoke(null, 0.0, "==", -0.0)).isTrue();
        assertThat((boolean) m.invoke(null, -0.0, "==", 0.0)).isTrue();
        // 1L == 1.0(Long vs Double 但都是 Number → 数值比 → 等)
        assertThat((boolean) m.invoke(null, 1L, "==", 1.0)).isTrue();
        assertThat((boolean) m.invoke(null, 1.0, "==", 1L)).isTrue();
        // NaN ≠ NaN
        assertThat((boolean) m.invoke(null, Double.NaN, "==", Double.NaN)).isFalse();
        assertThat((boolean) m.invoke(null, Double.NaN, "!=", Double.NaN)).isTrue();
    }

    /**
     * #5 回归:同型 String 走 compareTo(覆盖 String==String 主用例)。
     */
    @Test
    void cmp_同型String走CompareTo() throws Exception {
        java.lang.reflect.Method m = DataFrame.class.getDeclaredMethod(
            "cmp", Object.class, String.class, Object.class);
        m.setAccessible(true);
        assertThat((boolean) m.invoke(null, "abc", "==", "abc")).isTrue();
        assertThat((boolean) m.invoke(null, "abc", "==", "abd")).isFalse();
        assertThat((boolean) m.invoke(null, "abc", "!=", "abd")).isTrue();
        assertThat((boolean) m.invoke(null, "b", ">", "a")).isTrue();
        assertThat((boolean) m.invoke(null, "a", "<", "b")).isTrue();
    }

    /**
     * #5 回归:混型顺序比较(> <)维持既有 String 字典序(jian 宽厚行为,不抛 TypeError)。
     *
     * <p>这是 jian 对 pandas 的有意宽厚(AI agent2 共识):不把 String 列与数值比直接搞崩。
     * 但绝不把 == 也抬上去(== 必须停在与 pandas 一致的那一侧)。
     */
    @Test
    void cmp_混型顺序比较维持字典序宽厚行为() throws Exception {
        java.lang.reflect.Method m = DataFrame.class.getDeclaredMethod(
            "cmp", Object.class, String.class, Object.class);
        m.setAccessible(true);
        // "10" vs 9:String "10" < "9"(字典序,因为 '1' < '9')
        assertThat((boolean) m.invoke(null, "10", "<", 9)).isTrue();
        // 9 vs "10":9 > "10"(字典序反向)
        assertThat((boolean) m.invoke(null, 9, ">", "10")).isTrue();
    }

    // ======================== #6 loc RangeIndex 类型/整数/越界检查 ========================

    @Test
    void loc_rangeIndex非数字标签抛IAE带类型提示() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        // RangeIndex 下传 String 标签 → 原 CCE,现 IAE 带类型提示
        assertThatThrownBy(() -> df.loc("abc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RangeIndex")
            .hasMessageContaining("String");
    }

    @Test
    void loc_rangeIndexNull标签抛IAE() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        assertThatThrownBy(() -> df.loc((Object) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    void loc_rangeIndex非整数数字标签抛IAE避免静默截断() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        // 2.5 原 intValue() 静默截断为 2(取错行),现 IAE 拒绝
        assertThatThrownBy(() -> df.loc(2.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("整数")
            .hasMessageContaining("2.5");
    }

    @Test
    void loc_rangeIndex越界抛IndexOutOfBounds() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});  // 只有 0/1/2
        assertThatThrownBy(() -> df.loc(5))
            .isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessageContaining("越界");
        assertThatThrownBy(() -> df.loc(-1))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void loc_rangeIndex合法整数下标仍正常工作() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{10L}, {20L}, {30L}});
        // 回归:合法用法不能被新检查破坏
        DataFrame r = df.loc(0, 2);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.get(0, "v")).isEqualTo(10L);
        assertThat(r.get(1, "v")).isEqualTo(30L);
    }
}
