package jian.io.clipboard;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardTest {

    @BeforeEach
    void 清除内存降级缓存() {
        // 因为 memoryFallback 是 static volatile,测试间不清理会污染下一个测试的读
        // (先跑的测试设了 fallback,后跑的测试读到旧值 → 断言失败),所以每个测试前清一次,保证隔离。
        Clipboard.resetMemoryFallback();
        Clipboard.testForceMemoryFallback = true;   // 封闭测试:强制内存路径,防有 xclip 的机器上 daemon 竞争 flaky
    }

    @org.junit.jupiter.api.AfterEach
    void 还原测试缝() {
        Clipboard.testForceMemoryFallback = false;
    }

    @Test
    void 写入降级到内存后能读回() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        // CI 环境通常无剪贴板命令 → 自动降级到内存
        Clipboard.write(df);
        DataFrame r = Clipboard.read();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "name");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void 缺失值在TSV往返() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.STRING, "b", DType.LONG),
                new Object[][]{{"x", 1L}, {null, 2L}});
        Clipboard.write(df);
        DataFrame r = Clipboard.read();
        assertThat(r.rowCount()).isEqualTo(2);
        // 第二行 a 缺失
        assertThat(r.getStringColumn("a").get(1)).isNull();
    }
}
