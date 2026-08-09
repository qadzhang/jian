package jian.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §3.16 仍规划项补全测试(元信息/单单元格/迭代器/算术/重塑补全)。 */
class DataFrameCompleteTest {

    private DataFrame df() {
        return DataFrame.of(Schema.of("id", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{{1L, 10.0}, {2L, 20.0}, {3L, 30.0}});
    }

    // ======================== 元信息 ========================

    @Test void axes_返回行列轴() {
        var axes = df().axes();
        assertThat(axes).hasSize(2);
        assertThat(axes.get(1).size()).isEqualTo(2);
    }

    @Test void ndim_固定2() { assertThat(df().ndim()).isEqualTo(2); }

    @Test void memoryUsage_估算() {
        // 2 列 × 3 行 × 8 字节 = 48
        assertThat(df().memoryUsage()).isEqualTo(48L);
    }

    @Test void attrs_可读写() {
        DataFrame d = df();
        d.attrs().put("note", "test");
        assertThat(d.attrs().get("note")).isEqualTo("test");
    }

    // ======================== 类型转换补全 ========================

    @Test void inferObjects_OBJECT转LONG() {
        DataFrame d = DataFrame.of(Schema.of("x", DType.OBJECT),
            new Object[][]{{1L}, {2L}, {3L}});
        DataFrame r = d.inferObjects();
        assertThat(r.getColumn("x").dtype()).isEqualTo(DType.LONG);
    }

    @Test void convertDtypes_等价inferObjects() {
        DataFrame d = DataFrame.of(Schema.of("x", DType.OBJECT),
            new Object[][]{{1.0}, {2.0}});
        assertThat(d.convertDtypes().getColumn("x").dtype()).isEqualTo(DType.DOUBLE);
    }

    @Test void toNumpy_转二维数组() {
        Object[][] arr = df().toNumpy();
        assertThat(arr.length).isEqualTo(3);
        assertThat(arr[0][0]).isEqualTo(1L);
    }

    // ======================== 单单元格 ========================

    @Test void at_按标签读() {
        assertThat(df().at(0, "v")).isEqualTo(10.0);
    }

    @Test void iat_按位置读() {
        assertThat(df().iat(1, 1)).isEqualTo(20.0);
    }

    @Test void isetitem_按位置写() {
        DataFrame r = df().isetitem(0, 1, 99.0);
        assertThat(r.get(0, "v")).isEqualTo(99.0);
        // 原表不变
        assertThat(df().get(0, "v").equals(10.0)).isTrue();
    }

    // ======================== 增删列 ========================

    @Test void insert_在指定位置插入() {
        DataFrame r = df().insert(1, "new", new Object[]{"a", "b", "c"});
        assertThat(r.columnCount()).isEqualTo(3);
        assertThat(r.columnNames().get(1)).isEqualTo("new");
    }

    @Test void pop_弹出列() {
        Column popped = df().pop("v");
        assertThat(popped.name()).isEqualTo("v");
        assertThat(popped.size()).isEqualTo(3);
    }

    // ======================== 迭代器 ========================

    @Test void iterrows_行迭代() {
        int count = 0;
        for (Object[] row : df().iterrows()) {
            assertThat(row).hasSize(3);  // index + id + v
            count++;
        }
        assertThat(count).isEqualTo(3);
    }

    @Test void items_列迭代() {
        int count = 0;
        for (Object[] entry : df().items()) {
            assertThat(entry[0]).isInstanceOf(String.class);
            count++;
        }
        assertThat(count).isEqualTo(2);
    }

    @Test void keys_列名迭代() {
        int count = 0;
        for (String name : df().keys()) count++;
        assertThat(count).isEqualTo(2);
    }

    // ======================== 前后缀 ========================

    @Test void addPrefix_加前缀() {
        DataFrame r = df().addPrefix("p_");
        assertThat(r.columnNames()).containsExactly("p_id", "p_v");
    }

    @Test void addSuffix_加后缀() {
        DataFrame r = df().addSuffix("_s");
        assertThat(r.columnNames()).containsExactly("id_s", "v_s");
    }

    // ======================== 算术补全 ========================

    @Test void dot_点积() {
        DataFrame a = DataFrame.of(Schema.of("x", DType.DOUBLE), new Object[][]{{1.0}, {2.0}, {3.0}});
        DataFrame b = DataFrame.of(Schema.of("y", DType.DOUBLE), new Object[][]{{4.0}, {5.0}, {6.0}});
        assertThat(a.dot(b)).isEqualTo(32.0);  // 1*4 + 2*5 + 3*6 = 32
    }

    @Test void abs_绝对值() {
        DataFrame d = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{-5.0}, {3.0}});
        DataFrame r = d.abs();
        assertThat(r.getDoubleColumn("v_abs").getDouble(0)).isEqualTo(5.0);
    }

    @Test void colMode_众数() {
        DataFrame d = DataFrame.of(Schema.of("k", DType.STRING),
            new Object[][]{{"a"}, {"b"}, {"a"}, {"a"}});
        assertThat(d.colMode("k")).isEqualTo("a");
    }

    @Test void colValueCounts_值计数() {
        DataFrame d = DataFrame.of(Schema.of("k", DType.STRING),
            new Object[][]{{"a"}, {"b"}, {"a"}});
        Map<Object, Integer> counts = d.colValueCounts("k");
        assertThat(counts.get("a")).isEqualTo(2);
        assertThat(counts.get("b")).isEqualTo(1);
    }

    @Test void colNuniqueDf_唯一值数() {
        DataFrame d = DataFrame.of(Schema.of("k", DType.STRING),
            new Object[][]{{"a"}, {"b"}, {"a"}});
        assertThat(d.colNuniqueDf("k")).isEqualTo(2);
    }

    // ======================== 重塑补全 ========================

    @Test void reindex_按标签重排() {
        DataFrame d = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{10.0}, {20.0}, {30.0}});
        DataFrame r = d.reindex(new Object[]{2, 0, 5});  // 下标 2,0,5(5 不存在补 null)
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.get(0, "v")).isEqualTo(30.0);  // 原下标 2
        assertThat(r.getColumn("v").isNull(2)).isTrue();  // 下标 5 不存在
    }

    @Test void squeeze_单列降维() {
        DataFrame d = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}, {2L}});
        Object s = d.squeeze();
        assertThat(s).isInstanceOf(Column.class);
    }

    @Test void setAxis_替换列名() {
        DataFrame r = df().setAxis(new Object[]{"x", "y"});
        assertThat(r.columnNames()).containsExactly("x", "y");
    }

    @Test void firstValidIndex_首个非缺失() {
        DataFrame d = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}, {5.0}, {10.0}});
        assertThat(d.firstValidIndex()).isEqualTo(1);
    }

    @Test void lastValidIndex_末个非缺失() {
        DataFrame d = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{5.0}, {10.0}, {Double.NaN}});
        assertThat(d.lastValidIndex()).isEqualTo(1);
    }

    @Test void firstValidIndex_全缺失返回负1() {
        DataFrame d = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}});
        assertThat(d.firstValidIndex()).isEqualTo(-1);
    }
}
