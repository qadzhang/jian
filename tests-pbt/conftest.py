"""conftest.py —— tests-pbt/ 的 pytest 配置。"""
import sys
from pathlib import Path

# 让 pytest 能 import harness
_HARNESS = Path(__file__).parent / "harness"
if str(_HARNESS) not in sys.path:
    sys.path.insert(0, str(_HARNESS))
