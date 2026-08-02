package jian.io.latex;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LatexIoTest {

    @TempDir Path tmp;

    @Test
    void 写出LaTeX表格() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, "alice"}, {2L, "bob"}});
        Path p = tmp.resolve("out.tex");
        LatexIo.write(df, p.toString()).caption("员工").go();

        String content = java.nio.file.Files.readString(p);
        assertThat(content).contains("\\begin{tabular}");
        assertThat(content).contains("\\toprule");
        assertThat(content).contains("alice");
        assertThat(content).contains("\\caption{员工}");
    }
}
