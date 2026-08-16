package jian.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : 阶段 0 基础设施测试 —— MultiIndex N 级 + DatetimeIndex + Frequency
// │  Why  : 这三个类是后续阶段 C(stack/unstack)和阶段 D(resample/asfreq/shift)的依赖
// │  Who  : 阶段 C/D 算子通过它们实现
// │  When : jian-core 测试套件常规执行
// │  Where: jian-core/src/test/java/jian/core/InfrastructureTest.java
class InfrastructureTest {

    // ======================== MultiIndex N 级 ========================

    @Test
    void MultiIndex_N级构造_各级长度一致_OK() {
        MultiIndex mi = MultiIndex.of(
            new String[]{"dept", "uid", "day"},
            new Object[][]{
                new Object[]{"RD", "RD", "PM"},
                new Object[]{1, 2, 3},
                new Object[]{"Mon", "Tue", "Wed"}});
        assertThat(mi.numLevels()).isEqualTo(3);
        assertThat(mi.size()).isEqualTo(3);
        assertThat(mi.get(0, 1)).isEqualTo("RD");
        assertThat(mi.get(1, 2)).isEqualTo(3);
        assertThat(mi.names()).containsExactly("dept", "uid", "day");
    }

    @Test
    void MultiIndex_2级兼容旧API_OK() {
        MultiIndex mi = MultiIndex.of(
            new Object[]{"RD", "PM"},
            new Object[]{1, 2});
        assertThat(mi.numLevels()).isEqualTo(2);
        assertThat(mi.getLevel0(0)).isEqualTo("RD");
        assertThat(mi.getLevel1(1)).isEqualTo(2);
        assertThat(mi.level0()).containsExactly("RD", "PM");
    }

    @Test
    void MultiIndex_各级长度不一致_抛IAE() {
        assertThatThrownBy(() -> MultiIndex.of(
            new String[]{"a", "b"},
            new Object[][]{
                new Object[]{1, 2, 3},
                new Object[]{1, 2}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("level 1 长度");
    }

    @Test
    void MultiIndex_droplevel_OK() {
        MultiIndex mi = MultiIndex.of(
            new String[]{"a", "b", "c"},
            new Object[][]{
                new Object[]{1, 2},
                new Object[]{3, 4},
                new Object[]{5, 6}});
        MultiIndex dropped = mi.droplevel(0, 2);
        assertThat(dropped.numLevels()).isEqualTo(1);
        assertThat(dropped.names()).containsExactly("b");
        assertThat(dropped.get(0, 0)).isEqualTo(3);
    }

    @Test
    void MultiIndex_droplevel全删抛IAE() {
        MultiIndex mi = MultiIndex.of(new Object[]{1}, new Object[]{2});
        assertThatThrownBy(() -> mi.droplevel(0, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能删除所有级");
    }

    @Test
    void MultiIndex_swaplevel_OK() {
        MultiIndex mi = MultiIndex.of(
            new String[]{"a", "b"},
            new Object[][]{new Object[]{1, 2}, new Object[]{"x", "y"}});
        MultiIndex swapped = mi.swaplevel(0, 1);
        assertThat(swapped.name(0)).isEqualTo("b");
        assertThat(swapped.name(1)).isEqualTo("a");
        assertThat(swapped.get(0, 0)).isEqualTo("x");
        assertThat(swapped.get(1, 0)).isEqualTo(1);
    }

    @Test
    void MultiIndex_reorder_levels_OK() {
        MultiIndex mi = MultiIndex.of(
            new String[]{"a", "b", "c"},
            new Object[][]{
                new Object[]{1, 2},
                new Object[]{3, 4},
                new Object[]{5, 6}});
        MultiIndex reordered = mi.reorder_levels(2, 0, 1);
        assertThat(reordered.names()).containsExactly("c", "a", "b");
        assertThat(reordered.get(0, 0)).isEqualTo(5);
        assertThat(reordered.get(1, 0)).isEqualTo(1);
    }

    @Test
    void MultiIndex_reorder_levels长度不符抛IAE() {
        MultiIndex mi = MultiIndex.of(
            new String[]{"a", "b"},
            new Object[][]{new Object[]{1}, new Object[]{2}});
        assertThatThrownBy(() -> mi.reorder_levels(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("顺序长度");
    }

    @Test
    void MultiIndex_slice_OK() {
        MultiIndex mi = MultiIndex.of(
            new String[]{"a", "b"},
            new Object[][]{
                new Object[]{1, 2, 3, 4},
                new Object[]{5, 6, 7, 8}});
        MultiIndex sliced = mi.slice(1, 3);
        assertThat(sliced.size()).isEqualTo(2);
        assertThat(sliced.get(0, 0)).isEqualTo(2);
        assertThat(sliced.get(1, 1)).isEqualTo(7);
    }

    // ======================== DatetimeIndex ========================

    @Test
    void DatetimeIndex_基础构造_OK() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0),
            LocalDateTime.of(2026, 1, 3, 0, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts, "1D");
        assertThat(di.size()).isEqualTo(3);
        assertThat(di.freq()).isEqualTo("1D");
        assertThat(di.get(1)).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
    }

    @Test
    void DatetimeIndex_已知频率非升序抛IAE() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 3, 0, 0),
            LocalDateTime.of(2026, 1, 1, 0, 0)};  // 倒序
        assertThatThrownBy(() -> DatetimeIndex.of(ts, "1D"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("非升序");
    }

    @Test
    void DatetimeIndex_未知频率不校验升序() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 3, 0, 0),
            LocalDateTime.of(2026, 1, 1, 0, 0)};  // 乱序
        DatetimeIndex di = DatetimeIndex.of(ts);  // freq=null
        assertThat(di.freq()).isNull();
        assertThat(di.size()).isEqualTo(2);
    }

    @Test
    void DatetimeIndex_firstLastValidIndex() {
        LocalDateTime[] ts = {
            null,
            LocalDateTime.of(2026, 1, 2, 0, 0),
            null,
            LocalDateTime.of(2026, 1, 4, 0, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        assertThat(di.firstValidIndex()).isEqualTo(OptionalInt.of(1));
        assertThat(di.lastValidIndex()).isEqualTo(OptionalInt.of(3));
    }

    @Test
    void DatetimeIndex_firstValidIndex全null() {
        DatetimeIndex di = DatetimeIndex.of(new LocalDateTime[]{null, null});
        assertThat(di.firstValidIndex()).isEmpty();
        assertThat(di.lastValidIndex()).isEmpty();
    }

    @Test
    void DatetimeIndex_atTime() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 9, 30),
            LocalDateTime.of(2026, 1, 1, 14, 0),
            LocalDateTime.of(2026, 1, 2, 9, 30)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        int[] nineThirty = di.atTime(LocalTime.of(9, 30));
        assertThat(nineThirty).containsExactly(0, 2);
    }

    @Test
    void DatetimeIndex_betweenTime普通区间() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 8, 0),
            LocalDateTime.of(2026, 1, 1, 12, 0),
            LocalDateTime.of(2026, 1, 1, 18, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        int[] business = di.betweenTime(LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertThat(business).containsExactly(1);  // 12:00
    }

    @Test
    void DatetimeIndex_betweenTime跨午夜() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 23, 0),
            LocalDateTime.of(2026, 1, 2, 1, 0),
            LocalDateTime.of(2026, 1, 2, 10, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        int[] night = di.betweenTime(LocalTime.of(22, 0), LocalTime.of(2, 0));
        assertThat(night).containsExactly(0, 1);  // 23:00 和 01:00
    }

    @Test
    void DatetimeIndex_asofIndex() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0),
            LocalDateTime.of(2026, 1, 4, 0, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        // asof(1月3日) 应返回 ≤ 1月3日 的最后一个观测,即 1月2日(下标 1)
        OptionalInt i = di.asofIndex(LocalDateTime.of(2026, 1, 3, 12, 0));
        assertThat(i).hasValue(1);
        // asof 早于一切时返回 empty
        assertThat(di.asofIndex(LocalDateTime.of(2025, 12, 31, 0, 0))).isEmpty();
    }

    @Test
    void DatetimeIndex_inferFreq_日级() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0),
            LocalDateTime.of(2026, 1, 3, 0, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        assertThat(di.inferFreq()).isEqualTo("1D");
    }

    @Test
    void DatetimeIndex_inferFreq_小时级() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 1, 2, 0),
            LocalDateTime.of(2026, 1, 1, 4, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts);
        assertThat(di.inferFreq()).isEqualTo("2H");
    }

    @Test
    void DatetimeIndex_inferFreq_不等间隔_unknown() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0),
            LocalDateTime.of(2026, 1, 5, 0, 0)};  // 1D + 3D
        DatetimeIndex di = DatetimeIndex.of(ts);
        assertThat(di.inferFreq()).isEqualTo("unknown");
    }

    @Test
    void DatetimeIndex_slice_OK() {
        LocalDateTime[] ts = {
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0),
            LocalDateTime.of(2026, 1, 3, 0, 0),
            LocalDateTime.of(2026, 1, 4, 0, 0)};
        DatetimeIndex di = DatetimeIndex.of(ts, "1D");
        DatetimeIndex sliced = di.slice(1, 3);
        assertThat(sliced.size()).isEqualTo(2);
        assertThat(sliced.get(0)).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(sliced.freq()).isEqualTo("1D");
    }

    // ======================== Frequency ========================

    @Test
    void Frequency_parse日级() {
        Frequency f = Frequency.parse("1D");
        assertThat(f.amount()).isEqualTo(1);
        assertThat(f.unit()).isEqualTo(Frequency.Unit.DAYS);
        assertThat(f.toString()).isEqualTo("1D");
    }

    @Test
    void Frequency_parse小时级省略1() {
        Frequency f = Frequency.parse("h");  // 默认 1h
        assertThat(f.amount()).isEqualTo(1);
        assertThat(f.unit()).isEqualTo(Frequency.Unit.HOURS);
    }

    @Test
    void Frequency_parse月级() {
        Frequency f = Frequency.parse("3ME");  // 月末对齐(pandas MonthEnd)
        assertThat(f.amount()).isEqualTo(3);
        assertThat(f.unit()).isEqualTo(Frequency.Unit.MONTH_END);
    }

    @Test
    void Frequency_parse月初月末区分() {
        // 因为 M/MS/ME 是三个不同的锚点语义单位,所以解析须各自映射(MS=月初、ME=月末)
        assertThat(Frequency.parse("1M").unit()).isEqualTo(Frequency.Unit.MONTHS);
        assertThat(Frequency.parse("1MS").unit()).isEqualTo(Frequency.Unit.MONTH_START);
        assertThat(Frequency.parse("1ME").unit()).isEqualTo(Frequency.Unit.MONTH_END);
    }

    @Test
    void Frequency_月初月末锚点() {
        // MONTH_START:1/15 → 当月 1 日;MONTH_END:1/15 → 上月末(floor 语义)
        LocalDateTime t = LocalDateTime.of(2026, 1, 15, 10, 30);
        assertThat(Frequency.parse("1MS").alignStart(t)).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(Frequency.parse("1ME").alignStart(t)).isEqualTo(LocalDateTime.of(2025, 12, 31, 0, 0));
        assertThat(Frequency.parse("1ME").alignStart(LocalDateTime.of(2026, 1, 31, 8, 0)))
                .isEqualTo(LocalDateTime.of(2026, 1, 31, 0, 0));
        // 月末加法防漂移:1/31 + 1ME = 2/28(不是 3/3)
        assertThat(Frequency.parse("1ME").plus(LocalDateTime.of(2026, 1, 31, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 2, 28, 0, 0));
        assertThat(Frequency.parse("1MS").plus(LocalDateTime.of(2026, 1, 1, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        // 非月单位 alignStart 原样返回
        assertThat(Frequency.parse("1D").alignStart(t)).isEqualTo(t);
    }

    @Test
    void Frequency_parse季级() {
        Frequency f = Frequency.parse("2Q");
        assertThat(f.unit()).isEqualTo(Frequency.Unit.QUARTERS);
        assertThat(f.amount()).isEqualTo(2);
    }

    @Test
    void Frequency_parse非法格式抛IAE() {
        assertThatThrownBy(() -> Frequency.parse("xyz"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的频率格式");
    }

    @Test
    void Frequency_plus加减日级() {
        Frequency f = Frequency.parse("1D");
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(f.plus(t)).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(f.minus(t)).isEqualTo(LocalDateTime.of(2025, 12, 31, 0, 0));
    }

    @Test
    void Frequency_plus加减小时级() {
        Frequency f = Frequency.parse("2h");
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(f.plus(t)).isEqualTo(LocalDateTime.of(2026, 1, 1, 2, 0));
        assertThat(f.minus(t)).isEqualTo(LocalDateTime.of(2025, 12, 31, 22, 0));
    }

    @Test
    void Frequency_plus加减月级() {
        Frequency f = Frequency.parse("1M");
        LocalDateTime t = LocalDateTime.of(2026, 1, 15, 12, 0);
        assertThat(f.plus(t)).isEqualTo(LocalDateTime.of(2026, 2, 15, 12, 0));
        assertThat(f.minus(t)).isEqualTo(LocalDateTime.of(2025, 12, 15, 12, 0));
    }

    @Test
    void Frequency_plus加减季级() {
        Frequency f = Frequency.parse("1Q");  // 1 季 = 3 月
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(f.plus(t)).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    void Frequency_range生成时间网格() {
        Frequency f = Frequency.parse("1D");
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime[] grid = f.range(start, 5);
        assertThat(grid).hasSize(5);
        assertThat(grid[0]).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(grid[4]).isEqualTo(LocalDateTime.of(2026, 1, 5, 0, 0));
    }

    @Test
    void Frequency_stepsBetween日级() {
        Frequency f = Frequency.parse("1D");
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 11, 0, 0);
        assertThat(f.stepsBetween(from, to)).isEqualTo(10);
    }

    @Test
    void Frequency_stepsBetween负值表示反向() {
        Frequency f = Frequency.parse("2h");
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 1, 6, 0);  // 早 6 小时
        assertThat(f.stepsBetween(from, to)).isEqualTo(-3);  // -6h / 2h
    }

    @Test
    void Frequency_equalsHashCode基于amount和unit() {
        Frequency f1 = Frequency.parse("1D");
        Frequency f2 = Frequency.parse("1D");
        Frequency f3 = Frequency.parse("2D");
        assertThat(f1).isEqualTo(f2);
        assertThat(f1).isNotEqualTo(f3);
        assertThat(f1.hashCode()).isEqualTo(f2.hashCode());
    }
}
