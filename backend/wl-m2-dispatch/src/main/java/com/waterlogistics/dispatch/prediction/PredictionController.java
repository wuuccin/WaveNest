package com.waterlogistics.dispatch.prediction;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 预测看台只读 HTTP 入口（阶段二 1 后端，M2 · 张睿哲 · 11-02）。
 *
 * <pre>
 *   GET /api/prediction/ships?page=1&amp;size=50   在航船摘要（分页）
 *   GET /api/prediction/ships/{mmsi}            单船完整预测点列
 * </pre>
 *
 * <h3>★ 路径为什么是本地常量</h3>
 * 契约 {@code ApiPath} 目前未登记预测看台两项。按纪律「{@code wl-contract} 不私自改」，
 * 先以本地常量承载，并在下方标注待登记项：
 * <b>待收口方在 {@code ApiPath} 登记后，本类只改这两行常量引用。</b>
 * 路径口径与 {@code MonitorController} 同构（{@code /api/} 前缀、资源复数名词）。
 *
 * <h3>字段契约（前端对接以此为准，杨添爻按此字段名取值）</h3>
 * <ul>
 *   <li>列表：{@code ships[{mmsi, version, premise, courseDeg, pointCount, validityRemainingSec}]}
 *       ＋ {@code total}／{@code page}／{@code size}；</li>
 *   <li>单船：{@code {mmsi, version, t0Ms, courseDeg, validitySec, premise, points[...]}}，
 *       {@code points} 与 WS 帧 {@code ship_traj} 同源；</li>
 *   <li>分页：{@code page} 从 1 起，{@code size} 默认 50、上限 200；</li>
 *   <li>错误码：单船不存在 → 404；参数不合法不报错，按口径归一（page&lt;1→1、size≤0→50、size&gt;200→200）。</li>
 * </ul>
 *
 * <h3>只读</h3>
 * 两个端点只读 {@link PredictionService} 的最新快照，不刷新预测、不改订单、
 * 不触发重算——看台看到的是预测链路的<b>镜像</b>，不是新的真相来源。
 */
@RestController
public class PredictionController {

    /** 在航船摘要路径——待 {@code ApiPath} 登记（见类注释）。 */
    static final String PREDICTION_SHIPS = "/api/prediction/ships";

    /** 单船预测点列路径——待 {@code ApiPath} 登记（见类注释）。 */
    static final String PREDICTION_SHIP = "/api/prediction/ships/{mmsi}";

    private final PredictionQueryService queryService;

    public PredictionController(PredictionQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 在航船摘要分页。
     *
     * <p>空库返回 {@code {"ships":[],"total":0,"page":1,"size":50}}，<b>不返回 404</b>
     * ——「还没有在航船」与「接口不存在」是两件事。
     */
    @GetMapping(PREDICTION_SHIPS)
    public PredictionQueryService.ShipsResponse ships(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return queryService.ships(page, size);
    }

    /**
     * 单船完整预测点列。
     *
     * @throws ResponseStatusException 404：该船尚无预测快照
     */
    @GetMapping(PREDICTION_SHIP)
    public PredictionQueryService.PredictionDetail ship(@PathVariable("mmsi") String mmsi) {
        return queryService.detail(mmsi)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "该船暂无预测快照：" + mmsi));
    }
}
