package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 计划航迹点表 {@code plan_track} 的读写口（11-12 序号 2）。
 *
 * <p>三个自定方法与《1112-功能做实批次｜接口约定》v1.2 §2.2 一一对应：
 * {@link #insertBatch} 在派单事务内批量落点、{@link #selectByOrderId} 按绘制顺序查回、
 * {@link #deleteByOrderId} 供重规划时先删后插。写库失败须由调用方抛异常回滚派单事务，
 * 本层不吞异常、不做业务判断。
 */
@Mapper
public interface PlanTrackMapper extends BaseMapper<PlanTrackEntity> {

    /**
     * 批量落点：一条派发一次调用，与 {@code deleteByOrderId} 同事务使用。
     *
     * <p>单条多值 INSERT，减少往返；{@code created_at} 不入列，交给库默认值。
     *
     * @param points 计划点，非空；同一 {@code (order_id, route_id, segment_kind, seq)}
     *               重复会撞唯一键 {@code uk_plan_point}
     * @return 实际写入行数
     */
    @Insert("<script>"
            + "INSERT INTO plan_track"
            + " (order_id, route_id, combo_id, segment_kind, seq, lon, lat, alt_m, speed_ms, t_ms)"
            + " VALUES "
            + "<foreach collection='points' item='p' separator=','>"
            + "(#{p.orderId}, #{p.routeId}, #{p.comboId}, #{p.segmentKind}, #{p.seq},"
            + " #{p.lon}, #{p.lat}, #{p.altM}, #{p.speedMs}, #{p.tMs})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("points") List<PlanTrackEntity> points);

    /**
     * 按订单查回全部计划点，按绘制顺序返回：空机转场 → 去程 → 末段交接 → 返程，
     * 段内按 {@code seq} 升序（接口约定 §2.2「读取接口」）。
     *
     * @param orderId 订单号
     * @return 计划点列表；无记录为空列表，不返回 null
     */
    @Select("SELECT * FROM plan_track WHERE order_id = #{orderId}"
            + " ORDER BY FIELD(segment_kind, '空机转场', '去程', '末段交接', '返程'), seq")
    List<PlanTrackEntity> selectByOrderId(@Param("orderId") String orderId);

    /**
     * 按订单删除全部计划点，重规划时与 {@link #insertBatch} 同事务先删后插。
     *
     * <p>注意：接口约定 §2.2「版本保留」要求旧 {@code route_id} 行保留供复盘，
     * 因此重规划<b>不应</b>调用本方法；本方法只用于同一航迹版本的覆写。
     *
     * @param orderId 订单号
     * @return 实际删除行数
     */
    @Delete("DELETE FROM plan_track WHERE order_id = #{orderId}")
    int deleteByOrderId(@Param("orderId") String orderId);
}
