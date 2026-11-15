package com.waterlogistics.dispatch.rendezvous;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.rendezvous.conflict.ConflictParams;
import com.waterlogistics.rendezvous.handover.HandoverCommand;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCommand;

/**
 * 会面定案校验端口——M2 对 M3 门面「起飞时刻／空域二查／末段交接」三项能力的唯一出口。
 *
 * <p>与 {@link RendezvousPort}（{@code @FunctionalInterface}，仅求解）分开：三项能力各有
 * 独立入参类型，且本接口要被编排层按固定顺序调用，不做成函数式接口以免后续扩展破桩。
 * 实现由 {@code wl-app} 的 {@code RendezvousWiring} 转调 {@code RendezvousFacade}，
 * M2 不引用门面类，也不碰 solver／conflict／handover 的具体实现类。
 */
public interface RendezvousVerificationPort {

    /** 起飞时刻回填（只算不定案）。 */
    B_TakeoffTimeResult computeTakeoffTime(TakeoffTimeCommand cmd);

    /** 航迹级空域冲突二查（只给检测结论）。 */
    B_ConflictResult detectConflict(B_FlyableRoute route, B_FlyableRoute other, ConflictParams params);

    /** 末段会面与交接判定。 */
    B_HandoverConfirmation judgeHandover(HandoverCommand cmd);
}
