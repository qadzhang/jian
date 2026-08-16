package jian.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : RecordBridgeTest —— DataFrame ↔ record 映射 + selectBy 谓词选列的回归测试
// │  Why  : 借鉴 Kotlin DataFrame 的 convertTo/列选择器/schema 常量化设计;
// │         按 ai-code-testing skill 用蜕变关系绕开 oracle:df→records→df 回环后
// │         「形状与逐格值不变」,比单点期望值更能防"期望本身写错"
// │  Who  : mvn -pl jian-core test
// │  When : jian-core 测试套件常规执行
// │  Where: jian-core/src/test/java/jian/core/RecordBridgeTest.java
// │  How  : 覆盖面:①回环蜕变(全类型矩阵) ②中文 record 组件 ③null/缺失值包装类型
// │         ④异常路径(缺列/跨族类型/越界/null 进原始类型/非 record/空列表)
// │         ⑤selectBy(前缀/全不匹配/与 select 等价)
record Order(String 类别, String 名称, long 金额, double 折扣, boolean 会员, LocalDate 日期) {}

class RecordBridgeTest {

    private static DataFrame orders() {
        return DataFrame.of(Schema.of(
                        "类别", DType.STRING, "名称", DType.STRING, "金额", DType.LONG,
                        "折扣", DType.DOUBLE, "会员", DType.BOOL, "日期", DType.DATE,
                        "备注", DType.STRING),   // 备注 = df 多余列,验证投影语义
                new Object[][]{
                        {"食品", "苹果", 10L, 0.9, true, LocalDate.of(2026, 1, 1), "多余"},
                        {"文具", "铅笔", 5L, 0.8, false, LocalDate.of(2026, 1, 2), null},
                });
    }

    @Test
    void 回环蜕变_df到records到df后形状与值不变() {
        DataFrame src = orders();
        List<Order> list = src.toRecords(Order.class);
        // 蜕变 R2:行数不变;组件值逐格等于列值(多余列"备注"被投影掉)
        assertThat(list).hasSize(2);
        assertThat(list.get(0).类别()).isEqualTo("食品");
        assertThat(list.get(0).金额()).isEqualTo(10L);
        assertThat(list.get(0).折扣()).isEqualTo(0.9);
        assertThat(list.get(0).会员()).isTrue();
        assertThat(list.get(0).日期()).isEqualTo(LocalDate.of(2026, 1, 1));
        // 蜕变 R1:records → df 回环后,六列逐格相等(类型由组件声明精确定 DType)
        DataFrame back = DataFrame.fromRecords(list);
        assertThat(back.columnNames()).containsExactly("类别", "名称", "金额", "折扣", "会员", "日期");
        assertThat(back.rowCount()).isEqualTo(2);
        assertThat(back.getColumn("金额").get(1)).isEqualTo(5L);
        assertThat(back.getColumn("日期").get(1)).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(back.dtypes()).containsExactly(DType.STRING, DType.STRING, DType.LONG,
                DType.DOUBLE, DType.BOOL, DType.DATE);
    }

    @Test
    void 缺失值要求包装类型组件() {
        record Wrap(String s, Long l, Double d) {}
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING, "l", DType.LONG, "d", DType.DOUBLE),
                new Object[][]{{"a", null, null}, {"b", 2L, 2.5}});
        List<Wrap> list = df.toRecords(Wrap.class);
        assertThat(list.get(0).l()).isNull();          // LONG 列缺失 → null
        assertThat(list.get(0).d()).isNaN();           // §3.5:DOUBLE 列缺失以 NaN 不失真传递(API 出口不做 null 转换)
        assertThat(list.get(1).l()).isEqualTo(2L);
        assertThat(list.get(1).d()).isEqualTo(2.5);
    }

    @Test
    void 组件缺列报错并提示现有列() {
        record Mismatch(String 不存在的列) {}
        assertThatThrownBy(() -> orders().toRecords(Mismatch.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在的列")
                .hasMessageContaining("现有列");
    }

    @Test
    void 跨族类型不匹配报错() {
        record Bad(int 折扣) {}   // DOUBLE 列 → int 组件:跨族,拒绝
        assertThatThrownBy(() -> orders().toRecords(Bad.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型不匹配")
                .hasMessageContaining("astype");
    }

    @Test
    void null进原始类型组件报错() {
        record Prim(long l) {}
        DataFrame df = DataFrame.of(Schema.of("l", DType.LONG), new Object[][]{{1L}, {null}});
        assertThatThrownBy(() -> df.toRecords(Prim.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原始类型")
                .hasMessageContaining("包装类型");
    }

    @Test
    void 整型族窄化越界报错_合法范围放行() {
        record Narrow(int 金额) {}
        DataFrame ok = DataFrame.of(Schema.of("金额", DType.LONG),
                new Object[][]{{100L}, {Integer.MAX_VALUE + 0L}});
        assertThat(ok.toRecords(Narrow.class).get(1).金额()).isEqualTo(Integer.MAX_VALUE);
        DataFrame overflow = DataFrame.of(Schema.of("金额", DType.LONG),
                new Object[][]{{Integer.MAX_VALUE + 1L}});
        assertThatThrownBy(() -> overflow.toRecords(Narrow.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出 int 范围");
    }

    @Test
    void 非record与空列表报错() {
        assertThatThrownBy(() -> orders().toRecords(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 Java record");
        assertThatThrownBy(() -> DataFrame.fromRecords(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少一条");
    }

    @Test
    void fromRecords混合类型列表报错() {
        record A(String x) {}
        record B(String x) {}
        assertThatThrownBy(() -> DataFrame.fromRecords(List.of(new A("a"), new B("b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型不齐");
    }

    // ======================== selectBy(列选择器谓词)========================

    @Test
    void selectBy前缀与类型谓词() {
        DataFrame df = DataFrame.of(Schema.of(
                "q1", DType.LONG, "q2", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{1L, 2L, "a", 9.5}});
        assertThat(df.selectBy(c -> c.startsWith("q")).columnNames())
                .containsExactly("q1", "q2");
        assertThat(df.selectBy(c -> c.startsWith("q") || "name".equals(c)).columnNames())
                .containsExactly("q1", "q2", "name");
        // 全不命中 → 0 列表(与 select() 空参行为一致,不抛)
        assertThat(df.selectBy(c -> false).columnCount()).isZero();
        // 与显式 select 等价(差分:谓词版 == 枚举版)
        assertThat(df.selectBy(c -> c.startsWith("q")).columnNames())
                .isEqualTo(df.select("q1", "q2").columnNames());
    }
}
