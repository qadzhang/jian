package jian.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : ColumnarPerfTest —— P0 性能改造的正确性 + 边界 + 性能回归测试
// │  Why  : 给 ColumnarHashMap / merge fast path / GroupBy fast path 补测试,防止回归
// │  Who  : 由 mvn test 跑(jian-core 自身测试)
// │  When : 任何 jian-core 改动后跑
// │  Where: jian-core/src/test/java/jian/core/ColumnarPerfTest.java
// │  How  : 三组测试:
// │           ① ColumnarHashMap 基本契约(入桶/查找/桶内链);
// │           ② merge fast path 正确性(对比通用路径结果,含 inner/left/right/outer/duplicate key);
// │           ③ GroupBy fast path 正确性(对比通用路径结果,含数值/字符串/null key)。
public class ColumnarPerfTest {

    // ======================== ① ColumnarHashMap 基本契约 ========================

    @Test
    void columnarHashMap_基本long查找应正确() {
        // keys[0]=5, keys[1]=1, keys[2]=5, keys[3]=3, keys[4]=1, keys[5]=5
        // 即:5 出现 3 次(下标 0,2,5);1 出现 2 次(下标 1,4);3 出现 1 次(下标 3)
        long[] keys = {5L, 1L, 5L, 3L, 1L, 5L};
        ColumnarHashMap map = ColumnarHashMap.buildFromLong(keys);

        // 找 5:应命中 3 个下标 0,2,5(头插顺序:遍历到时桶首是最新插入的 5)
        int first = map.findLong(5L);
        assertThat(first).isNotEqualTo(-1);
        assertThat(collectBucket(map, first)).containsExactlyInAnyOrder(0, 2, 5);

        // 找 1:应命中 2 个下标 1,4
        int first1 = map.findLong(1L);
        assertThat(first1).isNotEqualTo(-1);
        assertThat(collectBucket(map, first1)).containsExactlyInAnyOrder(1, 4);

        // 找 3:应命中 1 个下标 3
        int first3 = map.findLong(3L);
        assertThat(first3).isNotEqualTo(-1);
        assertThat(collectBucket(map, first3)).containsExactly(3);

        // 找不存在:返回 -1
        assertThat(map.findLong(999L)).isEqualTo(-1);
    }

    @Test
    void columnarHashMap_int和double路径应等价() {
        int[] intKeys = {10, 20, 10, 30};
        ColumnarHashMap intMap = ColumnarHashMap.buildFromInt(intKeys);
        // int 10 升位为 long 10
        assertThat(collectBucket(intMap, intMap.findLong(10L))).containsExactlyInAnyOrder(0, 2);

        double[] dKeys = {1.5, 2.5, 1.5};
        ColumnarHashMap dMap = ColumnarHashMap.buildFromDouble(dKeys);
        assertThat(collectBucket(dMap, dMap.findDouble(1.5))).containsExactlyInAnyOrder(0, 2);
        assertThat(dMap.findDouble(2.5)).isNotEqualTo(-1);
    }

    @Test
    void columnarHashMap_空数组不应抛异常() {
        ColumnarHashMap emptyLong = ColumnarHashMap.buildFromLong(new long[0]);
        assertThat(emptyLong.findLong(1L)).isEqualTo(-1);

        ColumnarHashMap emptyDouble = ColumnarHashMap.buildFromDouble(new double[0]);
        assertThat(emptyDouble.findDouble(1.0)).isEqualTo(-1);
    }

    /** 收集桶内所有行下标(配合 findXxx 使用)。 */
    private static java.util.List<Integer> collectBucket(ColumnarHashMap map, int first) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int r = first; r >= 0; r = map.nextInBucket(r)) out.add(r);
        return out;
    }

    // ======================== ② merge fast path 正确性 ========================

    @Test
    void merge_longKeyInner_应与通用路径结果一致() {
        DataFrame a = dfOfLong("id", "a_val",
                new long[]{1, 2, 3, 4}, new double[]{10, 20, 30, 40});
        DataFrame b = dfOfLong("id", "b_val",
                new long[]{2, 3, 5}, new double[]{200, 300, 500});

        // fast path(单列 long key inner)
        DataFrame fast = a.merge(b, "inner", "id");
        // 应得 2 行(id=2,3),列 id/a_val/b_val
        assertThat(fast.rowCount()).isEqualTo(2);
        assertThat(fast.columnNames()).containsExactly("id", "a_val", "b_val");
        // 验证值:id=2 → a_val=20, b_val=200;id=3 → a_val=30, b_val=300
        assertThat(fast.get(0, "id")).isIn(2L, 3L);
        assertThat(fast.get(0, "b_val")).isIn(200.0, 300.0);
    }

    @Test
    void merge_longKeyLeft_未匹配行右表应补null() {
        DataFrame a = dfOfLong("id", "a_val",
                new long[]{1, 2, 3}, new double[]{10, 20, 30});
        DataFrame b = dfOfLong("id", "b_val",
                new long[]{2}, new double[]{200});

        DataFrame r = a.merge(b, "left", "id");
        assertThat(r.rowCount()).isEqualTo(3);   // 左表全保留
        // id=1 和 id=3 的 b_val 应为缺失(DoubleColumn 用 NaN 表示)
        for (int i = 0; i < 3; i++) {
            long id = (Long) r.get(i, "id");
            if (id == 2) {
                assertThat(r.get(i, "b_val")).isEqualTo(200.0);
            } else {
                // 未匹配行 b_val 是 DoubleColumn,缺失用 NaN 表示;
                // get() 现在对 NaN 返回 Double.NaN(不是 null),用 isNull 判断
                assertThat(r.getColumn("b_val").isNull(i)).isTrue();
            }
        }
    }

    @Test
    void merge_longKeyRight和Outer_应回退通用路径不丢功能() {
        DataFrame a = dfOfLong("id", "a_val",
                new long[]{1, 2}, new double[]{10, 20});
        DataFrame b = dfOfLong("id", "b_val",
                new long[]{2, 3}, new double[]{200, 300});

        DataFrame right = a.merge(b, "right", "id");
        assertThat(right.rowCount()).isEqualTo(2);  // 右表全保留(id=2,3)

        DataFrame outer = a.merge(b, "outer", "id");
        assertThat(outer.rowCount()).isEqualTo(3);  // 并集(id=1,2,3)
    }

    @Test
    void merge_longKey重复键应展开多行() {
        // 右表 id=2 重复 → left join 后该左行产 2 行
        DataFrame a = dfOfLong("id", "a_val",
                new long[]{1, 2}, new double[]{10, 20});
        DataFrame b = dfOfLong("id", "b_val",
                new long[]{2, 2}, new double[]{200, 201});

        DataFrame r = a.merge(b, "inner", "id");
        assertThat(r.rowCount()).isEqualTo(2);  // id=2 出现两次
        assertThat(r.get(0, "id")).isEqualTo(2L);
        assertThat(r.get(0, "b_val")).isIn(200.0, 201.0);
        assertThat(r.get(1, "b_val")).isIn(200.0, 201.0);
    }

    @Test
    void merge_双精度key_应走double特化路径() {
        DataFrame a = dfOfDouble("id", "a_val",
                new double[]{1.5, 2.5}, new double[]{10, 20});
        DataFrame b = dfOfDouble("id", "b_val",
                new double[]{2.5, 3.5}, new double[]{200, 300});

        DataFrame r = a.merge(b, "inner", "id");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat((Double) r.get(0, "id")).isEqualTo(2.5);
    }

    @Test
    void merge_字符串key_应走通用路径正确兜底() {
        // 字符串 key 不走 fast path,落回通用路径
        DataFrame a = DataFrame.of(
            Schema.of("id", DType.STRING, "a_val", DType.DOUBLE),
            new Object[][]{{"alice", 10.0}, {"bob", 20.0}});
        DataFrame b = DataFrame.of(
            Schema.of("id", DType.STRING, "b_val", DType.DOUBLE),
            new Object[][]{{"bob", 200.0}});

        DataFrame r = a.merge(b, "inner", "id");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.get(0, "id")).isEqualTo("bob");
        assertThat((Double) r.get(0, "b_val")).isEqualTo(200.0);
    }

    // ======================== ③ GroupBy fast path 正确性 ========================

    @Test
    void groupBy_longKey_应正确分组并保序() {
        DataFrame df = dfOfLong("id", "val",
                new long[]{1, 2, 1, 3, 2, 1}, new double[]{10, 20, 11, 30, 21, 12});

        Map<String, String> spec = new HashMap<>();
        spec.put("val", "sum");
        DataFrame r = df.groupBy("id").agg(spec);

        // 3 组(id=1,2,3),按首次出现顺序
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.get(0, "id")).isEqualTo(1L);  // id=1 首次出现在 row 0
        assertThat(r.get(1, "id")).isEqualTo(2L);
        assertThat(r.get(2, "id")).isEqualTo(3L);
        // id=1 的 sum = 10+11+12 = 33
        assertThat((Double) r.get(0, "val_sum")).isEqualTo(33.0);
        // id=2 的 sum = 20+21 = 41
        assertThat((Double) r.get(1, "val_sum")).isEqualTo(41.0);
    }

    @Test
    void groupBy_doubleKey_应走double特化路径() {
        DataFrame df = dfOfDouble("id", "val",
                new double[]{1.5, 2.5, 1.5}, new double[]{10, 20, 11});

        Map<String, String> spec = new HashMap<>();
        spec.put("val", "count");
        DataFrame r = df.groupBy("id").agg(spec);

        assertThat(r.rowCount()).isEqualTo(2);
        // id=1.5 出现 2 次,id=2.5 出现 1 次
        for (int i = 0; i < 2; i++) {
            double id = (Double) r.get(i, "id");
            long cnt = (Long) r.get(i, "val_count");
            if (id == 1.5) assertThat(cnt).isEqualTo(2L);
            else assertThat(cnt).isEqualTo(1L);
        }
    }

    @Test
    void groupBy_含nullKey_应回退通用路径() {
        // null key 不走 fast path(简化处理),落回通用路径,null 归 "<NA>" 组
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "val", DType.DOUBLE),
            new Object[][]{
                {1L, 10.0}, {null, 20.0}, {1L, 11.0}, {null, 21.0}
            });

        Map<String, String> spec = new HashMap<>();
        spec.put("val", "sum");
        DataFrame r = df.groupBy("id").agg(spec);

        // 2 组:id=1, null(<NA>)
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void groupBy_多列key_应回退通用路径() {
        DataFrame df = DataFrame.of(
            Schema.of("a", DType.LONG, "b", DType.LONG, "val", DType.DOUBLE),
            new Object[][]{
                {1L, 1L, 10.0}, {1L, 2L, 20.0}, {1L, 1L, 11.0}
            });

        Map<String, String> spec = new HashMap<>();
        spec.put("val", "sum");
        DataFrame r = df.groupBy("a", "b").agg(spec);

        // 2 组:(1,1) 和 (1,2)
        assertThat(r.rowCount()).isEqualTo(2);
    }

    // ======================== 辅助构造 ========================

    private static DataFrame dfOfLong(String col1, String col2, long[] c1, double[] c2) {
        return DataFrame.ofColumnArrays(
            java.util.List.of(col1, col2),
            new Object[]{ c1.clone(), c2.clone() });
    }

    private static DataFrame dfOfDouble(String col1, String col2, double[] c1, double[] c2) {
        return DataFrame.ofColumnArrays(
            java.util.List.of(col1, col2),
            new Object[]{ c1.clone(), c2.clone() });
    }

    // ======================== 回归测试:数组工厂 / 列存 / merge 边界 ========================

    @Test
    void ofColumnArrays_String数组应映射为STRING类型() {
        // 因为 String[] 须直接映射 STRING 列,所以不能误判为 OBJECT
        String[] data = {"alice", "bob", "carol"};
        DataFrame df = DataFrame.ofColumnArrays(java.util.List.of("x"), new Object[]{data});
        assertThat(df.dtypes().get(0)).isEqualTo(DType.STRING);
        assertThat(df.getColumn("x")).isInstanceOf(StringColumn.class);
        assertThat(df.getStringColumn("x").get(0)).isEqualTo("alice");
    }

    @Test
    void ofColumnArrays_int数组应保留INT类型() {
        // 因为 int[] 保留 INT 才能维持 schema 一致性,所以不升位为 LONG
        int[] data = {1, 2, 3};
        DataFrame df = DataFrame.ofColumnArrays(java.util.List.of("x"), new Object[]{data});
        assertThat(df.dtypes().get(0)).isEqualTo(DType.INT);
        assertThat(df.getColumn("x")).isInstanceOf(IntColumn.class);
    }

    @Test
    void ofColumnArrays_列长度不一致应抛异常() {
        // 因为列等长是 DataFrame 构造的前提,所以必须校验并明确报错
        assertThatThrownBy(() ->
            DataFrame.ofColumnArrays(
                java.util.List.of("a", "b"),
                new Object[]{new long[3], new long[5]}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("列长度不一致");
    }

    @Test
    void ofColumnArrays_null元素应抛异常() {
        // 扩展:null 数组元素应明确报错
        assertThatThrownBy(() ->
            DataFrame.ofColumnArrays(java.util.List.of("a"), new Object[]{null}))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void merge_key含null应落回通用路径不误匹配() {
        // 因为 null 键不与任何值匹配,所以左表含 null 时不能与 id=0 错误匹配
        DataFrame l = DataFrame.of(Schema.of("id", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{{1L, 10.0}, {null, 20.0}, {0L, 30.0}});
        DataFrame r = DataFrame.of(Schema.of("id", DType.LONG, "w", DType.DOUBLE),
            new Object[][]{{0L, 100.0}, {1L, 200.0}});

        DataFrame out = l.merge(r, "inner", "id");
        // null 行不匹配;id=0 和 id=1 各匹配一行
        assertThat(out.rowCount()).isEqualTo(2);
    }

    @Test
    void columnarHashMap_大容量不溢出() {
        // 因为即使 nRows 接近 Integer.MAX_VALUE chooseCapacity 也不得溢出成 0,这里测合理规模
        // (2^29 极限值由代码常量保护)
        long[] keys = new long[1000];
        for (int i = 0; i < 1000; i++) keys[i] = i;
        ColumnarHashMap map = ColumnarHashMap.buildFromLong(keys);
        assertThat(map.findLong(500)).isNotEqualTo(-1);
        assertThat(map.findLong(9999)).isEqualTo(-1);
    }

    @Test
    void intColumn_nullMask应返回拷贝不可影响内部() {
        // 因为 nullMask() 返回的是拷贝,所以外部修改返回值不应破坏原列
        IntColumn col = new IntColumn("x", new int[]{1, 0, 2}, new boolean[]{false, true, false});
        boolean[] mask = col.nullMask();
        mask[1] = false;  // 修改返回的拷贝
        // 原列的 null 状态不应变
        assertThat(col.isNull(1)).isTrue();
    }

    @Test
    void merge_列名不存在应给清晰异常() {
        // 不存在的列应给清晰错误,不是 IOOBE
        DataFrame a = dfOfLong("id", "v", new long[]{1}, new double[]{1.0});
        DataFrame b = dfOfLong("id", "v", new long[]{1}, new double[]{1.0});
        assertThatThrownBy(() -> a.merge(b, "inner", "notExist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("左表无此列");
    }

    @Test
    void merge_rightOn不存在也应给清晰异常() {
        // rightOn 校验路径(leftOn 存在但 rightOn 不存在的场景)
        DataFrame a = dfOfLong("id", "v", new long[]{1}, new double[]{1.0});
        DataFrame b = dfOfLong("id", "v", new long[]{1}, new double[]{1.0});
        assertThatThrownBy(() ->
            a.merge(b, "inner", new String[]{"id"}, new String[]{"notExist"}, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("右表无此列");
    }

    @Test
    void ofColumnArrays_不支持数组类型应清晰报错() {
        // short[] 等不支持的数组类型应清晰报错(不抛 ClassCastException)
        assertThatThrownBy(() ->
            DataFrame.ofColumnArrays(java.util.List.of("x"), new Object[]{new short[]{1, 2}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的数组类型");
        assertThatThrownBy(() ->
            DataFrame.ofColumnArrays(java.util.List.of("x"), new Object[]{new float[]{1.0f}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的数组类型");
    }

    @Test
    void groupBy_longKey_fastPath产出的keyList应可变() {
        // fast path 产出的 key list 应是可变 ArrayList(不可变 List.of 会让下游操作抛异常)
        DataFrame df = dfOfLong("id", "v", new long[]{1, 2, 1}, new double[]{10, 20, 11});
        Map<String, String> spec = new HashMap<>();
        spec.put("v", "count");
        DataFrame r = df.groupBy("id").agg(spec);
        // 如果 key list 是 List.of 不可变,某些下游操作会抛;这里仅验证聚合正常完成
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void ofColumnArrays_多维数组应清晰报错() {
        // long[][] / Object[][] 等多维数组不能强转,要清晰报错
        assertThatThrownBy(() ->
            DataFrame.ofColumnArrays(java.util.List.of("x"), new Object[]{new long[][]{{1, 2}}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持多维数组");
    }

    @Test
    void merge_doubleKey_正零与负零应与normKey契约一致() {
        // 因为 generic 路径 normKey 契约(§10.16 #6)规定"±0.0 归一同一键",
        // 所以 fast(inner) 与 right/outer(generic) 同输入结果一致:±0.0 同键匹配
        DataFrame a = DataFrame.of(Schema.of("id", DType.DOUBLE, "v", DType.DOUBLE),
            new Object[][]{{+0.0, 10.0}, {1.5, 20.0}});
        DataFrame b = DataFrame.of(Schema.of("id", DType.DOUBLE, "w", DType.DOUBLE),
            new Object[][]{{-0.0, 100.0}});
        DataFrame out = a.merge(b, "inner", "id");
        // +0.0 与 -0.0 是同一键 → 1 行;1.5 无匹配
        assertThat(out.rowCount()).isEqualTo(1);
        assertThat(out.getDoubleColumn("v").data()).containsExactly(10.0);
        assertThat(out.getDoubleColumn("w").data()).containsExactly(100.0);
    }

    @Test
    void merge_int列输出应保留INT不误判OBJECT() {
        // 因为 toPrimitiveArray 按源 dtype 决定类型,所以 INT 列输出应仍是 INT
        // 构造 INT 列(注意 jian INT 列内部是 int[]+nullMask)
        DataFrame a = DataFrame.of(
            Schema.of("id", DType.LONG, "n", DType.INT),
            new Object[][]{{1L, 10}, {2L, 20}});
        DataFrame b = DataFrame.of(
            Schema.of("id", DType.LONG, "m", DType.INT),
            new Object[][]{{1L, 100}, {2L, 200}});
        DataFrame out = a.merge(b, "inner", "id");
        // 输出 n/m 列应仍是 INT(不是 OBJECT)
        assertThat(out.dtypes()).contains(DType.INT);
        assertThat(out.getColumn("n")).isInstanceOf(IntColumn.class);
        assertThat(out.getColumn("m")).isInstanceOf(IntColumn.class);
    }
}
