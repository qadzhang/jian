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
        DataFrame r = left.merge(right, "right", "dept_id");
        // depts 全保留:RD, ENG, MGT
        assertThat(r.rowCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void outerJoin_全保留() {
        DataFrame left = users();
        DataFrame right = depts();
        DataFrame r = left.merge(right, "outer", "dept_id");
        // 左 3 + 右未匹配的(MGT) = 至少 4
        assertThat(r.rowCount()).isGreaterThanOrEqualTo(4);
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
