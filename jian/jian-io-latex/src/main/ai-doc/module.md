# jian-io-latex

## 基本信息
- **library**: jian
- **entryClass**: jian.io.latex.LatexIo
- **deps**: jian-core;纯 JDK(自写,无第三方库)

## 摘要
LaTeX 表格写出,对齐 pandas.to_latex;输出 `\begin{tabular}{对齐}...\end{tabular}` 块,纯 JDK,仅写不读。

## 能力
- LatexIo.write(df, path):builder + `.caption/.label/.index/.booktabs` + `.go()`
- booktabs 风格(`\toprule/\midrule/\bottomrule`,默认开启)
- 可选 caption + label(figure/table 浮动体)
- 可选行索引列输出(对齐 pandas to_latex index)
- 与 jian-export.LatexRenderer 互补:本模块是 io 入口(对齐 pandas to_latex),LatexRenderer 含 Styler 集成

## 限制
- 仅写出,不提供读取(pandas to_latex 也无 read_latex)
- 列对齐方式按 dtype 简单决定(数值右对齐、其它左对齐),不支持逐列自定义对齐
- 不支持多列表格(longtable)、合并单元格、跨页

## 快速上手
```java
import jian.io.latex.LatexIo;

LatexIo.write(df, "out.tex")
    .caption("员工表")
    .label("tab:staff")
    .booktabs(true)
    .index(false)
    .go();
```
