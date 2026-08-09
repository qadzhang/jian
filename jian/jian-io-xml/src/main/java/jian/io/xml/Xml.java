package jian.io.xml;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jian.core.DataFrame;
import jian.core.Schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ┌─ What : Xml —— XML 读写(对齐 pandas.read_xml / to_xml,基于 Jackson XML)
// │  Why  : 规范 02 §3.5;XML 用于配置、API 数据交换
// │  Who  : 用户经 Jian.readXml 或 Xml.read 调用
// │  When : XML 文件读写
// │  Where: jian-io-xml/Xml.java
// │  How  : 数据走向:
// │           读:Path → Jackson XmlMapper readTree → 找 rowName 子元素 → 每个 → 列;
// │           写:DataFrame → <root><row>col1=val</row>...</root> → 文件。
// │         关键变量变化:
// │           - rootName/rowName:可配置(默认 "rows"/"row");
// │           - attributeMode:列作属性还是子元素(M4 默认子元素)。
/**
 * XML 读写,对齐 pandas.read_xml / to_xml(基于 Jackson XML)。
 *
 * <p>用法:
 * <pre>{@code
 * DataFrame df = Xml.read("data.xml").rowName("item").go();
 * Xml.write(df, "out.xml").rootName("rows").rowName("item").go();
 * }</pre>
 */
public final class Xml {

    private Xml() {}

    private static final XmlMapper MAPPER = new XmlMapper();

    /**
     * 读 XML 的 builder(默认行元素名 "row")。
     * @param path String XML 文件路径,需为合法可读文件,不允许 null
     * @return XmlReader 配置器,链式调用 .rowName 后 .go() 执行
     */
    public static XmlReader read(String path) { return new XmlReader(Path.of(path)); }

    public static final class XmlReader {
        private final Path path;
        private String rowName = "row";

        XmlReader(Path p) { this.path = p; }

        /**
         * 设置行元素名(每条记录对应的 XML 元素名)。
         * @param n String 行元素标签名,需与 XML 中的元素名一致(大小写敏感),默认 "row"
         * @return XmlReader 当前配置器,便于链式调用
         */
        public XmlReader rowName(String n) { this.rowName = n; return this; }

        public DataFrame go() throws IOException {
            String xml = Files.readString(path, StandardCharsets.UTF_8);
            return parse(xml, rowName);
        }
    }

    /**
     * 解析 XML 字符串(简单实现:正则提取 rowName 元素的字段)。
     * @param xml String XML 文本内容,需为合法 XML,不允许 null
     * @param rowName String 行元素标签名(每条记录对应的元素名),如 "row"/"item",大小写敏感
     * @return DataFrame 解析出的数据帧(列名取首行元素的字段名,类型自动推断)
     * @throws IOException XML 解析错误时抛出
     */
    @SuppressWarnings("unchecked")
    public static DataFrame parse(String xml, String rowName) throws IOException {
        // 用 Jackson readTree 解析
        com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(xml);
        // root 是对象,找到所有 rowName 子节点
        List<com.fasterxml.jackson.databind.JsonNode> rows = new ArrayList<>();
        findRows(root, rowName, rows);
        if (rows.isEmpty()) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        // 取列名(首行的字段)
        List<String> names = new ArrayList<>();
        rows.get(0).fieldNames().forEachRemaining(names::add);
        Object[][] data = new Object[rows.size()][names.size()];
        for (int r = 0; r < rows.size(); r++) {
            com.fasterxml.jackson.databind.JsonNode row = rows.get(r);
            for (int c = 0; c < names.size(); c++) {
                com.fasterxml.jackson.databind.JsonNode v = row.get(names.get(c));
                data[r][c] = nodeToValue(v);
            }
        }
        return DataFrame.of(Schema.infer(names, data), data);
    }

    /** 递归找 rowName 元素。 */
    private static void findRows(com.fasterxml.jackson.databind.JsonNode node, String rowName,
                                  List<com.fasterxml.jackson.databind.JsonNode> out) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode child = node.get(rowName);
            if (child != null) {
                if (child.isArray()) {
                    child.forEach(out::add);
                } else {
                    out.add(child);
                }
                return;
            }
            node.fields().forEachRemaining(e -> findRows(e.getValue(), rowName, out));
        } else if (node.isArray()) {
            node.forEach(n -> findRows(n, rowName, out));
        }
    }

    private static Object nodeToValue(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }

    // ======================== 写 ========================

    /**
     * 写 XML 的 builder。
     * @param df DataFrame 要写出的数据帧,不允许 null
     * @param path String 输出 XML 文件路径,需为合法可写路径,不允许 null
     * @return XmlWriter 配置器,链式调用 .rootName/.rowName 后 .go() 执行
     */
    public static XmlWriter write(DataFrame df, String path) { return new XmlWriter(df, Path.of(path)); }

    public static final class XmlWriter {
        private final DataFrame df;
        private final Path path;
        private String rootName = "rows";
        private String rowName = "row";

        XmlWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        /**
         * 设置根元素名。
         * @param n String 根元素标签名,默认 "rows";含非法字符会自动清洗为合法 XML 名称
         * @return XmlWriter 当前配置器,便于链式调用
         */
        public XmlWriter rootName(String n) { this.rootName = n; return this; }

        /**
         * 设置行元素名。
         * @param n String 每行记录对应的元素标签名,默认 "row";含非法字符会自动清洗为合法 XML 名称
         * @return XmlWriter 当前配置器,便于链式调用
         */
        public XmlWriter rowName(String n) { this.rowName = n; return this; }

        /**
         * 执行写出。
         * @throws IOException 目标路径不可写或写出过程发生 IO 错误时抛出
         */
        public void go() throws IOException {
            String xml = render();
            Files.writeString(path, xml, StandardCharsets.UTF_8);
        }

        private String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append('<').append(escapeName(rootName)).append(">\n");
            List<String> cols = df.columnNames();
            for (Object[] row : df.iterRows()) {
                sb.append("  <").append(escapeName(rowName)).append(">\n");
                for (int c = 0; c < cols.size(); c++) {
                    Object v = row[c];
                    // 列名也转义(列名可能含 < & 等非法字符,防生成非法 XML)
                    sb.append("    <").append(escapeName(cols.get(c))).append('>');
                    if (v != null) sb.append(escape(String.valueOf(v)));
                    sb.append("</").append(escapeName(cols.get(c))).append(">\n");
                }
                sb.append("  </").append(escapeName(rowName)).append(">\n");
            }
            sb.append("</").append(escapeName(rootName)).append(">\n");
            return sb.toString();
        }
    }

    /** 文本内容转义:& < >(引号在元素文本中无需转义)。 */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 元素名清洗:XML 名称只允许字母/数字(含中文等 Unicode 字母)、. _ -;
     * 非法字符(如列名含 & < 空格)一律替换为 '_',保证生成合法 XML(转义在名称里无效,必须替换)。
     */
    private static String escapeName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-'
                    ? ch : '_');
        }
        if (sb.isEmpty() || !(Character.isLetter(sb.charAt(0)) || sb.charAt(0) == '_')) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
