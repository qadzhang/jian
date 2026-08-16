"""test_pandas_diff.py —— jian vs pandas 差分测试(真正的"同行评议")。

这是 jian 测试体系里**最有价值的一类**:用 pandas 当 oracle(老师),让 jian 和 pandas
对同一份随机输入做同样操作,结果应一致。任何差异都是 jian 的 bug 或行为不一致。

设计:
- Hypothesis 生成同一份随机 df(同一种子,可复现)
- 同时发给 jian(通过 JPype 直调 JianJpypeBridge)和 pandas(直接 import)
- 比对两者的结果(行数、列名、值,允许浮点容差)
- 失败时 Hypothesis 自动 shrink 到最小失败用例 —— 直接定位 jian 与 pandas 的差异

跑法:
    cd <jian 项目根>
    python3 -m pytest tests-pbt/properties/test_pandas_diff.py -v
"""

from __future__ import annotations
import io
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
# jian-core jar / 桥未构建时 skip 整套件(优雅降级)
# 因为 skip 守卫只在 import 期触发,若存在性检查推迟到 _ensure_jvm()(测试体内)
# 才执行,import 期 except FileNotFoundError 永不触发;所以显式调 ensure_built() 让 skip 生效
from jian_client import get_client, close_client, make_df, ensure_built  # noqa: E402
try:
    ensure_built()
except FileNotFoundError as _e:
    pytest.skip(f"jian 未构建,跳过 pandas 对照测试({_e})", allow_module_level=True)


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
    """把 jian df 字典结构({"columns","rows"})转 pandas DataFrame。"""
    cols = jian_df["columns"]
    rows = jian_df["rows"]
    if not rows:
        return pd.DataFrame(columns=cols)
    return pd.DataFrame(rows, columns=cols)


def _clean(v):
    """pandas 的 NaN/None 统一为 None(jian 客户端 _to_py 用 None 表缺失)。"""
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


def _dtype_class(jian_dtype: str) -> str:
    """jian dtype 名 → 等价类 num/text/bool/datetime(与 pandas dtype.kind 对照)。

    等价类而非精确名比对:int64↔float64 视为同类(pandas 含 NaN 的 int 列会变 float64,
    值层面已逐值比对);跨类(如 jian OBJECT ↔ pandas int64)即失败 —— 抓"值对但类型降级"。
    """
    if jian_dtype in ("LONG", "INT", "DOUBLE"):
        return "num"
    if jian_dtype == "BOOL":
        return "bool"
    if jian_dtype in ("DATE", "DATETIME"):
        return "datetime"
    return "text"   # STRING / OBJECT / CATEGORY


def _pd_kind_class(kind: str) -> str:
    """pandas dtype.kind → 同款等价类(i/u/f→num、b→bool、M→datetime、O/S/U→text)。"""
    if kind in "iuf":
        return "num"
    if kind == "b":
        return "bool"
    if kind == "M":
        return "datetime"
    return "text"


def assert_df_equal(jian_result: dict, pandas_result: pd.DataFrame, *, ignore_order: bool = False, tol: float = 1e-6):
    """断言 jian 结果和 pandas 结果等价(行数、列名、dtype 等价类、值)。

    含 dtype 等价类比对(桥 dfToMap 回传 dtypes;旧桥无该键时跳过),
    抓"值对但类型降级"(如 ffill/merge 后 LONG→OBJECT)的静默漏检;
    空结果(0 行)也不提前返回 —— 列名与 dtype 照比(抓 0 行丢列类 bug)。
    """
    # 行数
    j_rows = jian_result["rows"]
    p_rows = len(pandas_result)
    assert len(j_rows) == p_rows, f"行数不一致:jian={len(j_rows)} pandas={p_rows}"

    # 列名(jian 的列名 vs pandas 的;0 行也必须比 —— 空结果丢列是真实 bug 形态)
    j_cols = jian_result["columns"]
    p_cols = list(pandas_result.columns)
    assert [str(c) for c in j_cols] == [str(c) for c in p_cols], f"列名不一致:jian={j_cols} pandas={p_cols}"

    # dtype 等价类比对(逐列;jian↔pandas 跨类即失败)。
    # 0 行时跳过:to_pandas 对空表构造出的 object dtype 是构造伪差异(无数据无从判类型),
    # jian 侧空结果列 dtype 反映源 dtype(元数据),两者不可比。
    j_dtypes = jian_result.get("dtypes")
    if j_dtypes and p_rows > 0:
        for idx, col in enumerate(p_cols):
            if pandas_result.iloc[:, idx].notna().sum() == 0:
                continue   # 全空列无观测值,dtype 是构造伪差异(pandas 给 object),无从判型
            j_name = str(j_dtypes.get(str(col), ""))
            jc = _dtype_class(j_name)
            p_dtype = pandas_result.iloc[:, idx].dtype
            pc = _pd_kind_class(p_dtype.kind)
            assert jc == pc, (
                f"列 {col} dtype 类不一致:jian={j_name}(→{jc}) pandas={p_dtype}(→{pc})"
                f"(值相同但类型降级/漂移,见 doc/00-overview.md §10.16 已声明差异除外)"
            )

    if p_rows == 0:
        return

    # 值比对
    if ignore_order:
        # 多重集比对:把每行转成 tuple 排序后比。
        # 因为行含 None 时 tuple 直排抛 TypeError(None<float),
        # 所以用 (是否None, 类型名, 值) 三元组作 sort key —— None 被首元素挡住,类型名防 float/str 互比
        def _row_key(t):
            return tuple((v is None, "" if v is None else type(v).__name__, v) for v in t)
        j_sorted = sorted([tuple(_norm_val(v) for v in row) for row in j_rows], key=_row_key)
        p_sorted = sorted([tuple(_norm_val(v) for v in row.tolist()) for _, row in pandas_result.iterrows()], key=_row_key)
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
    """merge(inner, on='id') 应与 pandas merge(inner) 值一致(无序比对)。

    断言为行多重集全比对(只比行数的话,唯一 id 自连接行数恒 n,几乎必然通过)。
    列名两边一致(设计决策"以 pandas 为准":重名列两边都加后缀,
    jian 自连接输出 [id, v_x, v_y] == pandas [id, v_x, v_y]):
    jian (id, v_x, v_y) ↔ pandas 同名列,自连接下 v_x==v_y==v。
    """
    if not df["rows"]:
        return
    client = get_client()
    j = client.merge(df, df, "inner", "id")
    p = pd.merge(to_pandas(df), to_pandas(df), how="inner", on="id")
    assert_df_equal(j, p, ignore_order=True)


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
    """nlargest(n, 'v') 应与 pandas nlargest 一致(含行序与取值)。

    断言为 assert_df_equal 全行全序比对 —— nlargest 是顺序敏感算子,
    取错行/排错序时行数照旧,只比行数必漏检(并列值两边都按稳定序保留首现,
    见 jian sortBy stable + pandas keep='first')。
    """
    if not df["rows"] or n == 0:
        return
    client = get_client()
    j = client.nlargest(df, n, "v")
    p = to_pandas(df).nlargest(n, "v").reset_index(drop=True)
    # pandas 在 n≥len(取全表)时 nlargest 内部落到
    # sort_values(quicksort,不稳定),并列行序与 jian 稳定序(TimSort)不同 —— 这是
    # §10.12 已声明的稳定排序设计差异(jian 更优)。故 n≥len 用无序多重集比对;
    # n<len 时 pandas 走 heap 路径与 jian 同为稳定序(keep='first' 保留首现),用全行全序强断言。
    assert_df_equal(j, p, ignore_order=(n >= len(df["rows"])))


@given(df=_dfs, n=st.integers(min_value=0, max_value=50))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d9_nsmallest_jian等于pandas(df, n):
    """nsmallest(n, 'v') 应与 pandas nsmallest 一致(含行序与取值)。

    断言为 assert_df_equal 全行全序比对 —— 顺序敏感算子只验计数=放弃 oracle
    (并列值两边都按稳定序保留首现)。
    """
    if not df["rows"] or n == 0:
        return
    client = get_client()
    j = client.nsmallest(df, n, "v")
    p = to_pandas(df).nsmallest(n, "v").reset_index(drop=True)
    # 同 d8 —— n≥len 时 pandas 切换到不稳定 sort_values,豁免行序(取值/行集仍全验)
    assert_df_equal(j, p, ignore_order=(n >= len(df["rows"])))


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
        # 桥对 inf 保真传递,除零 ±inf 以数值形态到达 —— 先走相等短路
        #(inf==inf / -inf==-inf),避免下方容差对 inf 做 inf-inf=nan 的误判
        if isinstance(jv, float) and isinstance(pv, float) and jv == pv:
            continue
        if jv is None:
            # jian None ←→ pandas NaN(桥仅 NaN 折叠 None;inf 已保真)
            assert pv is None or (isinstance(pv, float) and math.isnan(pv)), \
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


# ======================== D16-D20:GroupBy/fillna/dropna/ffill/astype 对照算子 =======
# GroupBy/fillna/dropna/ffill/astype 在 bridge 已实现,
# 按 AGENTS.md §0.5「新增/修改算子必须有 pandas 对照」红线补齐对照。
# bridge client 已暴露这些 op(见 jian_client.py),直接用。

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

    jian DOUBLE 列内部用 NaN 表缺失;客户端 _to_py 把 NaN 转 None。
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
    (NaN 在客户端 _to_py 被规范化为 None),这是已知设计差异 —— 本测试只比对非缺失行。

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
    # id → count 映射逐键比对(对齐 d43 风格)—— 只比 count 多重集时,
    # 计数错配到别的组(多重集恰同)会漏检,逐键比对锁定"每个组拿到自己的计数"。
    j_map = {row[0]: row[1] for row in j["rows"]}
    p_map = dict(zip(p["id"].tolist(), p["v_count"].tolist()))
    assert set(j_map.keys()) == set(p_map.keys()), \
        f"groupBy 分组键集不一致:jian={sorted(j_map.keys())} pandas={sorted(p_map.keys())}"
    for k, pv in p_map.items():
        assert j_map[k] == pv, f"groupBy count[{k!r}] 不一致:jian={j_map[k]} pandas={pv}"


@given(df=_dfs_with_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d39_groupBy_first_last_jian等于pandas(df):
    """groupBy(id).agg(v, first/last) 应跳过组内缺失(对齐 pandas 默认 skipna=True)。

    语义:first/last 取组内第一个/最后一个**非空**值,而不是首/尾行原值
    (last 对 [10, null] 组取 10.0 而非 null);组内全缺失 → NaN(桥接层 null)。
    """
    if not df["rows"]:
        return
    client = get_client()
    pdf = to_pandas(df)
    # 只测有 ≥2 行的组(单元素组 long 列经桥接层转 DOUBLE,不在此测)
    for fn in ("first", "last"):
        j = client.groupBy(df, "id", "v", fn)
        p = pdf.groupby("id", sort=False)["v"].agg(fn)
        # jian 输出 [id, v_{fn}],值在末列;按 id 对齐到 pandas 索引
        j_map = {row[0]: row[1] for row in j["rows"]}
        for pid, pv in p.items():
            jv = j_map.get(pid)
            pv2 = pv if not (isinstance(pv, float) and math.isnan(pv)) else None
            jv2 = jv if jv is not None else None
            if jv2 is None and pv2 is None:
                continue
            assert jv2 is not None and pv2 is not None, \
                f"groupBy {fn} 缺失语义不一致:id={pid} jian={jv!r} pandas={pv!r}"
            assert float(jv2) == float(pv2), \
                f"groupBy {fn} 值不一致:id={pid} jian={jv2!r} pandas={pv2!r}"


@given(df=_dfs_with_nan)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d40_pivotTable_nunique_jian等于pandas(df):
    """pivotTable(index=id, columns=v, values=v, nunique) 应与 pandas pivot_table 对齐。

    nunique 跳过组内缺失(与 GroupBy 一致)。
    本测试用 id 作 index、v 的唯一值作展开列(对照 pandas pivot_table 的
    columns 子集),核验每个 (id, v) 单元格的 nunique 与 pandas 一致。
    """
    if not df["rows"]:
        return
    client = get_client()
    pdf = to_pandas(df)
    if len(df["rows"]) < 3:
        return
    # pandas:index=id, columns 取 v 列的非重值(≤3 个避免列爆炸)
    col_vals = sorted({row[1] for row in df["rows"] if row[1] is not None})[:3]
    sub = [r for r in df["rows"] if r[1] in col_vals]
    sub_df = {"columns": df["columns"], "rows": sub}
    j = client.pivotTable(sub_df, "id", "v", "v", "nunique")
    # pandas pivot_table:columns=v, values=v, aggfunc=nunique
    p = pd.pivot_table(pdf[pdf["v"].isin(col_vals)], index="id", columns="v",
                       values="v", aggfunc="nunique")
    # jian 输出 [id, 各 v 值列...];按 id 对齐
    j_map = {row[0]: row[1:] for row in j["rows"]}
    col_idx = {c: i for i, c in enumerate(j["columns"][1:])}
    for idv, prow in p.iterrows():
        jrow = j_map.get(idv)
        assert jrow is not None, f"pivotTable 缺 id={idv} 行"
        for pcol, pv in prow.items():
            jv = jrow[col_idx[pcol]]
            pv2 = None if (isinstance(pv, float) and math.isnan(pv)) else pv
            jv2 = None if jv is None else jv
            assert (jv2 is None and pv2 is None) or float(jv2) == float(pv2), \
                f"pivotTable nunique 不一致:id={idv} col={pcol} jian={jv!r} pandas={pv!r}"


def test_d41_resample乱序输入_jian等于pandas():
    """resample 对乱序时间输入:分桶必须按时间索引(对齐 pandas,即使输入未排序)。

    因为 Resampler 内部的升序排序数组若被丢弃、ts/origIdx 直接用扫描序,
    乱序输入下会网格起点错、桶分配错乱、出现 NaN;
    pandas 对乱序输入按时间索引正确分桶(01-01→2.0, 01-02→3.0, ...)。
    本测试用乱序用例(2024-01-03/01/02/04/06/05)固化该行为。
    """
    client = get_client()
    ts = ["2024-01-03T00:00:00", "2024-01-01T00:00:00", "2024-01-02T00:00:00",
          "2024-01-04T00:00:00", "2024-01-06T00:00:00", "2024-01-05T00:00:00"]
    vs = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0]
    df = make_df(["ts", "v"], [[t, v_] for t, v_ in zip(ts, vs)])
    j = client.resample(df, "ts", "1D", "v", "sum")
    # pandas:DatetimeIndex → resample('D').sum()(默认按索引升序分桶)
    pdf = pd.DataFrame({"v": vs}, index=pd.to_datetime(ts))
    p = pdf.resample("D").sum()["v"]
    assert len(j["rows"]) == len(p), f"resample 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
    # jian 输出 [_bucket_(ISO 字符串), v_sum];pandas 索引是 Timestamp,按日期对齐
    for i, (jrow, pv) in enumerate(zip(j["rows"], p)):
        jbucket, jv = jrow
        pdate = p.index[i].strftime("%Y-%m-%d")
        assert str(jbucket).startswith(pdate), f"第{i}行 bucket 不一致:jian={jbucket} pandas={pdate}"
        assert float(jv) == float(pv), f"第{i}行 sum 不一致:jian={jv!r} pandas={pv!r}"


# ======================== D21-D30:高频实用扩展对照(idxmax/idxmin/duplicated/sample/isin/where/mask 等)=======
# 补齐 §0.5 红线对 jian 新增方法的要求。
# bridge 已暴露这些 op(见 jian_client.py + JianJpypeBridge.java)。

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
    ③ replace=False 时行不重复(不重复性是确定语义,必须断言)
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
    # 无放回采样行必须唯一(原表本就有重复 id 时,用 (行索引可重复但) 行值定位:
    # 把原表行转多重集,采样行必须能从中不重复地取走 —— 简化:采样行的 id 序列无重复
    # 仅当原表 id 唯一时才可比;原表不唯一时退化为"采样行 ∈ 原表行多重集")
    sampled_ids = [row[0] for row in j["rows"]]
    id_list = [row[0] for row in df["rows"]]   # 用列表(原写法 set(集合) 是恒真式,守卫失效)
    if len(set(id_list)) == len(id_list):
        assert len(set(sampled_ids)) == len(sampled_ids), \
            f"replace=False 采样出现重复行:{sampled_ids}(无放回语义被破坏)"


@given(df=_dfs)
@settings(max_examples=50, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d25_isin_jian等于pandas(df):
    """isin(values) 应与 pandas isin 一致(任一列命中)——含多列 mask 直接对照。

    生产 DataFrameMissing.isin 是**数值跨类型 doubleValue()==**(对齐 pandas
    数值隐式比较,id(Long 1) 命中 values 中的 1.0),多列 mask 可与 pandas 逐行直接比对。
    单列 colIsin 对照保留,双保险。
    """
    if not df["rows"]:
        return
    client = get_client()
    values = [0.0, 1.0, 2.0, 3.0, 5.0]
    # 多列"任一列命中":jian isin vs pandas df.isin(values).any(axis=1),逐行全量比对
    j_mask = client.isin(df, values)
    p_mask = to_pandas(df).isin(values).any(axis=1).tolist()
    assert j_mask == p_mask, f"isin(多列任一命中)不一致:jian={j_mask} pandas={p_mask}"
    # 单列 colIsin 对照(v 列)
    p_v_only = to_pandas(df)["v"].isin(values).tolist()
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
    # where 是整行替换,cond==false 行的 id 列同样被替换为 other,
    # 只验 v 列会放过"只换 v 列"的实现。pandas 的 Series cond
    # 按行广播到全部列(整行替换),与 jian 语义一致,可逐行对照。
    p_id = pdf["id"].where(pdf["v"] > 0, 0.0).tolist()
    for i in range(len(j["rows"])):
        j_id, pid = j["rows"][i][0], p_id[i]
        assert abs(float(j_id) - float(pid)) < 1e-6, \
            f"第 {i} 行 where id 不一致:jian={j_id} pandas={pid}"


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
    # 同 d26,mask 也是整行替换,补验 id 列(cond==true 处替换为 other)
    p_id = pdf["id"].mask(pdf["v"] > 50, 0.0).tolist()
    for i in range(len(j["rows"])):
        j_id, pid = j["rows"][i][0], p_id[i]
        assert abs(float(j_id) - float(pid)) < 1e-6, \
            f"第 {i} 行 mask id 不一致:jian={j_id} pandas={pid}"


# ======================== D28-D35:统计变换对照 =======

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


# ======================== D36-D38:重塑合并对照 =======

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
    # 只容忍 pandas oracle 拒绝输入(重复索引等,pd.pivot 对其抛 ValueError),
    # oracle 拒绝 → skip;jian 侧异常一律向上抛(失败),不被宽吞。
    try:
        p = to_pandas(uniq_df).pivot(index="id", columns="id", values="v")
    except ValueError:
        return  # pandas 拒绝该输入(重复 index/columns 对),无 oracle 可比
    j = client.pivot(uniq_df, "id", "id", "v")
    assert len(j["rows"]) == len(p), f"pivot 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
    # 列数(去掉 index 列 "id")
    j_cols = [c for c in j["columns"] if c != "id"]
    p_cols = list(p.columns)
    assert len(j_cols) == len(p_cols), f"pivot 列数不一致:jian={len(j_cols)} pandas={len(p_cols)}"
    # 单元格全量比对(只验行列数时,单元格值错/行列错配都测不出)。
    # 双方各建 {行id: {列名(str): 值}} 嵌套映射(jian 的 pivot
    # 列名是字符串化的 id,pandas 是 int,统一 str() 归一),逐格对照,NaN↔None 等价。
    idx_i = j["columns"].index("id")
    j_map = {row[idx_i]: {str(c): row[ci] for ci, c in enumerate(j["columns"]) if ci != idx_i}
             for row in j["rows"]}
    p_map = {row["id"]: {str(c): row[c] for c in p.columns}
             for _, row in p.iterrows()}
    assert set(j_map.keys()) == set(p_map.keys()), \
        f"pivot 行键集不一致:jian={sorted(j_map.keys())} pandas={sorted(p_map.keys())}"
    for rid in p_map:
        assert set(j_map[rid].keys()) == set(p_map[rid].keys()), \
            f"pivot 行 {rid} 列键集不一致:jian={sorted(j_map[rid].keys())} pandas={sorted(p_map[rid].keys())}"
        for cid, pv in p_map[rid].items():
            jv = _norm_val(j_map[rid][cid])
            pv = _norm_val(pv)
            if jv is None and pv is None:
                continue  # 双方缺失等价
            assert jv is not None and pv is not None, \
                f"pivot({rid},{cid}) 缺失语义不一致:jian={jv!r} pandas={pv!r}"
            if isinstance(jv, float) and isinstance(pv, float):
                assert abs(jv - pv) <= 1e-6, f"pivot({rid},{cid}) 值不一致:jian={jv} pandas={pv}"
            else:
                assert jv == pv, f"pivot({rid},{cid}) 值不一致:jian={jv!r} pandas={pv!r}"


@given(df=_dfs_no_nan)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d37_explode_jian等于pandas(df):
    """explode(v) —— v 是标量单值,explode 后行数不变且 id/v 两列逐行一致(值比对)。"""
    if not df["rows"]:
        return
    client = get_client()
    j = client.explode(df, "v")
    assert len(j["rows"]) == len(df["rows"]), f"explode 单值行数变了:jian={len(j['rows'])} 原表={len(df['rows'])}"
    # id/v 两列逐行比对(只比行数时值篡改测不出;标量 explode 保序保值)
    p = to_pandas(df)
    p_exploded = p.explode("v").reset_index(drop=True)
    ii = j["columns"].index("id")
    vi = j["columns"].index("v")
    for i, row in enumerate(j["rows"]):
        assert _norm_val(row[ii]) == _norm_val(p_exploded["id"].iloc[i]), \
            f"explode 第 {i} 行 id 不一致:jian={row[ii]} pandas={p_exploded['id'].iloc[i]}"
        jv, pv = _norm_val(row[vi]), _norm_val(p_exploded["v"].iloc[i])
        if jv is None and pv is None:
            continue
        assert jv is not None and pv is not None, \
            f"explode 第 {i} 行 v 缺失语义不一致:jian={row[vi]!r} pandas={p_exploded['v'].iloc[i]!r}"
        assert abs(float(jv) - float(pv)) <= 1e-6, \
            f"explode 第 {i} 行 v 不一致:jian={jv} pandas={pv}"


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
    # pandas oracle 先行且只容忍 oracle 自身异常;jian 与断言不被吞,异常即失败。
    p_left = to_pandas(sorted_left).sort_values("id").reset_index(drop=True)
    p_right = to_pandas(right).sort_values("id").reset_index(drop=True)
    try:
        p = pd.merge_asof(p_left, p_right, on="id")
    except ValueError:
        return  # pandas 拒绝该输入(oracle 不可用),skip
    j = client.mergeAsof(sorted_left, right, "id")
    assert len(j["rows"]) == len(p), f"merge_asof 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
    # id/w 列逐行比对(只验行数时,匹配到错误的 right 行行数也不变,测不出;
    # 两边都按 left 升序,行序确定);left.id 小于 right 最小键的行
    # w 为缺失(pandas NaN ↔ jian None)。
    wi = j["columns"].index("w")
    ii = j["columns"].index("id")
    p_w = p["w"].tolist()
    p_id = p["id"].tolist()
    for i, row in enumerate(j["rows"]):
        assert float(row[ii]) == float(p_id[i]), \
            f"merge_asof 第 {i} 行 id 不一致:jian={row[ii]} pandas={p_id[i]}"
        jv, pv = _norm_val(row[wi]), _norm_val(p_w[i])
        if jv is None and pv is None:
            continue  # 双方缺失等价(无 ≤ 该 id 的 right 行)
        assert jv is not None and pv is not None, \
            f"merge_asof 第 {i} 行 w 缺失语义不一致:jian={row[wi]!r} pandas={p_w[i]!r}"
        assert abs(float(jv) - float(pv)) <= 1e-6, \
            f"merge_asof 第 {i} 行 w 不一致:jian={jv} pandas={pv}"


# 固定两表:左 id∈{1,2,3,5},右 id∈{1,2,4,5}(含左右各自独有键)
_MERGE_L = make_df(["id", "v"], [[1, 10.0], [2, 20.0], [3, 30.0], [5, 50.0]])
_MERGE_R = make_df(["id", "v"], [[1, 11.0], [2, 21.0], [4, 41.0], [5, 51.0]])


def test_d42_merge_rightOuter_右表独有行键列保留jian等于pandas():
    """merge right/outer 中右表独有行的键列必须保留右表 key。

    语义要求:右表独有行(lIdx<0)的 join 键列必须取右表 key
    (若把左表部分全填 null 会输出 (null, null, 41.0) 丢失 id;pandas 输出 (4, null, 41.0))。
    同 dtype LONG/DOUBLE key 走 fast path 入口,LONG/DOUBLE 的 right/outer 落回 generic,
    本测试直接覆盖 generic 回退分支(PITest 存活变异区),并对照 pandas。
    """
    client = get_client()
    pa = pd.DataFrame({"id": [1, 2, 3, 5], "v": [10.0, 20.0, 30.0, 50.0]})
    pb = pd.DataFrame({"id": [1, 2, 4, 5], "v": [11.0, 21.0, 41.0, 51.0]})
    for how in ["inner", "left", "right", "outer"]:
        j = client.merge(_MERGE_L, _MERGE_R, how, "id")
        p = pd.merge(pa, pb, how=how, on="id", suffixes=("_x", "_y")).reset_index(drop=True)
        # 行数一致 + 键列值集一致(行序与 pandas 无关)
        assert len(j["rows"]) == len(p), \
            f"merge({how}) 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
        j_ids = sorted({r[0] for r in j["rows"]})
        p_ids = sorted({r[0] for r in p.values.tolist()})
        assert j_ids == p_ids, \
            f"merge({how}) 键列值集不一致:jian={j_ids} pandas={p_ids}"


def test_d43_groupBy_聚合函数全家桶_jian等于pandas():
    """groupBy(g).agg(v, fn) 全聚合函数对照 pandas(fn=sum/mean/min/max/std/var/median/count/nunique)。

    覆盖 §0.5 红线(凡对标 pandas 算子必须有对照测试):基本分组 + null 组内值、
    单元素组 + 全 null 组、重复分组键,逐 fn 对照 pandas groupby(sort=False) 聚合结果。
    数值容差 1e-6;NaN/None 等价(桥接层 NaN→None);count 全空组返回 0(两边一致)。
    """
    cases = [
        # (用例名, df, pandas 期望)
        ("基本+null", make_df(["g", "v"], [["a", 1.0], ["a", 2.0], ["b", 10.0], ["b", None], ["c", 5.0]])),
        ("单元素+全null", make_df(["g", "v"], [["a", 3.0], ["b", None]])),
        ("重复键", make_df(["g", "v"], [["x", 1.0], ["x", 2.0], ["x", 3.0], ["y", 4.0], ["y", 5.0]])),
    ]
    fns = ["sum", "mean", "min", "max", "std", "var", "median", "count", "nunique"]
    client = get_client()
    for cname, df in cases:
        pdf = to_pandas(df)
        for fn in fns:
            j = client.groupBy(df, "g", "v", fn)
            p = pdf.groupby("g", sort=False)["v"].agg(fn)
            assert len(j["rows"]) == len(p), \
                f"[{cname}] {fn} 组数不一致:jian={len(j['rows'])} pandas={len(p)}"
            j_map = {row[0]: row[1] for row in j["rows"]}
            for g, pv in p.items():
                jv = j_map.get(g)
                jv2 = None if (jv is None or (isinstance(jv, float) and math.isnan(jv))) else jv
                pv2 = None if (isinstance(pv, float) and math.isnan(pv)) else pv
                if jv2 is None and pv2 is None:
                    continue
                assert jv2 is not None and pv2 is not None, \
                    f"[{cname}] {fn} 缺失语义不一致:g={g} jian={jv!r} pandas={pv!r}"
                if isinstance(jv2, float) and isinstance(pv2, float):
                    assert math.isclose(float(jv2), float(pv2), rel_tol=1e-6, abs_tol=1e-6), \
                        f"[{cname}] {fn} 值不一致:g={g} jian={jv2!r} pandas={pv2!r}"
                else:
                    assert str(jv2) == str(pv2), \
                        f"[{cname}] {fn} 值不一致:g={g} jian={jv2!r} pandas={pv2!r}"


# === pct_change 对照(pct_change 的除数语义)===

def test_d44_pctChange_负数前值符号_jian等于pandas():
    """pct_change 必须除 prev 而非 |prev|(prev<0 时用 |prev| 会符号反转:
    prev=-1, cur=3 时错得 +4,pandas 为 -4)。
    固定用例:[-1, 3, -2, 5]:
      pandas:  [nan, (3+1)/-1=-4, (-2-3)/3≈-1.6667, (5+2)/-2=-3.5]
    """
    client = get_client()
    df = make_df(["id", "v"],
                 [[0, -1.0], [1, 3.0], [2, -2.0], [3, 5.0]])
    j = client.pct_change(df, "v", 1, "v_pc")
    p = pd.Series([-1.0, 3.0, -2.0, 5.0]).pct_change(1)
    jv = [row[-1] for row in j["rows"]]
    for i in range(4):
        if i == 0:
            assert jv[0] is None or (isinstance(jv[0], float) and math.isnan(jv[0])), \
                f"首行应为缺失:jian={jv[0]!r}"
            continue
        assert math.isclose(float(jv[i]), float(p[i]), rel_tol=1e-6, abs_tol=1e-6), \
            f"pct_change 第 {i} 行不一致:jian={jv[i]!r} pandas={p[i]!r}"
    close_client()


def test_d45_pctChange_前值为零_设计差异声明():
    """设计差异声明(方案 B,doc/00-overview.md §10):
    prev==0 时 pandas 返回 ±inf(带 RuntimeWarning),jian 返回 NaN ——
    NaN 不污染后续聚合,更符合 jian 缺失语义。此用例显式锁定 jian 行为,不与 pandas 比对。"""
    client = get_client()
    df = make_df(["id", "v"], [[0, 0.0], [1, 5.0]])
    j = client.pct_change(df, "v", 1, "v_pc")
    jv = [row[-1] for row in j["rows"]]
    # 桥的 JSON 序列化把 NaN 转成 None,jian 侧实际返回 NaN
    assert jv[1] is None or (isinstance(jv[1], float) and math.isnan(jv[1])), \
        f"prev==0 应返 NaN(设计差异):jian={jv[1]!r}"
    close_client()


def test_d46_混型顺序比较双方都抛对齐pandas():
    """混型顺序比较(STRING 列 > 数值)对齐 pandas:双方都报错(jian IAE / pandas TypeError)。

    因为 String 字典序的"宽厚行为"是未声明偏离 pandas(且与 SimpleQueryParser/PrattEngine
    三入口不一致),所以混型顺序比较统一对齐 pandas 改抛。
    """
    import jpype
    client = get_client()
    df = make_df(["s"], [["a"], ["b"], ["c"]])
    # jian:STRING 列 > 整数 → 必须抛 Java IllegalArgumentException(精确断言异常类型,
    # 非 JException 基类;避免放过 NPE/CCE 等错误异常类型)
    IAE = jpype.JClass("java.lang.IllegalArgumentException")
    with pytest.raises(IAE):
        client.colCmp(df, "s", ">", 1)
    with pytest.raises(IAE):
        client.colCmp(df, "s", "<=", 2)
    # == / != 不抛(对齐 pandas 元素级相等比较:字符串 == 数字 恒 False)
    eq_mask = client.colCmp(df, "s", "==", 1)
    assert eq_mask == [False, False, False]
    ne_mask = client.colCmp(df, "s", "!=", 1)
    assert ne_mask == [True, True, True]
    # pandas:同输入 str > int 抛 TypeError(oracle 对照)
    with pytest.raises(TypeError):
        _ = pd.Series(["a", "b", "c"]) > 1


def test_d47_merge_right_outer行数键序对齐pandas():
    """merge right/outer 行数 + 键多重集对齐 pandas(pandas-oracle-probe.py 实测)。

    pandas oracle:right=4 行 / outer=5 行,键多重集 {RD,RD,ENG,MGT} / {RD,RD,PM,ENG,MGT}。
    行数与键多重集均精确比对(弱断言 `>=N` 会放过少行 bug)。
    """
    client = get_client()
    left = make_df(["k", "u"], [["RD", "alice"], ["PM", "bob"], ["RD", "carol"]])
    right = make_df(["k", "d"], [["RD", "研发"], ["ENG", "工程"], ["MGT", "管理"]])
    for how, expected in [("right", 4), ("outer", 5)]:
        j = client.merge(left, right, how, "k")
        p = pd.merge(to_pandas(left), to_pandas(right), on="k", how=how)
        assert len(j["rows"]) == len(p) == expected, \
            f"{how}: jian={len(j['rows'])} pandas={len(p)} 期望={expected}"
        # 键多重集一致(ignore_order 容忍 jian/pandas 键序差异)
        assert_df_equal(j, p, ignore_order=True)


def test_d48_astype含NaN转LONG是声明的设计差异():
    """DOUBLE 含 NaN → LONG:jian 转哨兵(不失真)vs pandas 抛 IntCastingNaNError。

    §10.16 #1/#2 声明的设计差异:jian 选择"内部不失真"(NaN→Long.MIN_VALUE 哨兵+isNull),
    区别于 pandas 的"报错"。本测试锁定双方各自行为(非等价,是有意差异)。
    """
    client = get_client()
    df = make_df(["v"], [[1.0], [None], [3.0]])
    # jian:转 LONG 不抛,缺失行用 Long.MIN_VALUE 哨兵(§3.5 缺失值契约)
    j = client.astype(df, "v", "LONG")
    assert j["columns"] == ["v"]
    rows = [r[0] for r in j["rows"]]
    assert rows[0] == 1 and rows[2] == 3            # 正常行
    # 桥经 IO 边界(toObjectArray)把缺失转 null(§3.5);哨兵 Long.MIN_VALUE 是 Java 内部表示,
    # 见 Java 测试 DataFrameQueryTest.astype_DOUBLE含NaN转LONG用哨兵且isNull为真(直查 getLong+isNull)。
    assert rows[1] is None                            # jian 不抛、缺失行为 null(对照 pandas 抛错)
    # pandas:同输入抛 IntCastingNaNError(ValueError 子类)—— 设计差异对照
    pdf = pd.DataFrame({"v": [1.0, np.nan, 3.0]})
    with pytest.raises(ValueError):
        pdf["v"].astype(int)
    close_client()


# ======================== d49-d54:统计/Window/Resampler 详细逐值对照 ========================
# 本组体现 Python 桥的根本目的:引入 jar 后用 pandas 当 oracle 做详细功能测试(非"跑通即可")。
# 每条逐值比对(浮点容差 1e-9),多边界。

def test_d49_偏度峰度无偏估计对齐pandas():
    """skewness/kurtosis 用无偏估计(G1/G2)逐值对齐 pandas。

    SimpleStatsProvider 的矩公式用【有偏】总体矩(m3/m2^1.5、m4/m2^2-3)时,
    对称数据(如 [1..8])skew=0 巧合一致、掩盖差异;非对称数据偏离 pandas。
    无偏 G1/G2 对齐 pandas。本测试用非对称数据(确保区分有偏/无偏)。
    """
    client = get_client()
    vals = [1.0, 2, 2, 3, 3, 3, 4, 5, 9]   # 非对称(skew≠0),有偏/无偏结果不同
    df = make_df(["v"], [[v] for v in vals])
    pdf = pd.DataFrame({"v": vals})
    for fn, pfn in [("skewness", "skew"), ("kurtosis", "kurt"),
                    ("mad", "mad"), ("sem", "sem"), ("median", "median")]:
        j = client.stat(df, "v", fn)
        p = float(getattr(pdf["v"], pfn)())
        if isinstance(p, float) and math.isnan(p):
            continue
        assert abs(j - p) <= 1e-9, f"{fn}: jian={j} pandas={p} (有偏/无偏差异)"
    # 分位数
    assert abs(client.stat(df, "v", "q25") - float(pdf["v"].quantile(0.25))) <= 1e-9
    close_client()


def test_d50_corr_cov对齐pandas():
    """corr(pearson)/cov 逐值对齐 pandas。"""
    client = get_client()
    df = make_df(["x", "y"], [[1, 2], [2, 5], [3, 5], [4, 8], [5, 7], [6, 9], [7, 11], [8, 13]])
    pdf = pd.DataFrame({"x": [1, 2, 3, 4, 5, 6, 7, 8], "y": [2, 5, 5, 8, 7, 9, 11, 13]}, dtype=float)
    assert abs(client.corr(df, "x", "y", "pearson") - float(pdf["x"].corr(pdf["y"]))) <= 1e-9
    assert abs(client.cov(df, "x", "y") - float(pdf["x"].cov(pdf["y"]))) <= 1e-9
    close_client()


def test_d51_rolling各聚合逐值对齐pandas():
    """rolling(窗口=3)的 mean/sum/min/max/std/count 全部逐值对齐 pandas(含窗口未满的 NaN 位)。"""
    client = get_client()
    vals = [1.0, 3, 2, 5, 4, 7, 6, 9]
    df = make_df(["v"], [[v] for v in vals])
    pdf = pd.DataFrame({"v": vals})
    for fn, pfn in [("mean", "mean"), ("sum", "sum"), ("min", "min"),
                    ("max", "max"), ("std", "std"), ("count", "count")]:
        j = client.rolling(df, "v", 3, fn)
        # jian 的 rolling.count 遵守 minPeriods(窗口不足为 NaN,对齐类文档与 pandas 2.x);
        # pandas 1.5.3 的 count 默认 min_periods=0(部分窗口也计数,FutureWarning 的历史怪癖)——
        # oracle 显式传 min_periods=3 固定语义,消除版本依赖(2.x 默认值已变更)
        p = getattr(pdf["v"].rolling(3, min_periods=3), pfn)().tolist()
        assert len(j) == len(p), f"{fn}: 长度差异 jian={len(j)} pandas={len(p)}"
        for i, (jv, pv) in enumerate(zip(j, p)):
            if pv is None or (isinstance(pv, float) and math.isnan(pv)):
                assert jv is None or (isinstance(jv, float) and math.isnan(jv)), \
                    f"{fn}[{i}]: pandas=NaN 但 jian={jv}"
            elif isinstance(pv, float):
                assert abs(float(jv) - float(pv)) <= 1e-9, f"{fn}[{i}]: jian={jv} pandas={pv}"
            else:
                assert float(jv) == float(pv), f"{fn}[{i}]: jian={jv} pandas={pv}"
    close_client()


def test_d52_ewm_mean_adjustFalse对齐pandas():
    """ewm.mean 用 adjust=False(§10.16 #8 声明的设计差异;jian 不实现 adjust=True,故只对照 adjust=False)。"""
    client = get_client()
    vals = [1.0, 2, 3, 4, 5, 6, 7, 8]
    df = make_df(["v"], [[v] for v in vals])
    pdf = pd.DataFrame({"v": vals})
    j = client.ewm(df, "v", 0.5, "mean")
    p = pdf["v"].ewm(alpha=0.5, adjust=False).mean().tolist()
    for i, (jv, pv) in enumerate(zip(j, p)):
        assert abs(float(jv) - float(pv)) <= 1e-9, f"ewm.mean[{i}]: jian={jv} pandas={pv}"
    close_client()


def test_d53_resample各聚合逐值对齐pandas():
    """resample(1D) 的 sum/mean/count/min/max/std/median/var 全部逐值对齐 pandas。"""
    client = get_client()
    ts = pd.date_range("2026-01-01", periods=8, freq="12H")
    rows = [[t.strftime("%Y-%m-%dT%H:%M:%S"), float(i + 1)] for i, t in enumerate(ts)]
    df = make_df(["ts", "v"], rows)
    pdf = pd.DataFrame({"ts": ts, "v": [1.0, 2, 3, 4, 5, 6, 7, 8]}).set_index("ts")
    for fn in ["sum", "mean", "count", "min", "max", "std", "median", "var"]:
        j = client.resample(df, "ts", "1D", "v", fn)
        p = pdf["v"].resample("1D").agg(fn)
        jrows = j["rows"]
        jvals = [r[-1] for r in jrows]   # 值列在最后一列
        pvals = p.tolist()
        assert len(jvals) == len(pvals), f"{fn}: jian={len(jvals)}行 pandas={len(pvals)}行"
        for i, (jv, pv) in enumerate(zip(jvals, pvals)):
            if pv is None or (isinstance(pv, float) and math.isnan(pv)):
                assert jv is None or (isinstance(jv, float) and math.isnan(jv)), \
                    f"{fn}[{i}]: pandas=NaN 但 jian={jv}"
            elif isinstance(pv, float):
                assert abs(float(jv) - float(pv)) <= 1e-9, f"{fn}[{i}]: jian={jv} pandas={pv}"
            else:
                assert float(jv) == float(pv), f"{fn}[{i}]: jian={jv} pandas={pv}"
    close_client()


def test_d54_expanding_valueCounts对齐pandas():
    """expanding 的 mean/sum/min/max 逐值对齐 pandas;value_counts 计数对齐。"""
    client = get_client()
    vals = [3.0, 1, 4, 1, 5, 9, 2, 6]
    df = make_df(["v"], [[v] for v in vals])
    pdf = pd.DataFrame({"v": vals})
    for fn in ["mean", "sum", "min", "max"]:
        j = client.expanding(df, "v", fn)
        p = getattr(pdf["v"].expanding(), fn)().tolist()
        for i, (jv, pv) in enumerate(zip(j, p)):
            if pv is None or (isinstance(pv, float) and math.isnan(pv)):
                assert jv is None or (isinstance(jv, float) and math.isnan(jv)), f"expanding.{fn}[{i}] jian={jv}"
            else:
                assert abs(float(jv) - float(pv)) <= 1e-9, f"expanding.{fn}[{i}]: jian={jv} pandas={pv}"
    # value_counts
    dfv = make_df(["c"], [["a"], ["b"], ["a"], ["c"], ["a"], ["b"]])
    jc = client.valueCounts(dfv, "c")
    pc = pd.DataFrame({"c": ["a", "b", "a", "c", "a", "b"]})["c"].value_counts().to_dict()
    jn = {str(k): v for k, v in jc.items()}   # 归一 key 类型
    pn = {str(k): v for k, v in pc.items()}
    assert jn == pn, f"value_counts: jian={jn} pandas={pn}"
    close_client()


# ======================== d55-d60:query 语法/统计边界/字符串聚合/fillna 对照 ========================

def test_d55_notin与算术与转义对齐pandas():
    """query 语法:notin 单字 / 算术 / '' 转义(桥 classpath 已加 jian-dsl → 主路径 PrattEngine)。"""
    client = get_client()
    df = make_df(["a", "b"], [[1, 4], [2, 5], [3, 6]])
    pdf = pd.DataFrame({"a": [1, 2, 3], "b": [4, 5, 6]})
    # notin 单字(解析不得当"尾部多余 token"拒绝)
    assert_df_equal(client.filter(df, "a notin (2, 4)"), pdf[~pdf["a"].isin([2, 4])])
    # not in 两词
    assert_df_equal(client.filter(df, "a not in (2)"), pdf[~pdf["a"].isin([2])])
    # 算术(pandas query 同款)
    assert_df_equal(client.filter(df, "a * b > 8"), pdf.query("a * b > 8"))
    assert_df_equal(client.filter(df, "a + 1 == 2"), pdf.query("a + 1 == 2"))
    # '' 转义(pandas 用双引号表达撇号;jian 三种等价写法之一)
    dfs = make_df(["name"], [["O'Brien"], ["Bob"]])
    pds = pd.DataFrame({"name": ["O'Brien", "Bob"]})
    assert_df_equal(client.filter(dfs, "name == 'O''Brien'"), pds[pds["name"] == "O'Brien"])
    close_client()


def test_d56_corr_N1与常量列对齐pandas():
    """N=1 与全常量列 corr 均为 NaN(无定义相关返 0.0 是错的,对齐 pandas NaN)。"""
    client = get_client()
    n1 = make_df(["a", "b"], [[1.0, 2.0]])
    v = client.corr(n1, "a", "b", "pearson")
    assert v is None or (isinstance(v, float) and math.isnan(v)), f"N=1 corr 应 NaN,实际 {v}"
    cst = make_df(["a", "b"], [[1.0, 5.0], [2.0, 5.0], [3.0, 5.0]])
    v2 = client.corr(cst, "a", "b", "pearson")
    assert v2 is None or (isinstance(v2, float) and math.isnan(v2)), f"常量列 corr 应 NaN,实际 {v2}"
    close_client()


def test_d57_corr错位NaN对齐pandas逐对删除():
    """错位 NaN 按同下标逐对删除(错位配对会算出 1.0 或误抛长度不一致)。"""
    client = get_client()
    df = make_df(["a", "b"], [[1.0, 1.0], [None, 2.0], [3.0, 3.0]])
    pdf = pd.DataFrame({"a": [1.0, None, 3.0], "b": [1.0, 2.0, 3.0]})
    j = client.corr(df, "a", "b", "pearson")
    p = pdf["a"].corr(pdf["b"])
    assert abs(float(j) - float(p)) <= 1e-9, f"corr: jian={j} pandas={p}"
    # 同下标仅剩 1 对 → 双方 NaN
    df2 = make_df(["a", "b"], [[1.0, 1.0], [None, 2.0], [3.0, None]])
    j2 = client.corr(df2, "a", "b", "pearson")
    assert j2 is None or (isinstance(j2, float) and math.isnan(j2)), f"单对 corr 应 NaN,实际 {j2}"
    close_client()


def test_d58_字符串rank对齐pandas():
    """字符串列 rank 字典序(pd.Series(['a','c','b']).rank() → [1,3,2])。"""
    client = get_client()
    df = make_df(["s"], [["a"], ["c"], ["b"]])
    pdf = pd.DataFrame({"s": ["a", "c", "b"]})
    j = client.rank(df, "s")          # 返回 {columns, rows};新列名经 null 兜底为 s_rank
    assert "s_rank" in j["columns"], f"rank 新列名兜底(STAT-001): {j['columns']}"
    jr = [row[1] for row in j["rows"]]
    p = pdf["s"].rank().tolist()
    for i, (jv, pv) in enumerate(zip(jr, p)):
        assert abs(float(jv) - float(pv)) <= 1e-9, f"rank[{i}]: jian={jv} pandas={pv}"
    close_client()


def test_d59_字符串groupBy_sum对齐pandas拼接():
    """字符串列 groupby sum 拼接(pandas 'x'+'y' → 'xy')。"""
    client = get_client()
    df = make_df(["g", "s"], [["a", "x"], ["a", "y"], ["b", "z"]])
    pdf = pd.DataFrame({"g": ["a", "a", "b"], "s": ["x", "y", "z"]})
    j = client.groupBy(df, "g", "s", "sum")
    p = pdf.groupby("g")["s"].sum().to_dict()
    assert j["rows"] and len(j["rows"]) == 2, f"分组数: {j}"
    jmap = {r[0]: r[1] for r in j["rows"]}
    assert jmap == p, f"字符串 sum: jian={jmap} pandas={p}"
    close_client()


def test_d60_fillna字典与超大整数对齐pandas():
    """fillna(dict) per-column;超大整数(>int64)读 CSV 归字符串不崩(对齐 pandas object)。"""
    import tempfile, os, subprocess, pathlib
    client = get_client()
    df = make_df(["a", "b"], [[None, 1.0], [2.0, None]])
    pdf = pd.DataFrame({"a": [None, 2.0], "b": [1.0, None]})
    j = client.fillna(df, {"a": 100.0, "b": 200.0})
    p = pdf.fillna({"a": 100.0, "b": 200.0})
    assert_df_equal(j, p)
    # 超大整数 CSV(桥无法直读文件,经 Java Csv;此用例验证 Schema 边界 —— 由 Java 侧
    # Schema 边界测试锁定,这里做 pandas 语义声明:pd.read_csv 同输入 dtype=object)
    p2 = pd.read_csv(io.StringIO("x\n123456789012345678901234567890\n"))
    assert p2["x"].dtype == object, "pandas 超大 int64 归 object;jian 对齐 STRING"
    close_client()


# ======================== d61-d63:测试补强对照(列算术/缺失语义)========================

def test_d61_列算术与assign全家桶对齐pandas():
    """colAdd/colSub/colDiv/colMulScalar/assign 的 pandas 对照(§0.5 红线)。

    这些算术算子不能只有 jqwik/Hypothesis PBT(内部一致性),必须有 pandas oracle;
    本测试在固定数据上五算子逐值对照 pandas(除法用 pandas 真除法语义)。
    """
    client = get_client()
    df = make_df(["a", "b"], [[1, 4], [2, 5], [3, 6]])
    pdf = pd.DataFrame({"a": [1, 2, 3], "b": [4, 5, 6]})
    assert_df_equal(client.colAdd(df, "ab", "a", "b"), pdf.assign(ab=pdf["a"] + pdf["b"]))
    assert_df_equal(client.colSub(df, "a_b", "a", "b"), pdf.assign(**{"a_b": pdf["a"] - pdf["b"]}))
    assert_df_equal(client.colDiv(df, "aob", "a", "b"), pdf.assign(aob=pdf["a"] / pdf["b"]))
    assert_df_equal(client.colMulScalar(df, "a3", "a", 2.5), pdf.assign(a3=pdf["a"] * 2.5))
    assert_df_equal(client.assign(df, "tag", "hi"), pdf.assign(tag="hi"))
    # 含缺失列的算术:null 传播(pandas NaN 传播一致)
    dfn = make_df(["a", "b"], [[1.0, 4.0], [None, 5.0]])
    pdfn = pd.DataFrame({"a": [1.0, None], "b": [4.0, 5.0]})
    assert_df_equal(client.colAdd(dfn, "ab", "a", "b"), pdfn.assign(ab=pdfn["a"] + pdfn["b"]))
    close_client()


def test_d62_colNe缺失行语义对齐pandas():
    """colNe 对缺失行 = True(对齐 pandas NaN != x 与 query 双引擎)。

    因为 compare 的 isNull 前置分支若把含 != 在内全部置 false,会与
    query("c != 'x'")(保留 null 行)同库相反;pandas 站 query 一边(NaN != x → True)。
    """
    client = get_client()
    df = make_df(["c"], [["x"], ["y"], [None]])
    pdf = pd.DataFrame({"c": ["x", "y", None]})
    j = client.colCmp(df, "c", "!=", "x")
    p = (pdf["c"] != "x").tolist()
    assert j == p, f"colNe 缺失行语义:jian={j} pandas={p}"
    # == 对照:缺失行 False(两边一致,NaN 传播)
    j2 = client.colCmp(df, "c", "==", "x")
    p2 = (pdf["c"] == "x").tolist()
    assert j2 == p2, f"colEq 缺失行语义:jian={j2} pandas={p2}"
    # 数值列同语义(null/NaN 行 != → True)
    dfn = make_df(["v"], [[1.0], [None], [3.0]])
    pdfn = pd.DataFrame({"v": [1.0, np.nan, 3.0]})
    jn = client.colCmp(dfn, "v", "!=", 2.0)
    pn = (pdfn["v"] != 2.0).tolist()
    assert jn == pn, f"数值列 colNe NaN 行:jian={jn} pandas={pn}"
    close_client()


def test_d63_merge重名列两边加后缀对齐pandas():
    """merge 重名列两边都加后缀 [id, v_x, v_y](设计决策"以 pandas 为准")。

    三条 merge 路径(long/double/generic)必须同口径:只给右表加 _y(输出 [id, v, v_y])
    会与 pandas [id, v_x, v_y] 分歧;本测试锁列名 + 列值双重对齐。
    """
    client = get_client()
    lft = make_df(["id", "v"], [[1, 10.0], [2, 20.0]])
    rgt = make_df(["id", "v"], [[1, 11.0], [3, 31.0]])
    for how in ["inner", "left", "right", "outer"]:
        j = client.merge(lft, rgt, how, "id")
        p = pd.merge(pd.DataFrame({"id": [1, 2], "v": [10.0, 20.0]}),
                     pd.DataFrame({"id": [1, 3], "v": [11.0, 31.0]}),
                     how=how, on="id").reset_index(drop=True)
        assert j["columns"] == ["id", "v_x", "v_y"], \
            f"merge({how}) 列名应为 [id, v_x, v_y](对齐 pandas):{j['columns']}"
        assert_df_equal(j, p, ignore_order=(how in ("right", "outer")))
    close_client()


def test_d64_异名键merge右键列保留对齐pandas():
    """leftOn≠rightOn 时右表键列必须保留(对齐 pandas)。

    三条路径都不得无条件跳过右键列(否则 k2 整列丢失);pandas 输出 ['k1','x_x','k2','x_y'],
    且 outer/right 右表独有行 k1=NaN(不把右键回填进左键列)。桥为此提供 mergeOn。
    """
    client = get_client()
    lft = make_df(["k1", "x"], [[1, 10.0], [2, 20.0], [3, 30.0]])
    rgt = make_df(["k2", "x"], [[1, 11.0], [4, 41.0]])
    pl = pd.DataFrame({"k1": [1, 2, 3], "x": [10.0, 20.0, 30.0]})
    pr = pd.DataFrame({"k2": [1, 4], "x": [11.0, 41.0]})
    for how in ["inner", "left", "right", "outer"]:
        j = client.mergeOn(lft, rgt, how, "k1", "k2")
        p = pd.merge(pl, pr, how=how, left_on="k1", right_on="k2").reset_index(drop=True)
        assert j["columns"] == ["k1", "x_x", "k2", "x_y"], \
            f"merge({how}) 异名键应保留 k2(对齐 pandas):{j['columns']}"
        assert_df_equal(j, p, ignore_order=(how in ("right", "outer")))
    close_client()


def test_d65_求和溢出保留Infinity对齐pandas():
    """Neumaier 补偿求和溢出应得 Infinity(而非 NaN)。

    pandas sum([MAX,MAX])=inf、mean=inf;桥对 Infinity 保真传递(仅 NaN 折叠 None,
    见 jian_client._to_py),故可直接 math.isinf 强断言。
    """
    client = get_client()
    big = make_df(["v"], [[1.7976931348623157e308], [1.7976931348623157e308]])
    pdf = pd.DataFrame({"v": [1.7976931348623157e308] * 2})
    j_sum = client.stat(big, "v", "sum")
    p_sum = float(pdf["v"].sum())
    assert math.isinf(p_sum) and p_sum > 0, f"pandas 应为 +inf:{p_sum}"
    assert isinstance(j_sum, float) and math.isinf(j_sum) and j_sum > 0, \
        f"jian sum 溢出应 +Infinity(桥已保真 inf),实际 {j_sum!r}"
    close_client()


# ======================== d66-d68:round/isin/nunique 语义对照 ========================

def test_d66_round银行家舍入对齐pandas():
    """round 是 half-even(银行家舍入),不得用 Math.round 的 half-up 或大数饱和。

    pandas Series.round 对精确 .5 走 half-even(2.5→2.0、-3.5→-4.0、0.125@2→0.12、125@-1→120);
    half-up 会得 2.5→3.0、-3.5→-3.0,Math.round 还会把 1e300 饱和到 9.22e18。全部边界逐值对照。
    """
    client = get_client()
    cases = [  # (值, decimals)
        (2.5, 0), (0.5, 0), (-3.5, 0), (2.4, 0), (2.6, 0),
        (0.125, 2), (125, -1), (123, -1), (1e300, 0),
    ]
    for v, dec in cases:
        df = make_df(["v"], [[v]])
        j = client.round(df, "v", dec)["rows"][0][1]
        p = float(pd.Series([v]).round(dec)[0])
        assert abs(j - p) <= max(1e-9, abs(p) * 1e-12), \
            f"round({v},{dec}): jian={j} pandas={p}"
    close_client()


def test_d67_isin含NaN对齐pandas():
    """isin/colIsin 的 values 含 NaN 时,NaN 行应命中(pandas True),不得恒 false。"""
    client = get_client()
    nan = float("nan")
    df = make_df(["v"], [[1.0], [None], [3.0]])   # None 经桥 → Java null → Double 列缺失(NaN)
    pdf = pd.DataFrame({"v": [1.0, np.nan, 3.0]})
    # 列级
    j = client.colIsin(df, "v", [nan])
    p = pdf["v"].isin([nan]).tolist()
    assert j == p, f"colIsin([NaN]): jian={j} pandas={p}"
    # 列级混合值
    j2 = client.colIsin(df, "v", [nan, 3.0])
    p2 = pdf["v"].isin([nan, 3.0]).tolist()
    assert j2 == p2, f"colIsin([NaN,3]): jian={j2} pandas={p2}"
    # 行级 isin
    j3 = client.isin(df, [nan, 3.0])
    p3 = pdf.isin([nan, 3.0]).any(axis=1).tolist()
    assert j3 == p3, f"isin([NaN,3]): jian={j3} pandas={p3}"
    close_client()


def test_d68_nunique正负零等价对齐pandas():
    """±0.0 数值等价计 1(pandas nunique([0,-0,1])=2);若 HashSet 拿 Double 原始键去重会错计 3。"""
    client = get_client()
    df = make_df(["v"], [[0.0], [-0.0], [1.0]])
    j = int(client.stat(df, "v", "nunique"))
    p = int(pd.Series([0.0, -0.0, 1.0]).nunique())
    assert j == p == 2, f"nunique ±0.0: jian={j} pandas={p}(期望均 2)"
    close_client()


# ======================== d69-d73:NaN 边界与负索引补网 ========================
# 主生成器 _dfs 系全部 allow_nan=False,sortBy/rank/clip/nlargest 的 NaN 行为零对照;
# slice 的负索引/空区间也需覆盖(桥的负索引分支要有 Python 测试触达)。以下 d69-d73 补齐。

_dfs_nan_v = (
    st.lists(st.one_of(st.floats(min_value=-50, max_value=50, allow_nan=False),
                       st.just(float("nan"))), min_size=1, max_size=20)
    .map(lambda vs: make_df(["id", "v"],
                            [[i, (None if math.isnan(v) else v)] for i, v in enumerate(vs)]))
)


@given(df=_dfs_nan_v)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d69_sortBy含NaN_值序对齐pandas(df):
    """含缺失值排序 —— pandas na_position='last'(升/降序 NaN 恒排尾);jian 同语义。

    行序逐行对照(None↔NaN 等价归一)。NaN 之外的值序必须完全一致。
    """
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j = client.sort(df, "v", True)
    p = to_pandas(df).sort_values("v", ascending=True, na_position="last").reset_index(drop=True)
    assert len(j["rows"]) == len(p), "sortBy 含 NaN 行数变了"
    vi = j["columns"].index("v")
    for i, row in enumerate(j["rows"]):
        jv, pv = _norm_val(row[vi]), _norm_val(p["v"].iloc[i])
        if jv is None and pv is None:
            continue  # 双方缺失(都应在尾部连续区)
        assert jv is not None and pv is not None, \
            f"sortBy NaN 落位不一致(第 {i} 行):jian={row[vi]!r} pandas={p['v'].iloc[i]!r}"
        assert abs(float(jv) - float(pv)) <= 1e-9, \
            f"sortBy 含 NaN 值序不一致(第 {i} 行):jian={jv} pandas={pv}"


@given(df=_dfs_nan_v)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d70_rank含NaN_缺失秩对齐pandas(df):
    """含缺失值 rank —— pandas rank() 对 NaN 返 NaN(不参与编号);jian 应同语义。

    逐行对照:非缺失行 rank 值相等(average 法,容差);缺失行双方都缺失。
    """
    if len(df["rows"]) < 2:
        return
    client = get_client()
    j = client.rank(df, "v", "average")
    p = to_pandas(df).rank()
    vi = j["columns"].index("v_rank") if "v_rank" in j["columns"] else j["columns"].index("v")
    p_rank = p["v"]
    for i, row in enumerate(j["rows"]):
        jv, pv = _norm_val(row[vi]), _norm_val(p_rank.iloc[i])
        if jv is None and pv is None:
            continue
        assert jv is not None and pv is not None, \
            f"rank NaN 语义不一致(第 {i} 行):jian={row[vi]!r} pandas={p_rank.iloc[i]!r}"
        assert abs(float(jv) - float(pv)) <= 1e-9, \
            f"rank 含 NaN 值不一致(第 {i} 行):jian={jv} pandas={pv}"


@given(df=_dfs_nan_v)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d71_clip含NaN_缺失透传对齐pandas(df):
    """含缺失值 clip —— pandas clip 对 NaN 透传(NaN 不被边界替换);jian 应同语义。"""
    client = get_client()
    j = client.clip(df, "v", -10, 10, "v_clipped")   # 裁剪结果是追加新列,显式命名便于定位
    p = to_pandas(df).assign(v_clipped=to_pandas(df)["v"].clip(-10, 10))
    vi = j["columns"].index("v_clipped")
    for i, row in enumerate(j["rows"]):
        jv, pv = _norm_val(row[vi]), _norm_val(p["v_clipped"].iloc[i])
        if jv is None and pv is None:
            continue
        assert jv is not None and pv is not None, \
            f"clip NaN 语义不一致(第 {i} 行):jian={row[vi]!r} pandas={p['v_clipped'].iloc[i]!r}"
        assert abs(float(jv) - float(pv)) <= 1e-9, \
            f"clip 含 NaN 值不一致(第 {i} 行):jian={jv} pandas={pv}"


@given(df=_dfs_nan_v)
@settings(max_examples=20, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d72_nlargest含NaN_只取有效值对齐pandas(df):
    """含缺失值 nlargest —— pandas 忽略 NaN 取前 n 大;结果多重集应一致。"""
    if len(df["rows"]) < 3:
        return
    client = get_client()
    n = 2
    # oracle 先行:全 None 列在 pandas 是 object dtype,nlargest 直接 TypeError —— oracle 拒绝输入则 skip
    try:
        p = to_pandas(df).nlargest(n, "v")
    except (TypeError, ValueError):
        return
    j = client.nlargest(df, n, "v")
    assert len(j["rows"]) == len(p), f"nlargest 含 NaN 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
    vi = j["columns"].index("v")
    j_vals = sorted((_norm_val(r[vi]) for r in j["rows"]), key=lambda x: (x is None, x))
    p_vals = sorted((_norm_val(v) for v in p["v"].tolist()), key=lambda x: (x is None, x))
    assert j_vals == p_vals, f"nlargest 含 NaN 值集不一致:jian={j_vals} pandas={p_vals}"


@given(df=_dfs)
@settings(max_examples=30, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_d73_slice负索引与空区间_对齐pandas_iloc(df):
    """负索引/空区间 slice —— pandas iloc 语义:负数从尾数、lo>=hi 为空;jian 同语义。

    负索引分支必须有 Python 测试触达(否则桥的归一化逻辑无回归保护)。
    """
    if not df["rows"]:
        return
    client = get_client()
    n = len(df["rows"])
    cases = [(-2, n), (-1, -1 + 0), (max(0, n - 2), n), (1, 1)]   # 尾部 2 行 / 空 / 头部截取 / 空
    for a, b in cases:
        j = client.slice(df, a, b)
        p = to_pandas(df).iloc[a:b]
        assert len(j["rows"]) == len(p), \
            f"slice[{a}:{b}] 行数不一致:jian={len(j['rows'])} pandas={len(p)}"
        for i, row in enumerate(j["rows"]):
            assert _norm_val(row[0]) == _norm_val(p["id"].iloc[i]), \
                f"slice[{a}:{b}] 第 {i} 行 id 不一致:jian={row[0]} pandas={p['id'].iloc[i]}"


# ═══════════════ 外部 AI 协作复审修复对照(d74-d80)═══════════════
# 修复对应算子在此以 pandas 为 oracle 逐项对照(AGENTS §0.5 红线)。


def test_d74_dropDuplicates正负零与NaN_对齐pandas():
    """±0.0 判重等价 + NaN 判重等价(修复:jian 曾把 +0.0/-0.0 当不同键)。

    pandas drop_duplicates:+0.0 == -0.0 相等、NaN 与 NaN 相等(hash 路径规范化);
    jian 修复后键经 normUniqueKey 归一,同口径(NaN 本就经 Double.equals 规范化判重)。
    """
    client = get_client()
    vals = [0.0, -0.0, float("nan"), float("nan"), 1.0]
    j_rows = client.dropDuplicates(make_df(["v"], [[v] for v in vals]), ["v"])["rows"]
    p = pd.DataFrame({"v": vals}).drop_duplicates(subset=["v"])
    assert len(j_rows) == len(p), f"行数不一致:jian={j_rows} pandas={p['v'].tolist()}"
    for i in range(len(p)):
        assert _norm_val(j_rows[i][0]) == _norm_val(p["v"].iloc[i]), \
            f"第 {i} 行不一致:jian={j_rows[i][0]} pandas={p['v'].iloc[i]}"


def test_d75_mergeAsof缺失键_双方都抛错_对齐pandas():
    """缺失 on 键(NaN/null)→ 双方一致抛错(修复:jian 曾静默容忍并复用/漏过匹配)。

    本机 pandas 1.5.3 实测(oracle):merge_asof 对左或右任一侧键含 null 抛
    ValueError("Merge keys contain null values on ..."),不输出 NaN 行;
    jian 修复后同口径抛 IAE(fail-fast,提示先清洗)。此前的"右列置 NaN"方案
    基于社区传言,与安装版 oracle 不符,已废弃。
    """
    client = get_client()
    left_nan = make_df(["ts", "lv"], [[1.0, "a"], [3.0, "b"], [float("nan"), "c"]])
    right_ok = make_df(["ts", "rv"], [[1.0, "R1"], [2.5, "R2"]])
    # 左键缺失:双方都抛
    with pytest.raises(ValueError):
        pd.merge_asof(to_pandas(left_nan), to_pandas(right_ok), on="ts")
    with pytest.raises(Exception):
        client.mergeAsof(left_nan, right_ok, "ts")
    # 右键缺失:双方都抛
    left_ok = make_df(["ts", "lv"], [[1.0, "a"], [3.0, "b"]])
    right_nan = make_df(["ts", "rv"], [[1.0, "R1"], [float("nan"), "R2"]])
    with pytest.raises(ValueError):
        pd.merge_asof(to_pandas(left_ok), to_pandas(right_nan), on="ts")
    with pytest.raises(Exception):
        client.mergeAsof(left_ok, right_nan, "ts")


def test_d76_pivotTable_first聚合字符串值_对齐pandas():
    """pivotTable first 聚合返回原值(修复:输出列曾硬编码 DOUBLE,String 值强转失败)。"""
    client = get_client()
    df = make_df(["r", "c", "name"],
                 [["r1", "c1", "alice"], ["r1", "c2", "bob"], ["r2", "c1", "carol"]])
    j = client.pivotTable(df, "r", "c", "name", "first")
    p = pd.pivot_table(to_pandas(df), index="r", columns="c", values="name",
                       aggfunc="first")
    jcols = j["columns"]
    assert "r" in jcols and "c1" in jcols and "c2" in jcols, f"jian 列异常:{jcols}"
    for i, r in enumerate(["r1", "r2"]):
        for c in ["c1", "c2"]:
            jv = _norm_val(j["rows"][i][jcols.index(c)])
            pv = _norm_val(p.loc[r, c])
            assert jv == pv, f"({r},{c}) jian={jv!r} pandas={pv!r}"


def test_d77_pivotTable_count聚合_对齐pandas():
    """pivotTable count 聚合值对齐(pandas count → 计数;jian 修复后 LONG 输出同值)。"""
    client = get_client()
    df = make_df(["r", "c", "v"],
                 [["r1", "x", 1.0], ["r1", "x", 2.0], ["r2", "x", 3.0], ["r2", "y", 4.0]])
    j = client.pivotTable(df, "r", "c", "v", "count")
    p = pd.pivot_table(to_pandas(df), index="r", columns="c", values="v",
                       aggfunc="count")
    jcols = j["columns"]
    for i, r in enumerate(["r1", "r2"]):
        for c in ["x", "y"]:
            jv = _norm_val(j["rows"][i][jcols.index(c)])
            pv = _norm_val(p.loc[r, c])
            assert jv == pv, f"({r},{c}) jian={jv!r} pandas={pv!r}"


def test_d78_大整数与DOUBLE列混型比较_有意差异声明():
    """[有意差异,§10.16#17] 整数(> 2^53)× DOUBLE 列混型比较:jian 精确,pandas 折叠。

    NumPy 把 Python int 标量 cast 成 float64 再比较(9007199254740993 → 折成 2^53),
    故 pandas 判 2^53 == 2^53+1 为 True(下方断言锁定 oracle 行为备查);
    jian 遵循 LONG"大 ID 不丢精度"契约(AGENTS §3.5 / DType javadoc),混型走
    compareLongVsDouble 精确路径判 False。已在 doc/00-overview.md §10.16 显式声明。
    修复背景:原实现 doubleValue 直比与 pandas 一样折叠,但那是"两边都错";
    修复后 jian 精确、pandas 折叠,方向按方案 B 声明而非对齐。
    """
    client = get_client()
    df = make_df(["v"], [[9007199254740992.0]])   # 2^53
    # oracle 行为备查:NumPy 标量折叠,判相等为 True(精度丢失)
    p_eq = (pd.Series([9007199254740992.0]) == 9007199254740993).tolist()
    assert p_eq[0] is True
    # jian 有意差异:精确判定(2^53 ≠ 2^53+1;修复前 doubleValue 直比与 NumPy 一样折叠)
    j_eq = client.colCmp(df, "v", "==", 9007199254740993)
    assert j_eq[0] is False, f"jian 应精确判不等:{j_eq}"
    j_lt = client.colCmp(df, "v", "<", 9007199254740993)
    assert j_lt[0] is True, "2^53 < 2^53+1 应为 True"
    j_gt = client.colCmp(df, "v", ">", 9007199254740991)
    assert j_gt[0] is True, "2^53 > 2^53-1 应为 True"


def test_d79_resample整数列sum_对齐pandas():
    """resample 整数列 sum 值对齐(pandas int64 累计;jian 修复后 long 累计,LONG 输出)。"""
    client = get_client()
    ts = ["2026-01-01T01:00:00", "2026-01-01T02:00:00", "2026-01-02T01:00:00"]
    vs = [2, 3, 4]
    j = client.resample(make_df(["ts", "v"], [[t, v] for t, v in zip(ts, vs)]), "ts", "1D", "v", "sum")
    p = pd.DataFrame({"v": vs}, index=pd.to_datetime(ts)).resample("D").sum()["v"]
    assert len(j["rows"]) == len(p), f"行数不一致:jian={j['rows']} pandas={p.tolist()}"
    for i, pv in enumerate(p):
        assert _norm_val(j["rows"][i][1]) == _norm_val(pv), \
            f"第 {i} 桶不一致:jian={j['rows'][i][1]} pandas={pv}"


def test_d80_桥LONG列null往返不失真_S0修复():
    """[桥修复] LONG null 掩码:None 过桥进 jian 再回 Python 仍是 None。

    修复前:桥把 LONG 列 null 写成 0L 且无 nullMask(isNull 恒 false)——
    roundtrip 变 0、fillna/dropna 等一切 LONG 缺失维度的对照全部失真。
    """
    client = get_client()
    df = make_df(["x"], [[1], [None], [3]])
    out = client.head(df, 10)
    vals = [r[0] for r in out["rows"]]
    assert vals[0] == 1 and vals[1] is None and vals[2] == 3, f"LONG null 往返失真:{vals}"
    # isNull 语义经 fillna 交叉验证:jian 填 0 后值序与 pandas 一致
    j = client.fillna(df, 0)["rows"]
    p = to_pandas(df).fillna(0)["x"].tolist()
    for i in range(3):
        assert _norm_val(j[i][0]) == _norm_val(p[i]), f"fillna 第 {i} 行:jian={j[i][0]} pandas={p[i]}"
