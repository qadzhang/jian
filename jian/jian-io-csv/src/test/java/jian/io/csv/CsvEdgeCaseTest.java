package jian.io.csv;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : CSV 异常数据/边界输入测试 —— 畸形与边界 CSV 的读写健壮性
// │  Why  : CsvTest 只覆盖正常往返;异常数据(引号/逗号/换行嵌入、公式注入、BOM、CRLF、空行、
// │         超长、空表头、混合类型)是 IO 崩溃/数据损坏高发区,必须实跑验证(AGENTS.md §3.3)。
// │  Who  : CI(./mvnw test -pl jian-io-csv),含异常数据的用例须实跑验证
// │  When : IO 改动/回归时
// │  Where: jian-io-csv/CsvEdgeCaseTest.java
// │  How  : 数据走向:@TempDir 提供临时目录 → Files.writeString 手构造畸形 CSV 文件
// │           (不经过 Csv.write,因为要测"读真实畸形文件")→ Csv.read(path).go() 解析
// │           → DataFrame → 逐字段断言(getStringColumn/getLongColumn/columnNames/rowCount)。
// │         关键变量变化:
// │           - 引号字段:原始 "hello, world" → RFC4180 解析 → 字段值 "hello, world"(逗号保留);
// │           - BOM:0xEF 0xBB 0xBF + "id,name" → 读回首列名 "id"(无 \ufeff 前缀污染);
// │           - 公式注入:DataFrame 含 "=cmd" → Csv.write 写出 → 文件原文含 "'=cmd"(加单引号前缀,§3.7.3);
// │           - 超长:5 万 'x' → 读回 get(0) 长度 == 50000(不截断)。
// │         逻辑路线(每类异常一条独立验证路径,互不干扰):
// │           路径 A(引号转义)→ 内嵌逗号/换行/引号 → 逐字段比对 get(0..2);
// │           路径 B(公式注入)→ write 危险前缀 → 读文件原文断言含 '= / '+ / '- / '@;
// │           路径 C(BOM/CRLF/空行)→ 读后断言 rowCount + 列名 + 值(空行跳过、CRLF 不残留 \r);
// │           路径 D(超长/空表头/混合类型)→ 断言长度 == 50000 / 列数 == 2 / dtype == STRING。
class CsvEdgeCaseTest {

    @TempDir Path tmp;

    @Test
    void 读_字段含逗号引号换行_RFC4180转义正确() throws Exception {
        // 标准引号转义:内嵌逗号、内嵌换行、内嵌引号(双写)
        Path p = tmp.resolve("quote.csv");
        Files.writeString(p, "id,desc\n"
                + "1,\"hello, world\"\n"
                + "2,\"line1\nline2\"\n"
                + "3,\"say \"\"hi\"\"\"\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.getStringColumn("desc").get(0)).isEqualTo("hello, world");   // 内嵌逗号
        assertThat(r.getStringColumn("desc").get(1)).isEqualTo("line1\nline2");   // 内嵌换行
        assertThat(r.getStringColumn("desc").get(2)).isEqualTo("say \"hi\"");     // 内嵌引号
    }

    @Test
    void 写_公式注入防护_危险前缀加单引号() throws Exception {
        // What:验证 §3.7.3 红线——CSV 写出对 = + - @ 开头单元格加单引号前缀。
        // Why :Excel/WPS 打开 CSV 会把 =cmd|... 当公式执行(可 RCE);写出端必须转义为 '=cmd。
        // 伪代码:1. 造含 =/+/-/@ 四种危险前缀的 STRING 列;2. Csv.write 写临时文件;
        //         3. Files.readString 读回原文;4. 断言四种前缀都加了单引号('。
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{
                        {"=cmd|'/c calc'!A1"},   // = 开头
                        {"+1+1"},                 // + 开头
                        {"-1+1"},                 // - 开头
                        {"@SUM(A1)"}              // @ 开头
                });
        Path p = tmp.resolve("inj.csv");
        Csv.write(df, p.toString()).go();
        String content = Files.readString(p);
        assertThat(content).contains("'=cmd");   // = → '=
        assertThat(content).contains("'+1");     // + → '+
        assertThat(content).contains("'-1");     // - → '-
        assertThat(content).contains("'@SUM");   // @ → '@
    }

    @Test
    void 读_UTF8_BOM_不污染首列名() throws Exception {
        // 常见 bug:BOM 粘在第一列名前 → 列名变 "\ufeffid"
        Path p = tmp.resolve("bom.csv");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "id,name\n1,alice\n".getBytes("UTF-8");
        byte[] all = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(content, 0, all, bom.length, content.length);
        Files.write(p, all);
        DataFrame r = Csv.read(p.toString()).go();
        String firstCol = r.columnNames().get(0);
        assertThat(firstCol).isEqualTo("id");   // 不含 BOM
        assertThat(firstCol.charAt(0)).isNotEqualTo('\ufeff');
        assertThat(r.rowCount()).isEqualTo(1);
    }

    @Test
    void 读_CRLF换行兼容() throws Exception {
        Path p = tmp.resolve("crlf.csv");
        Files.writeString(p, "id,name\r\n1,alice\r\n2,bob\r\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getStringColumn("name").get(1)).isEqualTo("bob");
    }

    @Test
    void 读_数据间空行应跳过() throws Exception {
        // 空行不应产生全 null 行(常见 bug:空行被当 1 列 null)
        Path p = tmp.resolve("blank.csv");
        Files.writeString(p, "id,name\n1,alice\n\n\n2,bob\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(2);   // 空行跳过,不是 4
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getStringColumn("name").get(1)).isEqualTo("bob");
    }

    @Test
    void 读_超长字段不截断() throws Exception {
        Path p = tmp.resolve("long.csv");
        String longStr = "x".repeat(50000);
        Files.writeString(p, "id,s\n1," + longStr + "\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.getStringColumn("s").get(0).toString()).hasSize(50000);
    }

    @Test
    void 读_空表头容错() throws Exception {
        Path p = tmp.resolve("emptyh.csv");
        Files.writeString(p, "id,\n1,alice\n");   // 第二列表头为空
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.columnNames()).hasSize(2);
    }

    @Test
    void 读_混合类型列推断为STRING() throws Exception {
        // What:同一列混入数字与字符串(123 / abc / 456)时,dtype 推断应为 STRING(不失真)。
        // Why :若强推 LONG 会丢 "abc" 行(或变 null),违反"读取不丢数据";pandas 也推断为 object(=STRING)。
        // How :数据走向 "v\n123\nabc\n456" → Csv.read 扫全列 → 全部能 parse 为数字才 LONG,否则 STRING
        //      → 断言 dtype==STRING 且三行原样读回。
        Path p = tmp.resolve("mixed.csv");
        Files.writeString(p, "v\n123\nabc\n456\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.dtypes().get(0)).isEqualTo(DType.STRING);   // 混合 → STRING
        assertThat(r.getStringColumn("v").get(0)).isEqualTo("123");
        assertThat(r.getStringColumn("v").get(1)).isEqualTo("abc");
    }

    @Test
    void 读_重复行不去重_全部保留() throws Exception {
        // What:IO 读不自动去重重复行(去重是 dropDuplicates 显式算子的事);重复行应全部读入,对齐 pandas read_csv。
        // Why :若 IO 层悄悄去重,会丢用户数据且无提示 —— 是严重 bug。ExcelPitfallTest 测的"去重"是
        //      表头【列名】去重(重名列→name_1),不是行去重;两者不能混为一谈。
        // How :数据走向 "id,name\n1,alice\n1,alice\n2,bob" → Csv.read → 3 行(不去重)
        //      → 断言前两行 id 都=1(重复行原样保留)。
        Path p = tmp.resolve("duprow.csv");
        Files.writeString(p, "id,name\n1,alice\n1,alice\n2,bob\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(3);   // 重复行全保留,不去重
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getLongColumn("id").getLong(1)).isEqualTo(1L);   // 第 2 行也是 1(未去重)
        assertThat(r.getStringColumn("name").get(2)).isEqualTo("bob");
    }

    @Test
    void 读_空格分隔DATETIME推断为DATETIME列() throws Exception {
        // What:YYYY-MM-DD HH:MM:SS(空格分隔)是导入探测的标准格式之一,
        //      应推断为 DATETIME;ISO T 分隔(2026-01-01T12:00:00)同样兼容。
        // Why :用户约定 DATETIME 默认格式为 YYYY-MM-DD HH24:MI:SS(空格);导入必须能探测。
        // How :"2026-01-01 12:00:00" → Schema 正则 [ T] 匹配 + parse 校验 → DATETIME 列。
        Path p = tmp.resolve("dt.csv");
        Files.writeString(p, "ts\n2026-01-01 12:00:00\n2026-06-15 08:30:00\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.dtypes().get(0)).isEqualTo(DType.DATETIME);
        assertThat(r.getColumn("ts").get(0))
                .isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0));
        assertThat(r.getColumn("ts").get(1))
                .isEqualTo(java.time.LocalDateTime.of(2026, 6, 15, 8, 30, 0));

        // ISO T 分隔同样推断 DATETIME(兼容格式)
        Path p2 = tmp.resolve("dt2.csv");
        Files.writeString(p2, "ts\n2026-01-01T12:00:00\n");
        assertThat(Csv.read(p2.toString()).go().dtypes().get(0)).isEqualTo(DType.DATETIME);
    }

    @Test
    void 读_非法时间不误判DATETIME归STRING() throws Exception {
        // What:2026-01-01 25:99:99 正则匹配但时间非法 → 必须归 STRING(导入不崩)。
        // Why :因为正则匹配后不再做时间合法性校验就标 DATETIME,下游 LocalDateTime.parse
        //      会抛异常导致导入直接崩,所以 parse 校验失败必须归 STRING(与 DATE 列校验同口径)。
        Path p = tmp.resolve("bad_dt.csv");
        Files.writeString(p, "ts\n2026-01-01 25:99:99\n");
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.dtypes().get(0)).isEqualTo(DType.STRING);   // 非法时间归 STRING
        assertThat(r.getStringColumn("ts").get(0)).isEqualTo("2026-01-01 25:99:99");
    }
}
