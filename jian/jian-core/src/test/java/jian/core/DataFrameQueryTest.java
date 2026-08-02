package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : M1.2 测试 —— query / 列比较 / loc / iloc / astype
class DataFrameQueryTest {

    @Test
    void query_比较与逻辑() {
        DataFrame df = sample();
        DataFrame r = df.query("age > 25 && city == 'SH'");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void query_支持or和括号() {
        DataFrame df = sample();
        DataFrame r = df.query("age < 30 || (age > 35 && city == 'SZ')");
        // alice(30SH)? bob(25BJ)? carol(40SZ)
        // age<30: bob(25); age>35 && SZ: carol(40SZ) → 共 2
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("name").data()).containsExactlyInAnyOrder("bob", "carol");
    }

    @Test
    void query_between() {
        DataFrame df = sample();
        DataFrame r = df.query("age between 26 and 40");
        assertThat(r.rowCount()).isEqualTo(2);  // alice(30), carol(40)
        assertThat(r.getStringColumn("name").data()).containsExactlyInAnyOrder("alice", "carol");
    }

    @Test
    void query_like通配() {
        DataFrame df = sample();
        DataFrame r = df.query("city like 'S%'");
        assertThat(r.rowCount()).isEqualTo(2);  // SH, SZ
    }

    @Test
    void query_isNull() {
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "nick", DType.STRING),
                new Object[][]{{"alice", "ali"}, {"bob", null}});
        DataFrame r = df.query("nick is null");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("bob");

        DataFrame r2 = df.query("nick is not null");
        assertThat(r2.rowCount()).isEqualTo(1);
    }

    @Test
    void query_列不存在抛友好提示() {
        DataFrame df = sample();
        assertThatThrownBy(() -> df.query("notExist > 18"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在")
                .hasMessageContaining("现有列");
    }

    @Test
    void query_语法错误带位置() {
        DataFrame df = sample();
        assertThatThrownBy(() -> df.query("age > "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("位置");
    }

    @Test
    void 列级比较_返回mask() {
        DataFrame df = sample();
        BoolColumn mask = df.colGt("age", 28);
        assertThat(mask.size()).isEqualTo(3);
        assertThat(mask.getBool(0)).isTrue();   // alice 30
        assertThat(mask.getBool(1)).isFalse();  // bob 25
        assertThat(mask.getBool(2)).isTrue();   // carol 40
    }

    @Test
    void 列级比较_mask链式过滤() {
        DataFrame df = sample();
        BoolColumn m1 = df.colGe("age", 30);
        BoolColumn m2 = df.colEq("city", "SH");
        // 用 AND 语义合并:数组逐元素与
        boolean[] and = new boolean[df.rowCount()];
        for (int i = 0; i < and.length; i++) and[i] = m1.getBool(i) && m2.getBool(i);
        DataFrame r = df.filter(and);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void iloc按位置选行() {
        DataFrame df = sample();
        DataFrame r = df.iloc(0, 2);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getStringColumn("name").get(1)).isEqualTo("carol");
    }

    @Test
    void loc按标签_RangeIndex等于位置() {
        DataFrame df = sample();
        DataFrame r = df.loc(1);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("bob");
    }

    @Test
    void astype_整列转DOUBLE() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.LONG),
                new Object[][]{{1L}, {2L}, {3L}});
        DataFrame r = df.astype("v", DType.DOUBLE);
        assertThat(r.dtypes()).containsExactly(DType.DOUBLE);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(1.0);
    }

    @Test
    void astype_数值列转STRING() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.5}, {2.5}});
        DataFrame r = df.astype("v", DType.STRING);
        assertThat(r.dtypes()).containsExactly(DType.STRING);
        assertThat(r.getStringColumn("v").get(0)).isEqualTo("1.5");
    }

    @Test
    void query_链式filter后取head() {
        DataFrame df = sample();
        DataFrame r = df.query("age >= 25").head(2);
        assertThat(r.rowCount()).isEqualTo(2);
    }

    // ======================== 2026-08-02 审查修复回归:in 谓词 / like 防注入 / df.sql 兜底 ========================

    @Test
    void query_in谓词() {
        DataFrame df = sample();
        DataFrame r = df.query("city in ('SH', 'BJ')");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("name").data()).containsExactlyInAnyOrder("alice", "bob");

        // not in
        DataFrame r2 = df.query("city not in ('SH', 'BJ')");
        assertThat(r2.rowCount()).isEqualTo(1);
        assertThat(r2.getStringColumn("name").get(0)).isEqualTo("carol");

        // 数值 in(跨类型:Long 30 vs Double 30.0 视为相等)
        DataFrame r3 = df.query("age in (25, 30.0)");
        assertThat(r3.rowCount()).isEqualTo(2);
    }

    @Test
    void query_in空列表() {
        DataFrame df = sample();
        // 空列表:任何值都不命中
        assertThat(df.query("city in ()").rowCount()).isEqualTo(0);
    }

    @Test
    void query_like不注入正则() {
        DataFrame df = DataFrame.of(
                Schema.of("s", DType.STRING),
                new Object[][]{{"a.b"}, {"axb"}});
        // 安全回归:like 模式除 % _ 外全按字面量(旧实现 "a.b" 会被正则 . 通配到 axb)
        DataFrame r = df.query("s like 'a.b'");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getStringColumn("s").get(0)).isEqualTo("a.b");
    }

    @Test
    void df_sql未引dsl抛ModuleNotLoaded() {
        // core 兜底:df.sql() 需要 jian-dsl,未引时抛带安装提示的异常(而非 NoClassDefFoundError)
        DataFrame df = sample();
        assertThatThrownBy(() -> df.sql("SELECT * FROM this"))
                .isInstanceOf(ModuleNotLoadedException.class)
                .hasMessageContaining("jian-dsl");
    }

    private DataFrame sample() {
        return DataFrame.of(
                Schema.of("name", DType.STRING, "age", DType.LONG, "city", DType.STRING),
                new Object[][]{
                        {"alice", 30L, "SH"},
                        {"bob", 25L, "BJ"},
                        {"carol", 40L, "SZ"}
                });
    }
}
