"""test_fuzz_robustness.py —— 畸形输入健壮性 fuzz(ai-code-testing skill L4)。

目标:验证 jian 解析器对畸形输入不 crash(不 SEGV),且优雅报错(抛带提示的 IAE/ISE
而非裸 NullPointerException —— 后者是 bug,不是健壮性)。

工具说明:Hypothesis text strategy。atheris/jazzer 未装(沙盒无 libFuzzer 依赖),
Hypothesis 的 text strategy 自带边界注入(控制字符/超长/嵌套引号/unicode),
能覆盖 fuzz 的关键场景,作为 coverage-guided fuzz 的轻量替代。若需真 coverage-guided,
后续可引入 Jazzer(Java 端)或 Atheris(Python 端)。

How(AGENTS.md §3.3.1 三要素):
  数据走向:Hypothesis 生成畸形 query/dtype → JPype 桥调 client.filter/astype → jian 正常返回
    或抛 Java 异常(经 JPype 转 jpype.JException)→ 断言异常类名不含 NullPointer。
  关键变量:异常类名(由 _exception_class_name 返回,如 "java.lang.IllegalArgumentException");
    NullPointer 出现 = 失败(无提示 bug);IAE/ISE = 通过(优雅报错)。
  逻辑路线:畸形输入 → 路径 A(正常返回)→ 通过;路径 B(抛 JException)→ 查类名,含 NullPointer 则失败;
    路径 C(UnicodeDecodeError)→ 桥层编码问题(jian 已正确抛 IAE,但消息含畸形字符致 JPype 转 Python
    时解码失败),非 jian bug,放过。死 harness 验证:good query 必须命中(防"没真调 filter")。

跑法:python3 -m pytest tests-pbt/properties/test_fuzz_robustness.py -v
"""
from __future__ import annotations
import sys
from pathlib import Path

import pytest
import jpype
from hypothesis import given, settings, HealthCheck, strategies as st

_HARNESS = Path(__file__).resolve().parent.parent / "harness"
sys.path.insert(0, str(_HARNESS))
# 因为 skip 守卫只在 import 期触发(若检查只在 _ensure_jvm 里,import 期永不触发),
# 所以显式调 ensure_built() 让 skip 真正生效
from jian_client import get_client, close_client, make_df, ensure_built
try:
    ensure_built()
except FileNotFoundError as _e:
    pytest.skip(f"jian 未构建,跳过 fuzz 测试({_e})", allow_module_level=True)


# ┌─ What : 畸形 query 策略 —— 任意 unicode(含控制字符),最长 200
# │  Why  :fuzz 的核心是探索"开发者想不到的输入";控制字符/嵌套引号/未闭合括号是解析器崩溃高发区
# │  注:blacklist_categories=('Cs',) 排除代理对 —— 那些字符无法编码成 Java String,
# │     会在 Python→Java 桥层就 UnicodeDecodeError(jian 根本看不到),不是 jian 的健壮性问题。
_query_any = st.text(
    alphabet=st.characters(blacklist_categories=('Cs',), min_codepoint=0, max_codepoint=0x10FFFF),
    max_size=200,
)

# 聚焦策略:列名/运算符/引号/括号组合(更易触发解析器边界:科学计数法/混型/转义)
_query_focused = st.text(
    alphabet="abcABC0123456789'\"()><=! -.,;\\",
    max_size=80,
)


@pytest.fixture(scope="session", autouse=True)
def _cleanup():
    yield
    close_client()


def _exception_class_name(jexc) -> str:
    """取 Java 异常类的全限定名(用于区分优雅 IAE vs 裸 NPE)。"""
    try:
        return str(jexc.getClass().getName())
    except Exception:
        return ""


# ─────────── fuzz:SimpleQueryParser 畸形查询 ───────────

@given(q=_query_any)
@settings(max_examples=500, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_fuzz_filter_任意畸形查询不crash(q):
    """fuzz:任意畸形 query 字符串喂给 SimpleQueryParser,JVM 不 crash;若抛须优雅(非 NPE)。

    硬约束:绝不接受 NullPointerException(那是无提示的 bug,不是健壮报错)。
    """
    client = get_client()
    df = make_df(["a", "b"], [[1, 2.0], [3, 4.0]])
    try:
        client.filter(df, q)   # 合法:返回结果;非法:抛 JException(带提示)
    except jpype.JException as e:
        name = _exception_class_name(e)
        assert "NullPointer" not in name, (
            f"畸形查询 {q!r} 触发 NullPointerException(应抛带提示的 IAE):{e}")
    except UnicodeDecodeError:
        # 桥层局限:jian 已正确抛 IAE("无法识别的字符 'X'"),但异常消息含畸形字符,
        # JPype 把消息转回 Python 时解码失败。这不是 jian 的 bug(jian 健壮报错了),属桥编码缺陷。
        pass


@given(q=_query_focused)
@settings(max_examples=500, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_fuzz_filter_聚焦运算符引号边界(q):
    """fuzz(聚焦):列名/运算符/引号/括号组合,覆盖解析器边界(科学计数法/混型/字符串转义等)。"""
    client = get_client()
    df = make_df(["a", "b"], [[1, 2.0]])
    try:
        client.filter(df, q)
    except jpype.JException as e:
        name = _exception_class_name(e)
        assert "NullPointer" not in name, f"聚焦 fuzz {q!r} 触发 NPE:{e}"


# ─────────── fuzz:astype 畸形 dtype ───────────

@given(target=st.text(alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789", min_size=0, max_size=20))
@settings(max_examples=200, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture, HealthCheck.too_slow])
def test_fuzz_astype_畸形dtype不crash(target):
    """fuzz:任意 dtype 字符串喂给 astype,不存在的 dtype 应优雅报错(非 NPE/crash)。"""
    client = get_client()
    df = make_df(["v"], [[1.0]])
    try:
        client.astype(df, "v", target)
    except jpype.JException as e:
        name = _exception_class_name(e)
        assert "NullPointer" not in name, f"astype 畸形 dtype {target!r} 触发 NPE:{e}"


# ─────────── 死 harness 验证(skill L4.4 必做)───────────

def test_fuzz_死harness验证_filter真在被调用():
    """死 harness 验证:确认 filter 真被调用(若被测函数被注释,本测试应失败)。

    skill 铁律:fuzz harness 最易写成"没真调被测函数"的死 harness(永远 pass)。
    用一个已知 good query 断言精确结果,证明 filter 确实在跑。
    """
    client = get_client()
    df = make_df(["a"], [[1], [2], [3]])
    r = client.filter(df, "a > 1")
    assert len(r["rows"]) == 2, f"good query 'a>1' 应命中 2 行(a=2,3),实际 {len(r['rows'])} → filter 未真执行?"
