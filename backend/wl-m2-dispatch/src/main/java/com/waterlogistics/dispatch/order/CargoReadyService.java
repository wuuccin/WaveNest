package com.waterlogistics.dispatch.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 货物到巢登记（M2 · 卢肄文 · 11-05 序号 1）。
 *
 * <p>对应 {@code POST /api/orders/{orderId}/cargo-ready}：把该订单的货物置为
 * {@code goods.status = 已就绪}（{@link GoodsStatus#READY}）。它<b>只做这一件事</b>——
 * 不排产、不放行、不唤醒订单；「货到巢关」的状态推进（{@code ACCEPTED_WAIT_WAKE + WAIT_CARGO}
 * → {@code WAIT_DISPATCH}）是《11-05 任务清单》序号 2 张睿哲的任务，接在本服务之后。
 *
 * <h3>为什么单独一个服务，而不是塞进 OrderAdmissionService</h3>
 * <p>接单判定的输入是「船位、航段、闸门、既有订单」，货物状态是它的一路<b>输入</b>而非输出；
 * 反过来，货物就绪是派单链的<b>触发条件</b>。两者读写方向相反，塞进同一个类会让
 * 「下单」与「货到」两条入口共享一个可变状态机，序号 2 接放行逻辑时必然再次拆分。
 *
 * <h3>幂等口径（本端点最容易被误用的地方）</h3>
 * <ol>
 *   <li><b>只翻「未就绪 → 已就绪」</b>：已经「已就绪」的行直接跳过、不算改动。
 *       重复调用因此是零写入，回 {@code changed=false} ＋ {@code ALREADY_READY}。</li>
 *   <li><b>不把后置状态拽回来</b>：「已装机／已交付／已回收／灭失」在后，
 *       把已装机改成已就绪等于让货物从天上回到巢里——状态机上是倒退，故拒（{@code GOODS_STATE_FROZEN}）。</li>
 *   <li><b>写侧不做 upsert</b>：查无货物记录时不新建。货物记录由 M6 在下单落库时一并建，
 *       本端点凭空造一条 {@code goods_id} 会让 {@code goods} 与 {@code order_task} 对不上号。</li>
 * </ol>
 *
 * <p><b>为什么用「按 {@code order_id} 查列表」而不是「按 {@code goods_id} 改一行」</b>：
 * {@code goods} 以 {@code order_id} 建了索引且允许一单多货行（{@code 001_init.sql} §5.3），
 * 现有口径是一单一货，但表结构不排斥多行；按订单查能对多行如实处理，不会只改中第一行就报成功。
 */
@Service
public class CargoReadyService {

    private static final Logger log = LoggerFactory.getLogger(CargoReadyService.class);

    /** 货物状态词，取值以 {@link GoodsStatus} 为准，不在本类重复字面量。 */
    private static final String READY = GoodsStatus.READY.getValue();

    private static final String NOT_READY = GoodsStatus.NOT_READY.getValue();

    /** 已是后置状态、不可回退到「已就绪」的集合。 */
    private static final List<String> FROZEN_STATES = List.of(
            GoodsStatus.LOADED.getValue(),
            GoodsStatus.DELIVERED.getValue(),
            GoodsStatus.RECYCLED.getValue(),
            GoodsStatus.LOST.getValue());

    private final GoodsMapper goodsMapper;

    public CargoReadyService(GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    /**
     * 登记「货物已到巢」。
     *
     * @param orderId 订单标识（首尾空白容忍，内部 trim）
     * @return 登记结果；重复调用回 {@code changed=false}
     */
    public CargoReadyResponse markReady(String orderId) {
        String id = orderId == null ? null : orderId.trim();
        if (id == null || id.isEmpty()) {
            return CargoReadyResponse.rejected(null, CargoReadyResponse.Reason.ORDER_ID_MISSING,
                    "订单标识缺失");
        }

        List<GoodsEntity> rows = goodsMapper.selectList(
                new LambdaQueryWrapper<GoodsEntity>().eq(GoodsEntity::getOrderId, id));
        if (rows == null || rows.isEmpty()) {
            log.warn("货物到巢登记失败：orderId={} 查无货物记录", id);
            return CargoReadyResponse.rejected(id, CargoReadyResponse.Reason.GOODS_NOT_FOUND,
                    "该订单查无货物记录（货物记录由下单落库时建立，本端点不新建）");
        }

        int updated = 0;
        int frozen = 0;
        for (GoodsEntity row : rows) {
            String status = row.getStatus();
            if (READY.equals(status)) {
                continue;
            }
            if (FROZEN_STATES.contains(status)) {
                frozen++;
                log.warn("货物状态不可回退，跳过：orderId={} goodsId={} 当前={}",
                        id, row.getGoodsId(), status);
                continue;
            }
            if (!NOT_READY.equals(status)) {
                // 未知状态词（如历史脏数据）：不猜语义，记 warn 并跳过，让运维看得见
                log.warn("货物状态未知，跳过：orderId={} goodsId={} 当前={}",
                        id, row.getGoodsId(), status);
                frozen++;
                continue;
            }
            row.setStatus(READY);
            goodsMapper.updateById(row);
            updated++;
        }

        if (updated > 0) {
            log.info("货物到巢登记：orderId={} 更新 {} 行 → {}", id, updated, READY);
            return CargoReadyResponse.updated(id, READY, updated);
        }
        if (frozen > 0) {
            return CargoReadyResponse.rejected(id, CargoReadyResponse.Reason.GOODS_STATE_FROZEN,
                    String.format(Locale.ROOT,
                            "货物已进入后续状态（%d 行），不能回退为「已就绪」", frozen));
        }
        // 全部已是「已就绪」：幂等命中
        log.info("货物到巢登记：orderId={} 已是「{}」，本次零写入", id, READY);
        return CargoReadyResponse.alreadyReady(id);
    }
}
