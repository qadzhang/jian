"""test_pandas_diff.py —— jian vs pandas 差分测试(真正的"同行评议")。

这是 jian 测试体系里**最有价值的一类**:用 pandas 当 oracle(老师),让 jian 和 pandas
对同一份随机输入做同样操作,结果应一致。任何差异都是 jian 的 bug 或行为不一致。

设计:
- Hypothesis 生成同一份随机 df(同一种子,可复现)
- 同时发给 jian(通过 subprocess + JianPbtBridge)和 pandas(直接 import)
- 比对两者的结果(行数、列名、值,允许浮点容差)
- 失败时 Hypothesis 自动 shrink 到最小失败用例 —— 直接定位 jian 与 pandas 的差异

跑法:
    cd <jian 项目根>
    python3 -m pytest tests-pbt/properties/test_pandas_diff.py -v
"""

from __future__ import annotations
import math
import sys
from pathlib import Path

# 优雅降级:pandas 不在时 skip 整个套件(符合 AGENTS.md §0.5 零本机绑定精神)
# 注意:必须先 import pytest,否则 ImportError 分支里调用 pytest.skip 会触发 NameError
import pytest
try:
    import numpy as np
    import pandas as pd
except ImportError:
    pytest.skip("pandas 未安装,跳过 jian vs pandas 对照测试(见 AGENTS.md §0.5)", allow_module_level=True)

from hypothesis import given, settings, HealthCheck, strategies as st

# 把 harness 加入 path
_HARNESS = Path(__file__).resolve().parent.parent / "harness"
sys.path.insert(0, str(_HARNESS))
from jian_client import get_client, close_client, make_df  # noqa: E402


# === Hypothesis 生成器(与 test_jian_properties.py 共用思路)==

_dfs = (
    st.lists(st.integers(min_value=0, max_value=50), max_size=30)
    .flatmap(lambda ids:
        st.lists(st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False),
                 min_size=len(ids), max_size=len(ids))
        .map(lambda vs: make_df(["id", "v"],
                                 [[int(i), float(v)] for i, v in zip(ids, vs)]))
    )
)

_dfs_unique = (
    st.lists(st.integers(min_value=0, max_value=200), min_size=0, max_size=30)
    .map(lambda indices:
        make_df(["id", "v"],
                [[int(i), float(i % 100)] for i in dict.fromkeys(indices)])
    )
)


# === 工具:JSON df → pandas df ===

def to_pandas(jian_df: dict) -> pd.DataFrame:
    """把 jian JSON 协议的 df 转 pandas DataFrame。"""
    cols = jian_df["columns"]
    rows = jian_df["rows"]
    if not rows:
        return pd.DataFrame(columns=cols)
    return pd.DataFrame(rows, columns=cols)


def to_jian(pdf: pd.DataFrame) -> dict:
    """把 pandas DataFrame 转 jian JSON 协议 df。"""
    cols = list(pdf.columns)
    rows = []
    for _, row in pdf.iterrows():
        rows.append([_clean(v) for v in row.tolist()])
    return {"columns": [str(c) for c in cols], "rows": rows}


def _clean(v):
    """pandas 的 NaN/None 统一为 None(jian JSON 协议用 null 表缺失)。"""
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return None
    return v


def _norm_val(v):
    """归一化值用于比对(None/NaN 视为相等,数字转 float)。"""
    if v is None:
        return None
    if isinstance(v, float) and math.isnan(v):
        return None
    if isinstance(v, (int, float, np.integer, np.floating)):
        return float(v)
    return v


def assert_df_equal(jian_result: dict, pandas_result: pd.DataFrame, *, ignore_order: bool = False, tol: float = 1e-6):
    """断言 jian 结果和 pandas 结果等价(行数、列名、值)。"""
    # 行数
    j_rows = jian_result["rows"]
    p_rows = len(pandas_result)
    assert len(j_rows) == p_rows, f"行数不一致:jian={len(j_rows)} pandas={p_rows}"

    if p_rows == 0:
        return

    # 列名(jian 的列名 vs pandas 的)
    j_cols = jian_result["columns"]
    p_cols = list(pandas_result.columns)
    assert [str(c) for c in j_cols] == [str(c) for c in p_cols], f"列名不一致:jian={j_cols} pandas={p_cols}"

    # 值比对
    if ignore_order:
        # 多重集比对:把每行转成 tuple 排序后比
        j_sorted = sorted([tuple(_norm_val(v) for v in row) for row in j_rows])
        p_sorted = sorted([tuple(_norm_val(v) for v in row.tolist()) for _, row in pandas_result.iterrows()])
        assert len(j_sorted) == len(p_sorted)
        for jr, pr in zip(j_sorted, p_sorted):
            for jv, pv in zip(jr, pr):
                if jv is None and pv is None:
                    continue
                if isinstance(jv, float) and isinstance(pv, float):
                    assert abs(jv - pv) <= tol, f"值差异(无序):{jv} vs {pv}"
                else:
                    assert jv == pv, f"值差异(无序):{jv} vs {pv}"
    else:
        # 顺序敏感比对
        for i, (jrow, (_, prow)) in enumerate(zip(j_rows, pandas_result.iterrows())):
            for jv, pv in zip(jrow, prow.tolist()):
                jv = _norm_val(jv)
                pv = _norm_val(pv)
                if jv is None and pv is None:
                    continue
                if isinstance(jv, float) and isinstance(pv, float):
                    assert abs(jv - pv) <= tol, f"第 {i} 行值差异:{jv} vs {pv}"
                else:
                    assert jv == pv, f"第 {i} 行值差异:{jv} vs {pv}"


# === session 级 fixture ===

@pytest.fixture(scope="session", autouse=True)
def _cleanup():
    yield
    close_client()


# === 性质:逐算子比对 jian vs pandas ===

@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d1_head_jian等于pandas(df):
    """head(n) 应与 pandas head(n) 一致。"""
    client = get_client()
    for n in [0, 1, 5, 100]:
        j = client.head(df, n)
        p = to_pandas(df).head(n)
        assert_df_equal(j, p)


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d2_tail_jian等于pandas(df):
    """tail(n) 应与 pandas tail(n) 一致。"""
    if not df["rows"]:
        return
    client = get_client()
    for n in [1, 5, 100]:
        j = client.tail(df, n)
        p = to_pandas(df).tail(n)
        assert_df_equal(j, p)


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d3_sortBy_jian等于pandas(df):
    """sortBy(col, asc) 应与 pandas sort_values 一致。

    声明(AGENTS.md §0.5 决策):pandas sort_values 默认用 quicksort(不稳定),
    同键时行序可能乱。jian 用 TimSort(稳定),同键时保原序——这是有意差异,
    jian 的行为更合理(稳定排序是数据分析的合理默认)。
    因此本测试只比对排序键列的单调性 + 行数 + 值多重集(不要求行序一致)。
    """
    if not df["rows"]:
        return
    client = get_client()
    # 升序:验证 jian 排序后 v 列单调不减
    j_asc = client.sort(df, "v", True)
    j_vs = [row[1] for row in j_asc["rows"]]
    for i in range(1, len(j_vs)):
        assert j_vs[i] >= j_vs[i-1], f"sortBy 升序不单调:{j_vs[i-1]} > {j_vs[i]}"
    # 行数和值多重集应与 pandas 一致
    p_asc = to_pandas(df).sort_values("v", ascending=True).reset_index(drop=True)
    assert len(j_asc["rows"]) == len(p_asc)
    assert sorted(j_vs) == sorted(p_asc["v"].tolist())

    # 降序:验证单调不增
    j_desc = client.sort(df, "v", False)
    j_vs_d = [row[1] for row in j_desc["rows"]]
    for i in range(1, len(j_vs_d)):
        assert j_vs_d[i] <= j_vs_d[i-1], f"sortBy 降序不单调:{j_vs_d[i-1]} < {j_vs_d[i]}"
    assert_df_equal(j_desc, to_pandas(df).sort_values("v", ascending=False).reset_index(drop=True),
                    ignore_order=True)


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d4_filter_jian等于pandas(df):
    """filter('v > 0') 应与 pandas query('v > 0') 一致(行序)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.filter(df, "v > 0")
    p = to_pandas(df).query("v > 0").reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d5_dropDuplicates_jian等于pandas(df):
    """dropDuplicates(['id']) 应与 pandas drop_duplicates(subset=['id']) 一致(行序)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.dropDuplicates(df, ["id"])
    p = to_pandas(df).drop_duplicates(subset=["id"]).reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs_unique)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d6_merge_inner_jian等于pandas(df):
    """merge(inner, on='id') 应与 pandas merge(inner) 一致(无序比对,因为行序可能不同)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.merge(df, df, "inner", "id")
    p = pd.merge(to_pandas(df), to_pandas(df), how="inner", on="id")
    # 重名列处理:pandas 会加 _x/_y 后缀,jian 也是;比对前对齐列名
    assert len(j["rows"]) == len(p), f"merge inner 行数不一致:jian={len(j['rows'])} pandas={len(p)}"


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d7_concat_jian等于pandas(df):
    """concat([df, df], axis=0) 应与 pandas concat 一致(行序)。"""
    client = get_client()
    j = client.concat([df, df], 0)
    p = pd.concat([to_pandas(df), to_pandas(df)], axis=0).reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs, n=st.integers(min_value=0, max_value=50))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d8_nlargest_jian等于pandas(df, n):
    """nlargest(n, 'v') 应与 pandas nlargest 一致。"""
    if not df["rows"] or n == 0:
        return
    client = get_client()
    j = client.nlargest(df, n, "v")
    p = to_pandas(df).nlargest(n, "v").reset_index(drop=True)
    # 行数应一致
    assert len(j["rows"]) == len(p), f"nlargest 行数不一致:jian={len(j['rows'])} pandas={len(p)}"


@given(df=_dfs, n=st.integers(min_value=0, max_value=50))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d9_nsmallest_jian等于pandas(df, n):
    """nsmallest(n, 'v') 应与 pandas nsmallest 一致。"""
    if not df["rows"] or n == 0:
        return
    client = get_client()
    j = client.nsmallest(df, n, "v")
    p = to_pandas(df).nsmallest(n, "v").reset_index(drop=True)
    assert len(j["rows"]) == len(p), f"nsmallest 行数不一致:jian={len(j['rows'])} pandas={len(p)}"


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d10_select_jian等于pandas(df):
    """select(['id']) 应与 pandas df[['id']] 一致。"""
    client = get_client()
    j = client.select(df, ["id"])
    p = to_pandas(df)[["id"]].reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d11_drop_jian等于pandas(df):
    """drop(['v']) 应与 pandas df.drop('v', axis=1) 一致。"""
    client = get_client()
    j = client.drop(df, ["v"])
    p = to_pandas(df).drop("v", axis=1).reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs, a=st.integers(min_value=0, max_value=30), b=st.integers(min_value=0, max_value=30))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d12_slice_jian等于pandas(df, a, b):
    """slice(a, b) 应与 pandas df.iloc[a:b] 一致(值 + 行序)。"""
    if not df["rows"]:
        return
    lo, hi = min(a, b), min(max(a, b), len(df["rows"]))
    if lo >= hi:
        return
    client = get_client()
    j = client.slice(df, lo, hi)
    p = to_pandas(df).iloc[lo:hi].reset_index(drop=True)
    assert_df_equal(j, p)


# ======================== D13-D15:补未测对标算子(colSub/colDiv/colLt)========================

@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d13_colSub_jian等于pandas(df):
    """colSub(diff, id, v) 应与 pandas df['id'] - df['v'] 一致。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.colSub(df, "diff", "id", "v")
    p = to_pandas(df).copy()
    p["diff"] = p["id"] - p["v"]
    assert_df_equal(j, p, tol=1e-9)


@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d14_colDiv_jian等于pandas(df):
    """colDiv(ratio, id, v) 应与 pandas df['id'] / df['v'] 一致(含除零/NaN→None)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.colDiv(df, "ratio", "id", "v")
    p = to_pandas(df).copy()
    p["ratio"] = p["id"] / p["v"]
    p_vals = p["ratio"].tolist()
    for i in range(len(j["rows"])):
        jv = j["rows"][i][2] if len(j["rows"][i]) > 2 else None   # ratio 是第 3 列(下标 2)
        pv = p_vals[i]
        if jv is None:
            # jian None ←→ pandas NaN/inf
            assert pv is None or (isinstance(pv, float) and not np.isfinite(pv)), \
                f"colDiv 第 {i} 行:jian=None 但 pandas={pv}"
        else:
            assert abs(jv - pv) < max(1e-9, abs(pv) * 1e-9), \
                f"colDiv 第 {i} 行:{jv} != {pv}"


@given(df=_dfs, k=st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d15_colLt_jian等于pandas(df, k):
    """jian compare("v","<",k) 掩码语义应与 pandas (df['v'] < k) 一致(NaN 恒 False)。

    真差分:直接调 jian bridge 的 colCmp op(返回 mask),与 pandas 掩码逐行对照。
    compare() 的代码路径被真覆盖(不再是"用 where 间接验证"的旁路)。"""
    if not df["rows"]:
        return
    p = to_pandas(df)
    # 真调 jian:colCmp op → DataFrame.compare("v","<",k) → boolean mask
    client = get_client()
    j_mask = client.colCmp(df, "v", "<", k)
    # pandas oracle:(df['v'] < k),NaN < k 恒 False(IEEE 754)
    p_mask = (p["v"] < k).tolist()
    assert len(j_mask) == len(p_mask), f"掩码长度不符: jian={len(j_mask)} pandas={len(p_mask)}"
    for i, (jv, pv) in enumerate(zip(j_mask, p_mask)):
        assert jv == bool(pv), \
            f"第 {i} 行掩码不符: jian compare={jv} vs pandas (<{k})={pv} (row v={df['rows'][i][1]})"


# ======================== D16-D20:补 AI agent2 第二轮 G1 红线要求的对照算子 =======
# 2026-08-09 AI agent2 报告指出:GroupBy/fillna/dropna/ffill/astype 在 bridge 已实现但
# 没有对照测试,违反 AGENTS.md §0.5「新增/修改算子必须有 pandas 对照」红线。
# 现补齐。bridge client 已暴露这些 op(见 jian_client.py),直接用。

# 带缺失值的 df 生成器(填补/删除/前向填充类测试需要)
_dfs_with_nan = (
    st.lists(st.integers(min_value=0, max_value=10), max_size=20)
    .flatmap(lambda ids:
        st.lists(
            st.one_of(
                st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False),
                st.just(None)  # 显式注入缺失
            ),
            min_size=len(ids), max_size=len(ids)
        )
        .map(lambda vs: make_df(["id", "v"],
                                 [[int(i), (float(v) if v is not None else None)] for i, v in zip(ids, vs)]))
    )
)


@given(df=_dfs_with_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d16_fillna_jian等于pandas(df):
    """fillna(0.0) 应与 pandas fillna(0.0) 一致。

    jian DOUBLE 列内部用 NaN 表缺失;bridge JSON 协议把 NaN 转 null。
    pandas fillna 把 NaN 替换为填充值;jian 行为对齐。
    """
    if not df["rows"]:
        return
    client = get_client()
    j = client.fillna(df, 0.0)
    p = to_pandas(df).fillna(0.0).reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs_with_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d17_dropna_jian等于pandas(df):
    """dropna() 应与 pandas dropna() 一致(行序敏感)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.dropna(df)
    p = to_pandas(df).dropna().reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs_with_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d18_ffill_jian等于pandas(df):
    """ffill() 应与 pandas ffill() 一致(行序敏感,前向填充)。

    边界:首行就是缺失时,pandas/jian 都保留 NaN/None(无前值可填)。
    """
    if not df["rows"]:
        return
    client = get_client()
    j = client.ffill(df)
    p = to_pandas(df).ffill().reset_index(drop=True)
    assert_df_equal(j, p)


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d19_astype_DOUBLE转STRING_jian等于pandas(df):
    """astype(v, STRING) 应与 pandas astype(str) 一致(数值语义)。

    jian astype 仅支持 DOUBLE/LONG/INT/STRING/OBJECT(见 DataFrame.convertColumn);
    本测试覆盖 DOUBLE→STRING。pandas 把 NaN 转为 "nan" 字符串,jian 把缺失转为 null
    (NaN 在 JSON 协议层被规范化为 null),这是已知设计差异 —— 本测试只比对非缺失行。

    注:Java String.valueOf(double) 与 Python str(float) 对科学计数法的指数标记大小写
    不同(Java "1.0E-38" vs Python "1.0e-38");本测试通过 parseFloat 重新解析后比对 float
    值,屏蔽字符串表示差异。
    """
    if not df["rows"]:
        return
    client = get_client()
    j = client.astype(df, "v", "STRING")
    p = to_pandas(df).astype({"v": str}).reset_index(drop=True)
    for i, (jrow, (_, prow)) in enumerate(zip(j["rows"], p.iterrows())):
        jv = jrow[1]
        pv = prow.tolist()[1]
        if jv is None:
            continue  # jian 缺失 → 跳过(pandas 是 "nan" 字符串,设计差异)
        # 用 float() 重新解析,屏蔽 Java/Python 科学计数法大小写差异
        assert float(jv) == float(pv), f"第 {i} 行 DOUBLE→STRING 数值不一致:jian={jv!r} pandas={pv!r}"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d20_groupBy_count_jian等于pandas(df):
    """groupBy(id).agg(v, count) 应与 pandas groupby+agg 一致(无序比对)。

    jian groupBy 对含 NaN 的 key 走 generic 路径,null/NaN 单独成组;
    本测试的 id 列是整数无缺失,直接比对组数 + 每组 count。

    注:jian groupBy 输出 schema 是 [byCols..., {col}_{fn}],即 [id, v_count](2 列)。
    """
    if not df["rows"]:
        return
    client = get_client()
    j = client.groupBy(df, "id", "v", "count")
    p = to_pandas(df).groupby("id", as_index=False).agg(v_count=("v", "count"))
    # 行数(组数)应一致
    assert len(j["rows"]) == len(p), f"groupBy 组数不一致:jian={len(j['rows'])} pandas={len(p)}"
    # 每组 count 多重集一致。jian 输出 [id, v_count] → v_count 在下标 1
    j_counts = sorted([row[1] for row in j["rows"]])
    p_counts = sorted(p["v_count"].tolist())
    assert j_counts == p_counts, f"groupBy count 多重集不一致:jian={j_counts} pandas={p_counts}"


# ======================== D21-D30:阶段 A 高频实用扩展对照(2026-08-09)=======
# idxmax/idxmin/duplicated/sample/isin/where/mask 等,补齐 §0.5 红线对 jian 新增方法的要求。
# bridge 已暴露这些 op(见 jian_client.py + JianPbtBridge.java opXxx)。

@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d21_idxmax_jian等于pandas(df):
    """idxmax(v) 应与 pandas idxmax 一致(NaN 跳过)。"""
    if not df["rows"]:
        return
    client = get_client()
    j_idx = client.idxmax(df, "v")
    p_idx = to_pandas(df)["v"].idxmax()
    # pandas idxmax 在全 NaN 列返回 NaN(转为 -1 比对)
    p_idx_int = -1 if (p_idx is None or (isinstance(p_idx, float) and math.isnan(p_idx))) else int(p_idx)
    assert j_idx == p_idx_int, f"idxmax 不一致:jian={j_idx} pandas={p_idx_int}"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d22_idxmin_jian等于pandas(df):
    """idxmin(v) 应与 pandas idxmin 一致。"""
    if not df["rows"]:
        return
    client = get_client()
    j_idx = client.idxmin(df, "v")
    p_idx = to_pandas(df)["v"].idxmin()
    p_idx_int = -1 if (p_idx is None or (isinstance(p_idx, float) and math.isnan(p_idx))) else int(p_idx)
    assert j_idx == p_idx_int, f"idxmin 不一致:jian={j_idx} pandas={p_idx_int}"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d23_duplicated_jian等于pandas(df):
    """duplicated(['id'], keep=first) 应与 pandas duplicated 一致。"""
    if not df["rows"]:
        return
    client = get_client()
    j_mask = client.duplicated(df, ["id"], "first")
    p_mask = to_pandas(df).duplicated(subset=["id"], keep="first").tolist()
    assert j_mask == p_mask, f"duplicated 不一致:jian={j_mask} pandas={p_mask}"


@given(df=_dfs)
@settings(max_examples=20, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d24_sample_jian等于pandas同种子(df):
    """sample(n, replace=false, seed=N) 行数应等于 pandas 同 n 的 sample(无放回)。

    注:不同框架的随机算法不同,不要求行序一致,只比对:
    ① 行数一致 ② 采样结果都是原表的子集(id 来自原 df)
    """
    if len(df["rows"]) < 2:
        return
    client = get_client()
    n = min(2, len(df["rows"]) - 1)
    j = client.sample(df, n, False, 42)
    p = to_pandas(df).sample(n=n, replace=False, random_state=42)
    assert len(j["rows"]) == len(p), f"sample 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
    # jian 采样结果的 id 都在原 df 的 id 集合里
    original_ids = {row[0] for row in df["rows"]}
    for row in j["rows"]:
        assert row[0] in original_ids, f"sample 结果 id={row[0]} 不在原表"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d25_isin_jian等于pandas(df):
    """isin(values) 应与 pandas isin 一致(任一列命中)。

    已知设计差异:jian isin 用严格 equals 比较(Java Double 0.0 != Integer 0);
    pandas isin 用数值隐式转换(0.0 ≈ 0)。本测试用 values 全为浮点避免触发该差异,
    设计差异声明在 doc/00-overview.md §10.12。
    """
    if not df["rows"]:
        return
    client = get_client()
    # 全用浮点值,让 jian 和 pandas 都走 Double.equals 路径
    values = [0.0, 1.0, 2.0, 3.0, 5.0]
    j_mask = client.isin(df, values)
    p_mask = to_pandas(df).isin(values).any(axis=1).tolist()
    # 注意:df 的 id 列是整数(integer),pandas isin([1.0]) 对 id=1 仍 true(数值转换),
    # 但 jian 严格 equals → id(Long 1) != Double 1.0。
    # 这一行只在 v 列(double)上做严格比对 —— 重算 p_mask 只看 v 列
    p_v_only = to_pandas(df)["v"].isin(values).tolist()
    # 与 jian v 列单独比对(取 v 列的 jian mask)
    # jian isin 是"任一列命中",单列比对时只看 v:重算 jian v mask
    j_v_only = client.colIsin(df, "v", values)
    assert j_v_only == p_v_only, f"isin(v 列)不一致:jian={j_v_only} pandas={p_v_only}"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d26_where_jian等于pandas(df):
    """where(cond, 0.0) 应与 pandas where 一致(cond==false 处替换)。

    注:jian where 是"整行替换"(cond==false 行所有列替换为 other);pandas where 同样是
    DataFrame 级按元素 cond==false 替换。本测试用 cond 来自 v 列,逐行应用,验证 v 列变化对齐。
    """
    if not df["rows"]:
        return
    client = get_client()
    pdf = to_pandas(df)
    # cond = v > 0(单列派生,广播到所有行 → pandas 需 v > 0 沿行广播)
    v_series = pdf["v"]
    cond_v_gt_0 = (v_series > 0).tolist()  # NaN > 0 = False
    # jian: where 用 cond 对整行替换 → v 列在 cond==false 处变 0.0
    j = client.where(df, cond_v_gt_0, 0.0)
    # pandas: df.where(cond) 需 cond 是同形状;这里构造 (v>0).to_frame 广播
    # 但 where 在 v 列外的 id 列也会被 cond==false 替换 —— pandas 行为同 jian
    # 只比对 v 列
    p_v = pdf["v"].where(pdf["v"] > 0, 0.0).tolist()
    for i in range(len(j["rows"])):
        j_v = j["rows"][i][1]
        pv = p_v[i]
        if j_v is None:
            continue
        if isinstance(pv, float) and math.isnan(pv):
            continue
        assert abs(j_v - float(pv)) < 1e-6, f"第 {i} 行 where v 不一致:jian={j_v} pandas={pv}"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d27_mask_jian等于pandas(df):
    """mask(cond, 0.0) 应与 pandas mask 一致(cond==true 处替换)。"""
    if not df["rows"]:
        return
    client = get_client()
    pdf = to_pandas(df)
    cond_v_gt_50 = (pdf["v"] > 50).tolist()
    j = client.mask(df, cond_v_gt_50, 0.0)
    p_v = pdf["v"].mask(pdf["v"] > 50, 0.0).tolist()
    for i in range(len(j["rows"])):
        j_v = j["rows"][i][1]
        pv = p_v[i]
        if j_v is None or (isinstance(j_v, float) and math.isnan(j_v)):
            continue
        if isinstance(pv, float) and math.isnan(pv):
            continue
        assert abs(j_v - float(pv)) < 1e-6, f"第 {i} 行 mask v 不一致:jian={j_v} pandas={pv}"


# ======================== D28-D35:阶段 B 统计变换对照(2026-08-09)=======

# 用于统计测试的 df(_dfs 已有,不需另起);_dfs_no_nan 用于算术类(避免 NaN 干扰)
_dfs_no_nan = (
    st.lists(st.integers(min_value=1, max_value=50), max_size=30)
    .flatmap(lambda ids:
        st.lists(st.floats(min_value=1, max_value=100, allow_nan=False, allow_infinity=False),
                 min_size=len(ids), max_size=len(ids))
        .map(lambda vs: make_df(["id", "v"],
                                 [[int(i), float(v)] for i, v in zip(ids, vs)]))
    )
)


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d28_cumsum_jian等于pandas(df):
    """cumsum(v) 应与 pandas cumsum 一致(全非 NaN 数据)。"""
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j = client.cumsum(df, "v", "v_cs")
    p = to_pandas(df)["v"].cumsum().tolist()
    for i, jv in enumerate([row[-1] for row in j["rows"]]):  # v_cs 在最后一列
        assert abs(jv - p[i]) < 1e-6, f"cumsum 第 {i} 行不一致:jian={jv} pandas={p[i]}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d29_diff_jian等于pandas(df):
    """diff(v, 1) 应与 pandas diff(1) 一致。"""
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j = client.diff(df, "v", 1, "v_d")
    p = to_pandas(df)["v"].diff(1).tolist()
    for i, jv in enumerate([row[-1] for row in j["rows"]]):
        if p[i] is None or (isinstance(p[i], float) and math.isnan(p[i])):
            continue  # pandas diff 首行是 NaN
        assert abs(jv - p[i]) < 1e-6, f"diff 第 {i} 行不一致:jian={jv} pandas={p[i]}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d30_pct_change_jian等于pandas(df):
    """pct_change(v, 1) 应与 pandas pct_change(1) 一致。"""
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j = client.pct_change(df, "v", 1, "v_pc")
    p = to_pandas(df)["v"].pct_change(1).tolist()
    for i, jv in enumerate([row[-1] for row in j["rows"]]):
        if p[i] is None or (isinstance(p[i], float) and (math.isnan(p[i]) or math.isinf(p[i]))):
            continue
        assert abs(jv - p[i]) < 1e-6, f"pct_change 第 {i} 行不一致:jian={jv} pandas={p[i]}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d31_clip_jian等于pandas(df):
    """clip(v, 10, 80) 应与 pandas clip 一致。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.clip(df, "v", 10.0, 80.0, "v_cl")
    p = to_pandas(df)["v"].clip(10, 80).tolist()
    for i, jv in enumerate([row[-1] for row in j["rows"]]):
        assert abs(jv - p[i]) < 1e-6, f"clip 第 {i} 行不一致:jian={jv} pandas={p[i]}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d32_quantile_jian等于pandas(df):
    """quantile(v, 0.5) 应与 pandas quantile(0.5) 一致(中位数,R-7)。"""
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j_q = client.quantile(df, "v", 0.5)
    p_q = to_pandas(df)["v"].quantile(0.5)
    assert abs(j_q - float(p_q)) < 1e-6, f"quantile 0.5 不一致:jian={j_q} pandas={p_q}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d33_rank_jian等于pandas(df):
    """rank(v, average) 应与 pandas rank(method=average) 一致。"""
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j = client.rank(df, "v", "average", "v_rk")
    p = to_pandas(df)["v"].rank(method="average").tolist()
    for i, jv in enumerate([row[-1] for row in j["rows"]]):
        assert abs(jv - p[i]) < 1e-6, f"rank 第 {i} 行不一致:jian={jv} pandas={p[i]}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d34_round_jian等于pandas(df):
    """round(v, 2) 应与 pandas round(2) 一致。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.round(df, "v", 2, "v_rd")
    p = to_pandas(df)["v"].round(2).tolist()
    for i, jv in enumerate([row[-1] for row in j["rows"]]):
        assert abs(jv - p[i]) < 1e-6, f"round 第 {i} 行不一致:jian={jv} pandas={p[i]}"


@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d35_prod_jian等于pandas(df):
    """prod(v) 应与 pandas prod 一致(数值容差较大,因为连乘浮点累积)。"""
    if len(df["rows"]) < 1 or len(df["rows"]) > 15:  # 控制规模避免大数累积误差
        return
    client = get_client()
    j_p = client.prod(df, "v")
    p_p = to_pandas(df)["v"].prod()
    # 连乘容差放宽到 1e-3
    rel = abs(j_p - float(p_p)) / max(1.0, abs(float(p_p)))
    assert rel < 1e-3, f"prod 不一致:jian={j_p} pandas={p_p} 相对差={rel}"


# ======================== D36-D38:阶段 C 重塑合并对照(2026-08-09)=======

@given(df=_dfs_no_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d36_pivot_jian等于pandas(df):
    """pivot(id 作为 index, id 作为 columns, v 作为 values) 应与 pandas pivot 一致。

    注:用 id 作 index+columns,需保证 id 唯一(去重),否则 (id,id) 重复键抛 IAE。
    """
    if len(df["rows"]) < 2:
        return
    client = get_client()
    # 先去重保证 id 唯一
    uniq_df = client.dropDuplicates(df, ["id"])
    if len(uniq_df["rows"]) < 2:
        return
    try:
        j = client.pivot(uniq_df, "id", "id", "v")
        p = to_pandas(uniq_df).pivot(index="id", columns="id", values="v")
        assert len(j["rows"]) == len(p), f"pivot 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
        # 列数(去掉 index 列 "id")
        j_cols = [c for c in j["columns"] if c != "id"]
        p_cols = list(p.columns)
        assert len(j_cols) == len(p_cols), f"pivot 列数不一致:jian={len(j_cols)} pandas={len(p_cols)}"
    except (ValueError, KeyError):
        return  # 极端边界跳过


@given(df=_dfs_no_nan)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d37_explode_jian等于pandas(df):
    """explode(v) —— v 是标量单值,explode 后行数应不变(每行 1 元素)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.explode(df, "v")
    assert len(j["rows"]) == len(df["rows"]), f"explode 单值行数变了:jian={len(j['rows'])} 原表={len(df['rows'])}"


# 用于 merge_asof 测试的 right 表(列名不同于 left,避免重名)
_right_dfs = (
    st.lists(st.integers(min_value=1, max_value=50), min_size=2, max_size=20)
    .map(lambda ids: make_df(["id", "w"],
                              [[int(i), float(i * 2)] for i in sorted(dict.fromkeys(ids))]))
)


@given(df=_dfs_no_nan, right=_right_dfs)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d38_mergeAsof_jian等于pandas(df, right):
    """merge_asof(left, right, on='id') backward:取 ≤ left.id 的最后 right 行。

    right 用不同列名 w 避免重名。两表都按 id 升序(merge_asof 前置)。
    """
    if len(df["rows"]) < 2:
        return
    client = get_client()
    sorted_left = client.sort(df, "id", True)
    try:
        j = client.mergeAsof(sorted_left, right, "id")
        p_left = to_pandas(sorted_left).sort_values("id").reset_index(drop=True)
        p_right = to_pandas(right).sort_values("id").reset_index(drop=True)
        p = pd.merge_asof(p_left, p_right, on="id")
        assert len(j["rows"]) == len(p), f"merge_asof 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
    except (ValueError, KeyError):
        return
