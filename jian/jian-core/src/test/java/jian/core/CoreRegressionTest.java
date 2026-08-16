package jian.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : CoreRegressionTest —— DataFrame/Series 结构算子回归测试集:固化切片(head/tail/slice/iloc)、
// │         索引(loc/setIndex/RangeIndex 标签校验)、排序(sortBy 稳定性与混型)、merge(行序/键语义/
// │         dtype)、groupBy/pivotTable(分组与透视)的行为
// │  Why  : 因为结构算子的行序、键匹配与异常口径都以 pandas 实测为准(稳定排序、首现键序、混型抛错),
// │         偏差会静默丢行/错行,所以用精确断言逐口径固化
// │  Who  : jian-core 测试套件(surefire)执行
// │  When : 改动 DataFrameSort / DataFrameReshape / DataFrameMerge / GroupBy / Index 相关行为后必须全绿
// │  Where: jian-core/src/test/java/jian/core/CoreRegressionTest.java
// │  How  : 数据走向:固定小表 → 结构算子 → 断言行序/行数/键值/dtype(精确到逐行逐列)。
// │         关键变量:行序(稳定排序同键保原序、right/outer 按右表序/首现键序)、
// │         键匹配(loc 标签等价性、null 与 "<NA>" 字面量是不同的键)、越界(clamp 或 IOOBE,不静默截断)。
// │         逻辑路线:合法输入 → 精确输出;非法标签/混型 → 带上下文的 IAE/IOOBE(不裸抛、不静默)。
class CoreRegressionTest {

    /** 三行样例表:v = [1.0, 2.0, 3.0]。 */
    static DataFrame d3() {
        return DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}, {2.0}, {3.0}});
    }

    // ======================== sortBy(稳定性 / 混型)========================

    @Test
    void sortBy_同键保原序_稳定排序验证() {
        // 构造:v 全是 5(同键),id = [3, 1, 2](原序)
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{
                {3L, 5.0},
                {1L, 5.0},
                {2L, 5.0}});
        DataFrame r = df.sortBy("v", true);  // 升序(同键)
        // 稳定排序:id 列应保持原序 [3, 1, 2]
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(3L);
        assertThat(r.getLongColumn("id").getLong(1)).isEqualTo(1L);
        assertThat(r.getLongColumn("id").getLong(2)).isEqualTo(2L);
    }

    @Test
    void sortBy_同键保原序_降序也稳定() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{
                {3L, 5.0},
                {1L, 5.0},
                {2L, 5.0}});
        DataFrame r = df.sortBy("v", false);  // 降序(同键)
        // 稳定排序:同键降序时也应保原序 [3, 1, 2]
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(3L);
        assertThat(r.getLongColumn("id").getLong(1)).isEqualTo(1L);
        assertThat(r.getLongColumn("id").getLong(2)).isEqualTo(2L);
    }

    /** sortBy 混型(数值 vs 字符串)抛 IAE(对齐 pandas sort_values 的 TypeError,不走字典序)。 */
    @Test
    void sortBy混型抛IAE不走字典序() {
        DataFrame df = DataFrame.of(Schema.of("x", DType.OBJECT),
                new Object[][]{{1}, {"a"}});
        assertThatThrownBy(() -> df.sortBy("x", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("混型");
    }

    /** 含 null 的比较语义:null 走 isNull 分支先行短路,不进类型比较。 */
    @Test
    void sortBy含null同型列正常排序() {
        DataFrame df = DataFrame.of(Schema.of("x", DType.OBJECT),
                new Object[][]{{2L}, {null}, {1L}});
        DataFrame r = df.sortBy("x", true);
        assertThat(r.getColumn("x").toObjectArray()).containsExactly(1L, 2L, null);
    }

    // ======================== head / tail / slice / iloc(负数与越界)========================

    @Test
    void head与tail负数是去掉末首行_对齐pandas() {
        // head(-1)/tail(-1) 是去掉末/首 |n| 行(pandas head(-1)=除最后一行全部,不是返回空表)
        assertThat(d3().head(-1).rowCount()).isEqualTo(2);
        assertThat(d3().head(-1).getColumn("v").get(1)).isEqualTo(2.0);  // 保留 [1,2]
        assertThat(d3().tail(-1).rowCount()).isEqualTo(2);
        assertThat(d3().tail(-1).getColumn("v").get(0)).isEqualTo(2.0);  // 保留 [2,3]
        // 正数路径不回归
        assertThat(d3().head(2).rowCount()).isEqualTo(2);
        assertThat(d3().tail(2).getColumn("v").get(0)).isEqualTo(2.0);
    }

    @Test
    void slice负数倒数且越界clamp_对齐pandas() {
        // 负数倒数且越界 clamp:pandas iloc[-5:2](len=3)=2 行、iloc[0:99]=3 行
        assertThat(d3().slice(-5, 2).rowCount()).isEqualTo(2);
        assertThat(d3().slice(0, 99).rowCount()).isEqualTo(3);
        assertThat(d3().slice(1, -1).rowCount()).isEqualTo(1);
        assertThat(d3().slice(2, 1).rowCount()).isZero();  // start≥end → 空,不抛
    }

    @Test
    void iloc负下标倒数_对齐pandas() {
        // 负下标倒数:pandas iloc[-1] = 最后一行
        assertThat(d3().iloc(-1).getColumn("v").get(0)).isEqualTo(3.0);
        assertThat(d3().iloc(-3, -1).rowCount()).isEqualTo(2);  // 倒数第 3~2 行
    }

    // ======================== loc(RangeIndex 校验 / 显式标签匹配)========================

    @Test
    void loc_Long标签与Integer标签等价() {
        // setIndex 后 id 列是 Long,loc(0L) 必须命中(标签匹配不能因 equals 类型敏感而漏配)
        // 注:单列表 setIndex 需 drop=false(单列 drop=true 时剩余 0 列,既有行为)
        DataFrame df = DataFrame.ofColumnArrays(List.of("id"), new Object[]{new long[]{0L, 1L, 2L}});
        DataFrame indexed = df.setIndex(new String[]{"id"}, false);
        assertThat(indexed.loc(0L).rowCount()).isEqualTo(1);
        assertThat(indexed.loc(0L).get(0, 0)).isEqualTo(0L);
        // Integer 0 与 Long 0 等价(对齐 pandas df.loc[1] == df.loc[1L])
        assertThat(indexed.loc(0).rowCount()).isEqualTo(1);
        // 多个标签混合类型
        assertThat(indexed.loc(0L, 2).rowCount()).isEqualTo(2);
    }

    @Test
    void loc_重复标签全部保留() {
        DataFrame df = DataFrame.of(Schema.of("id", DType.INT, "v", DType.DOUBLE),
            new Object[][]{{1, 10.0}, {1, 20.0}, {2, 30.0}});
        DataFrame indexed = df.setIndex("id");
        assertThat(indexed.loc(1).rowCount()).isEqualTo(2);
    }

    @Test
    void loc_超int范围标签抛IOOBE() {
        // Long.MAX_VALUE 不能 intValue() 静默溢出为 -1,超出 int 范围抛 IOOBE
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}});
        assertThatThrownBy(() -> df.loc(Long.MAX_VALUE))
            .isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessageContaining("超出 int 范围");
    }

    @Test
    void loc_显式标签查不到返回空表() {
        DataFrame df = DataFrame.of(Schema.of("id", DType.STRING, "v", DType.DOUBLE),
            new Object[][]{{"a", 1.0}, {"b", 2.0}});
        DataFrame indexed = df.setIndex("id");
        DataFrame r = indexed.loc("zzz");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void loc_rangeIndex非数字标签抛IAE带类型提示() {
        DataFrame df = DataFrame.of(
            Schema.of("v", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        // RangeIndex 下传 String 标签 → IAE 带类型提示
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
        // 2.5 不做 intValue() 静默截断为 2(会取错行),IAE 拒绝
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
        // 合法用法不受类型检查影响
        DataFrame r = df.loc(0, 2);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.get(0, "v")).isEqualTo(10L);
        assertThat(r.get(1, "v")).isEqualTo(30L);
    }

    // ======================== setIndex(单列 / 多列复合)========================

    @Test
    void setIndex单列DataFrame返回0列N行() {
        // pandas set_index 唯一列 → N rows × 0 cols
        DataFrame r = DataFrame.of(Schema.of("x", DType.LONG), new Object[][]{{1L}, {2L}, {3L}})
                .setIndex("x");
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnCount()).isZero();
        assertThat(r.index().get(0)).isEqualTo(1L);
        assertThat(r.index().get(2)).isEqualTo(3L);
        // 多列 setIndex(drop)不回归
        DataFrame m = DataFrame.of(Schema.of("a", DType.LONG, "b", DType.LONG),
                new Object[][]{{1L, 10L}}).setIndex("a");
        assertThat(m.columnCount()).isEqualTo(1);
        assertThat(m.getColumn("b").get(0)).isEqualTo(10L);
    }

    /** setIndex("a","b") 后 a/b 值全部进复合索引,数据不丢。 */
    @Test
    void 两列setIndex值进索引不丢数据() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.LONG, "b", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{1L, "x", 10.0}, {2L, "y", 20.0}, {3L, "z", 30.0}});
        DataFrame m = df.setIndex("a", "b");
        assertThat(m.rowCount()).as("行数不变").isEqualTo(3);
        assertThat(m.columnNames()).as("drop=true 时 a/b 全部提升,只剩 v").containsExactly("v");
        // a/b 值进索引:行标签是复合 List(值语义),内容逐级核对
        assertThat(m.index().get(0)).isEqualTo(List.of(1L, "x"));
        assertThat(m.index().get(1)).isEqualTo(List.of(2L, "y"));
        assertThat(m.index().get(2)).isEqualTo(List.of(3L, "z"));
    }

    /** 三列提升(remaining 空 → 0 列 N 行工厂路径)。 */
    @Test
    void 三列setIndex返回零列N行() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.LONG, "b", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{1L, "x", 10.0}, {2L, "y", 20.0}});
        DataFrame m = df.setIndex("a", "b", "v");
        assertThat(m.rowCount()).isEqualTo(2);
        assertThat(m.columnCount()).isZero();
        assertThat(m.index().get(1)).isEqualTo(List.of(2L, "y", 20.0));
    }

    /** drop=false 时键列保留在数据列中,索引照常复合。 */
    @Test
    void 多列setIndexDropFalse保留原列() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.LONG, "b", DType.STRING),
                new Object[][]{{1L, "x"}, {2L, "y"}});
        DataFrame m = df.setIndex(new String[]{"a", "b"}, false);
        assertThat(m.columnNames()).containsExactly("a", "b");
        assertThat(m.index().get(0)).isEqualTo(List.of(1L, "x"));
    }

    /** 单列 setIndex 行为完全不变(平铺标签,非复合)。 */
    @Test
    void 单列setIndex行为不变() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.LONG, "b", DType.STRING),
                new Object[][]{{1L, "x"}, {2L, "y"}});
        DataFrame m = df.setIndex("a");
        assertThat(m.columnCount()).isEqualTo(1);
        assertThat(m.index().get(0)).isEqualTo(1L);
        assertThat(m.index().get(1)).isEqualTo(2L);
    }

    // ======================== pivotTable / GroupBy 边界 ========================

    /** index/columns 键缺失的行按 pandas dropna=True 丢弃,不 NPE。 */
    @Test
    void pivotTable缺失键行被丢弃() {
        DataFrame df = DataFrame.of(
                Schema.of("i", DType.STRING, "c", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{
                        {"RD", "Q1", 1.0}, {null, "Q1", 2.0},   // index 键缺失
                        {"PM", null, 3.0},                       // columns 键缺失
                        {"PM", "Q2", 4.0}});
        DataFrame p = df.pivotTable("i", "c", "v", "sum");
        // pandas pivot_table(dropna=True 默认):键缺失的 2 行被丢,剩 RD/Q1 与 PM/Q2
        assertThat(p.rowCount()).as("index 侧只剩 RD/PM 两行").isEqualTo(2);
        assertThat(p.columnNames()).as("columns 侧只剩 Q1/Q2 两列").containsExactly("i", "Q1", "Q2");
        assertThat(p.getColumn("i").toObjectArray()).containsExactly("RD", "PM");
        assertThat((Double) p.get(0, 1)).isEqualTo(1.0);   // RD × Q1
        assertThat((Double) p.get(1, 2)).isEqualTo(4.0);   // PM × Q2
    }

    @Test
    void groupBy含null的数值列size不抛NFE() {
        // generic 路径含 null key 的数值列:size 聚合不因 null 混入而抛 NFE
        DataFrame s = DataFrame.of(Schema.of("k", DType.LONG), new Object[][]{{1L}, {null}, {2L}})
                .groupBy("k").size();
        assertThat(s.columnNames()).containsExactly("key", "size");
        assertThat(s.getColumn("key").toObjectArray()).containsExactlyInAnyOrder(1L, null, 2L);
        assertThat(s.getColumn("size").toObjectArray()).containsExactlyInAnyOrder(1L, 1L, 1L);
    }

    /** 分组键列含字面量 "<NA>" 与 null 共存时是两个不同的组(各成一组,不合并)。 */
    @Test
    void groupBy字面量NA与null是两个组() {
        DataFrame df = DataFrame.of(
                Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"<NA>", 1.0}, {null, 2.0}, {"x", 3.0}});
        DataFrame r = df.groupBy("g").agg("v", "count");
        // null 不归并为 "<NA>" 字符串:3 个不同的组,字面量组保原值、null 组展示为 null
        assertThat(r.rowCount()).as("'<NA>' 字面量组 / null 组 / 'x' 组互不合并").isEqualTo(3);
        assertThat(r.getColumn("g").toObjectArray()).containsExactlyInAnyOrder("<NA>", null, "x");
        assertThat(r.getLongColumn("v_count").data()).containsExactlyInAnyOrder(1L, 1L, 1L);
    }

    // ======================== merge(行序 / 键语义 / dtype)========================

    /** right join 输出按右表键序驱动(pandas 1.5.3 实测口径)。 */
    @Test
    void rightJoin按右表键序驱动() {
        DataFrame l = DataFrame.of(Schema.of("k", DType.LONG, "x", DType.DOUBLE),
                new Object[][]{{2L, 10.0}, {1L, 20.0}});
        DataFrame r = DataFrame.of(Schema.of("k", DType.LONG, "y", DType.DOUBLE),
                new Object[][]{{1L, 100.0}, {2L, 200.0}});
        DataFrame m = l.merge(r, "right", "k");
        // pandas 1.5.3 实测:right 输出键序 [1,2](右表序);x 列取左表匹配行
        assertThat(m.getLongColumn("k").data()).containsExactly(1L, 2L);
        assertThat(m.getDoubleColumn("x").data()).containsExactly(20.0, 10.0);
        assertThat(m.getDoubleColumn("y").data()).containsExactly(100.0, 200.0);
    }

    /** outer join 按键分组、键序=首次出现序(先左表键后右表键;pandas 1.5.3 sort=False 实测)。 */
    @Test
    void outerJoin按首现键序分组() {
        DataFrame l = DataFrame.of(Schema.of("k", DType.LONG, "x", DType.DOUBLE),
                new Object[][]{{2L, 10.0}, {1L, 20.0}});
        DataFrame r = DataFrame.of(Schema.of("k", DType.LONG, "y", DType.DOUBLE),
                new Object[][]{{1L, 100.0}, {2L, 200.0}});
        // pandas 实测:左 k=[2,1]/右 k=[1,2] 的 outer → 键序 [2,1](首现键序,非字典序)
        assertThat(l.merge(r, "outer", "k").getLongColumn("k").data()).containsExactly(2L, 1L);

        // 三键场景:左 [3,1]、右 [2,1] → pandas 实测键序 [3,1,2](左首现 3,1 + 右独有 2)
        DataFrame l2 = DataFrame.of(Schema.of("k", DType.LONG, "x", DType.DOUBLE),
                new Object[][]{{3L, 30.0}, {1L, 10.0}});
        DataFrame r2 = DataFrame.of(Schema.of("k", DType.LONG, "y", DType.DOUBLE),
                new Object[][]{{2L, 200.0}, {1L, 100.0}});
        DataFrame o2 = l2.merge(r2, "outer", "k");
        assertThat(o2.getLongColumn("k").data()).containsExactly(3L, 1L, 2L);
        // 右表独有行(k=2)的左表列 x 为 null,右表列 y=200
        assertThat(o2.getColumn("x").isNull(2)).isTrue();
        assertThat(o2.getDoubleColumn("y").data()[2]).isEqualTo(200.0);
    }

    /** 字符串键 inner 零匹配(0 行)各列保留源 dtype(不整体退化为 STRING)。 */
    @Test
    void inner零匹配保留源列dtype() {
        DataFrame a = DataFrame.of(Schema.of("id", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"a", 10.0}});
        DataFrame b = DataFrame.of(Schema.of("id", DType.STRING, "w", DType.DOUBLE),
                new Object[][]{{"z", 100.0}});
        DataFrame m = a.merge(b, "inner", "id");
        assertThat(m.rowCount()).isZero();
        assertThat(m.getColumn("id").dtype()).as("STRING 键 0 行不退化为 STRING 以外的口径").isEqualTo(DType.STRING);
        assertThat(m.getColumn("v").dtype()).as("零匹配后 v 仍 DOUBLE").isEqualTo(DType.DOUBLE);
        assertThat(m.getColumn("w").dtype()).as("零匹配后 w 仍 DOUBLE").isEqualTo(DType.DOUBLE);
    }

    /** left join 右表全 null 列保留 LONG + nullMask(不推成 STRING)。 */
    @Test
    void leftJoin右表全null列保留dtype() {
        DataFrame a = DataFrame.of(Schema.of("id", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"x", 1.0}, {"y", 2.0}});
        DataFrame b = DataFrame.of(Schema.of("id", DType.STRING, "w", DType.LONG),
                new Object[][]{{"z", 30L}});
        DataFrame m = a.merge(b, "left", "id");
        assertThat(m.rowCount()).isEqualTo(2);
        assertThat(m.getColumn("w").dtype()).as("全 null 的 w 列仍 LONG").isEqualTo(DType.LONG);
        assertThat(m.getColumn("w").nullCount()).isEqualTo(2);
        assertThat(m.getColumn("w").isNull(0)).isTrue();
    }

    /** 键列含字面量 "<NA>" 与 null 共存时是两个不同的键(互不匹配)。 */
    @Test
    void merge字面量NA与null键不匹配() {
        DataFrame l = DataFrame.of(Schema.of("id", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"<NA>", 1.0}, {"a", 2.0}});
        DataFrame r = DataFrame.of(Schema.of("id", DType.STRING, "w", DType.DOUBLE),
                new Object[][]{{"<NA>", 10.0}, {"b", 20.0}});
        // 字面量 "<NA>" 两表都有 → 正常匹配 1 行(pandas 中 "<NA>" 是普通字符串)
        assertThat(l.merge(r, "inner", "id").rowCount()).isEqualTo(1);
        assertThat(l.merge(r, "inner", "id").getStringColumn("id").get(0)).isEqualTo("<NA>");

        // null 键(左)与字面量 "<NA>"(右)互不匹配(normKey 不得把 null 映射为 "<NA>" 字面量)
        DataFrame l2 = DataFrame.of(Schema.of("id", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{null, 1.0}, {"a", 2.0}});
        assertThat(l2.merge(r, "inner", "id").rowCount())
                .as("null 键与字面量 '<NA>' 是不同的键;'a'/'b' 也不匹配 → inner 0 行").isZero();
    }

    // ======================== renameColumns / isetitem ========================

    /** renameColumns/isetitem 的 API 行为锁定。 */
    @Test
    void renameColumns与isetitem行为锁定() {
        DataFrame df = DataFrame.of(Schema.of("a", DType.LONG, "b", DType.LONG),
                new Object[][]{{1L, 10L}, {2L, 20L}});
        DataFrame r = df.renameColumns(java.util.Map.of("a", "x"));
        assertThat(r.columnNames()).containsExactly("x", "b");
        assertThatThrownBy(() -> df.renameColumns(java.util.Map.of("nope", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        DataFrame is = df.isetitem(0, 0, 99L);
        assertThat(is.getColumn("a").get(0)).isEqualTo(99L);
        assertThat(is.getColumn("a").get(1)).isEqualTo(2L);
    }

    @Test
    void renameColumns对齐pandas() {
        DataFrame d = DataFrame.of(Schema.of("中文列", DType.LONG, "ok", DType.LONG),
                new Object[][]{{1L, 3L}, {2L, 4L}});
        DataFrame r = d.renameColumns(java.util.Map.of("中文列", "ascii_col"));
        assertThat(r.columnNames()).containsExactly("ascii_col", "ok");
        // 旧名不存在快速失败
        assertThatThrownBy(() -> d.renameColumns(java.util.Map.of("nope", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        // 产生重复列名快速失败
        assertThatThrownBy(() -> d.renameColumns(java.util.Map.of("中文列", "ok")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    // ======================== Series 结构行为(与 DataFrame 同口径)========================

    /** Series.head(-1) 对齐 DataFrame.head(-1)(去掉末尾 |n| 行);tail(-1) 同口径。 */
    @Test
    void seriesHead负数去掉尾部行() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}, {5.0}});
        Series h = df.getSeries("v").head(-1);
        assertThat(h.size()).isEqualTo(4);
        assertThat(h.toArray()).containsExactly(1.0, 2.0, 3.0, 4.0);
        Series t = df.getSeries("v").tail(-1);
        assertThat(t.size()).isEqualTo(4);
        assertThat(t.toArray()).containsExactly(2.0, 3.0, 4.0, 5.0);
        // 与 DataFrame.head(-1) 行为一致
        assertThat(df.head(-1).rowCount()).isEqualTo(4);
    }

    /** Series.sortIndices 混型抛 IAE(与 DataFrame.sortBy 口径一致)。 */
    @Test
    void seriesSortIndices混型抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("x", DType.OBJECT),
                new Object[][]{{1}, {"a"}});
        assertThatThrownBy(() -> df.getSeries("x").sortIndicesAscending())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("混型");
        // 同型 OBJECT 列不受影响
        DataFrame ok = DataFrame.of(Schema.of("x", DType.OBJECT),
                new Object[][]{{2L}, {1L}});
        assertThat(ok.getSeries("x").sortIndicesAscending()).containsExactly(1, 0);
    }
}
