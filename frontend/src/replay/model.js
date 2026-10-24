import { isRenderableFrame, normalizeFrame } from '../api/adapter.js'

const labels = {
  alarm: '告警事件',
  rendezvous: '会面点更新',
  drone_state: '无人机状态',
  ship_traj: '船舶轨迹'
}

/** 监控任务以订单号供用户选择，以无人机号作为回放查询键。 */
export function normalizeReplayTasks(payload) {
  if (!Array.isArray(payload?.tasks)) return []
  return payload.tasks.filter(task => typeof task?.orderId === 'string' && task.orderId)
}

export function droneIdForOrder(tasks, orderId) {
  return tasks.find(task => task.orderId === orderId)?.droneId || ''
}

/** 将服务端帧转换成回放步骤；带异常坐标的帧在进入地图渲染前被跳过。 */
export function buildReplaySteps(frames) {
  if (!Array.isArray(frames)) return []
  return frames.flatMap(frame => {
    if (!frame || typeof frame.type !== 'string') return []
    try {
      const data = normalizeFrame(frame.type, frame.data)
      if (!isRenderableFrame(frame.type, data)) return []
      return [{
        title: frame.type === 'order_state' ? data.orderStatus : (labels[frame.type] || frame.type),
        frames: [[frame.type, data]]
      }]
    } catch {
      return []
    }
  })
}
