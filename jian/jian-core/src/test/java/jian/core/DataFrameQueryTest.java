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
        // 因为 pandas 的 query/布尔索引保持原行序,所以这里用精确序断言(AnyOrder 弱断言会放过乱序)。
        // sample 行序 alice/bob/carol → 命中 bob(行1)、carol(行2),保序应为 [bob, carol]。
        assertThat(r.getStringColumn("name").data()).containsExactly("bob", "carol");
    }

    @Test
    void query_between() {
        DataFrame df = sample();
        DataFrame r = df.query("age between 26 and 40");
        assertThat(r.rowCount()).isEqualTo(2);  // alice(30), carol(40)
        // 对齐 pandas保序:命中 alice(行0)、carol(行2),应为 [alice, carol]。
        assertThat(r.getStringColumn("name").data()).containsExactly("alice", "carol");
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
    void astype_DOUBLE含NaN转LONG用哨兵且isNull为真() {
        // 契约(§3.5 + §10.16 #1/#2):
        //   DOUBLE 列含 NaN/缺失 → LONG 时,NaN 行转 Long.MIN_VALUE 哨兵 + isNull 为真
        //   (对齐 jian 缺失值语义;区别于 pandas 的 IntCastingNaNError——jian 选择不失真而非报错)。
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}, {3.0}});
        DataFrame r = df.astype("v", DType.LONG);
        assertThat(r.dtypes()).containsExactly(DType.LONG);
        // 正常行
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(1L);
        assertThat(r.getLongColumn("v").getLong(2)).isEqualTo(3L);
        // 缺失行:isNull 真(权威判断)+ getLong 哨兵(§3.5 LONG 列缺失标记)
        assertThat(r.getLongColumn("v").isNull(1)).isTrue();
        assertThat(r.getLongColumn("v").getLong(1)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void query_链式filter后取head() {
        DataFrame df = sample();
        DataFrame r = df.query("age >= 25").head(2);
        assertThat(r.rowCount()).isEqualTo(2);
    }

    // ======================== 扩展回归:in 谓词 / like 防注入 / df.sql 兜底 ========================

    @Test
    void query_in谓词() {
        DataFrame df = sample();
        DataFrame r = df.query("city in ('SH', 'BJ')");
        assertThat(r.rowCount()).isEqualTo(2);
        // 对齐 pandas 保序:sample 行序 alice/bob/carol,命中 alice(行0)、bob(行1)
        assertThat(r.getStringColumn("name").data()).containsExactly("alice", "bob");

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
        // 因为 like 走字面量匹配(仅 % _ 是通配符,不按正则解释),所以 "a.b" 不会命中 "axb"
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

    @Test
    void astype_DATETIME空格分隔默认格式解析() {
        // What:YYYY-MM-DD HH:MM:SS(空格)是 DATETIME 默认格式;ISO T 兼容;
        //      错误消息必须说明两种格式(只说 ISO T 会误导 —— 实际空格一直支持)。
        DataFrame df = DataFrame.of(Schema.of("ts", DType.STRING),
                new Object[][]{{"2026-01-01 12:00:00"}, {"2026-01-01T08:30:00"}});
        DataFrame r = df.astype("ts", DType.DATETIME);
        assertThat(r.getColumn("ts").get(0)).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0));
        assertThat(r.getColumn("ts").get(1)).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 8, 30, 0));
        // 非法值 → IAE 且消息含 YYYY-MM-DD HH:MM:SS(默认格式说明)
        DataFrame bad = DataFrame.of(Schema.of("ts", DType.STRING), new Object[][]{{"not a date"}});
        assertThatThrownBy(() -> bad.astype("ts", DType.DATETIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD HH:MM:SS");
    }

    @Test
    void toString_DATETIME列显示空格分隔格式() {
        // What:DATETIME 显示用 YYYY-MM-DD HH:MM:SS(空格,对齐 pandas)。
        // Why :LocalDateTime.toString() 输出 ISO T 分隔且整分省略秒("12:00"),不符合默认格式约定。
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME),
                new Object[][]{{java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0)}});
        String s = df.toString();
        assertThat(s).contains("2026-01-01 12:00:00");   // 空格分隔 + 完整秒
        assertThat(s).doesNotContain("2026-01-01T12");   // 不是 ISO T、不是省略秒
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
