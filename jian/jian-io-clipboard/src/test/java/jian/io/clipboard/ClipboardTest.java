package jian.io.clipboard;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardTest {

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
