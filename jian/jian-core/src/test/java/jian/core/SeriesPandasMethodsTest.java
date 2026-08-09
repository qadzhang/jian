package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Series pandas 同名方法补全测试(argmax/argmin/between/tolist/to_dict/is_unique/hasnans 等)。 */
class SeriesPandasMethodsTest {

    private Series numSeries() {
        return DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{30.0}, {10.0}, {20.0}, {Double.NaN}}).getSeries("v");
    }

    @Test
    void argmax_找最大值行号() {
        assertThat(numSeries().argmax()).isEqualTo(0);  // 30 在下标 0
    }

    @Test
    void argmin_找最小值行号() {
        assertThat(numSeries().argmin()).isEqualTo(1);  // 10 在下标 1
    }

    @Test
    void argmax_全NaN返回负1() {
        Series s = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}}).getSeries("v");
        assertThat(s.argmax()).isEqualTo(-1);
    }

    @Test
    void between_区间判断() {
        boolean[] mask = numSeries().between(15, 35);
        assertThat(mask).containsExactly(true, false, true, false);  // 30 在 [15,35],10 不在,20 在
    }

    @Test
    void tolist_转列表() {
        java.util.List<Object> list = numSeries().tolist();
        assertThat(list).hasSize(4);
        assertThat(list.get(0)).isEqualTo(30.0);
    }

    @Test
    void to_dict_转字典() {
        java.util.Map<Integer, Object> dict = numSeries().to_dict();
        assertThat(dict.get(0)).isEqualTo(30.0);
        assertThat(dict.get(2)).isEqualTo(20.0);
    }

    @Test
    void is_unique_无重复() {
        assertThat(numSeries().is_unique()).isTrue();
    }

    @Test
    void is_unique_有重复() {
        Series s = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {1.0}}).getSeries("v");
        assertThat(s.is_unique()).isFalse();
    }

    @Test
    void hasnans_含NaN() {
        assertThat(numSeries().hasnans()).isTrue();
    }

    @Test
    void hasnans_无NaN() {
        Series s = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}}).getSeries("v");
        assertThat(s.hasnans()).isFalse();
    }

    @Test
    void is_monotonic_increasing_递增() {
        Series s = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}}).getSeries("v");
        assertThat(s.is_monotonic_increasing()).isTrue();
    }

    @Test
    void is_monotonic_increasing_非递增() {
        Series s = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {2.0}}).getSeries("v");
        assertThat(s.is_monotonic_increasing()).isFalse();
    }

    @Test
    void tzLocalize_基本() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME),
            new Object[][]{{java.time.LocalDateTime.of(2026, 1, 1, 12, 0)}});
        DataFrame r = df.tzLocalize("ts", "Asia/Shanghai");
        Column c = r.getColumn("ts");
        Object v = c.get(0);
        assertThat(v).isInstanceOf(java.time.ZonedDateTime.class);
        java.time.ZonedDateTime zdt = (java.time.ZonedDateTime) v;
        assertThat(zdt.getZone()).isEqualTo(java.time.ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void tzConvert_时区转换() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME),
            new Object[][]{{java.time.LocalDateTime.of(2026, 1, 1, 12, 0)}});
        DataFrame localized = df.tzLocalize("ts", "UTC");
        DataFrame converted = localized.tzConvert("ts", "Asia/Shanghai");
        java.time.ZonedDateTime zdt = (java.time.ZonedDateTime) converted.getColumn("ts").get(0);
        // UTC 12:00 → Shanghai 20:00(+8)
        assertThat(zdt.getHour()).isEqualTo(20);
    }

    @Test
    void tzConvert_未localize抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME),
            new Object[][]{{java.time.LocalDateTime.of(2026, 1, 1, 12, 0)}});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> df.tzConvert("ts", "UTC"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tz_convert");
    }
}
