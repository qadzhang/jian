package jian.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : M2.2 merge/concat 测试 —— inner/left/right/outer join + 纵向/横向拼接
class DataFrameMergeTest {

    @Test
    void innerJoin_只保留匹配行() {
        DataFrame left = users();
        DataFrame right = depts();
        DataFrame r = left.merge(right, "inner", "dept_id");
        // alice(RD), carol(RD) 匹配 RD dept → 2 行
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).contains("name", "dept_id", "dept_name");
    }

    /**
     * 对齐 pandas:leftOn≠rightOn 异名键时右表键列必须保留。
     * 三条路径(long/double/generic)都不得跳过右键列(否则 k2 列及数据整体丢失);
     * pandas(1.5.3 实测)输出 ['k1','x_x','k2','x_y'],且 outer/right 右表独有行 k1=NaN(不回填)。
     * 期望值全部来自 pandas 实测(pd.merge left_on/right_on 四种 how)。
     */
    @Test
    void 异名键merge_右表键列保留_对齐pandas() {
        DataFrame l = DataFrame.of(Schema.of("k1", DType.LONG, "x", DType.DOUBLE),
                new Object[][]{{1L, 10.0}, {2L, 20.0}, {3L, 30.0}});
        DataFrame r = DataFrame.of(Schema.of("k2", DType.LONG, "x", DType.DOUBLE),
                new Object[][]{{1L, 11.0}, {4L, 41.0}});
        for (String how : new String[]{"inner", "left", "outer", "right"}) {
            DataFrame m = l.merge(r, how, new String[]{"k1"}, new String[]{"k2"}, null);
            assertThat(m.columnNames()).as("merge(%s) 异名键应保留 k2(对齐 pandas)", how)
                    .containsExactly("k1", "x_x", "k2", "x_y");
        }
        // inner:1 行 [1, 10.0, 1, 11.0]
        DataFrame inner = l.merge(r, "inner", new String[]{"k1"}, new String[]{"k2"}, null);
        assertThat(inner.rowCount()).isEqualTo(1);
        assertThat(inner.getRow(0)).containsExactly(1L, 10.0, 1L, 11.0);
        // left:3 行,未匹配行 k2/x_y 为 null
        DataFrame lf = l.merge(r, "left", new String[]{"k1"}, new String[]{"k2"}, null);
        assertThat(lf.rowCount()).isEqualTo(3);
        assertThat(lf.getRow(1)).containsExactly(2L, 20.0, null, null);
        // outer:4 行;右表独有行(k2=4)的 k1 为 null(pandas 不把右键回填进左键列)
        DataFrame out = l.merge(r, "outer", new String[]{"k1"}, new String[]{"k2"}, null);
        assertThat(out.rowCount()).isEqualTo(4);
        assertThat(out.getRow(3)).containsExactly(null, null, 4L, 41.0);
    }

    @Test
    void leftJoin_左表全保留() {
        DataFrame left = users();
        DataFrame right = depts();
        DataFrame r = left.merge(right, "left", "dept_id");
        assertThat(r.rowCount()).isEqualTo(3);  // alice, bob, carol 全保留
        // bob 的 dept_name 应为 null(PM 在 depts 表不存在)
        for (Object[] row : r.iterRows()) {
            // 列序:name, dept_id, dept_name
            if ("bob".equals(row[0])) assertThat(row[2]).isNull();  // dept_name
        }
    }

    @Test
    void rightJoin_右表全保留() {
        DataFrame left = users();
        DataFrame right = depts();
        // STRING 键 → 落 generic 路径(right 不走 fast path),验证右表序驱动
        DataFrame r = left.merge(right, "right", "dept_id");
        // 对齐 pandas(pandas 1.5.3 实测):right=4 行,输出按右表键序驱动
        //   —— RD 匹配 alice+carol(2) + ENG 未匹配(1) + MGT 未匹配(1)(精确断言,不用 ≥3 弱断言)
        assertThat(r.rowCount()).isEqualTo(4);
        // right join 输出按右表序驱动(pandas 实测键序 [RD,RD,ENG,MGT],即 depts 表的行序;
        // 不是左序再追加未匹配)。同时抓"右表未匹配行键丢失"类 bug(ENG/MGT 的键非 null)。
        assertThat(r.getStringColumn("dept_id").data())
            .containsExactly("RD", "RD", "ENG", "MGT");
        // 行级精确(对齐 pandas):前两行是左表匹配行(alice/carol),后两行右表独有(name=null)
        assertThat(r.getStringColumn("name").data())
            .containsExactly("alice", "carol", null, null);
    }

    @Test
    void outerJoin_全保留() {
        DataFrame left = users();
        DataFrame right = depts();
        DataFrame r = left.merge(right, "outer", "dept_id");
        // 对齐 pandas(pandas 1.5.3 实测,sort=False 默认):outer=5 行,
        // 按键分组、键序=首次出现序(先扫左表键再扫右表键)——
        //   RD(匹配 alice+carol)→ PM(左独有 bob)→ ENG → MGT(右独有)
        // 精确断言锁定 pandas 实测的键序 [RD,RD,PM,ENG,MGT]
        // (注意:这不是字典序 —— pandas merge 默认 sort=False 不排序,是首现键序)。
        assertThat(r.rowCount()).isEqualTo(5);
        assertThat(r.getStringColumn("dept_id").data())
            .containsExactly("RD", "RD", "PM", "ENG", "MGT");
        assertThat(r.getStringColumn("name").data())
            .containsExactly("alice", "carol", "bob", null, null);
    }

    @Test
    void concat纵向_列对齐缺失补null() {
        DataFrame a = DataFrame.of(Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}});
        DataFrame b = DataFrame.of(Schema.of("id", DType.LONG, "age", DType.LONG),
                new Object[][]{{2L, 30L}});
        DataFrame r = DataFrame.concat(Arrays.asList(a, b), 0);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "name", "age");
        // 第 0 行 age 缺失,第 1 行 name 缺失
        assertThat(r.get(0, "age")).isNull();
        assertThat(r.get(1, "name")).isNull();
    }

    @Test
    void concat横向_列拼接() {
        DataFrame a = DataFrame.of(Schema.of("id", DType.LONG),
                new Object[][]{{1L}, {2L}});
        DataFrame b = DataFrame.of(Schema.of("name", DType.STRING),
                new Object[][]{{"alice"}, {"bob"}});
        DataFrame r = DataFrame.concat(Arrays.asList(a, b), 1);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "name");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void 多列键join() {
        DataFrame left = DataFrame.of(
                Schema.of("a", DType.STRING, "b", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"x", "1", 10.0}, {"y", "2", 20.0}});
        DataFrame right = DataFrame.of(
                Schema.of("a2", DType.STRING, "b2", DType.STRING, "w", DType.DOUBLE),
                new Object[][]{{"x", "1", 100.0}, {"z", "3", 300.0}});
        DataFrame r = left.merge(right, "inner", new String[]{"a", "b"}, new String[]{"a2", "b2"}, null);
        assertThat(r.rowCount()).isEqualTo(1);  // 只 (x,1) 匹配
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(10.0);
        assertThat(r.getDoubleColumn("w").getDouble(0)).isEqualTo(100.0);
    }

    private DataFrame users() {
        return DataFrame.of(
                Schema.of("name", DType.STRING, "dept_id", DType.STRING),
                new Object[][]{
                        {"alice", "RD"},
                        {"bob", "PM"},
                        {"carol", "RD"}
                });
    }

    private DataFrame depts() {
        return DataFrame.of(
                Schema.of("dept_id", DType.STRING, "dept_name", DType.STRING),
                new Object[][]{
                        {"RD", "Research"},
                        {"ENG", "Engineering"},
                        {"MGT", "Management"}
                });
    }
}
