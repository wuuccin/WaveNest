const origin = [115.850, 28.650]
const now = () => Date.now()
const order = (status, version, phase = null) => ({ orderId: 'DEMO-01', orderStatus: status, eventTs: now(), stateVersion: version, waitReason: null, wakeEtaTs: null, emergencyLandingPoint: null, handoverPhase: phase })
const ship = (shift = 0, turn = 0) => ({ mmsi: '413000918', shipName: '江运 08', lon: origin[0] + shift, lat: origin[1] + shift,
  points: [[115.850 + shift,28.650 + shift],[115.862 + shift,28.666 + shift],[115.875 + shift,28.681 + shift]].map(([lon,lat]) => ({lon,lat})),
  cov: {sigmaAlong: 170, sigmaCross: 80}, version: now(), t0: now(), courseDeg: 72 + turn, validitySec: 300, premise: '匀速外推演示' })
const drone = (lon, lat, battery, flightPhase, id='UAV-01') => ({ uavId: id, lon, lat, battery, flightPhase, eventTs: now(), segments: [
  {phase:'去程',points:[{lon:115.854,lat:28.647},{lon:115.862,lat:28.666}]},
  {phase:'末段交接',points:[{lon:115.862,lat:28.666},{lon:115.875,lat:28.681}]},
  {phase:'返程',points:[{lon:115.875,lat:28.681},{lon:115.854,lat:28.647}]}] })
const meeting = (lon,lat,locked=false) => ({orderId:'DEMO-01',lon,lat,locked,eventTs:now()})
export const SCENES = [
  { title:'初始态势', frames:[['ship_traj',ship()],['order_state',order('待受理',1)]] },
  { title:'下单 · 预测与会面', frames:[['ship_traj',ship()],['rendezvous',meeting(115.870,28.678)],['order_state',order('已派发',2)]] },
  { title:'无人机起飞', frames:[['drone_state',drone(115.858,28.657,84,'去程')],['order_state',order('去程中',3)]] },
  { title:'船舶变向 · 重新评估会面点', frames:[['ship_traj',ship(0.004,27)],['rendezvous',meeting(115.878,28.685)],['alarm',{level:'警告',alarmType:'船舶变向',targetId:'DEMO-01',message:'演示场景：船舶改变航向，原会面点取消并更新',eventTs:now()}]] },
  { title:'本例预设原机返航', frames:[['drone_state',drone(115.860,28.657,72,'返航')],['order_state',order('返航中',4)]] },
  { title:'货物回收 · 重入队', frames:[['drone_state',drone(115.854,28.647,65,'已回收')],['order_state',order('待调度',5)]] },
  { title:'新机与船舶交接完成', frames:[['drone_state',drone(115.870,28.678,91,'末段交接','UAV-02')],['order_state',order('末段交接中',6,'交接')],['drone_state',drone(115.854,28.647,78,'返航完成','UAV-02')],['order_state',order('已完成',7)]] }
]
