"""Calculate simulation metrics from paired prediction and noiseless truth samples.

The caller supplies already paired rows. Every row needs ``sample_ts`` (epoch
milliseconds), ``tau_sec``, ``err_along_m``, ``err_cross_m``,
``sigma_along_m``, and ``sigma_cross_m``. The database row count is supplied
separately because filtering invalid pairs must not change the insertion count.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from math import asin, cos, isfinite, radians, sin, sqrt
from typing import Any


EVIDENCE_CLASSES = ("simulation_closed_loop", "externally_anchored")
NORMAL_END_SEC = 945
METRIC_NAMES = (
    "nrmse_along",
    "nrmse_cross",
    "coverage_2sigma_along",
    "coverage_2sigma_cross",
    "nees",
)


def _number(row: Mapping[str, Any], name: str, alternative: str | None = None) -> float:
    value = row.get(name, row.get(alternative) if alternative else None)
    if isinstance(value, bool):
        raise ValueError(f"{name} must be a finite number")
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be a finite number") from error
    if not isfinite(result):
        raise ValueError(f"{name} must be a finite number")
    return result


def _summarize(rows: list[tuple[float, float, float, float]]) -> dict[str, float | None]:
    if not rows:
        return dict.fromkeys(METRIC_NAMES)
    count = len(rows)
    along_squared = sum((along / sigma_along) ** 2 for along, _, sigma_along, _ in rows)
    cross_squared = sum((cross / sigma_cross) ** 2 for _, cross, _, sigma_cross in rows)
    return {
        "nrmse_along": sqrt(along_squared / count),
        "nrmse_cross": sqrt(cross_squared / count),
        "coverage_2sigma_along": sum(abs(along) <= 2 * sigma_along for along, _, sigma_along, _ in rows) / count,
        "coverage_2sigma_cross": sum(abs(cross) <= 2 * sigma_cross for _, cross, _, sigma_cross in rows) / count,
        "nees": (along_squared + cross_squared) / count,
    }


def _distance_m(predicted: tuple[float, float], truth: tuple[float, float]) -> float:
    try:
        pred_lon, pred_lat = map(float, predicted)
        truth_lon, truth_lat = map(float, truth)
    except (TypeError, ValueError) as error:
        raise ValueError("meeting points must contain longitude and latitude") from error
    if not all(map(isfinite, (pred_lon, pred_lat, truth_lon, truth_lat))):
        raise ValueError("meeting point coordinates must be finite")
    if not all(-180 <= lon <= 180 for lon in (pred_lon, truth_lon)) or not all(
        -90 <= lat <= 90 for lat in (pred_lat, truth_lat)
    ):
        raise ValueError("meeting point coordinates are outside WGS-84 ranges")
    latitude_delta = radians(truth_lat - pred_lat)
    longitude_delta = radians(truth_lon - pred_lon)
    a = sin(latitude_delta / 2) ** 2 + cos(radians(pred_lat)) * cos(radians(truth_lat)) * sin(longitude_delta / 2) ** 2
    return 2 * 6371008.8 * asin(min(1.0, sqrt(a)))


def calculate_metrics(
    samples: Iterable[Mapping[str, Any]],
    *,
    t0_ms: int,
    duration_sec: int,
    rows_pred_error_sample: int,
    predicted_meet_lon_lat: tuple[float, float],
    truth_meet_lon_lat: tuple[float, float],
    evidence_class: str = "simulation_closed_loop",
) -> dict[str, Any]:
    """Return contract metrics, keeping evidence classes and time segments apart.

    The five top-level statistical metrics describe only the current evidence
    class's normal prediction segment. The fallback segment has its own metrics
    under ``by_evidence_class`` and never contributes to top-level NEES.
    ``sample_ts`` means epoch milliseconds; ``tau_sec`` means prediction lag.
    """
    if evidence_class not in EVIDENCE_CLASSES:
        raise ValueError("evidence_class is not recognized")
    if isinstance(duration_sec, bool) or not isinstance(duration_sec, int) or duration_sec <= 0:
        raise ValueError("duration_sec must be a positive integer")
    if isinstance(rows_pred_error_sample, bool) or not isinstance(rows_pred_error_sample, int) or rows_pred_error_sample < 0:
        raise ValueError("rows_pred_error_sample must be a nonnegative integer")
    if isinstance(t0_ms, bool) or not isinstance(t0_ms, int):
        raise ValueError("t0_ms must be an integer epoch millisecond value")

    groups: dict[str, dict[str, list[tuple[float, float, float, float]]]] = {
        name: {"normal_prediction": [], "bbox_fallback": []} for name in EVIDENCE_CLASSES
    }
    tau_grid: set[int] = set()
    for row in samples:
        row_class = row.get("evidence_class", evidence_class)
        if row_class not in EVIDENCE_CLASSES:
            raise ValueError("sample evidence_class is not recognized")
        sample_ts = _number(row, "sample_ts", "sampleTs")
        tau = _number(row, "tau_sec", "tauSec")
        elapsed_sec = (sample_ts - t0_ms) / 1000
        if not 0 <= elapsed_sec <= duration_sec:
            raise ValueError("sample timestamp is outside the run")
        if not tau.is_integer() or not 0 < tau <= duration_sec:
            raise ValueError("tau_sec must be a positive integer within the run duration")
        along = _number(row, "err_along_m", "errAlongM")
        cross = _number(row, "err_cross_m", "errCrossM")
        sigma_along = _number(row, "sigma_along_m", "sigmaAlongM")
        sigma_cross = _number(row, "sigma_cross_m", "sigmaCrossM")
        if sigma_along <= 0 or sigma_cross <= 0:
            raise ValueError("sigma values must be positive")
        segment = "normal_prediction" if elapsed_sec < NORMAL_END_SEC else "bbox_fallback"
        groups[row_class][segment].append((along, cross, sigma_along, sigma_cross))
        tau_grid.add(int(tau))

    if not tau_grid:
        raise ValueError("at least one paired prediction sample is required")
    if rows_pred_error_sample == 0:
        raise ValueError("database row count cannot be zero when paired samples exist")

    by_class: dict[str, dict[str, Any]] = {}
    for name, segments in groups.items():
        normal = segments["normal_prediction"]
        fallback = segments["bbox_fallback"]
        by_class[name] = {
            "normal_prediction": {"samples": len(normal), **_summarize(normal)},
            "bbox_fallback": {"samples": len(fallback), **_summarize(fallback)},
        }
    current_normal = by_class[evidence_class]["normal_prediction"]
    if current_normal["samples"] == 0:
        raise ValueError("the selected evidence class has no normal prediction samples")
    result = {name: current_normal[name] for name in METRIC_NAMES}
    result.update(
        meet_error_m=_distance_m(predicted_meet_lon_lat, truth_meet_lon_lat),
        tau_grid_sec=sorted(tau_grid),
        bbox_fallback_samples=sum(len(groups[name]["bbox_fallback"]) for name in EVIDENCE_CLASSES),
        rows_pred_error_sample=rows_pred_error_sample,
        evidence_class=evidence_class,
        by_evidence_class=by_class,
    )
    return result
