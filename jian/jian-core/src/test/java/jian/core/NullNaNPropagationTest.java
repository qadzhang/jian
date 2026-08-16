package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : NullNaNPropagationTest —— NaN/缺失值在全链路传递时不失真的蜕变测试
// │  Why  : DoubleColumn.get 对 NaN 返回 Double.NaN(不是 null)+ 全 Column 子类缺失值统一,
// │         必须验证:① 内部计算(getDouble 路径)NaN 正确传播 ② get() 返回 NaN 不失真
// │         ③ getRow/iterRows(IO 边界)缺失行返回 null ④ ffill/bfill 正确识别 NaN 为缺失
// │         ⑤ merge left join 补 null 保留缺失语义
// │  Who  : jian-core 测试套件(surefire)
// │  When : jian-core 测试套件常规执行
// │  Where: jian-core/NullNaNPropagationTest.java
class NullNaNPropagationTest {

    // ======================== 1. get() 不失真(NaN 就是 NaN,不是 null)========================

    /**
     * MR-NaN-1: DoubleColumn.get(NaN 行) 返回 Double.NaN,不是 null。
     * 之前 NaN→null 失真;现在 NaN 透传,内部计算路径不失真。
     */
    @Test
    void doubleColumn_get_NaN行返回NaN不返回null() {
        DoubleColumn col = new DoubleColumn("v", new double[]{1.0, Double.NaN, 3.0});
        assertThat(col.get(0)).isEqualTo(1.0);
        assertThat(col.get(1)).isEqualTo(Double.NaN);   // NaN 对象,不是 null
        assertThat(col.get(1)).isNotEqualTo(null);       // 明确不是 null
        assertThat(col.get(2)).isEqualTo(3.0);
    }

    /**
     * MR-NaN-2: getDouble(缺失行) 返回 NaN,所有数值列类型一致。
     */
    @Test
    void 全数值列_getDouble_缺失行返回NaN() {
        // DoubleColumn
        DoubleColumn dc = new DoubleColumn("d", new double[]{1.0, Double.NaN});
        assertThat(dc.getDouble(1)).isNaN();
        // LongColumn 缺失
        LongColumn lc = new LongColumn("l", new long[]{1L, 0L}, new boolean[]{false, true});
        assertThat(lc.getDouble(1)).isNaN();
        // IntColumn 缺失
        IntColumn ic = new IntColumn("i", new int[]{1, 0}, new boolean[]{false, true});
        assertThat(ic.getDouble(1)).isNaN();
    }

    /**
     * MR-NaN-3: getLong(缺失行) 返回 Long.MIN_VALUE(缺失标记),不抛异常、不返回垃圾值。
     * 所有数值列类型一致;下游可用 == Long.MIN_VALUE 或 isNull 识别。
     */
    @Test
    void 全数值列_getLong_缺失行返回LongMinValue() {
        // DoubleColumn
        DoubleColumn dc = new DoubleColumn("d", new double[]{1.0, Double.NaN});
        assertThat(dc.getLong(1)).isEqualTo(Long.MIN_VALUE);
        // LongColumn 缺失
        LongColumn lc = new LongColumn("l", new long[]{1L, 0L}, new boolean[]{false, true});
        assertThat(lc.getLong(1)).isEqualTo(Long.MIN_VALUE);
        // IntColumn 缺失
        IntColumn ic = new IntColumn("i", new int[]{1, 0}, new boolean[]{false, true});
        assertThat(ic.getLong(1)).isEqualTo(Long.MIN_VALUE);
    }

    // ======================== 2. getRow/iterRows(IO 边界)缺失行返回 null ========================

    /**
     * MR-NaN-4: getRow(i) 对 DoubleColumn 的 NaN 行返回 null(不是 NaN 对象)。
     * 这是 IO 安全网:CSV/JSON/SQL 依赖 getRow 的 null 表示缺失。
     */
    @Test
    void getRow_NaN行在边界转null() {
        DataFrame df = DataFrame.ofColumnArrays(
                java.util.List.of("id", "v"),
                new Object[]{ new long[]{1, 2, 3}, new double[]{10.0, Double.NaN, 30.0} });
        Object[] row1 = df.getRow(1);
        assertThat(row1[0]).isEqualTo(2L);
        assertThat(row1[1]).isNull();   // NaN 在 getRow 边界转成 null
    }

    // ======================== 3. ffill/bfill 正确识别 NaN 为缺失 ========================

    /**
     * MR-NaN-5: ffill 把 NaN 视为缺失,用前一个有效值填充。
     * 之前 NaN→null→ffill 识别;现在 NaN→Double.NaN,ffill 用 isNull 判断仍正确。
     */
    @Test
    void ffill_NaN被视为缺失并填充() {
        DataFrame df = DataFrame.ofColumnArrays(
                java.util.List.of("v"),
                new Object[]{ new double[]{90.0, Double.NaN, Double.NaN, 80.0} });
        DataFrame r = df.ffill();
        double[] vs = ((DoubleColumn) r.getColumn("v")).dataInPlace();
        assertThat(vs[0]).isEqualTo(90.0);
        assertThat(vs[1]).isEqualTo(90.0);   // NaN 被 ffill 成 90
        assertThat(vs[2]).isEqualTo(90.0);   // 同上
        assertThat(vs[3]).isEqualTo(80.0);
    }

    /**
     * MR-NaN-6: bfill 把 NaN 视为缺失,用后一个有效值填充。
     */
    @Test
    void bfill_NaN被视为缺失并填充() {
        DataFrame df = DataFrame.ofColumnArrays(
                java.util.List.of("v"),
                new Object[]{ new double[]{Double.NaN, Double.NaN, 80.0, 70.0} });
        DataFrame r = df.bfill();
        double[] vs = ((DoubleColumn) r.getColumn("v")).dataInPlace();
        assertThat(vs[0]).isEqualTo(80.0);   // NaN 被 bfill 成 80
        assertThat(vs[1]).isEqualTo(80.0);
        assertThat(vs[2]).isEqualTo(80.0);
        assertThat(vs[3]).isEqualTo(70.0);
    }

    // ======================== 4. merge left join 补 null 保留缺失语义 ========================

    /**
     * MR-NaN-7: left join 未匹配行,右表列的 DoubleColumn 值用 NaN 表示缺失。
     * isNull(未匹配行) == true;getDouble(未匹配行) == NaN;getRow(未匹配行) == null。
     */
    @Test
    void merge_leftJoin未匹配行_缺失语义正确() {
        DataFrame a = DataFrame.ofColumnArrays(
                java.util.List.of("id", "v"),
                new Object[]{ new long[]{1, 2, 3}, new double[]{10.0, 20.0, 30.0} });
        DataFrame b = DataFrame.ofColumnArrays(
                java.util.List.of("id", "w"),
                new Object[]{ new long[]{1, 3}, new double[]{100.0, 300.0} });
        DataFrame r = a.merge(b, "left", "id");

        assertThat(r.rowCount()).isEqualTo(3);
        // id=2 未匹配,w 列缺失
        assertThat(r.getColumn("w").isNull(1)).isTrue();           // isNull
        assertThat(r.getDoubleColumn("w").getDouble(1)).isNaN();   // getDouble → NaN
        assertThat(r.getRow(1)[2]).isNull();                       // getRow → null(IO 边界)
        // id=1/id=3 匹配
        assertThat(r.getDoubleColumn("w").getDouble(0)).isEqualTo(100.0);
        assertThat(r.getDoubleColumn("w").getDouble(2)).isEqualTo(300.0);
    }

    // ======================== 5. 排序正确把 NaN/缺失排到末尾 ========================

    /**
     * MR-NaN-8: sortBy 对含 NaN 的 DoubleColumn 排序,NaN 被排到末尾(不是当成最大数值)。
     * 之前 NaN→null→排末尾;现在 NaN→Double.NaN,排序用 isNull 判断仍排末尾。
     */
    @Test
    void sortBy_NaN排到末尾() {
        DataFrame df = DataFrame.ofColumnArrays(
                java.util.List.of("v"),
                new Object[]{ new double[]{3.0, Double.NaN, 1.0, 2.0} });
        DataFrame r = df.sortBy(new String[]{"v"}, new boolean[]{true});
        double[] vs = ((DoubleColumn) r.getColumn("v")).dataInPlace();
        // 升序:1, 2, 3, NaN(末尾)
        assertThat(vs[0]).isEqualTo(1.0);
        assertThat(vs[1]).isEqualTo(2.0);
        assertThat(vs[2]).isEqualTo(3.0);
        assertThat(vs[3]).isNaN();    // NaN 排末尾
    }

    // ======================== 6. 算术 NaN 传播正确 ========================

    /**
     * MR-NaN-9: colSub 两列相减,任一缺失行结果为 NaN(传播正确)。
     */
    @Test
    void colSub_NaN传播正确() {
        DataFrame df = DataFrame.ofColumnArrays(
                java.util.List.of("a", "b"),
                new Object[]{ new double[]{10.0, 20.0, Double.NaN}, new double[]{1.0, Double.NaN, 3.0} });
        DataFrame r = df.colSub("diff", "a", "b");
        double[] vs = r.getDoubleColumn("diff").dataInPlace();
        assertThat(vs[0]).isEqualTo(9.0);       // 10-1
        assertThat(vs[1]).isNaN();               // 20-NaN → NaN
        assertThat(vs[2]).isNaN();               // NaN-3 → NaN
    }
}
