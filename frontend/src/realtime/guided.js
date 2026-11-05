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
    short: '船舶变向', title: '船舶航向变化，原会面方案需要重新评估',
    happens: '后端仿真记录推送新的船舶预测轨迹、候选会面点和变向告警。这表示原会面方案需要重评，不表示无人机已经错过某个固定点。',
    why: '正式流程要判断船舶偏离预测航迹的情况是否持续，再评估原机能否按新方案继续送货。变向本身不会直接触发返航。',
    observe: ['船舶预测轨迹发生变化', '会面点位置更新', '事件流出现船舶变向告警']
  },
  {
    short: '带货返航', title: '已选择放弃本次会面，原机带货返航',
    happens: '你选择了“放弃本次会面”分支。后端仿真记录按预设时间推送 UAV-01 返航和订单未完成的状态。',
    why: '正式调度还可能让原机继续追赶；只有综合新会面方案、时间和返航安全等条件后决定放弃，才让原机带货返航。演示没有实时运行这项选择。',
    observe: ['飞行阶段变为返航中', '订单状态变为返航中', '第一架无人机的电量仍可查看']
  },
  {
    short: '回收入队', title: '演示原机已回收，订单重新进入调度',
    happens: '演示记录接着显示 UAV-01“已回收”和订单“待调度”。正式流程必须先确认原机落巢、货物回收，才能让订单重入队。',
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

export const CONTINUE_STAGES = [
  ...GUIDE_STAGES.slice(0, 3),
  { short: '原机续送', title: '重新评估后，原机追向新的候选会面点',
    happens: '你选择了原机继续配送。画面推演 UAV-01 根据更新后的会面点调整去程航线。',
    why: '这是前端情境推演，不是后端下发的飞行指令。正式调度需先验证剩余电量、时间窗口和新会面方案可行。',
    observe: ['蓝色航线转向新的会面点', 'UAV-01 仍携货继续飞行', '订单保持去程中'] },
  { short: '接近新点', title: '原机接近更新后的候选会面点',
    happens: '情境推演将 UAV-01 推进至新会面点附近，船舶按更新的预测航迹接近。',
    why: '地图只演示一个可行分支；真实系统应持续校验船位、无人机位置与时间窗口。',
    observe: ['UAV-01 沿蓝色新航线前进', '新会面点和船舶预测航迹保持可见', '没有生成第二架无人机'] },
  { short: '原机交货', title: '原机在新会面点与船舶交货',
    happens: '情境推演显示 UAV-01 到达更新后的会面点，并进入与船舶交接阶段。',
    why: '交货结果由前端推演生成，不代表后端收到实物交接确认。',
    observe: ['交接仍由 UAV-01 执行', '订单进入末段交接中', '原机航线终点是更新后的会面点'] },
  { short: '返航完成', title: '交货后原机返航，情境推演结束',
    happens: '情境推演显示 UAV-01 交货后返航，订单结束。',
    why: '该完成状态是前端情境推演；正式流程必须由后端确认交货和落巢。',
    observe: ['返航线从新会面点通向机巢', '全程只有 UAV-01', '订单显示已完成'] }
]

// The live backend currently records only a pre-scripted return branch. Keep the
// alternative entirely local and label it as a scenario in the UI.
export function buildContinueGuideSteps(baseSteps, orderId) {
  const opening = baseSteps.slice(0, 3).flatMap(step => step.frames)
  const latest = type => opening.filter(frame => frame[0] === type).at(-1)?.[1]
  const start = latest('drone_state')
  const meeting = latest('rendezvous')
  const ship = latest('ship_traj')
  if (!start || !meeting || !Number.isFinite(meeting.lon) || !Number.isFinite(meeting.lat))
    throw new Error('缺少无人机或更新后的会面点，无法展示继续配送情境')
  const origin = baseSteps[1]?.frames.find(([type]) => type === 'drone_state')?.[1]
  const nest = { lon: origin?.segments?.[0]?.points?.[0]?.lon ?? start.lon - .025,
    lat: origin?.segments?.[0]?.points?.[0]?.lat ?? start.lat - .015 }
  const from = { lon: start.lon, lat: start.lat }
  const target = { lon: meeting.lon, lat: meeting.lat }
  const between = fraction => ({ lon: from.lon + (target.lon - from.lon) * fraction,
    lat: from.lat + (target.lat - from.lat) * fraction })
  const waypoint = between(.55)
  const route = [{ phase: '去程', points: [from, waypoint, target] }]
  const previousVersion = Math.max(0, ...opening.filter(([type]) => type === 'order_state').map(([, data]) => data.stateVersion || 0))
  const eventTs = Date.now()
  const order = (status, offset) => ['order_state', { orderId, orderStatus: status,
    stateVersion: previousVersion + offset, eventTs: eventTs + offset * 1000,
    waitReason: null, wakeEtaTs: null, emergencyLandingPoint: null, handoverPhase: null }]
  const drone = (point, battery, phase, segments, offset) => ['drone_state', {
    ...start, ...point, uavId: start.uavId || 'UAV-01', battery,
    flightPhase: phase, eventTs: eventTs + offset * 1000, ts: eventTs + offset * 1000, segments }]
  const shipAtMeeting = ship ? ['ship_traj', { ...ship, lon: target.lon, lat: target.lat,
    version: (ship.version || 0) + 1 }] : null
  const frames = [
    [drone(waypoint, Math.max(20, (start.battery ?? 80) - 8), '去程中', route, 1), order('去程中', 1)],
    [drone(between(.88), Math.max(18, (start.battery ?? 80) - 15), '去程中', route, 2), order('去程中', 2)],
    [shipAtMeeting, drone(target, Math.max(15, (start.battery ?? 80) - 20), '末段交接中', route, 3), order('末段交接中', 3)].filter(Boolean),
    [drone(nest, Math.max(10, (start.battery ?? 80) - 32), '返航完成',
      [...route, { phase: '返程', points: [target, nest] }], 4), order('已完成', 4)]
  ]
  return [...baseSteps.slice(0, 3), ...frames.map((items, index) => ({
    title: CONTINUE_STAGES[index + 3].title, frames: items
  }))]
}

const orderStatus = frame => frame.type === 'order_state' ? frame.data.orderStatus : null
const dronePhase = frame => frame.type === 'drone_state' ? frame.data.flightPhase : null

export function buildLiveGuideSteps(rawFrames) {
  const frames = rawFrames.map(frame => ({ type: frame.type, data: normalizeFrame(frame.type, frame.data) }))
  const firstShip = frames.find(frame => frame.type === 'ship_traj')?.data.version
  const firstDrone = frames.find(frame => frame.type === 'drone_state')?.data.uavId
  const starts = [0,
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId === firstDrone && dronePhase(frame) === '去程中'),
    frames.findIndex(frame => frame.type === 'ship_traj' && Number.isFinite(firstShip) && frame.data.version > firstShip),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId === firstDrone && dronePhase(frame) === '返航中'),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId === firstDrone && dronePhase(frame) === '已回收'),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId !== firstDrone && dronePhase(frame) === '末段交接中'),
    frames.findIndex(frame => frame.type === 'drone_state' && frame.data.uavId !== firstDrone && dronePhase(frame) === '返航中')]
  if (!firstDrone || starts.some((start, index) => start < 0 || (index && start <= starts[index - 1])) || !frames.some(frame => orderStatus(frame) === '已完成')) {
    throw new Error('后端回放缺少完整的预测、变向、返航或交接记录，无法生成逐步讲解')
  }
  return GUIDE_STAGES.map((stage, index) => ({ title: stage.title, frames: frames.slice(starts[index], starts[index + 1] ?? frames.length).map(frame => [frame.type, frame.data]) }))
}

export function buildOfflineGuideSteps(orderId) {
  const clone = ([type, data]) => [type, { ...data,
    ...(data.orderId === 'DEMO-01' ? { orderId } : {}),
    ...(data.targetId === 'DEMO-01' ? { targetId: orderId } : {}) }]
  const steps = [
    [...SCENES[0].frames, ...SCENES[1].frames],
    SCENES[2].frames, SCENES[3].frames, SCENES[4].frames, SCENES[5].frames,
    SCENES[6].frames.slice(0, 2), SCENES[6].frames.slice(2)
  ]
  return steps.map((frames, index) => ({ title: GUIDE_STAGES[index].title, frames: frames.map(clone) }))
}
