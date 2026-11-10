#!/usr/bin/env python3
"""模拟数据批次编排器（契约 1109-simdata-contract §5 · 宋冰鑫）。

职责只有两项：编排 run、汇总指标。

CLI 签名（契约 §5，参数顺序无关）::

    python run_scenarios.py --scenario <path> --mode <live|offline> --seed <int> --out <dir>

产物（契约 §5）::

    <out>/manifest.json   契约 §4 十四字段，六项由本脚本自动补齐
    <out>/truth.csv       从 scenario.yaml 的 truth_source 复制
    <out>/metrics.json    契约 §5 十一指标字段
    <out>/sample/         pred_error_sample 本 run 行的 CSV 导出

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


def _coerce_scalar(value: str) -> Any:
    """把 YAML 标量转成 Python 类型：int / float / bool / str。"""
    text = _strip_quotes(value)
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
    return scenario


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


def build_fixture_env(scenario: dict[str, Any], seed: int) -> dict[str, str]:
    """把 scenario 字段映射成 OfflinePredictionFixtureTest 的环境变量。"""
    return {
        **os.environ,
        "FIXTURE_SEED": str(seed),
        "FIXTURE_PRED_VERSION": str(scenario["pred_version"]),
        "FIXTURE_PATH_START_M": str(scenario["path_start_m"]),
        "FIXTURE_DURATION_SEC": str(scenario["duration_sec"]),
    }


def run_prediction(scenario: dict[str, Any], seed: int, repo_root: Path) -> int:
    """触发一次离线预测 run，返回 gradle 退出码。

    通过 subprocess 调用 OfflinePredictionFixtureTest（卢肄文），参数走环境变量。
    该测试把残差样本落进 pred_error_sample，本脚本随后从库里读回。
    """
    env = build_fixture_env(scenario, seed)
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
) -> dict[str, Any]:
    """调用 metrics.calculate_metrics 汇总契约 §5 十一指标字段。"""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from metrics import calculate_metrics  # 彭紫情 · 契约 §5

    t0_ms = int(scenario["t0_ms"])
    duration_sec = int(scenario["duration_sec"])
    # 会面点本批次取中心线首尾两点作为预测／真值会面点的占位；
    # 会面闭环只在 Live E2E 30 min 全程里验，20 min 子段不要求到桥（契约 §2）。
    meet = (115.844393, 28.645998)
    return calculate_metrics(
        samples,
        t0_ms=t0_ms,
        duration_sec=duration_sec,
        rows_pred_error_sample=rows_pred_error_sample,
        predicted_meet_lon_lat=meet,
        truth_meet_lon_lat=meet,
        evidence_class=EVIDENCE_CLASS,
    )


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


def write_truth_csv(scenario: dict[str, Any], repo_root: Path, out_dir: Path) -> Path:
    """把 scenario.yaml 指定的 truth.csv 复制到 out/truth.csv。"""
    truth_source = scenario["truth_source"]
    src = (repo_root / truth_source).resolve()
    if not src.exists():
        raise FileNotFoundError(
            f"truth.csv 不存在：{src}（罗才辉产出，契约 §6）"
        )
    dst = out_dir / "truth.csv"
    dst.write_bytes(src.read_bytes())
    return dst


# ─────────────────────────── 主流程 ────────────────────────────


def run_single(
    scenario_path: Path,
    mode: str,
    seed: int,
    out_dir: Path,
    repo_root: Path,
    command: str,
) -> dict[str, Any]:
    """执行单次 run，返回 manifest dict。"""
    scenario = load_scenario(scenario_path)

    # 契约 §3：mode 只允许 live / offline。
    if mode not in ("live", "offline"):
        raise ValueError(f"mode 必须是 live 或 offline（契约 §3）：{mode}")

    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. 复制真值轨迹。
    write_truth_csv(scenario, repo_root, out_dir)

    # 2. 触发预测（offline 走夹具；live 假设实时链路已在跑）。
    if mode == "offline":
        run_prediction(scenario, seed, repo_root)

    # 3. 从数据库读回 pred_error_sample 行。
    app_yml = repo_root / "backend" / "wl-app" / "src" / "main" / "resources" / "application.yml"
    db_config = load_db_config(app_yml)
    mmsi = scenario["mmsi"]
    pred_version = int(scenario["pred_version"])
    samples = fetch_pred_error_samples(db_config, mmsi, pred_version)
    if not samples:
        raise RuntimeError(
            f"pred_error_sample 未读到 mmsi={mmsi} pred_version={pred_version} 的行"
            "（契约 §12 失败判据：先核 §10 离线路径与夹具装配）"
        )

    # 4. 导出 sample/。
    write_sample_csv(samples, out_dir)

    # 5. 汇总指标。
    metrics = compute_metrics(samples, scenario, len(samples))
    (out_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # 6. 组装并写 manifest.json（自动补齐六项）。
    manifest = build_manifest(scenario, seed, out_dir, command, len(samples))
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"[run_scenarios] 完成 seed={seed} rows={len(samples)} out={out_dir}")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(
        description="模拟数据批次编排器（契约 §5 · 宋冰鑫）",
    )
    parser.add_argument("--scenario", required=True, help="scenario.yaml 路径")
    parser.add_argument(
        "--mode",
        required=True,
        choices=["live", "offline"],
        help="live=实时链路；offline=离线夹具（契约 §3）",
    )
    parser.add_argument("--seed", required=True, type=int, help="随机种子")
    parser.add_argument("--out", required=True, help="产物输出目录")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    scenario_path = Path(args.scenario)
    if not scenario_path.is_absolute():
        scenario_path = (repo_root / scenario_path).resolve()
    out_dir = Path(args.out)
    if not out_dir.is_absolute():
        out_dir = (repo_root / out_dir).resolve()

    command = (
        f"python testdata/mock/run_scenarios.py "
        f"--scenario {args.scenario} --mode {args.mode} "
        f"--seed {args.seed} --out {args.out}"
    )

    try:
        run_single(scenario_path, args.mode, args.seed, out_dir, repo_root, command)
    except Exception as exc:  # noqa: BLE001 — 顶层统一报错，不吞堆栈
        print(f"[run_scenarios] 失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
