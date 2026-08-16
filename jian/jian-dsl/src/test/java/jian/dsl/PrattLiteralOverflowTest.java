package jian.dsl;

// ┌─ What : Pratt 引擎数字字面量超 long 范围的 fail-fast 回归(第 1 轮审计 BUG-7)
// │  Why  : 修复前超 long 的纯整数字面量静默回退 double(9223372036854775808 折成
// │         9.223372036854776E18,恰等于 Long.MAX 列值的 double 投影,> / == 误匹配);
// │         修复后抛 IAE 提示改写科学计数法 —— 与 SimpleQueryParser 双引擎口径同步。
// │  Who  : 审计修复配套(1测试结果.md BUG-7)
// │  When : jian-dsl 测试运行
// │  Where: jian-dsl/src/test/java/jian/dsl/PrattLiteralOverflowTest.java
// │  How  : 三条路径 —— ①纯整数超 Long.MAX → IAE;②科学计数法照常按 double 近似;
// │         ③合法 long 字面量(含 Long.MAX 本身)精确解析。
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PrattLiteralOverflowTest {

    @Test
    void 纯整数字面量超long_抛IAE带改写提示() {
        DataFrame df = DataFrame.of(Schema.of("id", DType.LONG),
            new Object[][]{{Long.MAX_VALUE}});
        assertThatThrownBy(() -> PrattEngine.query(df, "id > 9223372036854775808", Params.EMPTY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超出 long 范围")
            .hasMessageContaining("科学计数法");
        // 负方向超界同样拒绝(词法器把负号作一元运算,字面量本身是纯数字串)
        assertThatThrownBy(() -> PrattEngine.query(df, "id < 99999999999999999999", Params.EMPTY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超出 long 范围");
    }

    @Test
    void 科学计数法_照常按double近似() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{200.0}});
        DataFrame r = PrattEngine.query(df, "v > 1e2", Params.EMPTY);
        assertThat(r.rowCount()).isEqualTo(1);
        DataFrame r2 = PrattEngine.query(df, "v > 1.5E1", Params.EMPTY);
        assertThat(r2.rowCount()).isEqualTo(1);
    }

    @Test
    void 合法long字面量_含边界_精确解析() {
        // Long.MAX_VALUE 本身是合法 long 字面量,必须走 long 精确路径(与 LONG 列整数精确比)
        DataFrame df = DataFrame.of(Schema.of("id", DType.LONG),
            new Object[][]{{Long.MAX_VALUE}, {Long.MAX_VALUE - 1}});
        DataFrame eq = PrattEngine.query(df, "id == 9223372036854775807", Params.EMPTY);
        assertThat(eq.rowCount()).isEqualTo(1);
        DataFrame gt = PrattEngine.query(df, "id >= 9223372036854775806", Params.EMPTY);
        assertThat(gt.rowCount()).isEqualTo(2);   // MAX-1 与 MAX 都 ≥ 字面量(严格 > 只有 MAX)
        DataFrame strict = PrattEngine.query(df, "id > 9223372036854775806", Params.EMPTY);
        assertThat(strict.rowCount()).isEqualTo(1);
    }
}
