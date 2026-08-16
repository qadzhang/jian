package jian.io.pickle;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : PickleRegressionTest —— .jpk 序列化回归测试集
// │  Why  : 固化 pickle 读写行为(因为恶意长度头必须抛可读 IOException 而非裸越界、
// │         0 行 round-trip 必须保留列名等边界行为一旦退化会崩 JVM 或丢 schema,
// │         所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-pickle)
// │  When : 改动 Pickle.read 头部校验 / write 空表分支后必须跑
// │  Where: jian-io-pickle/src/test/java/jian/io/pickle/PickleRegressionTest.java
// │  How  : 数据走向:构造畸形字节头 → Pickle.read → 断言 IOException 文案;
// │         0 行 DataFrame → Pickle.write → Pickle.read → 断言列名保留。
class PickleRegressionTest {

    @TempDir Path tmp;

    // ======================== 恶意长度头:可读 IOException ========================

    @Test
    void 恶意长度头抛可读IOException不裸越界() {
        // 因为 payloadLen 接近 Integer.MAX_VALUE 时 int 算术会溢出为负、长度检查静默
        // 通过,后续读缓冲时裸抛 ArrayIndexOutOfBoundsException,所以长度校验用 long
        // 提升并抛带 "payload" 提示的可读 IOException
        byte[] all = new byte[12];
        all[0] = 'J'; all[1] = 'P'; all[2] = 'K'; all[3] = '2';
        all[4] = (byte) 0x7F; all[5] = (byte) 0xFF; all[6] = (byte) 0xFF; all[7] = (byte) 0xFC;  // 0x7FFFFFFC
        assertThatThrownBy(() -> Pickle.read(new ByteArrayInputStream(all)))
                .isInstanceOf(IOException.class)                    // 不是 ArrayIndexOutOfBoundsException
                .hasMessageContaining("payload");
        byte[] neg = all.clone();
        neg[7] = (byte) 0xFF;  // payloadLen = 0x7FFFFFFF?改负:neg[4]=0xFF 全 FF = -1
        neg[4] = (byte) 0xFF; neg[5] = (byte) 0xFF; neg[6] = (byte) 0xFF;
        assertThatThrownBy(() -> Pickle.read(new ByteArrayInputStream(neg)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("payload");
    }

    // ======================== 0 行 round-trip:保留列 ========================

    @Test
    void 零行pickle往返保留列名() throws Exception {
        // 因为 0 行表的 schema 信息只存在于容器头,读侧若按数据推断会得到 0 列,
        // 所以列名随容器元数据写出、读侧按元数据还原
        DataFrame empty = DataFrame.of(Schema.of("a", DType.LONG, "b", DType.DOUBLE), new Object[0][]);
        Path p = tmp.resolve("e.jpk");
        Pickle.write(empty, p.toString());
        DataFrame back = Pickle.read(p.toString());
        assertThat(back.columnNames()).containsExactly("a", "b");
    }

    // ======================== 严格等长校验 ========================

    @Test
    void 尾部多余字节_读取抛IOException不再静默忽略() throws Exception {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{1L}, {2L}});
        Path p = tmp.resolve("trailing.jpk");
        Pickle.write(df, p.toString());
        // 在合法文件末尾追加垃圾字节(拼接/损坏形态);修复前静默忽略通过全部校验
        byte[] orig = java.nio.file.Files.readAllBytes(p);
        byte[] tampered = new byte[orig.length + 3];
        System.arraycopy(orig, 0, tampered, 0, orig.length);
        tampered[orig.length] = 1; tampered[orig.length + 1] = 2; tampered[orig.length + 2] = 3;
        Path p2 = tmp.resolve("trailing2.jpk");
        java.nio.file.Files.write(p2, tampered);
        assertThatThrownBy(() -> Pickle.read(p2.toString()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("多余尾部字节");
    }
}
