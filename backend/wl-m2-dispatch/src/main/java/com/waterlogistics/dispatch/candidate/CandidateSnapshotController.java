package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.CandidateSnapshotView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候选留痕查询（11-19 序号 3-1 · 张睿哲；供 3-2 前端接）。
 *
 * <ul>
 *   <li>{@code GET /api/orders/{orderId}/candidates}——按订单回查逐候选九项留痕。</li>
 * </ul>
 *
 * <p><b>为什么空结果回 200＋空数组而不是 404</b>：展示层要区分的是「这单有没有派单记录」，
 * 而不是「订单号拼错没有」；「还没派过单」用空列表表达，前端直接显示空态即可，
 * 不必为两种情形写两套提示。订单号本身是否合法由下单入口校验。
 *
 * <p><b>为什么单独一个控制器</b>：{@code OrderController} 的 owner 是卢肄文，
 * 同一文件只有一个 owner；本端点只读本任务新建的快照表，独立成类不触碰他人映射表。
 */
@RestController
public class CandidateSnapshotController {

    private final CandidateSnapshotService candidateSnapshotService;

    public CandidateSnapshotController(CandidateSnapshotService candidateSnapshotService) {
        this.candidateSnapshotService = candidateSnapshotService;
    }

    /**
     * 按订单回查候选九项留痕。
     *
     * @param orderId 订单号
     * @return 候选视图列表（按生成序）；查无记录为空数组
     */
    @GetMapping(ApiPath.CANDIDATES_TEMPLATE)
    public List<CandidateSnapshotView> list(@PathVariable("orderId") String orderId) {
        return candidateSnapshotService.listByOrder(orderId);
    }
}
