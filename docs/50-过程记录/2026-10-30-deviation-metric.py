#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
T4 偏差指标计算器（卢肄文 10-30）
口径见 docs/50-过程记录/2026-10-30-卢肄文-偏差指标口径草案.md

真值两种来源：
  --truth db    : ship_ais 导出 CSV（ts,lon,lat），严格口径
  --truth chain : 帧内 tau=0 链（代理真值，会低估偏差，仅作对照）
"""
import argparse
import bisect
import json
import math
import random
import statistics
from collections import defaultdict

# ── 参考折线（口径草案 §1.2）───────────────────────────────────────────────
V = [(115.852, 28.646), (115.859, 28.663), (115.879, 28.692)]
LAT_REF = 28.666
KY = 111320.0
KX = 111320.0 * math.cos(math.radians(LAT_REF))
EXTEND_M = 15000.0          # 口径 §1.3 投影专用延长
NOMINAL_V = 4.115           # 8 节


def build_polyline():
    pts = [(0.0, 0.0)]
    for i in range(len(V) - 1):
        pts.append(((V[i + 1][0] - V[0][0]) * KX, (V[i + 1][1] - V[0][1]) * KY))
    dx = pts[-1][0] - pts[-2][0]
    dy = pts[-1][1] - pts[-2][1]
    L = math.hypot(dx, dy)
    pts.append((pts[-1][0] + dx / L * EXTEND_M, pts[-1][1] + dy / L * EXTEND_M))
    cum = [0.0]
    for i in range(len(pts) - 1):
        cum.append(cum[-1] + math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]))
    return pts, cum


PL, CUM = build_polyline()


def project(lon, lat):
    """返回 (s, e)：s 累计弧长 m，e 横向偏移 m（前进方向右侧为正）。"""
    px = (lon - V[0][0]) * KX
    py = (lat - V[0][1]) * KY
    best = None
    for i in range(len(PL) - 1):
        ax, ay = PL[i]
        bx, by = PL[i + 1]
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
        cx, cy = ax + t * dx, ay + t * dy
        d = math.hypot(px - cx, py - cy)
        if best is None or d < best[0]:
            L = math.sqrt(L2)
            e = (dy * (px - ax) - dx * (py - ay)) / L
            best = (d, CUM[i] + t * L, e)
    return best[1], best[2]


def load_frames(path):
    rows = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            if o.get('type') != 'ship_traj':
                continue
            rows.append(o['data'])
    rows.sort(key=lambda r: r['t0'])
    return rows


def load_truth_csv(path):
    out = []
    with open(path, encoding='utf-8') as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line:
                continue
            parts = line.replace('\t', ',').split(',')
            if i == 0 and not parts[0].replace('.', '').isdigit():
                continue
            ts = float(parts[0])
            if ts < 1e11:
                ts *= 1000.0
            out.append((ts, float(parts[1]), float(parts[2])))
    out.sort()
    return out


class Truth:
    def __init__(self, rows):
        self.rows = rows
        self.keys = [r[0] for r in rows]
        self.s = [project(r[1], r[2])[0] for r in rows]

    def nearest(self, t, tol=500.0):
        i = bisect.bisect_left(self.keys, t)
        cand = [self.rows[j] for j in (i - 1, i, i + 1)
                if 0 <= j < len(self.rows) and abs(self.keys[j] - t) <= tol]
        if not cand:
            return None
        return min(cand, key=lambda r: abs(r[0] - t))

    def s_at(self, t, tol=500.0):
        r = self.nearest(t, tol)
        return None if r is None else project(r[1], r[2])[0]


def stats(xs):
    n = len(xs)
    if n == 0:
        return None
    ab = sorted(abs(x) for x in xs)
    return {
        'N': n,
        'MAE': sum(ab) / n,
        'RMSE': math.sqrt(sum(x * x for x in xs) / n),
        'MAX': ab[-1],
        'P95': ab[min(n - 1, int(0.95 * n))],
        'BIAS': sum(xs) / n,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--frames', required=True)
    ap.add_argument('--truth', default='chain', choices=['db', 'chain'])
    ap.add_argument('--truth-csv')
    ap.add_argument('--tol-ms', type=float, default=500.0)
    ap.add_argument('--json-out')
    ap.add_argument('--t-max-rel', type=float, default=None,
                    help='只保留相对首帧不超过该秒数的帧（用于截取单遍干净数据）')
    args = ap.parse_args()

    frames = load_frames(args.frames)
    if args.t_max_rel is not None:
        t0 = frames[0]['t0']
        frames = [r for r in frames if (r['t0'] - t0) / 1000.0 <= args.t_max_rel]
    print(f'帧数 {len(frames)}  t0 {frames[0]["t0"]} .. {frames[-1]["t0"]}')

    segs = [[0]]
    for i in range(1, len(frames)):
        if frames[i]['version'] < frames[i - 1]['version']:
            segs.append([i])
        else:
            segs[-1].append(i)
    print(f'分段 {len(segs)}  ' + ' | '.join(f'{s[0]}..{s[-1]}({len(s)})' for s in segs))

    if args.truth == 'db':
        truth = Truth(load_truth_csv(args.truth_csv))
        print(f'真值 ship_ais {len(truth.rows)} 条  {truth.rows[0][0]} .. {truth.rows[-1][0]}')
    else:
        truth = Truth(sorted((r['t0'], r['points'][0]['lon'], r['points'][0]['lat']) for r in frames))
        print(f'真值 帧内 tau=0 链 {len(truth.rows)} 条（代理口径，会低估偏差）')

    # ── 逐帧诊断：帧内隐含速度 v0、缺口 ──
    v0s = []
    for i, r in enumerate(frames):
        s0, _ = project(r['points'][0]['lon'], r['points'][0]['lat'])
        s1, _ = project(r['points'][1]['lon'], r['points'][1]['lat'])
        v0s.append((s1 - s0) / 60.0)

    # ── R4：复位后重收敛窗口 ──
    # 判据：帧间 tau=0 链在航道上「真后退」（s 减小 >5 m）。仿真船不会后退，
    # 后退只可能来自船位复位而预测侧状态未复位。冷启动的速度爬升是 s 照常前进、
    # 只是 v0 偏小，不触发本条（它属于正常但可报告的收敛过程）。
    chain_s = [project(r['points'][0]['lon'], r['points'][0]['lat'])[0] for r in frames]
    back = [False] * len(frames)
    for i in range(1, len(frames)):
        if chain_s[i] < chain_s[i - 1] - 5.0:
            back[i] = True
    r4 = [False] * len(frames)
    i = 0
    while i < len(frames):
        if back[i]:
            j = i
            while j < len(frames) and back[j]:
                j += 1
            for k in range(max(0, i - 3), min(len(frames), j + 3)):
                r4[k] = True
            i = j
            continue
        i += 1

    base_course = statistics.mode([round(r['courseDeg'], 2) for r in frames])
    pairs = []
    drop = defaultdict(int)

    for si, seg in enumerate(segs):
        seg_gaps = [(frames[seg[j]]['t0'], frames[seg[j + 1]]['t0'])
                    for j in range(len(seg) - 1)
                    if frames[seg[j + 1]]['t0'] - frames[seg[j]]['t0'] > 5000]
        for i in seg:
            r = frames[i]
            k_max = min(int(r['validitySec'] // 60), len(r['points']) - 1)
            n_here = k_max + 1
            if r.get('premise') != 'VALID':
                drop['R1 前提失效'] += n_here
                continue
            if round(r['courseDeg'], 2) != base_course:
                drop['R5 越框回落'] += n_here
                continue
            if r4[i]:
                drop['R4 复位重收敛'] += n_here
                continue
            s_start = project(r['points'][0]['lon'], r['points'][0]['lat'])[0]
            for k in range(n_here):
                pt = r['points'][k]
                t_target = r['t0'] + 60000 * k
                if any(a < t_target < b for a, b in seg_gaps):
                    drop['R3 跨重启缺口'] += 1
                    continue
                tv = truth.nearest(t_target, args.tol_ms)
                if tv is None:
                    drop['R6 无真值'] += 1
                    continue
                s_p, e_p = project(pt['lon'], pt['lat'])
                s_t, e_t = project(tv[1], tv[2])
                # 船在仿真里不会后退：真值倒退说明区间里发生过复位
                if s_t < s_start - 20.0:
                    drop['R3 跨复位跳变'] += 1
                    continue
                ds, de = s_p - s_t, e_p - e_t
                pairs.append({'seg': si, 'i': i, 'k': k, 'tau': 60 * k, 't': t_target,
                              'ds': ds, 'de': de, 'rho': math.hypot(ds, de),
                              'e_p': e_p, 'sa': pt['sigmaAlong'], 'sc': pt['sigmaCross']})

    print(f'跳数 {len(pairs)}  剔除 {dict(sorted(drop.items()))}')

    def layer(sel, tag):
        by = defaultdict(list)
        for p in pairs:
            if sel(p):
                by[p['tau']].append(p)
        print(f'\n### {tag}')
        hdr = ' tau  N_跳   N_独立 | MAE_along RMSE_along  max_along | MAE_cross RMSE_cross max_cross | n_along n_cross | 2σ覆盖  1σ命中'
        print(hdr)
        out = []
        for tau in sorted(by):
            g = by[tau]
            a = stats([p['ds'] for p in g])
            b = stats([p['de'] for p in g])
            sa = sum(p['sa'] for p in g) / len(g)
            sc = sum(p['sc'] for p in g) / len(g)
            na, nc = a['RMSE'] / sa, b['RMSE'] / sc
            nd = len({p['t'] // 1000 for p in g})
            c2 = sum(1 for p in g if abs(p['ds']) <= 2 * p['sa'] and abs(p['de']) <= 2 * p['sc']) / len(g)
            c1 = sum(1 for p in g if abs(p['ds']) <= p['sa'] and abs(p['de']) <= p['sc']) / len(g)
            print(f'{tau:4d} {a["N"]:6d} {nd:7d} | {a["MAE"]:9.1f} {a["RMSE"]:10.1f} {a["MAX"]:10.1f} | '
                  f'{b["MAE"]:9.1f} {b["RMSE"]:10.1f} {b["MAX"]:9.1f} | {na:6.2f} {nc:6.2f} | '
                  f'{c2*100:6.1f}% {c1*100:6.1f}%')
            out.append({'tau': tau, 'N': a['N'], 'N_indep': nd,
                        'mae_along': a['MAE'], 'rmse_along': a['RMSE'], 'max_along': a['MAX'],
                        'mae_cross': b['MAE'], 'rmse_cross': b['RMSE'], 'max_cross': b['MAX'],
                        'n_along': na, 'n_cross': nc, 'mean_sa': sa, 'mean_sc': sc,
                        'cov2s': c2, 'cov1s': c1})
        return out

    layers = layer(lambda p: True, '全部（按 tau 分层）')

    ta = stats([p['ds'] for p in pairs])
    tc = stats([p['de'] for p in pairs])
    tr = stats([p['rho'] for p in pairs])
    te = stats([p['e_p'] for p in pairs])
    indep = len({p['t'] // 1000 for p in pairs})
    print(f'\n### 合计  N_跳={len(pairs)}  N_独立(按秒去重)={indep}')
    for name, st in (('沿程 Δs (m)', ta), ('横向 Δe (m)', tc), ('点位 ρ (m)', tr), ('预测点垂距 |e_p| (m)', te)):
        print(f"{name:20s} MAE={st['MAE']:9.2f}  RMSE={st['RMSE']:9.2f}  max={st['MAX']:9.2f}  "
              f"P95={st['P95']:9.2f}  偏置={st['BIAS']:+8.2f}")

    cov2 = sum(1 for p in pairs if abs(p['ds']) <= 2 * p['sa'] and abs(p['de']) <= 2 * p['sc']) / len(pairs)
    cov1 = sum(1 for p in pairs if abs(p['ds']) <= p['sa'] and abs(p['de']) <= p['sc']) / len(pairs)
    print(f'\n2σ 带覆盖率 {cov2*100:.1f}%   1σ 命中率 {cov1*100:.1f}%')

    # ── 块 bootstrap：按秒归档的独立时刻，块长 60 s ──
    random.seed(20261030)
    by_bin = defaultdict(list)
    for p in pairs:
        by_bin[p['t'] // 1000].append(p)
    bins = sorted(by_bin)
    t_origin = bins[0]
    blocks = defaultdict(list)
    for b in bins:
        blocks[(b - t_origin) // 60].append(b)
    bk = sorted(blocks)
    B = 2000
    maes, rmses = [], []
    for _ in range(B):
        picked = []
        for _ in range(len(bk)):
            for b in blocks[random.choice(bk)]:
                picked.extend(by_bin[b])
        maes.append(sum(abs(p['ds']) for p in picked) / len(picked))
        rmses.append(math.sqrt(sum(p['ds'] ** 2 for p in picked) / len(picked)))
    maes.sort(); rmses.sort()
    lo, hi = int(0.025 * len(maes)), int(0.975 * len(maes))
    print(f'沿程 MAE 95%CI [{maes[lo]:.1f}, {maes[hi]:.1f}] m    '
          f'RMSE 95%CI [{rmses[lo]:.1f}, {rmses[hi]:.1f}] m  （块 bootstrap，块={len(bk)}×60s，B={B}）')

    # ── 帧内隐含速度的时间演变（诊断冷启动爬升）──
    print('\n### 帧内隐含速度 v0（每 60 帧采样）')
    print('idx  tRel(s)   v0(m/s)  R4')
    for i in range(0, len(frames), max(1, len(frames) // 14)):
        print(f'{i:4d} {(frames[i]["t0"]-frames[0]["t0"])/1000:8.0f} {v0s[i]:9.3f}  {"X" if r4[i] else ""}')

    if args.json_out:
        with open(args.json_out, 'w', encoding='utf-8') as f:
            json.dump({'frames': len(frames), 'pairs': len(pairs), 'indep': indep,
                       'drop': dict(drop), 'layers': layers,
                       'cov2': cov2, 'cov1': cov1,
                       'total_along': ta, 'total_cross': tc, 'total_rho': tr, 'total_ep': te,
                       'mae_ci': [maes[lo], maes[hi]], 'rmse_ci': [rmses[lo], rmses[hi]],
                       'v0_series': v0s}, f, ensure_ascii=False, indent=1)
        print('\nJSON ->', args.json_out)


if __name__ == '__main__':
    main()
