# jian-io-xml

## 基本信息
- **library**: jian
- **entryClass**: jian.io.xml.Xml
- **deps**: jian-core;Jackson `XmlMapper`(jackson-dataformat-xml)

## 摘要
XML 读写,对齐 pandas.read_xml / to_xml;基于 Jackson XmlMapper,可配置 rootName/rowName,从 `<root><row>...</row></root>` 结构读写表格。

## 能力
- 读 Xml.read(path):builder + `.rowName(name)` + `.go()`;默认行元素名 "row"
- 读:XmlMapper readTree → 找 rowName 子元素 → 每个元素取列 → 推断类型 → DataFrame
- 写 Xml.write(df, path):builder + `.rootName(name).rowName(name)` + `.go()`;输出 `<root><row>col=val</row>...</root>`
- 默认 rootName="rows"、rowName="row",均可配置

## 限制
- 默认列作为子元素(attributeMode=M4 暂为子元素,不支持列作 XML 属性)
- 仅处理简单二维表语义的 XML;深层嵌套结构会按字符串原样保留,不自动展开
- 不支持 XML Schema 校验、命名空间精细控制、XSLT 转换

## 快速上手
```java
import jian.io.xml.Xml;

DataFrame df = Xml.read("data.xml").rowName("item").go();
Xml.write(df, "out.xml").rootName("rows").rowName("item").go();
```
