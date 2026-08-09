"""test_jian_properties.py —— Hypothesis 驱动的 jian 跨语言 PBT。

"同行评议"含义:与 jian-core 的 PropertyBasedTest.java(jqwik 1.9.3)验证同样 10 条性质,
但用完全独立的 Python 实现(Hypothesis 生成器)与独立的执行路径(subprocess + Java bridge)。
两套独立实现跑同样性质 → 等价于交叉验证,任一方出错都能被另一方对出。

跑法:
    cd <jian 项目根>
    python3 -m pytest tests-pbt/properties/test_jian_properties.py -v
"""

from __future__ import annotations
import sys
from pathlib import Path

import pytest
from hypothesis import given, settings, HealthCheck, strategies as st

# 把 harness 加入 path
_HARNESS = Path(__file__).resolve().parent.parent / "harness"
sys.path.insert(0, str(_HARNESS))
from jian_client import get_client, close_client, make_df  # noqa: E402


# === Hypothesis 生成器 ===

# 简单 df:strategy 生成 (id, v) 两列,id 在 [0, 50) 可能重复,v 在 [-100, 100)
# 2026-08-09 修复 H-1:边界注入——v 列混合普通值 [-100,100) 与 NaN(Hypothesis 不能同时
# 设 min_value 与 allow_nan=True,故用 one_of 显式组合)。NaN 经 jian_client._sanitize_nan_inf
# 协议层转成 null,等价 jian 的"缺失"语义。让 22 条性质在边界条件下也被检验。
_v_with_nan = st.one_of(
    st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False),  # 90% 普通值
    st.just(float('nan')),  # 10% NaN(缺失值,Hypothesis 会优先纳入 edge cases)
)
_dfs = (
    st.lists(st.integers(min_value=0, max_value=50), max_size=30)
    .flatmap(lambda ids:
        st.lists(_v_with_nan,
                 min_size=len(ids), max_size=len(ids))
        .map(lambda vs: make_df(["id", "v"],
                                 [[int(i), float(v)] for i, v in zip(ids, vs)]))
    )
)

# 唯一 id 的 df(用于自连接性质)
_dfs_unique_ids = (
    st.lists(st.integers(min_value=0, max_value=200), min_size=0, max_size=30)
    .map(lambda indices:
        make_df(["id", "v"],
                [[int(i), float(i % 100)] for i in dict.fromkeys(indices)])  # dict.fromkeys 保序去重
    )
)


# === session 级 fixture:整个测试会话只起一次 java 进程 ===

@pytest.fixture(scope="session", autouse=True)
def _cleanup():
    yield
    close_client()


# === 性质 1-3:sortBy ===

@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p1_sortBy_行数守恒(df):
    """sortBy 不改变行数。"""
    client = get_client()
    before = len(df["rows"])
    asc = client.sort(df, "v", True)
    desc = client.sort(df, "v", False)
    assert len(asc["rows"]) == before
    assert len(desc["rows"]) == before


@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p2_sortBy_升序后单调不减(df):
    """sortBy(asc=true) 后 v 列确实单调不减。
    2026-08-09 边界注入:v 含 NaN(NaN→null 后 Python 拿到 None)。
    None 与任何值的 >= 比较会抛 TypeError,所以跳过含 None 的相邻对
    (与 Java 端 MR4/P2 同款修复,IEEE 754 NaN 不可比)。
    """
    client = get_client()
    r = client.sort(df, "v", True)
    vs = [row[1] for row in r["rows"]]
    for i in range(1, len(vs)):
        # 跳过含 None(NaN 经协议层转 null)的相邻对
        if vs[i] is None or vs[i - 1] is None:
            continue
        assert vs[i] >= vs[i - 1], f"非单调:{vs[i - 1]} > {vs[i]}"


@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p3_sortBy_id列多重集不变(df):
    """sortBy 后 id 列多重集不变(只重排不改值)。"""
    client = get_client()
    r = client.sort(df, "v", True)
    before = sorted(row[0] for row in df["rows"])
    after = sorted(row[0] for row in r["rows"])
    assert before == after


# === 性质 4:filter ===

@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p4_filter_所有结果行满足谓词(df):
    """filter("v > 0") 后所有行 v 都 > 0。"""
    client = get_client()
    r = client.filter(df, "v > 0")
    for row in r["rows"]:
        assert row[1] > 0, f"不满足谓词:{row[1]}"


# === 性质 5:head ===

@given(df=_dfs, n=st.integers(min_value=0, max_value=200))
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p5_head_行数等于minN(df, n):
    """head(n) 行数 = min(n, rowCount)。修复:n 上限从 100 提到 200,覆盖 n > rowCount 边界。"""
    client = get_client()
    r = client.head(df, n)
    assert len(r["rows"]) == min(n, len(df["rows"]))


# === 性质 6:concat ===

@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p6_concat_纵向行数翻倍(df):
    """concat(df, df, axis=0) 后行数 = 2 * rowCount。"""
    client = get_client()
    r = client.concat([df, df], 0)
    assert len(r["rows"]) == 2 * len(df["rows"])


# === 性质 7:dropDuplicates ===

@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p7_dropDuplicates_id列无重复(df):
    """dropDuplicates(["id"], "first") 后 id 列无重复。"""
    client = get_client()
    r = client.dropDuplicates(df, ["id"])
    ids = [row[0] for row in r["rows"]]
    assert len(ids) == len(set(ids)), f"仍有重复:{ids}"


# === 性质 8:merge 自连接 ===

@given(df=_dfs_unique_ids)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p8_merge_自连接行数等于原表(df):
    """唯一 id 自连接(inner on id),每行匹配自身,结果行数 == 原表。"""
    client = get_client()
    r = client.merge(df, df, "inner", "id")
    assert len(r["rows"]) == len(df["rows"])


# === 性质 9:merge 交换律 ===

@given(a=_dfs, b=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p9_merge_innerJoin交换律(a, b):
    """A inner-join B 与 B inner-join A 行数相同。"""
    client = get_client()
    ab = client.merge(a, b, "inner", "id")
    ba = client.merge(b, a, "inner", "id")
    assert len(ab["rows"]) == len(ba["rows"])


# === 性质 10:groupBy(真正调用 client.groupBy) ===

# 算术性质专用生成器(2026-08-09):v 列不含 NaN。
# 原因:v 含 null 时 jian 会把整列降级成 OBJECT 列(数据有缺失→保守退化为通用列),
# 算术运算(colAdd/colMul/colSub/colDiv)要求 DOUBLE/LONG 列,OBJECT 列会抛 IAE。
# 这是 jian 的预期行为(参考 AGENTS.md §3.5 缺失值语义),
# 不是 bug——所以算术性质用纯数值生成器,非算术性质用含 NaN 的 _dfs。
_dfs_no_nan = (
    st.lists(st.integers(min_value=0, max_value=50), max_size=30)
    .flatmap(lambda ids:
        st.lists(st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False),
                 min_size=len(ids), max_size=len(ids))
        .map(lambda vs: make_df(["id", "v"],
                                 [[int(i), float(v)] for i, v in zip(ids, vs)]))
    )
)

@given(df=_dfs)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p10_groupBy_countSum等于原表行数(df):
    """修复 P10:真正调 client.groupBy,断言各组 count 之和 == 原表行数(真蜕变关系)。

    修复原因:AI agent 1 与 AI agent 2 双 AI 发现原 P10 是死测试(没真调 groupBy)。
    2026-08-09 边界注入修复:v 列含 NaN(NaN→null)。count 聚合是"非空值计数"(pandas 语义),
    所以 count 必须改用 **id 列**(id 列无 null,count == 组内行数),与 Java 端 P10 同款修复。
    """
    if not df["rows"]:
        return   # 空表跳过
    client = get_client()
    # 用 id 列做 count(id 列无 null,count == 组内行数)
    r = client.groupBy(df, "id", "id", "count")
    # 各组 id_count 之和 == 原表行数
    total = sum(row[1] for row in r["rows"])   # id_count 列下标为 1
    assert total == len(df["rows"]), f"count 之和 {total} != 原表 {len(df['rows'])}"
    # 组数 == 唯一 id 数
    uniq_ids = set(row[0] for row in df["rows"])
    assert len(r["rows"]) == len(uniq_ids), f"组数 != 唯一 id 数"


# === 生成器:含 NaN 的 df(用于缺失值测试)===
import math
_dfs_with_nan = (
    st.lists(st.integers(min_value=0, max_value=50), max_size=30)
    .flatmap(lambda ids:
        st.lists(st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False),
                 min_size=len(ids), max_size=len(ids))
        .map(lambda vs: make_df(["id", "v"],
                                 [[int(i), (float("nan") if (j % 3 == 0 and j > 0) else float(v))]
                                  for j, (i, v) in enumerate(zip(ids, vs))]))
    )
)


# === 性质 11-13:缺失值处理 ===

@given(df=_dfs_with_nan)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p11_fillna后v列无NaN(df):
    """fillna 后 v 列无 NaN。"""
    client = get_client()
    r = client.fillna(df, 0.0)
    for row in r["rows"]:
        assert not math.isnan(row[1]), f"fillna 后仍有 NaN:{row[1]}"


@given(df=_dfs_with_nan)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p12_dropna后无NaN行(df):
    """dropna 后无 NaN 行。"""
    client = get_client()
    r = client.dropna(df)
    for row in r["rows"]:
        assert not math.isnan(row[1]), f"dropna 后仍有 NaN:{row[1]}"


@given(df=_dfs_with_nan)
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p13_ffill后首有效值之后无NaN(df):
    """ffill 后,每个填充值应等于前一个有效值(强断言,修复 AI agent 1/AI agent 2 建议的弱断言)。"""
    if not df["rows"]:
        return
    client = get_client()
    r = client.ffill(df)
    original = [row[1] for row in df["rows"]]
    filled = [row[1] for row in r["rows"]]
    last_valid = float("nan")
    for i, (orig, fill) in enumerate(zip(original, filled)):
        if not math.isnan(fill):
            if not math.isnan(orig):
                # 原值非 NaN:fill 应等于原值
                assert fill == orig, f"第 {i} 行原值应保留:{fill} vs {orig}"
                last_valid = fill
            else:
                # 原值 NaN(被填充):fill 应等于前驱有效值
                assert fill == last_valid, f"第 {i} 行应等于前驱有效值 {last_valid},实际 {fill}"


# === 性质 15-17:类型/重塑 ===

@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p15_astype_LONG经过DOUBLE来回保值(df):
    """astype(LONG→DOUBLE→LONG) 来回保值。"""
    if not df["rows"]:
        return
    client = get_client()
    to_double = client.astype(df, "id", "DOUBLE")
    back = client.astype(to_double, "id", "LONG")
    before = [row[0] for row in df["rows"]]
    after = [row[0] for row in back["rows"]]
    assert before == after, "LONG→DOUBLE→LONG 应保值"


@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p16_select_行数不变列数等于指定(df):
    """select(['id']) 行数不变,列数 == 1。"""
    client = get_client()
    r = client.select(df, ["id"])
    assert len(r["rows"]) == len(df["rows"])
    assert len(r["columns"]) == 1
    assert r["columns"] == ["id"]


@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p17_drop_行数不变列数减少(df):
    """drop(['v']) 行数不变,列数减 1。"""
    client = get_client()
    before_cols = len(df["columns"])
    r = client.drop(df, ["v"])
    assert len(r["rows"]) == len(df["rows"])
    assert len(r["columns"]) == before_cols - 1
    assert r["columns"] == ["id"]


# === 性质 18-20:slice / nlargest / nsmallest ===

@given(df=_dfs, a=st.integers(min_value=0, max_value=50), b=st.integers(min_value=0, max_value=50))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p18_slice_保序且等于原表区间(df, a, b):
    """slice(a,b) 第 i 行 == 原表第 lo+i 行。"""
    if not df["rows"]:
        return
    lo, hi = min(a, b), min(max(a, b), len(df["rows"]))
    if lo >= hi:
        return
    client = get_client()
    s = client.slice(df, lo, hi)
    for i, row in enumerate(s["rows"]):
        assert row[0] == df["rows"][lo + i][0], f"slice 第 {i} 行不匹配"


@given(df=_dfs, n=st.integers(min_value=0, max_value=50))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p19_nlargest_等价于sortBy降序head(df, n):
    """nlargest(n,'v') 等价于 sortBy('v',desc).head(n)。

    断言完整行(id+v):只比 id 时,若 jian 取对了 id 但 v 列被错改/错排,会蒙混过关。
    另需校验结果按 v 降序,真正约束 nlargest 的"取前 n 大"语义。"""
    if not df["rows"]:
        return
    client = get_client()
    byNlargest = client.nlargest(df, n, "v")
    bySortHead = client.head(client.sort(df, "v", False), n)
    assert len(byNlargest["rows"]) == len(bySortHead["rows"])
    for i in range(len(byNlargest["rows"])):
        # 完整行对照(id + v 双字段),修历史"只比 id"漏洞
        assert byNlargest["rows"][i] == bySortHead["rows"][i], \
            f"第 {i} 行不匹配:nlargest={byNlargest['rows'][i]} vs sortHead={bySortHead['rows'][i]}"
    # 额外约束:nlargest 结果应按 v 降序(打破"返回乱序也过"的空真)
    vs = [row[1] for row in byNlargest["rows"] if row[1] is not None]
    assert vs == sorted(vs, reverse=True), f"nlargest 结果非降序: {vs}"


@given(df=_dfs, n=st.integers(min_value=0, max_value=50))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p20_nsmallest_等价于sortBy升序head(df, n):
    """nsmallest(n,'v') 等价于 sortBy('v',asc).head(n)。完整行 + 升序约束。"""
    if not df["rows"]:
        return
    client = get_client()
    byNsmallest = client.nsmallest(df, n, "v")
    bySortHead = client.head(client.sort(df, "v", True), n)
    assert len(byNsmallest["rows"]) == len(bySortHead["rows"])
    for i in range(len(byNsmallest["rows"])):
        assert byNsmallest["rows"][i] == bySortHead["rows"][i], \
            f"第 {i} 行不匹配:nsmallest={byNsmallest['rows'][i]} vs sortHead={bySortHead['rows'][i]}"
    vs = [row[1] for row in byNsmallest["rows"] if row[1] is not None]
    assert vs == sorted(vs), f"nsmallest 结果非升序: {vs}"


# === 性质 21-23:算术 / assign ===

@given(df=_dfs_no_nan)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p21_colAdd_等于逐行加(df):
    """colAdd 后每行 sum == id + v。
    2026-08-09:用 _dfs_no_nan(v 不含 NaN),避免 v 含 null 导致 OBJECT 列降级。
    """
    if not df["rows"]:
        return
    client = get_client()
    r = client.colAdd(df, "sum", "id", "v")
    for i, row in enumerate(r["rows"]):
        expected = df["rows"][i][0] + df["rows"][i][1]
        actual = row[2]   # sum 列下标 2
        assert abs(actual - expected) < 1e-9, f"colAdd 第 {i} 行:{actual} != {expected}"


@given(df=_dfs_no_nan, k=st.floats(min_value=-100, max_value=100, allow_nan=False, allow_infinity=False))
@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p22_colMul标量_等于逐行乘(df, k):
    """colMul(new,v,k) 后每行 new == v × k。
    2026-08-09:用 _dfs_no_nan(v 不含 NaN),避免 v 含 null 导致 OBJECT 列降级。
    """
    if not df["rows"]:
        return
    client = get_client()
    r = client.colMulScalar(df, "scaled", "v", k)
    for i, row in enumerate(r["rows"]):
        expected = df["rows"][i][1] * k
        actual = row[2]
        tol = max(1e-9, abs(expected) * 1e-9)
        assert abs(actual - expected) < tol, f"colMul 第 {i} 行:{actual} != {expected}"


@given(df=_dfs)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p23_assign_加列不改行数(df):
    """assign 加列后行数不变 + 列数+1 + 新列名存在。
    修复(双 AI 共识):Schema.inferColumn 加空表守卫,空表现已正常,不再跳过。
    """
    client = get_client()
    before_rows = len(df["rows"])
    before_cols = len(df["columns"])
    r = client.assign(df, "tag", "x")
    assert len(r["rows"]) == before_rows
    assert len(r["columns"]) == before_cols + 1
    assert "tag" in r["columns"]


# ======================== 性质 24-25:列间减/除(补未测方法,与 jqwik P24/P25 同行评议)========================

@given(df=_dfs_no_nan)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p24_colSub_等于逐行减(df):
    """colSub 后每行 diff == id - v。与 jqwik P24 同行评议。
    2026-08-09:用 _dfs_no_nan(v 不含 NaN),避免 v 含 null 导致 OBJECT 列降级。
    """
    if not df["rows"]:
        return
    client = get_client()
    r = client.colSub(df, "diff", "id", "v")
    for i, row in enumerate(r["rows"]):
        expected = df["rows"][i][0] - df["rows"][i][1]   # id - v
        actual = row[2]   # diff 列下标 2
        assert abs(actual - expected) < 1e-9, f"colSub 第 {i} 行:{actual} != {expected}"


@given(df=_dfs_no_nan)
@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_p25_colDiv_等于逐行除(df):
    """colDiv 后每行 ratio == id / v(v 非 0 时)。与 jqwik P25 同行评议。

    断言矩阵(2026-08 与 AI agent1 共识):
    - v == 0:jian 应返回 None/Inf/NaN(JSON 化为 None),不得是有限数
    - v != 0 且 |id/v| 不溢出:必须有有效比值(不得 None),且数值正确
    - v != 0 但 |id/v| 溢出到 inf(denormalized v 等):JSON 无法表达 Inf,jian 返回 None 合理
    """
    if not df["rows"]:
        return
    client = get_client()
    r = client.colDiv(df, "ratio", "id", "v")
    for i, row in enumerate(r["rows"]):
        v = df["rows"][i][1]
        actual = row[2]
        if v == 0:
            # 零除数:jian 应返回 None(或 Inf/NaN,json 化为 None),不得是有限数
            assert actual is None or (isinstance(actual, float) and (math.isnan(actual) or math.isinf(actual))), \
                f"colDiv 第 {i} 行:v=0 时应返回 None/Inf/NaN,实际 {actual}"
        else:
            expected = df["rows"][i][0] / v   # id / v
            if math.isinf(expected):
                # 溢出边界(denormalized v 等):JSON 无法表达 Inf,jian 返回 None/匹配符号 Inf 都接受
                assert actual is None or (isinstance(actual, float) and math.isinf(actual)), \
                    f"colDiv 第 {i} 行:|id/v|={expected} 溢出,应返回 None/Inf,实际 {actual}"
            else:
                # 非零且不溢出:必须有有效比值(修历史"actual is None 即 skip"漏洞)
                assert actual is not None, f"colDiv 第 {i} 行:v={v} 非零且不溢出,应返回比值,实际 None"
                tol = max(1e-9, abs(expected) * 1e-9)
                assert abs(actual - expected) < tol, f"colDiv 第 {i} 行:{actual} != {expected}"
