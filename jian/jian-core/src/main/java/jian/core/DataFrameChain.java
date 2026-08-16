package jian.core;

// ┌─ What : DataFrameChain —— DataFrame 链式/列编辑入口的实现体(从主类拆出,§3.1 红线 600 行)
// │  Why  : DataFrame.java 非注释行超 §3.1 红线,拆分实现体;
// │         本类收 renameColumns/isetitem 实现(主类保留完整 javadoc + 单行委托,API 零变化),
// │         与既有 DataFrameSort/Missing/Stats/Index 等伴生类同模式(§3.1.1.1)。
// │  Who  : 由 DataFrame.renameColumns / isetitem 委托调用
// │  When : 列改名(renameColumns)/ 按行列下标设值(isetitem)
// │  Where: jian-core/DataFrameChain.java
// │  How  : 数据走向:df(原表)→ 校验(旧名存在/重名检查)→ Column.rename 逐列重建 → rebuild 新表。
// │         关键变量变化:mapping(旧名→新名)逐 key 校验存在性;out(重建列)按映射改名,未命中原样;
// │         names(新列名表)经 Set 判重,重复抛 IAE(防静默产生非法表)。
// │         isetitem:目标列 toObjectArray → 改单格 → 按原 dtype convertColumn 重建
// │         (类型不兼容退 OBJECT,catch 收窄 IAE/CCE)。
final class DataFrameChain {
    private DataFrameChain() {}

    /**
     * 批量改列名(实现体;入口见 {@code DataFrame.renameColumns})。
     * @param df DataFrame 原表,非 null
     * @param mapping Map 旧名→新名;非 null;旧名必须存在,产物不得重名
     * @return DataFrame 改名后的新表(行数据与 dtype 不变)
     */
    static DataFrame renameColumnsImpl(DataFrame df, java.util.Map<String, String> mapping) {
        java.util.Objects.requireNonNull(mapping, "mapping 不能为 null");
        java.util.List<String> cur = df.columnNames();
        for (String old : mapping.keySet()) {
            if (!cur.contains(old)) {
                throw new IllegalArgumentException("renameColumns 旧列名不存在:" + old + ",现有列:" + cur);
            }
        }
        java.util.List<Column> out = new java.util.ArrayList<>();
        for (Column c : df.columnsInternal()) {
            String newName = mapping.get(c.name());
            out.add(newName == null ? c : c.rename(newName));
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Column c : out) names.add(c.name());
        if (names.size() != new java.util.HashSet<>(names).size()) {
            throw new IllegalArgumentException("renameColumns 产生重复列名:" + names);
        }
        return df.rebuild(out, df.index());
    }

    /**
     * 按行列下标设值(实现体;入口见 {@code DataFrame.isetitem})。
     * @param df DataFrame 原表,非 null
     * @param rowIdx int 行下标
     * @param colIdx int 列下标
     * @param value Object 新值;可为 null
     * @return DataFrame 设值后的新表(目标列类型不兼容时该列退 OBJECT,不抛)
     */
    static DataFrame isetitemImpl(DataFrame df, int rowIdx, int colIdx, Object value) {
        java.util.List<Column> newCols = new java.util.ArrayList<>();
        java.util.List<Column> columns = df.columnsInternal();
        for (int c = 0; c < columns.size(); c++) {
            if (c == colIdx) {
                Column src = columns.get(c);
                Object[] arr = src.toObjectArray();
                arr[rowIdx] = value;
                // 用原 dtype 重建列;若类型不兼容则退化到 OBJECT。
                // catch 收窄为类型转换失败(IAE/CCE),
                // 不吞 OOM/NPE 等与 dtype 推断无关的异常(catch(Exception) 会静默吞掉)。
                try {
                    newCols.add(DataFrameConvert.convertColumn(new ObjectColumn(src.name(), arr), src.dtype()));
                } catch (IllegalArgumentException | ClassCastException e) {
                    newCols.add(new ObjectColumn(src.name(), arr));
                }
            } else {
                newCols.add(columns.get(c));
            }
        }
        return df.rebuild(newCols, df.index());   // rebuild 为包级(保 index/元信息)
    }
}
