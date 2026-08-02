package jian.num;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : Ndarray 多 dtype 引擎的单元测试
// │  Why  : 验证 INT64/FLOAT64/BOOL/OBJECT 五类、整数精度、字符串高频操作、缺失值、类型提升
// │  How  : 每个用例给定输入与预期,断言完全相等;关键场景:整数不丢精度、字符串批量变换、NaN 传播
class NdarrayTest {

    @Test
    void 整数独立保留精度_不转浮点() {
        // 大整数超 2^53,double 会失真;INT64 必须精确
        long bigId = 9_000_000_000_000_000_001L;
        Ndarray a = Ndarray.of(new long[]{bigId, bigId + 1, bigId + 2});
        assertThat(a.dtype()).isEqualTo(DType.INT64);
        assertThat(a.getInt(0)).isEqualTo(bigId);
        assertThat(a.getInt(2)).isEqualTo(bigId + 2);
    }

    @Test
    void 浮点缺失值为NaN() {
        Ndarray a = Ndarray.of(new double[]{1.0, Double.NaN, 3.0});
        assertThat(Double.isNaN(a.getFloat(1))).isTrue();
        assertThat(a.getFloat(0)).isEqualTo(1.0);
    }

    @Test
    void 整数加整数_结果仍INT64() {
        Ndarray a = Ndarray.of(new long[]{1, 2, 3});
        Ndarray b = Ndarray.of(new long[]{10, 20, 30});
        Ndarray r = a.add(b);
        assertThat(r.dtype()).isEqualTo(DType.INT64);
        assertThat(r.toLongArray()).containsExactly(11L, 22L, 33L);
    }

    @Test
    void 整数加浮点_提升为FLOAT64() {
        Ndarray a = Ndarray.of(new long[]{1, 2, 3});
        Ndarray b = Ndarray.of(new double[]{0.5, 0.5, 0.5});
        Ndarray r = a.add(b);
        assertThat(r.dtype()).isEqualTo(DType.FLOAT64);
        assertThat(r.toDoubleArray()).containsExactly(1.5, 2.5, 3.5);
    }

    @Test
    void NaN参与算术_结果传播为NaN() {
        Ndarray a = Ndarray.of(new double[]{1.0, Double.NaN, 3.0});
        Ndarray b = Ndarray.of(new double[]{10.0, 20.0, 30.0});
        Ndarray r = a.add(b);
        assertThat(r.getFloat(0)).isEqualTo(11.0);
        assertThat(Double.isNaN(r.getFloat(1))).isTrue();
    }

    @Test
    void 字符串批量大写_null透传() {
        Ndarray a = Ndarray.ofStrings("alice", null, "Bob");
        Ndarray r = a.str().upper();
        assertThat(r.dtype()).isEqualTo(DType.OBJECT);
        assertThat(r.get(0)).isEqualTo("ALICE");
        assertThat(r.get(1)).isNull();
        assertThat(r.get(2)).isEqualTo("BOB");
    }

    @Test
    void 字符串长度_返回INT64() {
        Ndarray a = Ndarray.ofStrings("abc", "hello", null);
        Ndarray r = a.str().length();
        assertThat(r.dtype()).isEqualTo(DType.INT64);
        assertThat(r.getInt(0)).isEqualTo(3L);
        assertThat(r.getInt(1)).isEqualTo(5L);
        // null → Long.MIN_VALUE 标记缺失
        assertThat(r.getInt(2)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void 字符串包含子串_返回BOOL() {
        Ndarray a = Ndarray.ofStrings("alice@x.com", "bob@y.com", null, "carol");
        Ndarray r = a.str().contains("@");
        assertThat(r.dtype()).isEqualTo(DType.BOOL);
        assertThat(r.getBool(0)).isTrue();
        assertThat(r.getBool(1)).isTrue();
        assertThat(r.getBool(2)).isNull();  // null 透传
        assertThat(r.getBool(3)).isFalse();
    }

    @Test
    void 字符串切片_支持负索引() {
        Ndarray a = Ndarray.ofStrings("hello world", "jian", null);
        // 正索引取前 5 字符
        Ndarray r = a.str().slice(0, 5);
        assertThat(r.get(0)).isEqualTo("hello");
        assertThat(r.get(1)).isEqualTo("jian");  // "jian".substring(0,5) 会被 clamp 到 4
        assertThat(r.get(2)).isNull();

        // 负索引取后 5 字符
        Ndarray tail = a.str().slice(-5, Integer.MAX_VALUE);
        assertThat(tail.get(0)).isEqualTo("world");
    }

    @Test
    void 长文本支持_至少10M() {
        // JVM String 无长度上限,验证 10M 字符串能塞进 Ndarray 并运算
        char[] chars = new char[10_000_000];
        java.util.Arrays.fill(chars, 'A');
        String big = new String(chars);
        Ndarray a = Ndarray.ofStrings(big, big);
        assertThat(((String) a.get(0)).length()).isEqualTo(10_000_000);
        Ndarray len = a.str().length();
        assertThat(len.getInt(0)).isEqualTo(10_000_000L);
    }

    @Test
    void 比较运算_字符串字典序() {
        Ndarray a = Ndarray.ofStrings("apple", "banana", "cherry");
        Ndarray b = Ndarray.ofStrings("apple", "apple", "apple");
        Ndarray gt = a.gt(b);
        assertThat(gt.getBool(0)).isFalse();  // apple == apple
        assertThat(gt.getBool(1)).isTrue();   // banana > apple
        assertThat(gt.getBool(2)).isTrue();   // cherry > apple
    }

    @Test
    void 非数值算术抛异常() {
        Ndarray a = Ndarray.ofStrings("a", "b");
        Ndarray b = Ndarray.ofStrings("c", "d");
        assertThatThrownBy(() -> a.add(b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅数值 dtype");
    }

    @Test
    void isna标记缺失_全dtype通用() {
        Ndarray a = Ndarray.of(new Object[]{"x", null, 123, null});
        Ndarray mask = a.isna();
        assertThat(mask.dtype()).isEqualTo(DType.BOOL);
        assertThat(mask.getBool(0)).isFalse();
        assertThat(mask.getBool(1)).isTrue();
        assertThat(mask.getBool(3)).isTrue();
    }

    @Test
    void astype_整数转浮点() {
        Ndarray a = Ndarray.of(new long[]{1, 2, 3});
        Ndarray f = a.astype(DType.FLOAT64);
        assertThat(f.dtype()).isEqualTo(DType.FLOAT64);
        assertThat(f.toDoubleArray()).containsExactly(1.0, 2.0, 3.0);
    }

    // ======================== 2026-08-02 补齐:实例 sum/mean(规范 06 §2.1) ========================

    @Test
    void sum与mean() {
        Ndarray a = Ndarray.of(new long[]{1, 2, 3, 4});
        assertThat(a.sum()).isEqualTo(10.0);
        assertThat(a.mean()).isEqualTo(2.5);

        // FLOAT64 跳过 NaN
        Ndarray b = Ndarray.of(new double[]{1.0, Double.NaN, 3.0});
        assertThat(b.sum()).isEqualTo(4.0);
        assertThat(b.mean()).isEqualTo(2.0);
    }

    @Test
    void sum空数组为NaN() {
        Ndarray a = Ndarray.of(new double[]{});
        assertThat(Double.isNaN(a.mean())).isTrue();
        assertThat(a.sum()).isEqualTo(0.0);
    }

    @Test
    void sum非数值dtype抛异常() {
        Ndarray b = Ndarray.of(new Boolean[]{true, false});
        assertThatThrownBy(b::sum).isInstanceOf(IllegalStateException.class);
    }
}

