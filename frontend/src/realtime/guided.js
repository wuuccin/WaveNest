import { normalizeFrame } from '../api/adapter'
import { SCENES } from './mock'

export const GUIDE_STAGES = [
  {
    short: '预测会面', title: '查看船舶预测轨迹和预计会面点',
    happens: '订单创建后，演示记录展示船舶预测轨迹、会面点，并依次推进待调度和已派发状态。正式流程需根据船舶轨迹与可用无人机求解会面点。',
    why: '船舶一直在移动，会面点是预计交货的位置，不是船舶必须经过的固定检查点。',
    observe: ['订单编号已经生成', '地图上出现船舶预测轨迹和会面点', '订单状态从待受理走向已派发']
  },
  {
    short: '起飞去程', title: '首架无人机进入去程，运送物资',
    happens: '演示记录使用 UAV-01 表示首架执飞无人机，推送它的位置、电量和“去程中”状态。',
    why: '正式任务需完成选机和派发，起飞后持续监控位置与电量；演示中的机号和轨迹为预设数据。',
    observe: ['地图上出现第一架无人机', '右侧能看到无人机编号和电量', '订单状态变为去程中']
  },
  {
    short: '风速突变', title: '风速突变触发气象超限与返航处置',
    happens: '仿真气象注入把风速推高到超限区间，事件流显示气象超限；系统评估飞行安全后，原机进入返航。',
    why: '风况超限由后端告警和安全处置链路判断。订单状态可能仍为去程中，应以无人机返航状态确认这一步。',
    observe: ['风况标尺显示超限风速', '事件流出现气象超限', '原机飞行阶段进入返航中']
  },
  {
    short: '原机返航', title: '气象超限后，原机按安全决策返航',
    happens: '气象超限触发安全处置后，原机进入返航；订单状态可能仍处于去程中。',
    why: '原机返航应以无人机状态和后端安全决策为准，不能从订单状态直接推断。',
    observe: ['原机飞行阶段变为返航中', '订单状态可能保持去程中', '第一架无人机的电量仍可查看']
  },
  {
    short: '回收入队', title: '原机落巢，风况回落后等待重新派单',
    happens: '演示记录显示 UAV-01“已回收”；清除气象偏置后等待风况回落与闸门解除，订单才能重新进入调度。',
    why: '货物仍在原机上时不能派给另一架无人机。演示中的“已回收”是预设状态帧，不是实物回收传感器的确认结果。',
    observe: ['第一架无人机显示已回收', '订单状态重新变为待调度', '接下来才能重新派出第二架无人机']
  },
  {
    short: '重派并交货', title: '演示重派结果：第二架无人机与船舶交接',
    happens: '演示记录显示 UAV-02 进入“末段交接中”，表示第二架无人机向船舶配送。正式流程应在货物回收后重新选机、装货和派发。',
    why: '第二架无人机要从货物所在机巢取货，不能在空中从第一架无人机接货。演示只推送阶段状态，不证明实物交接已经发生。',
    observe: ['无人机编号从 UAV-01 变为 UAV-02', '飞行阶段显示末段交接中', '订单状态变为末段交接中']
  },
  {
    short: '返航完成', title: '演示收尾：第二架无人机返航，订单已完成',
    happens: '后端仿真记录最后推送 UAV-02 返航、返航完成和订单“已完成”。',
    why: '正式任务需确认与船舶交货和无人机落巢后才能完成闭环；演示中的“已完成”由仿真编排器推进。',
    observe: ['订单最终状态为已完成', '无人机显示返航完成', '历史记录保留完整过程，可再次回放']
  }
]

const orderStatus = frame => frame.type === 'order_state' ? frame.data.orderStatus : null
const dronePhase = frame => frame.type === 'drone_state' ? frame.data.flightPhase : null

export function buildLiveGuideSteps(rawFrames) {
  const frames = rawFrames.map(frame => ({ type: frame.type, data: normalizeFrame(frame.type, frame.data) }))
  const firstDrone = frames.find(frame => frame.type === 'drone_state')?.data.uavId
  const starts = [0,
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId === firstDrone && dronePhase(frame) === '去程中'),
    frames.findIndex(frame => frame.type === 'alarm' && ['气象超限', 'WEATHER_EXCEED'].includes(frame.data.alarmType)),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId === firstDrone && dronePhase(frame) === '返航中'),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId === firstDrone && dronePhase(frame) === '已回收'),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId !== firstDrone && dronePhase(frame) === '末段交接中'),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId !== firstDrone && dronePhase(frame) === '返航中')]
  if (!firstDrone || starts.some((start, index) => start < 0 || (index && start <= starts[index - 1])) || !frames.some(frame => orderStatus(frame) === '已完成')) {
    throw new Error('后端回放缺少完整的预测、气象处置、返航或交接记录，无法生成逐步讲解')
  }
  return GUIDE_STAGES.map((stage, index) => ({ title: stage.title, frames: frames.slice(starts[index], starts[index + 1] ?? frames.length).map(frame => [frame.type, frame.data]) }))
}

export function buildOfflineGuideSteps(orderId, goodsType = '') {
  const clone = ([type, data]) => [type, { ...data,
    ...(data.orderId === 'DEMO-01' ? { orderId } : {}),
    ...(data.targetId === 'DEMO-01' ? { targetId: orderId } : {}),
    ...(type === 'order_state' ? { goodsType: goodsType || data.goodsType || '' } : {}) }]
  const steps = [
    [...SCENES[0].frames, ...SCENES[1].frames],
    SCENES[2].frames, SCENES[3].frames, SCENES[4].frames, SCENES[5].frames,
    SCENES[6].frames.slice(0, 2), SCENES[6].frames.slice(2)
  ]
  return steps.map((frames, index) => ({ title: GUIDE_STAGES[index].title, frames: frames.map(clone) }))
}
