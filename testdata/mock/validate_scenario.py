"""Validate mock-data artifacts against the 1109 simulation-data contract v1.3.

Covered sections:

* §3   ``scenario.yaml`` — fourteen fields, types, units and family rules.
* §4   ``manifest.json`` — fourteen fields, six of them filled by the runner.
* §5   ``metrics.json``  — eleven metric fields and the two-column split.
* §8   WS envelope and order-state wording — four rows.
* §13  ``param_sources`` coverage, three allowed source classes, fixed labels.

Every check prints ``PASS`` or ``FAIL``. Exit code is 0 only when nothing failed.
The checker is read-only: it never rewrites the artifacts it inspects.

Usage::

    python validate_scenario.py --scenarios testdata/mock/scenarios \
        --runs testdata/mock/_generated --replay testdata/mock/replay

Only the standard library is required; PyYAML is used when present, otherwise a
small parser for the flat ``key: value`` layout of the contract is used.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable

try:  # PyYAML is convenient but not part of the contract's toolchain
    import yaml  # type: ignore
except ImportError:  # pragma: no cover - exercised on a bare interpreter
    yaml = None


FAMILIES = (
    "normal_straight",
    "bend_turn",
    "speed_change",
    "wind_gust",
    "uav_low_battery",
    "cargo_late",
)
MODES = ("live", "offline")
SPEED_PROFILES = ("normal", "overspeed")
EVIDENCE_CLASSES = ("simulation_closed_loop", "externally_anchored")
SOURCE_CLASSES = ("measured", "standard", "design_assumption")

SCENARIO_FIELDS = (
    "family",
    "seed",
    "mode",
    "t0_ms",
    "path_start_m",
    "duration_sec",
    "speed_limits_ms",
    "speed_profile",
    "tick_millis",
    "truth_source",
    "noise_deg",
    "mmsi",
    "pred_version",
    "param_sources",
)
MANIFEST_FIELDS = (
    "baseline_commit",
    "tick_millis",
    "path_start_m",
    "pred_version",
    "speed_profile",
    "seed",
    "truth_source",
    "noise_deg",
    "param_sources",
    "evidence_class",
    "rows",
    "bytes",
    "sha256",
    "command",
)
METRIC_FIELDS = (
    "nrmse_along",
    "nrmse_cross",
    "coverage_2sigma_along",
    "coverage_2sigma_cross",
    "nees",
    "meet_error_m",
    "tau_grid_sec",
    "bbox_fallback_samples",
    "rows_pred_error_sample",
    "evidence_class",
    "by_evidence_class",
)
NON_EMPTY_METRICS = (
    "nrmse_along",
    "nrmse_cross",
    "coverage_2sigma_along",
    "coverage_2sigma_cross",
    "nees",
    "meet_error_m",
)

# §2 three-segment limits for the ``normal`` family, in metres per second.
NORMAL_SPEED_LIMITS = "0,2000,4.5;2000,4000,4.5;4000,6003.4,2.5"
TRUTH_MAX_S_M = 6003.3510
TRUTH_HEADER = ("ts_ms", "s_m", "lon", "lat", "sog_ms", "cog_deg")
LIVE_DURATION_SEC = 1800
OFFLINE_DURATION_SEC = 1200
LIVE_PATH_START_M = 0.0
OFFLINE_PATH_START_M = 1000.0
FIXED_TICK_MILLIS = 1000
LIVE_MMSI = "123456789"
OFFLINE_TAU_LIMIT_SEC = 1200

WS_FRAME_TYPES = (
    "ship_traj",
    "drone_state",
    "rendezvous",
    "alarm",
    "order_state",
    "plan_track",
)
ORDER_STATUS_ZH = (
    "待受理",
    "已受理待唤醒",
    "待调度",
    "调度中",
    "已派发",
    "去程中",
    "末段交接中",
    "返航中",
    "已完成",
    "已拒绝",
    "失败",
    "异常终止",
)
FORBIDDEN_STATUS_WORDS = ("COMPLETED", "FINISHED", "PENDING", "DISPATCHED", "OUTBOUND")

# §13 「必须标注的参数」 with tolerated spellings.
PARAM_ALIASES = {
    "sog_ms": ("sog_ms", "sog"),
    "speed_limits_ms": ("speed_limits_ms", "segment_speed_limits"),
    "speed_profile": ("speed_profile",),
    "noise_deg": ("noise_deg", "noise"),
    "sigmaEM": ("sigmaEM", "sigma_em", "sigmaEM_m"),
    "tauESec": ("tauESec", "tau_e_sec", "tauESec_s"),
    "alphaAlongMs": ("alphaAlongMs", "alpha_along_ms"),
    "wind_gust_amplitude": ("wind_gust_amplitude", "wind_gust_ms", "wind_gust", "风突变幅度"),
    "tick_millis": ("tick_millis", "tickMillis"),
    "path_start_m": ("path_start_m", "pathStartM"),
    "centerline_points": ("centerline_points", "centerline_v3_points", "centerline", "中心线十三点"),
}

# §13 fixed labels: these keys may not be recorded as anything else.
FIXED_SOURCE_CLASSES = {
    "sog_ms": "design_assumption",
    "noise_deg": "design_assumption",
    "sigmaEM": "design_assumption",
    "tauESec": "design_assumption",
    "alphaAlongMs": "design_assumption",
    "wind_gust_amplitude": "design_assumption",
    "centerline_points": "measured",
}

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SPEED_LIMIT_PATTERN = re.compile(r"^\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*$")


class Report:
    """Collects PASS/FAIL lines and keeps a machine-readable copy."""

    def __init__(self) -> None:
        self.items: list[dict[str, Any]] = []

    def check(self, scope: str, target: str, problems: Iterable[str]) -> bool:
        problems = [item for item in problems if item]
        self.items.append(
            {
                "scope": scope,
                "target": target,
                "status": "FAIL" if problems else "PASS",
                "problems": problems,
            }
        )
        head = f"[{'FAIL' if problems else 'PASS'}] {scope} · {target}"
        print(head)
        for problem in problems:
            print(f"       - {problem}")
        return not problems

    def failed(self) -> int:
        return sum(1 for item in self.items if item["status"] == "FAIL")

    def summary(self) -> dict[str, Any]:
        return {
            "checked": len(self.items),
            "passed": len(self.items) - self.failed(),
            "failed": self.failed(),
            "by_scope": self._by_scope(),
            "items": self.items,
        }

    def _by_scope(self) -> dict[str, dict[str, int]]:
        result: dict[str, dict[str, int]] = {}
        for item in self.items:
            bucket = result.setdefault(item["scope"], {"passed": 0, "failed": 0})
            bucket["failed" if item["status"] == "FAIL" else "passed"] += 1
        return result


def _load_yaml(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    if yaml is not None:
        loaded = yaml.safe_load(text)
        if not isinstance(loaded, dict):
            raise ValueError("scenario.yaml must contain a mapping at the top level")
        return loaded
    return _parse_flat_yaml(text)


def _parse_flat_yaml(text: str) -> dict[str, Any]:
    """Parse the flat ``key: value`` subset used by the contract's §3 table."""
    result: dict[str, Any] = {}
    current_map: dict[str, Any] | None = None
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indented = line[:1].isspace()
        stripped = line.strip()
        if ":" not in stripped:
            raise ValueError(f"cannot parse line: {raw}")
        key, value = stripped.split(":", 1)
        key = key.strip()
        value = value.strip()
        if indented and current_map is not None:
            current_map[key] = _scalar(value)
            continue
        if not value:
            current_map = {}
            result[key] = current_map
            continue
        current_map = None
        result[key] = _scalar(value)
    return result


def _scalar(value: str) -> Any:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    lowered = value.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    if lowered in ("null", "~", ""):
        return None
    try:
        return int(value)
    except ValueError:
        pass
    try:
        return float(value)
    except ValueError:
        return value


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _missing(payload: dict[str, Any], fields: Iterable[str]) -> list[str]:
    return [f"缺少必填字段 {name}" for name in fields if name not in payload]


def _normalize_sources(param_sources: Any) -> dict[str, str]:
    if not isinstance(param_sources, dict):
        return {}
    return {str(key): str(value) for key, value in param_sources.items()}


def _source_of(sources: dict[str, str], canonical: str) -> str | None:
    for alias in PARAM_ALIASES[canonical]:
        if alias in sources:
            return sources[alias]
    return None


def _check_param_sources(param_sources: Any, context: str) -> list[str]:
    problems: list[str] = []
    if not isinstance(param_sources, dict) or not param_sources:
        return [f"{context} 的 param_sources 缺失，按 §13 一律计 design_assumption 并报 FAIL"]
    sources = _normalize_sources(param_sources)
    for canonical in PARAM_ALIASES:
        value = _source_of(sources, canonical)
        if value is None:
            problems.append(f"§13 参数 {canonical} 未标来源档")
            continue
        if value not in SOURCE_CLASSES:
            problems.append(f"§13 参数 {canonical} 来源档 `{value}` 不在三值之内")
            continue
        expected = FIXED_SOURCE_CLASSES.get(canonical)
        if expected and value != expected:
            problems.append(f"§13 参数 {canonical} 应标 `{expected}`，实标 `{value}`")
    for key, value in sources.items():
        if value not in SOURCE_CLASSES:
            problems.append(f"§13 来源档 `{key}` 取值 `{value}` 不在三值之内")
    return problems


def check_scenario(path: Path, report: Report) -> str | None:
    scope = "§3 scenario.yaml"
    try:
        payload = _load_yaml(path)
    except (OSError, ValueError) as error:
        report.check(scope, str(path), [f"无法解析：{error}"])
        return None

    problems = _missing(payload, SCENARIO_FIELDS)

    family = payload.get("family")
    if family is not None and family not in FAMILIES:
        problems.append(f"family `{family}` 不在六个家族之内")

    seed = payload.get("seed")
    if seed is not None and (not _is_int(seed) or seed < 0):
        problems.append(f"seed 必须为非负整数，实为 `{seed}`")

    mode = payload.get("mode")
    if mode is not None and mode not in MODES:
        problems.append(f"mode `{mode}` 不在 live／offline 之内")

    t0_ms = payload.get("t0_ms")
    if t0_ms is not None and (not _is_int(t0_ms) or t0_ms <= 0):
        problems.append(f"t0_ms 必须为 epoch 毫秒正整数，实为 `{t0_ms}`")

    path_start_m = payload.get("path_start_m")
    if path_start_m is not None:
        if not _is_number(path_start_m):
            problems.append(f"path_start_m 必须为数值，实为 `{path_start_m}`")
        elif mode == "live" and float(path_start_m) != LIVE_PATH_START_M:
            problems.append(f"Live E2E 的 path_start_m 应为 0，实为 `{path_start_m}`")
        elif mode == "offline" and float(path_start_m) != OFFLINE_PATH_START_M:
            problems.append(f"20 min 子段的 path_start_m 应为 1000，实为 `{path_start_m}`")

    duration_sec = payload.get("duration_sec")
    if duration_sec is not None:
        if not _is_int(duration_sec):
            problems.append(f"duration_sec 必须为整数秒，实为 `{duration_sec}`")
        elif mode == "live" and duration_sec != LIVE_DURATION_SEC:
            problems.append(f"Live E2E 的 duration_sec 应为 1800，实为 `{duration_sec}`")
        elif mode == "offline" and duration_sec != OFFLINE_DURATION_SEC:
            problems.append(f"20 min 子段的 duration_sec 应为 1200，实为 `{duration_sec}`")

    speed_limits = payload.get("speed_limits_ms")
    if speed_limits is not None:
        text = str(speed_limits)
        segments = [item for item in text.split(";") if item.strip()]
        if not segments:
            problems.append("speed_limits_ms 为空")
        for segment in segments:
            if not SPEED_LIMIT_PATTERN.match(segment):
                problems.append(f"speed_limits_ms 段 `{segment}` 不是 fromS,toS,speedMs 形式")
                continue
            from_s, to_s, speed = (float(part) for part in segment.split(","))
            if to_s <= from_s:
                problems.append(f"speed_limits_ms 段 `{segment}` 的 toS 不大于 fromS")
            if speed <= 0:
                problems.append(f"speed_limits_ms 段 `{segment}` 的限速不为正")
        if payload.get("speed_profile") == "normal" and text.replace(" ", "") != NORMAL_SPEED_LIMITS:
            problems.append(f"normal 家族的三段限速应为 `{NORMAL_SPEED_LIMITS}`，实为 `{text}`")

    speed_profile = payload.get("speed_profile")
    if speed_profile is not None and speed_profile not in SPEED_PROFILES:
        problems.append(f"speed_profile `{speed_profile}` 不在 normal／overspeed 之内")

    tick_millis = payload.get("tick_millis")
    if tick_millis is not None and tick_millis != FIXED_TICK_MILLIS:
        problems.append(f"tick_millis 固定 1000，实为 `{tick_millis}`")

    truth_source = payload.get("truth_source")
    if truth_source is not None:
        text = str(truth_source).strip()
        if not text:
            problems.append("truth_source 为空")
        elif Path(text).is_absolute() or re.match(r"^[A-Za-z]:", text):
            problems.append("truth_source 必须是相对路径")

    noise_deg = payload.get("noise_deg")
    if noise_deg is not None and (not _is_number(noise_deg) or float(noise_deg) < 0):
        problems.append(f"noise_deg 必须为非负数值，实为 `{noise_deg}`")

    mmsi = payload.get("mmsi")
    if mmsi is not None:
        text = str(mmsi)
        if mode == "live":
            if text != LIVE_MMSI:
                problems.append(f"Live E2E 的 mmsi 应为 {LIVE_MMSI}，实为 `{text}`")
        elif mode == "offline":
            if not text.startswith("T"):
                problems.append(f"离线夹具的 mmsi 需以 T 前缀，实为 `{text}`")
            elif _is_int(seed) and text != f"T{seed}":
                problems.append(f"离线夹具的 mmsi 应为 T+seed（T{seed}），实为 `{text}`")

    pred_version = payload.get("pred_version")
    if pred_version is not None and (not _is_int(pred_version) or pred_version <= 0):
        problems.append(f"pred_version 必须为正整数，实为 `{pred_version}`")

    problems.extend(_check_param_sources(payload.get("param_sources"), "scenario.yaml"))

    report.check(scope, str(path), problems)
    return family if isinstance(family, str) else None


def check_truth(path: Path, report: Report) -> None:
    scope = "§6 truth.csv"
    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.reader(handle)
            header = next(reader, None)
            problems: list[str] = []
            if header is None:
                report.check(scope, str(path), ["文件为空"])
                return
            if tuple(name.strip() for name in header) != TRUTH_HEADER:
                problems.append(f"表头应为 {','.join(TRUTH_HEADER)}，实为 {','.join(header)}")
            previous_ts: int | None = None
            previous_s: float | None = None
            rows = 0
            for row in reader:
                if not row:
                    continue
                rows += 1
                if len(row) != len(TRUTH_HEADER):
                    problems.append(f"第 {rows + 1} 行列数为 {len(row)}，应为 {len(TRUTH_HEADER)}")
                    break
                try:
                    ts_ms = int(float(row[0]))
                    s_m = float(row[1])
                    lon = float(row[2])
                    lat = float(row[3])
                    sog = float(row[4])
                except ValueError:
                    problems.append(f"第 {rows + 1} 行存在非数值字段")
                    break
                if s_m > TRUTH_MAX_S_M:
                    problems.append(f"第 {rows + 1} 行 s_m={s_m} 超过 v3 全长 {TRUTH_MAX_S_M}")
                    break
                if previous_ts is not None and ts_ms <= previous_ts:
                    problems.append(f"第 {rows + 1} 行 ts_ms 未递增")
                    break
                if previous_s is not None and s_m < previous_s:
                    problems.append(f"第 {rows + 1} 行 s_m 回退")
                    break
                if not (-180 <= lon <= 180 and -90 <= lat <= 90):
                    problems.append(f"第 {rows + 1} 行经纬度越界")
                    break
                if sog < 0:
                    problems.append(f"第 {rows + 1} 行 sog 为负")
                    break
                previous_ts, previous_s = ts_ms, s_m
            if rows == 0:
                problems.append("truth.csv 无数据行")
    except OSError as error:
        problems = [f"无法读取：{error}"]
    report.check(scope, str(path), problems)


def _load_json(path: Path, scope: str, report: Report) -> dict[str, Any] | None:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        report.check(scope, str(path), [f"无法解析：{error}"])
        return None
    if not isinstance(payload, dict):
        report.check(scope, str(path), ["顶层必须是 JSON 对象"])
        return None
    return payload


def check_manifest(path: Path, report: Report) -> None:
    scope = "§4 manifest.json"
    payload = _load_json(path, scope, report)
    if payload is None:
        return
    problems = _missing(payload, MANIFEST_FIELDS)
    if payload.get("tick_millis") is not None and payload.get("tick_millis") != FIXED_TICK_MILLIS:
        problems.append(f"tick_millis 固定 1000，实为 `{payload.get('tick_millis')}`")
    if payload.get("speed_profile") is not None and payload["speed_profile"] not in SPEED_PROFILES:
        problems.append(f"speed_profile `{payload['speed_profile']}` 不在 normal／overspeed 之内")
    if payload.get("evidence_class") is not None and payload["evidence_class"] not in EVIDENCE_CLASSES:
        problems.append(f"evidence_class `{payload['evidence_class']}` 不在两值之内")
    elif payload.get("evidence_class") != "simulation_closed_loop":
        problems.append("本批次 evidence_class 固定 simulation_closed_loop")
    for name in ("rows", "bytes"):
        value = payload.get(name)
        if value is not None and (not _is_int(value) or value < 0):
            problems.append(f"{name} 必须为非负整数，实为 `{value}`")
    sha = payload.get("sha256")
    if sha is not None and not SHA256_PATTERN.match(str(sha)):
        problems.append("sha256 必须为 64 位小写十六进制")
    command = payload.get("command")
    if command is not None and not str(command).strip():
        problems.append("command 为空")
    baseline = payload.get("baseline_commit")
    if baseline is not None and not str(baseline).strip():
        problems.append("baseline_commit 为空")
    if _is_number(payload.get("path_start_m")) is False and "path_start_m" in payload:
        problems.append("path_start_m 必须为数值")
    if _is_number(payload.get("noise_deg")) is False and "noise_deg" in payload:
        problems.append("noise_deg 必须为数值")
    problems.extend(_check_param_sources(payload.get("param_sources"), "manifest.json"))
    report.check(scope, str(path), problems)


def check_metrics(path: Path, report: Report, tau_limit_sec: int | None) -> None:
    scope = "§5 metrics.json"
    payload = _load_json(path, scope, report)
    if payload is None:
        return
    problems = _missing(payload, METRIC_FIELDS)
    for name in NON_EMPTY_METRICS:
        value = payload.get(name)
        if value is None:
            problems.append(f"指标 {name} 为空，本批次要求四项指标可计算且非空")
        elif not _is_number(value):
            problems.append(f"指标 {name} 必须为数值，实为 `{value}`")
        elif name.startswith("coverage") and not 0 <= float(value) <= 1:
            problems.append(f"指标 {name} 必须落在 0～1，实为 `{value}`")
        elif name in ("nrmse_along", "nrmse_cross", "nees", "meet_error_m") and float(value) < 0:
            problems.append(f"指标 {name} 不得为负，实为 `{value}`")
    tau_grid = payload.get("tau_grid_sec")
    if tau_grid is not None:
        if not isinstance(tau_grid, list) or not tau_grid:
            problems.append("tau_grid_sec 必须为非空数组")
        else:
            for tau in tau_grid:
                if not _is_int(tau) or tau <= 0:
                    problems.append(f"tau_grid_sec 元素 `{tau}` 必须为正整数")
                    break
            if tau_limit_sec is not None and any(_is_int(t) and t > tau_limit_sec for t in tau_grid):
                problems.append(f"20 min 子段只统计 τ ≤ {tau_limit_sec}，不得补造更大 τ")
    for name in ("bbox_fallback_samples", "rows_pred_error_sample"):
        value = payload.get(name)
        if value is not None and (not _is_int(value) or value < 0):
            problems.append(f"{name} 必须为非负整数，实为 `{value}`")
    evidence = payload.get("evidence_class")
    if evidence is not None and evidence not in EVIDENCE_CLASSES:
        problems.append(f"evidence_class `{evidence}` 不在两值之内")
    elif evidence != "simulation_closed_loop":
        problems.append("本批次 evidence_class 固定 simulation_closed_loop")
    by_class = payload.get("by_evidence_class")
    if by_class is not None:
        if not isinstance(by_class, dict):
            problems.append("by_evidence_class 必须为对象")
        else:
            for name in EVIDENCE_CLASSES:
                if name not in by_class:
                    problems.append(f"by_evidence_class 缺 `{name}` 分栏")
                elif not isinstance(by_class[name], dict):
                    problems.append(f"by_evidence_class.{name} 必须为对象")
            if len(EVIDENCE_CLASSES) == 1:
                problems.append("两栏不得合并出 NEES 或 2σ 覆盖")
    report.check(scope, str(path), problems)


def _iter_json_payloads(path: Path) -> list[Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and "frames" in payload and isinstance(payload["frames"], list):
        return payload["frames"]
    return [payload]


def check_replay(path: Path, report: Report) -> None:
    """§8: envelope shape, frame types, Chinese order states, no COMPLETED."""
    scope = "§8 WS 契约"
    try:
        frames = _iter_json_payloads(path)
    except (OSError, json.JSONDecodeError) as error:
        report.check(scope, str(path), [f"无法解析：{error}"])
        return
    problems: list[str] = []
    seen_types: set[str] = set()
    for index, frame in enumerate(frames):
        label = f"第 {index + 1} 帧"
        if not isinstance(frame, dict):
            problems.append(f"{label} 不是对象")
            continue
        if "payload" in frame:
            problems.append(f"{label} 使用了 payload，§8 要求字段名为 data")
        if "ts" in frame:
            problems.append(f"{label} 顶层写了 ts，§8 要求顶层不写 ts")
        if "type" not in frame or "data" not in frame:
            problems.append(f"{label} 缺 type 或 data 字段")
            continue
        frame_type = frame["type"]
        if frame_type not in WS_FRAME_TYPES:
            problems.append(f"{label} type `{frame_type}` 不在六类帧之内")
        else:
            seen_types.add(str(frame_type))
        data = frame["data"]
        if not isinstance(data, dict):
            continue
        status = data.get("status", data.get("orderStatus"))
        if frame_type == "order_state" and status is not None and status not in ORDER_STATUS_ZH:
            problems.append(f"{label} 订单状态 `{status}` 不是 §8 中文状态词")
        timeline = data.get("orderTimeline")
        if isinstance(timeline, list):
            flat = json.dumps(timeline, ensure_ascii=False)
            if "WAIT_CARGO" in flat:
                problems.append(f"{label} orderTimeline 混入 WAIT_CARGO")
            if any(word in flat for word in FORBIDDEN_STATUS_WORDS):
                problems.append(f"{label} orderTimeline 出现英文状态词")
        expected = data.get("expected")
        if isinstance(expected, list):
            for item in expected:
                if isinstance(item, str) and item not in ORDER_STATUS_ZH:
                    problems.append(f"{label} expected 出现非中文状态词 `{item}`")
                    break
        if "COMPLETED" in json.dumps(frame, ensure_ascii=False):
            problems.append(f"{label} 出现 COMPLETED，终态应写「已完成」")
    if not frames:
        problems.append("replay 无帧")
    report.check(scope, f"{path}（{len(frames)} 帧，覆盖 {len(seen_types)} 类）", problems)


def _sample_rows(directory: Path) -> int | None:
    total = 0
    found = False
    for path in sorted(directory.glob("*")):
        if not path.is_file() or path.suffix.lower() not in (".csv", ".jsonl"):
            continue
        found = True
        with path.open("r", encoding="utf-8") as handle:
            lines = [line for line in handle if line.strip()]
        if path.suffix.lower() == ".csv":
            total += max(0, len(lines) - 1)
        else:
            total += len(lines)
    return total if found else None


def check_run(run_dir: Path, report: Report, require_families: bool) -> None:
    name = run_dir.name
    manifest = run_dir / "manifest.json"
    metrics = run_dir / "metrics.json"
    truth = run_dir / "truth.csv"
    sample = run_dir / "sample"

    if not manifest.is_file():
        report.check("§4 manifest.json", str(manifest), ["文件缺失，run 未完成"])
    else:
        check_manifest(manifest, report)
        payload = _load_json(manifest, "§4 manifest.json·一致性", report)
        if payload is not None:
            problems: list[str] = []
            if metrics.is_file():
                try:
                    metric_payload = json.loads(metrics.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError):
                    metric_payload = {}
                if isinstance(metric_payload, dict):
                    rows_manifest = payload.get("rows")
                    rows_metric = metric_payload.get("rows_pred_error_sample")
                    if _is_int(rows_manifest) and _is_int(rows_metric) and rows_manifest != rows_metric:
                        problems.append(
                            f"manifest.rows={rows_manifest} 与 metrics.rows_pred_error_sample={rows_metric} 不一致"
                        )
                    if metric_payload.get("evidence_class") != payload.get("evidence_class"):
                        problems.append("manifest 与 metrics 的 evidence_class 不一致")
            if sample.is_dir():
                counted = _sample_rows(sample)
                if counted is not None and _is_int(payload.get("rows")) and counted > int(payload["rows"]):
                    problems.append(f"sample/ 行数 {counted} 超过 manifest.rows={payload['rows']}")
            report.check("§4 manifest.json·一致性", name, problems)

    if not metrics.is_file():
        report.check("§5 metrics.json", str(metrics), ["文件缺失，run 未完成"])
    else:
        check_metrics(metrics, report, tau_limit_sec=OFFLINE_TAU_LIMIT_SEC)

    if truth.is_file():
        check_truth(truth, report)
    else:
        report.check("§6 truth.csv", str(truth), ["文件缺失，run 未完成"])

    if not sample.is_dir():
        report.check("§5 产物 sample/", str(sample), ["目录缺失，§11 要求一份 sample/"])


def collect_scenarios(target: Path) -> list[Path]:
    """Accept a single file or a directory holding ``<family>.yaml`` files."""
    if target.is_file():
        return [target]
    found = sorted(target.glob("**/*.yaml")) + sorted(target.glob("**/*.yml"))
    return list(dict.fromkeys(found))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate 1109 mock-data artifacts against the contract (v1.3).",
    )
    parser.add_argument("--scenarios", nargs="*", default=[], help="scenario.yaml 文件或所在目录")
    parser.add_argument("--runs", nargs="*", default=[], help="run 产物目录（含 manifest／metrics／truth／sample）")
    parser.add_argument("--replay", nargs="*", default=[], help="replay 帧 JSON 文件或目录")
    parser.add_argument("--json", dest="json_out", default=None, help="把结果写入 JSON 报告")
    parser.add_argument(
        "--require-families",
        action="store_true",
        help="要求六个家族全部出现，缺一族即 FAIL",
    )
    args = parser.parse_args(argv)

    if not (args.scenarios or args.runs or args.replay):
        parser.error("至少给出 --scenarios、--runs、--replay 之一")

    report = Report()
    seen_families: set[str] = set()

    for target in args.scenarios:
        paths = collect_scenarios(Path(target))
        if not paths:
            report.check("§3 scenario.yaml", str(target), ["未找到任何 scenario.yaml"])
        for path in paths:
            family = check_scenario(path, report)
            if family:
                seen_families.add(family)

    if args.require_families:
        missing = [name for name in FAMILIES if name not in seen_families]
        report.check("§3 scenario.yaml·家族覆盖", "六个家族", [f"缺家族 {name}" for name in missing])

    for target in args.runs:
        base = Path(target)
        run_dirs = [base] if (base / "manifest.json").is_file() else sorted(p for p in base.glob("*") if p.is_dir())
        if not run_dirs:
            report.check("§4 manifest.json", str(base), ["未找到任何 run 目录"])
        for run_dir in run_dirs:
            check_run(run_dir, report, args.require_families)

    for target in args.replay:
        base = Path(target)
        files = [base] if base.is_file() else sorted(base.glob("**/*.json"))
        if not files:
            report.check("§8 WS 契约", str(base), ["未找到 replay JSON"])
        for path in files:
            check_replay(path, report)

    summary = report.summary()
    print()
    print(f"合计 {summary['checked']} 项：PASS {summary['passed']}，FAIL {summary['failed']}")
    for scope, bucket in summary["by_scope"].items():
        print(f"  {scope}: PASS {bucket['passed']}／FAIL {bucket['failed']}")

    if args.json_out:
        Path(args.json_out).write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"报告已写入 {args.json_out}")

    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
