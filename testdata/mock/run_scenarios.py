#!/usr/bin/env python3
"""模拟数据批次编排器（契约 1109-simdata-contract §5 · 宋冰鑫）。

职责只有两项：编排 run、汇总指标。

CLI 签名（《1112-功能做实批次｜接口约定》§6.2，参数顺序无关）::

    # 单 run（保留 --seed）
    python run_scenarios.py --scenario <path> --mode <live|offline> --seed <int> --out <dir>
    # 批量（闭区间）
    python run_scenarios.py --scenario <path> --mode <live|offline> \
        --seed-from <int> --seed-to <int> --out <dir> [--t0-ms <int>]

产物（契约 §6.3）::

    <out>/manifest.json   单 run 为契约 §4 十四字段并合并 §6.3 批量字段
    <out>/truth.csv       从 scenario.yaml 的 truth_source 复制
    <out>/metrics.json    契约 §5 十一指标字段
    <out>/sample/         pred_error_sample 本 run 行的 CSV 导出

    批量模式下每个 seed 的 run 目录为 <out>/seed_<n>，<out> 根目录另写
    契约 §6.3 的 manifest.json 汇总与 metrics.json 汇总。

与他段的交互边界（契约 §5、§6）：
  - 只读 truth.csv，不生成 CSV 全量数据集；
  - 写 metrics.json 与 manifest.json；
  - 不 import 他人未完成的 Java 实现类；预测执行走 subprocess 调
    OfflinePredictionFixtureTest（卢肄文），指标计算 import metrics.py（彭紫情）。
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

# 契约 §2 探测滞后设计网格（与 PrecisePredictionEngine.PROBE_LAG_GRID_SEC 同值）。
DESIGN_TAU_GRID_SEC = (60, 180, 300, 600, 900, 1200, 1800)

# 契约 §13 本批次固定证据分级。
EVIDENCE_CLASS = "simulation_closed_loop"

# 契约 §4 manifest 十四字段中由本脚本自动补齐的六项。
AUTO_MANIFEST_FIELDS = (
    "param_sources",
    "evidence_class",
    "rows",
    "bytes",
    "sha256",
    "command",
)

# 契约 §6.2 统一时基：2026-11-10T00:00:00Z，与 normal_straight.yaml 的 t0_ms 同源。
DEFAULT_T0_MS = 1_794_268_800_000

# 契约 §6.2 禁用值：夹具旧硬编码，与场景时基不同源会致 pred_error_sample 落 0 行。
FORBIDDEN_T0_MS = 1_760_000_000_000

# 契约 §6 的 truth.csv 列序，与 validate_scenario.py 的 TRUTH_HEADER 同值。
TRUTH_HEADER = ("ts_ms", "s_m", "lon", "lat", "sog_ms", "cog_deg")

# 契约 §3 scenario.yaml 十四个必填字段。
REQUIRED_SCENARIO_FIELDS = (
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


# ────────────────────────────── 轻量 YAML 解析 ──────────────────────────────


def _strip_quotes(value: str) -> str:
    """去掉 YAML 标量两端的成对引号。"""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def _strip_inline_comment(value: str) -> str:
    """去掉 YAML 行内注释：引号外且位于空白之后的 ``#`` 起视为注释。

    scenario.yaml 的取值行普遍带行尾注释（如 ``t0_ms: 1794268800000  # ...``），
    不剥离会把注释文本当数值或路径的一部分。
    """
    in_single = False
    in_double = False
    for index, char in enumerate(value):
        if char == "'" and not in_double:
            in_single = not in_single
        elif char == '"' and not in_single:
            in_double = not in_double
        elif char == "#" and not in_single and not in_double:
            if index == 0 or value[index - 1] in (" ", "\t"):
                return value[:index].rstrip()
    return value


def _coerce_scalar(value: str) -> Any:
    """把 YAML 标量转成 Python 类型：int / float / bool / str。"""
    text = _strip_quotes(_strip_inline_comment(value.strip()))
    if text == "":
        return ""
    lowered = text.lower()
    if lowered in ("true", "yes"):
        return True
    if lowered in ("false", "no"):
        return False
    if lowered in ("null", "~"):
        return None
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        pass
    return text


def parse_yaml(text: str) -> dict[str, Any]:
    """解析只含「扁平 key: value + 一层缩进 map」的 YAML。

    scenario.yaml 与 application.yml 都满足这个子集；遇到更深的嵌套会抛错，
    提醒调用方改用正经 YAML 库。
    """
    result: dict[str, Any] = {}
    current_key: str | None = None
    current_map: dict[str, Any] | None = None
    for raw_line in text.splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        stripped = raw_line.rstrip()
        if stripped.startswith(" ") or stripped.startswith("\t"):
            if current_key is None or current_map is None:
                raise ValueError(f"YAML 缩进出现在顶层键之前：{raw_line!r}")
            indent = len(stripped) - len(stripped.lstrip())
            if indent < 2:
                raise ValueError(f"YAML 嵌套缩进不足 2 格：{raw_line!r}")
            content = stripped.strip()
            if ":" not in content:
                raise ValueError(f"YAML 嵌套行缺少冒号：{raw_line!r}")
            key, _, val = content.partition(":")
            current_map[key.strip()] = _coerce_scalar(val)
            continue
        if ":" not in stripped:
            raise ValueError(f"YAML 行缺少冒号：{raw_line!r}")
        key, _, val = stripped.partition(":")
        key = key.strip()
        val = val.strip()
        if val == "":
            current_key = key
            current_map = {}
            result[key] = current_map
        else:
            current_key = None
            current_map = None
            result[key] = _coerce_scalar(val)
    return result


# ───────────────────────────── scenario 加载 ─────────────────────────────


def load_scenario(path: Path) -> dict[str, Any]:
    """加载 scenario.yaml 并校验契约 §3 的十四个必填字段。"""
    if not path.exists():
        raise FileNotFoundError(f"scenario 文件不存在：{path}")
    scenario = parse_yaml(path.read_text(encoding="utf-8"))
    missing = [f for f in REQUIRED_SCENARIO_FIELDS if f not in scenario]
    if missing:
        raise ValueError(f"scenario.yaml 缺少必填字段（契约 §3）：{missing}")
    if not isinstance(scenario["param_sources"], dict):
        raise ValueError("scenario.yaml 的 param_sources 必须是 map（契约 §3）")
    if int(scenario["t0_ms"]) == FORBIDDEN_T0_MS:
        raise ValueError(
            f"scenario.yaml 的 t0_ms 为契约 §6.2 禁用值 {FORBIDDEN_T0_MS}，"
            "必须与统一时基同源"
        )
    return scenario


def resolve_t0_ms(scenario: dict[str, Any], t0_ms: int) -> int:
    """按契约 §6.2 统一时基：以调用方给定的 t0_ms 为准，并核场景值。

    场景 t0_ms 与统一值不同时给出提示但不中断：统一时基由编排器决定，
    夹具与指标计算都读同一个数，才不会出现「样本时间落在 run 之外」。
    """
    if t0_ms == FORBIDDEN_T0_MS:
        raise ValueError(
            f"t0_ms 不得使用契约 §6.2 禁用值 {FORBIDDEN_T0_MS}"
        )
    scenario_t0 = int(scenario["t0_ms"])
    if scenario_t0 != t0_ms:
        print(
            f"[run_scenarios] 注意：scenario 的 t0_ms={scenario_t0} 与统一时基 "
            f"{t0_ms} 不同，以统一时基为准（契约 §6.2）",
            file=sys.stderr,
        )
    return t0_ms


# ─────────────────────────── 数据库配置解析 ────────────────────────────


_PLACEHOLDER_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")


def _resolve_placeholder(value: str) -> str:
    """解析 ${ENV:default} 形式的占位符，支持字符串中内嵌多个。"""
    if not isinstance(value, str):
        return value

    def repl(match: re.Match[str]) -> str:
        env_name = match.group(1)
        default = match.group(2) if match.group(2) is not None else ""
        return os.environ.get(env_name, default)

    return _PLACEHOLDER_RE.sub(repl, value)


def load_db_config(app_yml_path: Path) -> dict[str, str]:
    """从 application.yml 解析 MySQL 连接信息。

    application.yml 是多层嵌套 YAML，轻量解析器无法覆盖；此处用正则提取
    spring.datasource 下的 url／username／password，足以支撑离线读库。
    """
    text = app_yml_path.read_text(encoding="utf-8")
    # 提取 spring.datasource 块（到下一个同级或更高级键为止）。
    block_match = re.search(
        r"^\s+datasource:\s*\n((?:\s{4,}.*\n?)+)",
        text,
        re.MULTILINE,
    )
    if not block_match:
        raise ValueError("application.yml 未找到 spring.datasource 节点")
    block = block_match.group(1)

    def grab(name: str) -> str | None:
        m = re.search(rf"^\s+{re.escape(name)}:\s*(.+)$", block, re.MULTILINE)
        return m.group(1).strip() if m else None

    url = grab("url")
    username = grab("username")
    password = grab("password")
    if not url or not username or not password:
        raise ValueError(
            f"application.yml 的 spring.datasource 缺少 url/username/password"
        )
    username = _resolve_placeholder(username)
    password = _resolve_placeholder(password)
    if not url.startswith("jdbc:mysql://"):
        raise ValueError(f"无法解析 datasource.url：{url}")
    body = url[len("jdbc:mysql://"):]
    # 先解析占位符，避免 ${MYSQL_HOST:127.0.0.1} 里的冒号干扰 host:port 分割。
    body = _resolve_placeholder(body)
    host_port, _, db_query = body.partition("/")
    host, _, port = host_port.partition(":")
    db_name = db_query.split("?", 1)[0] if db_query else "wavenest"
    return {
        "host": host,
        "port": int(port) if port else 3306,
        "user": username,
        "password": password,
        "database": db_name,
    }


# ─────────────────────────── 预测执行（subprocess） ────────────────────────────


def build_fixture_env(scenario: dict[str, Any], seed: int, t0_ms: int) -> dict[str, str]:
    """把 scenario 字段映射成 OfflinePredictionFixtureTest 的环境变量。

    ``FIXTURE_T0_MS`` 是契约 §6.2 约定的时基入口：夹具改读该变量后与编排器
    同源；夹具未改前它仍用旧硬编码，表现为样本时间落在 run 之外，属夹具侧待改项。
    """
    return {
        **os.environ,
        "FIXTURE_SEED": str(seed),
        "FIXTURE_PRED_VERSION": str(scenario["pred_version"]),
        "FIXTURE_PATH_START_M": str(scenario["path_start_m"]),
        "FIXTURE_DURATION_SEC": str(scenario["duration_sec"]),
        "FIXTURE_T0_MS": str(t0_ms),
        # 夹具默认在 finally 删除本 run 写入的行；编排器要在清理前读回行算指标，
        # 故显式要求保留，清理由夹具自身在 run 前按 mmsi 幂等地做（契约 §9）。
        "FIXTURE_KEEP_SAMPLES": "1",
    }


def run_prediction(
    scenario: dict[str, Any], seed: int, repo_root: Path, t0_ms: int
) -> int:
    """触发一次离线预测 run，返回 gradle 退出码。

    通过 subprocess 调用 OfflinePredictionFixtureTest（卢肄文），参数走环境变量。
    该测试把残差样本落进 pred_error_sample，本脚本随后从库里读回。
    """
    env = build_fixture_env(scenario, seed, t0_ms)
    # --rerun 不可省略：seed 走环境变量传入，而环境变量不在 Gradle 的任务输入清单里，
    # 不加这一项时第二次起 test 任务会被判为 UP-TO-DATE 直接跳过，200 个 seed 只有
    # 第一个真跑，且不会报错。
    cmd = [
        "./gradlew" if os.name != "nt" else "gradlew.bat",
        ":wl-app:test",
        "--tests",
        "*OfflinePredictionFixtureTest",
        "--rerun",
        "--console=plain",
    ]
    print(f"[run_scenarios] 触发离线预测：seed={seed} mmsi=T{seed}")
    result = subprocess.run(
        cmd,
        cwd=repo_root,
        env=env,
        capture_output=True,
        text=True,
        # Windows 上 Gradle 输出可能混入非 UTF-8 字节（控制台代码页），
        # 不指定容错会让解码异常盖住真正的测试结果。
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise RuntimeError(
            f"离线预测夹具执行失败（退出码 {result.returncode}），"
            "检查数据库是否启动、016_centerline_v3.sql 与 004_seed_airspace.sql 是否已灌库"
        )
    for line in result.stdout.splitlines():
        if line.startswith("[FIXTURE]"):
            print(line)
    return result.returncode


# ─────────────────────── 数据库读取 pred_error_sample ───────────────────────


def fetch_pred_error_samples(
    db_config: dict[str, str], mmsi: str, pred_version: int
) -> list[dict[str, Any]]:
    """从 pred_error_sample 读取本 run 的全部行。

    返回的 dict 同时带 snake_case 与 camelCase 键，兼容 metrics.py 的两种取值口径。
    """
    try:
        import pymysql
    except ImportError as exc:
        raise RuntimeError(
            "需要 pymysql 读取 pred_error_sample：pip install pymysql"
        ) from exc

    conn = pymysql.connect(
        host=db_config["host"],
        port=db_config["port"],
        user=db_config["user"],
        password=db_config["password"],
        database=db_config["database"],
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT ship_mmsi, pred_version, tau_sec, err_along_m, err_cross_m, "
                "err_course_deg, sigma_along_m, sigma_cross_m, v_along_ms, sample_ts "
                "FROM pred_error_sample WHERE ship_mmsi = %s AND pred_version = %s "
                "ORDER BY sample_ts, tau_sec",
                (mmsi, pred_version),
            )
            rows = cur.fetchall()
    finally:
        conn.close()

    samples: list[dict[str, Any]] = []
    for row in rows:
        sample_ts = row["sample_ts"]
        if hasattr(sample_ts, "timestamp"):
            sample_ts_ms = int(sample_ts.timestamp() * 1000)
        else:
            sample_ts_ms = int(sample_ts)
        record = {
            "sample_ts": sample_ts_ms,
            "sampleTs": sample_ts_ms,
            "tau_sec": int(row["tau_sec"]),
            "tauSec": int(row["tau_sec"]),
            "err_along_m": float(row["err_along_m"]),
            "errAlongM": float(row["err_along_m"]),
            "err_cross_m": float(row["err_cross_m"]),
            "errCrossM": float(row["err_cross_m"]),
            "sigma_along_m": float(row["sigma_along_m"]),
            "sigmaAlongM": float(row["sigma_along_m"]),
            "sigma_cross_m": float(row["sigma_cross_m"]),
            "sigmaCrossM": float(row["sigma_cross_m"]),
            "evidence_class": EVIDENCE_CLASS,
        }
        samples.append(record)
    return samples


# ─────────────────────────── 指标汇总（调用 metrics.py） ────────────────────────────


def compute_metrics(
    samples: list[dict[str, Any]],
    scenario: dict[str, Any],
    rows_pred_error_sample: int,
    t0_ms: int,
    predicted_meet_lon_lat: tuple[float, float],
    truth_meet_lon_lat: tuple[float, float],
) -> dict[str, Any]:
    """调用 metrics.calculate_metrics 汇总契约 §5 十一指标字段。

    两个会面点由真值轨迹与预测轨迹分别给出（契约 §6.2），不再使用单一占位坐标。
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from metrics import calculate_metrics  # 彭紫情 · 契约 §5

    duration_sec = int(scenario["duration_sec"])
    return calculate_metrics(
        samples,
        t0_ms=t0_ms,
        duration_sec=duration_sec,
        rows_pred_error_sample=rows_pred_error_sample,
        predicted_meet_lon_lat=predicted_meet_lon_lat,
        truth_meet_lon_lat=truth_meet_lon_lat,
        evidence_class=EVIDENCE_CLASS,
    )


# ─────────────────────────── 真值与会面点 ────────────────────────────


def read_truth_points(path: Path) -> list[dict[str, Any]]:
    """读取 truth.csv，返回按时间升序的真值点。

    表头与列序按契约 §6，与 validate_scenario.py 的 TRUTH_HEADER 一致。
    """
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        if header is None:
            raise ValueError(f"truth.csv 为空：{path}")
        if tuple(name.strip() for name in header) != TRUTH_HEADER:
            raise ValueError(
                f"truth.csv 表头应为 {','.join(TRUTH_HEADER)}，"
                f"实为 {','.join(header)}（契约 §6）"
            )
        points: list[dict[str, Any]] = []
        for line_no, row in enumerate(reader, start=2):
            if not row:
                continue
            if len(row) != len(TRUTH_HEADER):
                raise ValueError(
                    f"truth.csv 第 {line_no} 行列数为 {len(row)}，"
                    f"应为 {len(TRUTH_HEADER)}"
                )
            points.append(
                {
                    "ts_ms": int(float(row[0])),
                    "s_m": float(row[1]),
                    "lon": float(row[2]),
                    "lat": float(row[3]),
                    "sog_ms": float(row[4]),
                    "cog_deg": float(row[5]),
                }
            )
    if len(points) < 2:
        raise ValueError(f"truth.csv 至少需要两个点才能确定末段航向：{path}")
    return points


def _offset_lonlat(
    lon: float, lat: float, east_m: float, north_m: float
) -> tuple[float, float]:
    """把以米为单位的东向／北向偏移换算成经纬度偏移。"""
    dlat = north_m / 111320.0
    dlon = east_m / (111320.0 * math.cos(math.radians(lat)))
    return lon + dlon, lat + dlat


def resolve_meet_points(
    truth_points: list[dict[str, Any]], samples: list[dict[str, Any]]
) -> tuple[tuple[float, float], tuple[float, float]]:
    """由真值轨迹与末段残差给出预测、真值两个会面点（契约 §6.2）。

    真值会面点取 truth.csv 末点。预测会面点由末段样本的沿向／横向误差在真值末点的
    航向坐标系上偏移得到：``pred_error_sample`` 不存预测经纬度，残差是它唯一可用的
    位置信息。两者同值时按契约 §6.2 视为失败，直接报错而不是静默沿用。
    """
    last = truth_points[-1]
    truth_lon = float(last["lon"])
    truth_lat = float(last["lat"])

    # 船抵达全长后可能仍有若干静止点（sog=0、经纬度不变），末两点会重合。
    # 从尾部往前取最后一个位移非零的相邻点对定末段航向，真值会面点仍取末点。
    u_east = None
    u_north = None
    for i in range(len(truth_points) - 1, 0, -1):
        a, b = truth_points[i - 1], truth_points[i]
        north = float(b["lat"]) - float(a["lat"])
        east = float(b["lon"]) - float(a["lon"])
        norm = math.hypot(north, east)
        if norm > 0.0:
            u_east, u_north = east / norm, north / norm
            break
    if u_east is None or u_north is None:
        raise ValueError("truth.csv 全段无位移，无法确定末段航向")
    # 右转 90 度得到横向单位向量。
    v_east, v_north = u_north, -u_east

    latest = max(samples, key=lambda s: (s["sample_ts"], s["tau_sec"]))
    along = float(latest["err_along_m"])
    cross = float(latest["err_cross_m"])

    pred_lon, pred_lat = _offset_lonlat(
        truth_lon,
        truth_lat,
        u_east * along + v_east * cross,
        u_north * along + v_north * cross,
    )
    if abs(pred_lon - truth_lon) < 1e-12 and abs(pred_lat - truth_lat) < 1e-12:
        raise ValueError(
            "末段残差为零，预测与真值会面点相同（契约 §6.2 要求两者不得同值）"
        )
    return (pred_lon, pred_lat), (truth_lon, truth_lat)


# ─────────────────────────── manifest 自动补齐 ────────────────────────────


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(
    scenario: dict[str, Any],
    seed: int,
    out_dir: Path,
    command: str,
    rows: int,
) -> dict[str, Any]:
    """组装契约 §4 的 manifest.json 十四字段。

    前八项取自 scenario.yaml，后六项（param_sources、evidence_class、rows、
    bytes、sha256、command）由本脚本自动补齐。
    """
    sample_path = out_dir / "sample" / "pred_error_samples.csv"
    truth_path = out_dir / "truth.csv"
    metrics_path = out_dir / "metrics.json"

    total_bytes = 0
    combined = hashlib.sha256()
    for p in (truth_path, metrics_path, sample_path):
        if p.exists():
            total_bytes += p.stat().st_size
            combined.update(_sha256_file(p).encode())

    manifest: dict[str, Any] = {
        "baseline_commit": os.environ.get("BASELINE_COMMIT", "cade3ef"),
        "tick_millis": scenario["tick_millis"],
        "path_start_m": scenario["path_start_m"],
        "pred_version": scenario["pred_version"],
        "speed_profile": scenario["speed_profile"],
        "seed": seed,
        "truth_source": scenario["truth_source"],
        "noise_deg": scenario["noise_deg"],
        # ── 以下六项自动补齐 ──
        "param_sources": dict(scenario["param_sources"]),
        "evidence_class": EVIDENCE_CLASS,
        "rows": rows,
        "bytes": total_bytes,
        "sha256": combined.hexdigest(),
        "command": command,
    }
    return manifest


# ─────────────────────────── 产物写入 ────────────────────────────


def write_sample_csv(samples: list[dict[str, Any]], out_dir: Path) -> Path:
    """把本 run 的 pred_error_sample 行写到 out/sample/pred_error_samples.csv。"""
    sample_dir = out_dir / "sample"
    sample_dir.mkdir(parents=True, exist_ok=True)
    path = sample_dir / "pred_error_samples.csv"
    fieldnames = [
        "sample_ts",
        "tau_sec",
        "err_along_m",
        "err_cross_m",
        "sigma_along_m",
        "sigma_cross_m",
    ]
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for s in samples:
            writer.writerow({k: s[k] for k in fieldnames})
    return path


def write_truth_csv(
    scenario: dict[str, Any], repo_root: Path, out_dir: Path
) -> list[dict[str, Any]]:
    """把 scenario.yaml 指定的 truth.csv 复制到 out/truth.csv 并返回真值点。"""
    truth_source = scenario["truth_source"]
    # repo_root 是仓库根，而场景里的 truth_source 相对 testdata/mock/ 书写；按本文件目录解析。
    src = (Path(__file__).resolve().parent / truth_source).resolve()
    if not src.exists():
        raise FileNotFoundError(
            f"truth.csv 不存在：{src}（罗才辉产出，契约 §6）"
        )
    dst = out_dir / "truth.csv"
    dst.write_bytes(src.read_bytes())
    return read_truth_points(dst)


# ─────────────────────────── 主流程 ────────────────────────────


def run_single(
    scenario: dict[str, Any],
    mode: str,
    seed: int,
    out_dir: Path,
    repo_root: Path,
    command: str,
    t0_ms: int,
) -> dict[str, Any]:
    """执行单次 run，返回 ``{"manifest": ..., "metrics": ...}``。"""
    if mode not in ("live", "offline"):
        raise ValueError(f"mode 必须是 live 或 offline（契约 §3）：{mode}")

    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. 复制真值轨迹，并取出真值点供会面点使用。
    truth_points = write_truth_csv(scenario, repo_root, out_dir)

    # 2. 触发预测（offline 走夹具；live 假设实时链路已在跑）。
    if mode == "offline":
        run_prediction(scenario, seed, repo_root, t0_ms)

    # 3. 从数据库读回 pred_error_sample 行。
    app_yml = repo_root / "backend" / "wl-app" / "src" / "main" / "resources" / "application.yml"
    db_config = load_db_config(app_yml)
    # 离线夹具的船号是 T+seed（契约 §6.2 数据隔离），不能拿 scenario 的 live mmsi 查。
    mmsi = f"T{seed}" if mode == "offline" else str(scenario["mmsi"])
    pred_version = int(scenario["pred_version"])
    samples = fetch_pred_error_samples(db_config, mmsi, pred_version)
    if not samples:
        raise RuntimeError(
            f"pred_error_sample 未读到 mmsi={mmsi} pred_version={pred_version} 的行"
            "（契约 §12 失败判据：先核 §10 离线路径、§6.2 时基同源与夹具装配）"
        )

    # 4. 导出 sample/。
    write_sample_csv(samples, out_dir)

    # 5. 会面点取真实预测与真值两点，再汇总指标。
    predicted_meet, truth_meet = resolve_meet_points(truth_points, samples)
    metrics = compute_metrics(
        samples, scenario, len(samples), t0_ms, predicted_meet, truth_meet
    )
    (out_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # 6. 组装并写 manifest.json（契约 §4 十四字段，六项自动补齐）。
    manifest = build_manifest(scenario, seed, out_dir, command, len(samples))
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"[run_scenarios] 完成 seed={seed} rows={len(samples)} out={out_dir}")
    return {"manifest": manifest, "metrics": metrics}


def _summarize_batch_metrics(
    per_seed: list[dict[str, Any]], summary: dict[str, Any]
) -> dict[str, Any]:
    """把各 seed 的 metrics 汇总成批量 metrics.json（契约 §6.3 的「汇总」）。"""
    averaged = (
        "nrmse_along",
        "nrmse_cross",
        "coverage_2sigma_along",
        "coverage_2sigma_cross",
        "nees",
        "meet_error_m",
    )
    aggregate: dict[str, Any] = {}
    for name in averaged:
        values = [
            float(item["metrics"][name])
            for item in per_seed
            if isinstance(item["metrics"].get(name), (int, float))
        ]
        aggregate[name] = sum(values) / len(values) if values else None
    aggregate["rows_pred_error_sample"] = sum(
        int(item["metrics"].get("rows_pred_error_sample", 0)) for item in per_seed
    )
    aggregate["bbox_fallback_samples"] = sum(
        int(item["metrics"].get("bbox_fallback_samples", 0)) for item in per_seed
    )
    return {
        "scenario": summary["scenario"],
        "mode": summary["mode"],
        "t0_ms": summary["t0_ms"],
        "seed_from": summary["seed_from"],
        "seed_to": summary["seed_to"],
        "evidence_class": EVIDENCE_CLASS,
        "runs": per_seed,
        "aggregate": aggregate,
    }


def run_batch(
    scenario_path: Path,
    mode: str,
    seed_from: int,
    seed_to: int,
    out_root: Path,
    t0_ms: int,
) -> dict[str, Any]:
    """批量入口（契约 §6.2 约定签名）。

    单 run 等价于闭区间长度为 1 的批量：``seed_from == seed_to``。
    批量时每个 seed 的产物写到 ``out_root/seed_<n>``，根目录另写汇总
    ``manifest.json`` 与 ``metrics.json``（契约 §6.3）。
    """
    if seed_to < seed_from:
        raise ValueError(
            f"--seed-to 不得小于 --seed-from（契约 §6.2）：{seed_from}..{seed_to}"
        )
    scenario = load_scenario(scenario_path)
    t0_ms = resolve_t0_ms(scenario, t0_ms)
    repo_root = Path(__file__).resolve().parents[2]
    is_batch = seed_from != seed_to

    runs: list[dict[str, Any]] = []
    per_seed: list[dict[str, Any]] = []
    for seed in range(seed_from, seed_to + 1):
        run_dir = out_root / f"seed_{seed}" if is_batch else out_root
        command = (
            f"python testdata/mock/run_scenarios.py --scenario {scenario_path} "
            f"--mode {mode} --seed {seed} --out {run_dir} --t0-ms {t0_ms}"
        )
        result = run_single(scenario, mode, seed, run_dir, repo_root, command, t0_ms)
        runs.append(
            {
                "seed": seed,
                "out_dir": str(run_dir),
                "row_count": int(result["manifest"]["rows"]),
            }
        )
        per_seed.append({"seed": seed, "metrics": result["metrics"]})

    summary: dict[str, Any] = {
        "scenario": str(scenario_path),
        "mode": mode,
        "t0_ms": t0_ms,
        "seed_from": seed_from,
        "seed_to": seed_to,
        "truth_source": str(scenario["truth_source"]),
        "pred_version": int(scenario["pred_version"]),
        "runs": runs,
        "total_rows": sum(item["row_count"] for item in runs),
    }

    out_root.mkdir(parents=True, exist_ok=True)
    manifest_path = out_root / "manifest.json"
    if is_batch:
        manifest_path.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        (out_root / "metrics.json").write_text(
            json.dumps(_summarize_batch_metrics(per_seed, summary), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    else:
        # 单 run：run_single 已写契约 §4 十四字段，这里合并契约 §6.3 的批量字段。
        merged = json.loads(manifest_path.read_text(encoding="utf-8"))
        merged.update(summary)
        manifest_path.write_text(
            json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    print(
        f"[run_scenarios] 批量完成 seeds={seed_from}..{seed_to} "
        f"total_rows={summary['total_rows']} out={out_root}"
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(
        description="模拟数据批次编排器（契约 §6.2 · 宋冰鑫）",
    )
    parser.add_argument("--scenario", required=True, help="scenario.yaml 路径")
    parser.add_argument(
        "--mode",
        required=True,
        choices=["live", "offline"],
        help="live=实时链路；offline=离线夹具（契约 §3）",
    )
    parser.add_argument("--seed", type=int, help="单 run 随机种子（与 --seed-from 互斥）")
    parser.add_argument("--seed-from", dest="seed_from", type=int, help="批量种子区间起点")
    parser.add_argument("--seed-to", dest="seed_to", type=int, help="批量种子区间终点，含")
    parser.add_argument("--out", required=True, help="产物输出目录")
    parser.add_argument(
        "--t0-ms",
        dest="t0_ms",
        type=int,
        default=DEFAULT_T0_MS,
        help=f"统一时基，默认 {DEFAULT_T0_MS}（契约 §6.2）",
    )
    args = parser.parse_args()

    if args.seed is not None and args.seed_from is not None:
        parser.error("--seed 与 --seed-from 不能同时给出（契约 §6.2）")
    if args.seed is None and args.seed_from is None:
        parser.error("必须给出 --seed 或 --seed-from／--seed-to 之一（契约 §6.2）")
    if args.seed_from is not None and args.seed_to is None:
        parser.error("给出 --seed-from 时必须同时给出 --seed-to（契约 §6.2）")
    if args.seed_from is not None and args.seed is None and args.seed_to < args.seed_from:
        parser.error("--seed-to 不得小于 --seed-from（契约 §6.2）")

    if args.seed is not None:
        seed_from = seed_to = args.seed
    else:
        seed_from, seed_to = args.seed_from, args.seed_to

    repo_root = Path(__file__).resolve().parents[2]
    scenario_path = Path(args.scenario)
    if not scenario_path.is_absolute():
        scenario_path = (repo_root / scenario_path).resolve()
    out_dir = Path(args.out)
    if not out_dir.is_absolute():
        out_dir = (repo_root / out_dir).resolve()

    try:
        run_batch(scenario_path, args.mode, seed_from, seed_to, out_dir, args.t0_ms)
    except Exception as exc:  # noqa: BLE001 — 顶层统一报错，不吞堆栈
        print(f"[run_scenarios] 失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
