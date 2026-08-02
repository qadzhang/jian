package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TransformAccessorTest {

    @Test
    void groupBy_transform广播回原行序() {
        DataFrame df = DataFrame.of(
                Schema.of("dept", DType.STRING, "salary", DType.DOUBLE),
                new Object[][]{{"RD", 100.0}, {"PM", 200.0}, {"RD", 300.0}});
        // transform:每人的 salary - 所在部门的平均 salary
        double[] deptMean = df.groupBy("dept").transform("salary", "mean");
        // RD 平均=200,PM 平均=200
        assertThat(deptMean[0]).isCloseTo(200.0, within(1e-10));  // RD 行
        assertThat(deptMean[1]).isCloseTo(200.0, within(1e-10));  // PM 行
        assertThat(deptMean[2]).isCloseTo(200.0, within(1e-10));  // RD 行
    }

    @Test
    void groupBy_transformAsColumn派生新列() {
        DataFrame df = DataFrame.of(
                Schema.of("dept", DType.STRING, "salary", DType.DOUBLE),
                new Object[][]{{"RD", 100.0}, {"RD", 300.0}, {"PM", 200.0}});
        DataFrame r = df.groupBy("dept").transformAsColumn("dept_avg", "salary", "mean");
        assertThat(r.columnNames()).contains("dept_avg");
        assertThat(r.getDoubleColumn("dept_avg").getDouble(0)).isCloseTo(200.0, within(1e-10));
        assertThat(r.getDoubleColumn("dept_avg").getDouble(1)).isCloseTo(200.0, within(1e-10));
    }

    @Test
    void series_str_accessor() {
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING),
                new Object[][]{{"alice"}, {"bob"}, {null}});
        Series s = df.getSeries("name");
        // .str().upper()
        StringColumn upper = s.str().upper();
        assertThat(upper.get(0)).isEqualTo("ALICE");
        assertThat(upper.get(1)).isEqualTo("BOB");
        assertThat(upper.get(2)).isNull();
        // .str().contains
        BoolColumn mask = s.str().contains("li");
        assertThat(mask.getBool(0)).isTrue();
        assertThat(mask.getBool(1)).isFalse();
    }

    @Test
    void series_dt_accessor() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.DATETIME),
                new Object[][]{
                        {java.time.LocalDateTime.of(2026, 3, 15, 10, 30, 0)},
                        {java.time.LocalDateTime.of(2025, 12, 1, 14, 0, 0)},
                        {null}
                });
        Series s = df.getSeries("ts");
        double[] years = s.dt().year();
        assertThat(years[0]).isEqualTo(2026.0);
        assertThat(years[1]).isEqualTo(2025.0);
        assertThat(Double.isNaN(years[2])).isTrue();

        double[] months = s.dt().month();
        assertThat(months[0]).isEqualTo(3.0);
        assertThat(months[1]).isEqualTo(12.0);

        double[] hours = s.dt().hour();
        assertThat(hours[0]).isEqualTo(10.0);
        assertThat(hours[1]).isEqualTo(14.0);

        double[] dow = s.dt().dayOfWeek();
        // 2026-03-15 是周日(7)
        assertThat(dow[0]).isEqualTo(7.0);
    }

    @Test
    void dt_非时间列抛异常() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}});
        try {
            df.getSeries("v").dt();
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("仅时间列");
        }
    }
}
