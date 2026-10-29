const origin = [115.8611, 28.6660]
const now = () => Date.now()
const order = (status, version, phase = null) => ({ orderId: 'DEMO-01', orderStatus: status, eventTs: now(), stateVersion: version, waitReason: null, wakeEtaTs: null, emergencyLandingPoint: null, handoverPhase: phase })
const ship = (shift = 0, turn = 0, sigmaAlong = 170, sigmaCross = 80) => ({ mmsi: '413000918', shipName: '江运 08', lon: origin[0] + shift, lat: origin[1] + shift,
  points: [[origin[0] + shift,origin[1] + shift],[115.870 + shift,28.678 + shift],[115.879 + shift,28.692 + shift]].map(([lon,lat], index) => ({lon,lat,t:now()+index*150000,sigmaAlong:sigmaAlong+index*35,sigmaCross:sigmaCross+index*15})),
  cov: {sigmaAlong, sigmaCross}, version: now(), t0: now(), courseDeg: 31.2 + turn, validitySec: 300, premise: '沿赣江航道匀速外推' })
const drone = (lon, lat, battery, flightPhase, id='UAV-01') => ({ uavId: id, lon, lat, battery, flightPhase, eventTs: now(), segments: [
  {phase:'去程',points:[{lon:115.854,lat:28.647},{lon:115.862,lat:28.666}]},
  {phase:'末段交接',points:[{lon:115.862,lat:28.666},{lon:115.875,lat:28.681}]},
  {phase:'返程',points:[{lon:115.875,lat:28.681},{lon:115.854,lat:28.647}]}] })
const meeting = (lon,lat,locked=false) => ({orderId:'DEMO-01',lon,lat,locked,eventTs:now(),candidateCount:4,
  chosenNestId:locked?'NEST-02':'NEST-01',rejectionReasons:['M30T（UAV-03）：电量不足','M3T（UAV-04）：超出服务半径'],
  candidates:[
    {candidateId:'C-01',nestId:'NEST-01',droneId:'UAV-01',model:'M400',distKm:1.42,feasible:true,selected:!locked},
    {candidateId:'C-02',nestId:'NEST-02',droneId:'UAV-02',model:'M400',distKm:1.18,feasible:true,selected:locked},
    {candidateId:'C-03',nestId:'NEST-01',droneId:'UAV-03',model:'M30T',distKm:1.46,feasible:false,reasons:['电量不足']},
    {candidateId:'C-04',nestId:'NEST-02',droneId:'UAV-04',model:'M3T',distKm:5.24,feasible:false,reasons:['超出服务半径']}
  ]})
export const SCENES = [
  { title:'初始态势', frames:[['ship_traj',ship(0,0,210,100)],['order_state',order('待受理',1)]] },
  { title:'下单 · 预测与会面', frames:[['ship_traj',ship(.0008,0,145,65)],['rendezvous',meeting(115.870,28.678)],['order_state',order('已派发',2)]] },
  { title:'无人机起飞', frames:[['drone_state',drone(115.858,28.657,84,'去程')],['order_state',order('去程中',3)]] },
  { title:'船舶变向 · 重新评估会面点', frames:[['ship_traj',ship(0.004,12,75,32)],['rendezvous',meeting(115.878,28.685,true)],['alarm',{level:'警告',alarmType:'船舶变向',targetId:'DEMO-01',message:'演示场景：船舶改变航向，原会面点取消并更新',eventTs:now()}]] },
  { title:'本例预设原机返航', frames:[['drone_state',drone(115.860,28.657,72,'返航')],['order_state',order('返航中',4)]] },
  { title:'货物回收 · 重入队', frames:[['drone_state',drone(115.854,28.647,65,'已回收')],['order_state',order('待调度',5)]] },
  { title:'新机与船舶交接完成', frames:[['drone_state',drone(115.870,28.678,91,'末段交接','UAV-02')],['order_state',order('末段交接中',6,'交接')],['drone_state',drone(115.854,28.647,78,'返航完成','UAV-02')],['order_state',order('已完成',7)]] }
]
