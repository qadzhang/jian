package jian.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : 阶段 F 测试 —— astype 9 种 dtype + interpolate + notna/pad/backfill 别名
// │  Why  : §3.16 路线图"astype 部分支持 + interpolate 缺失"的最终落地
// │  Who  : 阶段 F 回归
// │  When : jian-core 测试套件常规执行
// │  Where: jian-core/src/test/java/jian/core/StageFTest.java
class StageFTest {

    // ======================== astype 扩到 7 种(CATEGORY 仍不支持)========================

    @Test
    void astype_DOUBLE_to_BOOL_非0为true() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{0.0}, {1.0}, {2.5}, {null}});
        DataFrame r = df.astype("v", DType.BOOL);
        Column c = r.getColumn("v");
        assertThat(c.dtype()).isEqualTo(DType.BOOL);
        assertThat(c.get(0)).isEqualTo(Boolean.FALSE);  // 0.0 → false
        assertThat(c.get(1)).isEqualTo(Boolean.TRUE);   // 1.0 → true
        assertThat(c.get(2)).isEqualTo(Boolean.TRUE);   // 2.5 → true(非 0)
        assertThat(c.isNull(3)).isTrue();
    }

    @Test
    void astype_STRING_to_BOOL_各种字符串() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"true"}, {"false"}, {"1"}, {"0"}, {"True"}, {null}});
        DataFrame r = df.astype("v", DType.BOOL);
        Column c = r.getColumn("v");
        assertThat(c.get(0)).isEqualTo(Boolean.TRUE);   // "true"
        assertThat(c.get(1)).isEqualTo(Boolean.FALSE);  // "false"
        assertThat(c.get(2)).isEqualTo(Boolean.TRUE);   // "1"
        assertThat(c.get(3)).isEqualTo(Boolean.FALSE);  // "0"
        assertThat(c.get(4)).isEqualTo(Boolean.TRUE);   // "True"(转小写匹配)
        assertThat(c.isNull(5)).isTrue();
    }

    @Test
    void astype_STRING_to_DATETIME_ISO格式() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.STRING),
            new Object[][]{{"2026-01-01T12:00:00"}, {"2026-01-02 08:30:00"}, {null}});
        DataFrame r = df.astype("ts", DType.DATETIME);
        Column c = r.getColumn("ts");
        assertThat(c.dtype()).isEqualTo(DType.DATETIME);
        assertThat(c.get(0)).isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0));
        assertThat(c.get(1)).isEqualTo(LocalDateTime.of(2026, 1, 2, 8, 30));  // 空格分隔也认
        assertThat(c.isNull(2)).isTrue();
    }

    @Test
    void astype_DATETIME_to_DATE() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME),
            new Object[][]{{LocalDateTime.of(2026, 3, 15, 10, 30)}});
        DataFrame r = df.astype("ts", DType.DATE);
        Column c = r.getColumn("ts");
        assertThat(c.dtype()).isEqualTo(DType.DATE);
        assertThat(c.get(0)).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void astype_STRING_to_DATE_ISO() {
        DataFrame df = DataFrame.of(Schema.of("d", DType.STRING),
            new Object[][]{{"2026-08-09"}});
        DataFrame r = df.astype("d", DType.DATE);
        assertThat(r.getColumn("d").get(0)).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    @Test
    void astype_DATETIME_非法字符串抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.STRING),
            new Object[][]{{"not-a-date"}});
        assertThatThrownBy(() -> df.astype("ts", DType.DATETIME))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无法解析");
    }

    @Test
    void astype_CATEGORY_仍不支持() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"a"}});
        assertThatThrownBy(() -> df.astype("v", DType.CATEGORY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("暂不支持");
    }

    @Test
    void astype_LONG_字符串解析() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"123"}, {"456"}});
        DataFrame r = df.astype("v", DType.LONG);
        Column c = r.getColumn("v");
        assertThat(c.dtype()).isEqualTo(DType.LONG);
        assertThat(c.getLong(0)).isEqualTo(123L);
        assertThat(c.getLong(1)).isEqualTo(456L);
    }

    @Test
    void astype_DOUBLE_字符串解析() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"3.14"}});
        DataFrame r = df.astype("v", DType.DOUBLE);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isCloseTo(3.14, within(1e-9));
    }

    // ======================== interpolate 线性插值 ========================

    @Test
    void interpolate_单值中间缺失() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {Double.NaN}, {3.0}});
        DataFrame r = df.interpolate();
        DoubleColumn v = r.getDoubleColumn("v");
        assertThat(v.getDouble(0)).isEqualTo(1.0);
        assertThat(v.getDouble(1)).isEqualTo(2.0);  // 线性插值 (1+3)/2
        assertThat(v.getDouble(2)).isEqualTo(3.0);
    }

    @Test
    void interpolate_连续多值缺失() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{10.0}, {Double.NaN}, {Double.NaN}, {40.0}});
        DataFrame r = df.interpolate();
        DoubleColumn v = r.getDoubleColumn("v");
        // 10 → 20 → 30 → 40(span=3,等分)
        assertThat(v.getDouble(0)).isEqualTo(10.0);
        assertThat(v.getDouble(1)).isEqualTo(20.0);
        assertThat(v.getDouble(2)).isEqualTo(30.0);
        assertThat(v.getDouble(3)).isEqualTo(40.0);
    }

    @Test
    void interpolate_首尾缺失保持() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}, {2.0}, {Double.NaN}});
        DataFrame r = df.interpolate();
        DoubleColumn v = r.getDoubleColumn("v");
        assertThat(Double.isNaN(v.getDouble(0))).isTrue();  // 首缺失无前锚点,保持
        assertThat(v.getDouble(1)).isEqualTo(2.0);
        assertThat(Double.isNaN(v.getDouble(2))).isTrue();  // 尾缺失无后锚点,保持
    }

    @Test
    void interpolate_非数值列原样保留() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.DOUBLE, "name", DType.STRING),
            new Object[][]{{1.0, "a"}, {Double.NaN, null}, {3.0, "c"}});
        DataFrame r = df.interpolate();
        // 字符串列不应被改
        assertThat(r.getColumn("name").get(0)).isEqualTo("a");
        assertThat(r.getColumn("name").isNull(1)).isTrue();
        assertThat(r.getColumn("name").get(2)).isEqualTo("c");
        // 数值列正常插值
        assertThat(r.getDoubleColumn("v").getDouble(1)).isEqualTo(2.0);
    }

    @Test
    void interpolate_无缺失不变() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}});
        DataFrame r = df.interpolate();
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDoubleColumn("v").getDouble(1)).isEqualTo(2.0);
        assertThat(r.getDoubleColumn("v").getDouble(2)).isEqualTo(3.0);
    }

    // ======================== notna / notnull / pad / backfill 别名 ========================

    @Test
    void notna_反转isna() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {Double.NaN}, {3.0}});
        DataFrame r = df.notna();
        // isna:[false, true, false] → notna:[true, false, true]
        BoolColumn c = (BoolColumn) r.getColumn("v");
        assertThat(c.get(0)).isEqualTo(Boolean.TRUE);   // 非缺失
        assertThat(c.get(1)).isEqualTo(Boolean.FALSE);  // 缺失
        assertThat(c.get(2)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void notnull_等于notna() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"a"}, {null}});
        DataFrame r1 = df.notna();
        DataFrame r2 = df.notnull();
        BoolColumn c1 = (BoolColumn) r1.getColumn("v");
        BoolColumn c2 = (BoolColumn) r2.getColumn("v");
        assertThat(c1.get(0)).isEqualTo(c2.get(0));
        assertThat(c1.get(1)).isEqualTo(c2.get(1));
    }

    @Test
    void pad_等于ffill() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {Double.NaN}, {Double.NaN}});
        DataFrame r1 = df.ffill();
        DataFrame r2 = df.pad();
        assertThat(r1.getDoubleColumn("v").getDouble(1))
            .isEqualTo(r2.getDoubleColumn("v").getDouble(1));  // 都应填 1.0
    }

    @Test
    void backfill_等于bfill() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}, {Double.NaN}, {3.0}});
        DataFrame r1 = df.bfill();
        DataFrame r2 = df.backfill();
        assertThat(r1.getDoubleColumn("v").getDouble(0))
            .isEqualTo(r2.getDoubleColumn("v").getDouble(0));  // 都应填 3.0
    }
}
