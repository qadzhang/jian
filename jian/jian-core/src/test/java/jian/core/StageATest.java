package jian.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : 阶段 A 高频实用方法测试 —— 验证 idxmax/idxmin/duplicated/reset/set_index/sample/pipe/applyRow/isin/where/mask/info/selectDtypes
// │  Why  : 这些方法是 §3.16 路线图移过来的"已实现"项,需要 A 级断言(精确值 + 边界)守护
// │  Who  : 阶段 A 落地的回归测试
// │  When : 2026-08-09 阶段 A
// │  Where: jian-core/src/test/java/jian/core/StageATest.java
class StageATest {

    private DataFrame sampleDf() {
        return DataFrame.of(Schema.of("id", DType.LONG, "v", DType.DOUBLE, "name", DType.STRING),
            new Object[][]{
                {1L, 10.0, "alice"},
                {2L, 20.0, "bob"},
                {3L, 15.0, "alice"},
                {4L, Double.NaN, "carol"},
                {5L, 20.0, null}});
    }

    // ======================== idxmax / idxmin(并入 DataFrameSort)========================

    @Test
    void idxmax_找最大值首行下标() {
        DataFrame df = sampleDf();
        // v 列最大值 20.0,首行下标 1(id=2)
        assertThat(df.idxmax("v")).isEqualTo(1);
    }

    @Test
    void idxmin_找最小值首行下标() {
        DataFrame df = sampleDf();
        // v 列最小值 10.0,下标 0
        assertThat(df.idxmin("v")).isEqualTo(0);
    }

    @Test
    void idxmax_NaN跳过() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{5.0}, {Double.NaN}, {3.0}});
        assertThat(df.idxmax("v")).isEqualTo(0);
    }

    @Test
    void idxmax_全缺失返回负1() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}, {Double.NaN}});
        assertThat(df.idxmax("v")).isEqualTo(-1);
    }

    @Test
    void idxmax_空表返回负1() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[0][]);
        assertThat(df.idxmax("v")).isEqualTo(-1);
    }

    // ======================== duplicated(并入 DataFrameReshape)========================

    @Test
    void duplicated_全部列_保持首次() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING, "v", DType.LONG),
            new Object[][]{{"a", 1L}, {"b", 2L}, {"a", 1L}, {"a", 3L}});
        boolean[] dup = df.duplicated();
        assertThat(dup).containsExactly(false, false, true, false);  // 第 3 行(“a”,1)是第 1 行重复
    }

    @Test
    void duplicated_subset只判某些列_保持首次() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING, "v", DType.LONG),
            new Object[][]{{"a", 1L}, {"b", 2L}, {"a", 3L}});
        boolean[] dup = df.duplicated(new String[]{"k"}, "first");
        // 只看 k:第 3 行的 k=a 重复第 1 行 → true
        assertThat(dup).containsExactly(false, false, true);
    }

    @Test
    void duplicated_keepLast() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING),
            new Object[][]{{"a"}, {"a"}, {"b"}});
        boolean[] dup = df.duplicated(null, "last");
        assertThat(dup).containsExactly(true, false, false);
    }

    @Test
    void duplicated_keepNone_重复全判重() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING),
            new Object[][]{{"a"}, {"a"}, {"b"}, {"a"}});
        boolean[] dup = df.duplicated(null, "none");
        assertThat(dup).containsExactly(true, true, false, true);  // 出现 ≥2 次的都判重
    }

    @Test
    void duplicated_空表返回空掩码() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING), new Object[0][]);
        assertThat(df.duplicated()).isEmpty();
    }

    // ======================== sample / pipe / applyRow(主类承载)========================

    @Test
    void sample_无放回_n行不重() {
        DataFrame df = sampleDf();
        DataFrame s = df.sample(3, false, 42L);
        assertThat(s.rowCount()).isEqualTo(3);
        // 验证所有行来自原表(id ∈ {1..5})
        for (int i = 0; i < 3; i++) {
            long id = df.getLongColumn("id").getLong(i);
            assertThat(id).isBetween(1L, 5L);
        }
    }

    @Test
    void sample_同种子可复现() {
        DataFrame df = sampleDf();
        DataFrame s1 = df.sample(3, false, 42L);
        DataFrame s2 = df.sample(3, false, 42L);
        // 同种子 → 同样抽取顺序
        for (int i = 0; i < 3; i++) {
            assertThat(s1.getLongColumn("id").getLong(i))
                .isEqualTo(s2.getLongColumn("id").getLong(i));
        }
    }

    @Test
    void sample_有放回可超过rowCount() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}});
        DataFrame s = df.sample(5, true, 7L);
        assertThat(s.rowCount()).isEqualTo(5);
    }

    @Test
    void sample_n超rowCount无放回抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}});
        assertThatThrownBy(() -> df.sample(5, false, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replace=true");
    }

    @Test
    void pipe_链式管道() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {5.0}, {3.0}});
        DataFrame r = df.<DataFrame>pipe(d -> d.sortBy("v", false))   // 降序 → [5,3,1]
                       .pipe(d -> d.head(2));                          // 前 2 → [5,3]
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(5.0);
    }

    @Test
    void applyRow_生成新列() {
        DataFrame df = DataFrame.of(Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
            new Object[][]{{1.0, 2.0}, {3.0, 4.0}});
        DataFrame r = df.applyRow("sum_ab", row -> (Double) row[0] + (Double) row[1]);
        assertThat(r.columnCount()).isEqualTo(3);
        assertThat(r.getDoubleColumn("sum_ab").getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDoubleColumn("sum_ab").getDouble(1)).isEqualTo(7.0);
    }

    @Test
    void applyRow_支持返回null缺失() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}});
        DataFrame r = df.applyRow("cond", row -> ((Double) row[0] > 1.0) ? row[0] : null);
        assertThat(r.getDoubleColumn("cond").isNull(0)).isTrue();
        assertThat(r.getDoubleColumn("cond").getDouble(1)).isEqualTo(2.0);
    }

    // ======================== isin / where / mask(并入 DataFrameMissing)========================

    @Test
    void isin_行级任一列命中() {
        DataFrame df = sampleDf();
        boolean[] mask = df.isin("alice", 99L);
        // 行命中:第 0 行 name=alice;第 2 行 name=alice;第 3 行 v=NaN(不在 values)
        // id 列没有 99,所以命中只看 name
        assertThat(mask[0]).isTrue();   // alice
        assertThat(mask[1]).isFalse();  // bob
        assertThat(mask[2]).isTrue();   // alice
        assertThat(mask[3]).isFalse();
        assertThat(mask[4]).isFalse();
    }

    @Test
    void colIsin_列级判断() {
        DataFrame df = sampleDf();
        boolean[] mask = df.colIsin("name", "alice", "bob");
        assertThat(mask).containsExactly(true, true, true, false, false);
    }

    @Test
    void where_cond为假处替换() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {5.0}, {3.0}});
        // cond=[true, false, true] → 第 1 行(下标 1)替换为 0
        DataFrame r = df.where(new boolean[]{true, false, true}, 0.0);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDoubleColumn("v").getDouble(1)).isEqualTo(0.0);  // 被替换
        assertThat(r.getDoubleColumn("v").getDouble(2)).isEqualTo(3.0);
    }

    @Test
    void mask_cond为真处替换() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {5.0}, {3.0}});
        // cond=[false, true, false] → 第 1 行替换为 0
        DataFrame r = df.mask(new boolean[]{false, true, false}, 0.0);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDoubleColumn("v").getDouble(1)).isEqualTo(0.0);
        assertThat(r.getDoubleColumn("v").getDouble(2)).isEqualTo(3.0);
    }

    @Test
    void where_cond长度不符抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}});
        assertThatThrownBy(() -> df.where(new boolean[]{true}, 0.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("where cond 长度");
    }

    // ======================== reset_index / set_index / info / selectDtypes(主类承载)========================

    @Test
    void resetIndex_RangeIndex直接返回同表() {
        DataFrame df = sampleDf();  // 默认 RangeIndex
        DataFrame r = df.resetIndex("idx");
        // RangeIndex 无标签可转 → 列数不变
        assertThat(r.columnCount()).isEqualTo(df.columnCount());
    }

    @Test
    void setIndex_提升列为Index() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING, "v", DType.LONG),
            new Object[][]{{"a", 1L}, {"b", 2L}});
        DataFrame r = df.setIndex("k");
        // drop=true(默认)→ 列数 -1
        assertThat(r.columnCount()).isEqualTo(1);
        assertThat(r.index().isRange()).isFalse();
        assertThat(r.index().get(0)).isEqualTo("a");
        assertThat(r.index().get(1)).isEqualTo("b");
    }

    @Test
    void setIndex_dropFalse保留列() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.STRING, "v", DType.LONG),
            new Object[][]{{"a", 1L}, {"b", 2L}});
        DataFrame r = df.setIndex(new String[]{"k"}, false);
        assertThat(r.columnCount()).isEqualTo(2);
        assertThat(r.index().get(0)).isEqualTo("a");
    }

    @Test
    void info_返回可读性表格() {
        DataFrame df = sampleDf();
        String info = df.info();
        assertThat(info).contains("5 行");
        assertThat(info).contains("3 列");
        assertThat(info).contains("id");
        assertThat(info).contains("LONG");
        assertThat(info).contains("总内存估算");
    }

    @Test
    void selectDtypes_按dtype筛选() {
        DataFrame df = sampleDf();
        DataFrame numeric = df.selectDtypes(new DType[]{DType.DOUBLE, DType.LONG}, null);
        assertThat(numeric.columnCount()).isEqualTo(2);  // id + v
        assertThat(numeric.columnNames()).containsExactly("id", "v");
    }

    @Test
    void selectDtypes_排除模式() {
        DataFrame df = sampleDf();
        DataFrame nonString = df.selectDtypes(null, new DType[]{DType.STRING});
        assertThat(nonString.columnNames()).containsExactly("id", "v");
    }

    @Test
    void selectDtypes_无匹配返回空表() {
        DataFrame df = sampleDf();
        DataFrame r = df.selectDtypes(new DType[]{DType.BOOL}, null);
        assertThat(r.columnCount()).isEqualTo(0);
        assertThat(r.rowCount()).isEqualTo(0);
    }
}
