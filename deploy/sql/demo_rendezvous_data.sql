-- =====================================================================
-- 动态会面演示数据（test/wkx-rendezvous-wiring 分支专用 · 勿入 develop）
-- 配套：backend/wl-app/src/test/java/com/waterlogistics/app/RendezvousDemoTest.java
--
-- 用法：docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -pwavenest < 本文件
-- 幂等：全部先DELETE 后 INSERT，可重复执行。
-- 清理：把下面各条 DELETE 的 LIKE 条件再执行一次即可（见文末）。
--
-- 设计取舍：
--   · 机巢沿 M1 仿真船初始航线（114.30,30.60 向正东、8节）布成带状，南北各放一座
--     支线，覆盖船数小时内漂移，保证演示时船总在 maxNestDistanceKm=5 之内。
--   · 无人机三档机型（M350-RTK 2.5kg / MA300 1.5kg / MA200 0.5kg）×五种状态
--     （空闲 9、已预占 1、执行任务中 1、返航中 1、维护 1），另有 1 架健康告警，
--     避免「一片全是空闲机」的单调观感；battery 全部 >30 满足候选门槛。
--   · 订单覆盖全生命周期，其中 DEMO-ORD-08 为超载压测件（5kg > 全场最大载重
--     2.5kg），带失败原因，用来演示「无解就不派发」。
--   · sys_param 补 9 个键：minBatteryPercent/maxNestDistanceKm 是张睿哲序 4
--     合入的键，因 002_sys_param.sql 只在容器首次启动时执行，本地既有容器灌不到；
--     其余 7 个是动态会面接线引入的求解阈值（drone 表无巡航速度列，按铁律读
--     sys_param 而非硬编码）。
-- =====================================================================
USE `wavenest`;
DELETE FROM `nest` WHERE `nest_id` LIKE 'NEST-%';
INSERT INTO `nest` (`nest_id`,`lon`,`lat`,`capacity`,`supported_models`,`owner`,`status`) VALUES
('NEST-HUB-01',114.2650000,30.5980000,4,'M350-RTK,MA300','Wuhan Terminal','可用'),
('NEST-HUB-02',114.2950000,30.6040000,6,'M350-RTK,MA300,MA200','Wuhan Terminal','可用'),
('NEST-HUB-03',114.3250000,30.5960000,4,'M350-RTK,MA300','Changjiang Hub','可用'),
('NEST-HUB-04',114.3550000,30.6020000,8,'M350-RTK,MA300,MA200','Changjiang Hub','可用'),
('NEST-HUB-05',114.3850000,30.5970000,4,'MA300,MA200','Changjiang Hub','可用'),
('NEST-HUB-06',114.4150000,30.6050000,6,'MA300,MA200','Ezhou Depot','可用'),
('NEST-RIDGE-N',114.3100000,30.6320000,4,'MA200','Huangpi Supply','可用'),
('NEST-DELTA-S',114.3400000,30.5620000,4,'MA200','Hannan Point','可用'),
('NEST-MAINT-01',114.2450000,30.5900000,4,'M350-RTK','Wuhan Terminal','维护中');
DELETE FROM `drone` WHERE `drone_id` LIKE 'UAV-%';
INSERT INTO `drone` (`drone_id`,`home_nest_id`,`current_nest_id`,`position_type`,`model`,`max_payload`,`max_endurance`,`battery`,`health_status`,`status`) VALUES
('UAV-M350-01','NEST-HUB-02','NEST-HUB-02','原巢','M350-RTK',2.50,5400,96.00,'健康','空闲'),
('UAV-M350-02','NEST-HUB-04','NEST-HUB-04','原巢','M350-RTK',2.50,5400,88.00,'健康','空闲'),
('UAV-M350-03','NEST-HUB-05','NEST-HUB-05','空中','M350-RTK',2.50,5400,45.00,'健康','执行任务中'),
('UAV-M350-04','NEST-HUB-01','NEST-HUB-01','原巢','M350-RTK',2.50,5400,72.00,'健康','维护'),
('UAV-MA300-01','NEST-HUB-01','NEST-HUB-01','原巢','MA300',1.50,3600,92.00,'健康','空闲'),
('UAV-MA300-02','NEST-HUB-02','NEST-HUB-02','原巢','MA300',1.50,3600,71.00,'健康','已预占'),
('UAV-MA300-03','NEST-HUB-03','NEST-HUB-03','原巢','MA300',1.50,3600,85.00,'健康','空闲'),
('UAV-MA300-04','NEST-HUB-04',NULL,'空中','MA300',1.50,3600,33.00,'健康','返航中'),
('UAV-MA300-05','NEST-HUB-06','NEST-HUB-06','原巢','MA300',1.50,3600,79.00,'健康','空闲'),
('UAV-MA300-06','NEST-HUB-03','NEST-HUB-03','原巢','MA300',1.50,3600,58.00,'健康','空闲'),
('UAV-MA200-01','NEST-HUB-01','NEST-HUB-01','原巢','MA200',0.50,1800,64.00,'健康','空闲'),
('UAV-MA200-02','NEST-RIDGE-N','NEST-RIDGE-N','原巢','MA200',0.50,1800,90.00,'健康','空闲'),
('UAV-MA200-03','NEST-DELTA-S','NEST-DELTA-S','原巢','MA200',0.50,1800,41.00,'告警','空闲'),
('UAV-MA200-04','NEST-HUB-05','NEST-HUB-05','原巢','MA200',0.50,1800,55.00,'健康','空闲');
INSERT INTO `sys_param` (`param_key`,`param_value`,`category`,`description`) VALUES
('minBatteryPercent','30','候选筛选','候选门槛：最低电量百分比（张睿哲序 4）'),
('maxNestDistanceKm','5','候选筛选','候选门槛：机巢到取货点最大距离 km（张睿哲序 4）'),
('droneCruiseSpeedMs','12.0','会面求解','无人机巡航速度 m/s（drone 表无速度列，按铁律读 sys_param）'),
('rendezvousHorizonSec','1800','会面求解','求解时限 T_max 秒（最晚会面时刻相对当前的多远）'),
('rendezvousWindowMarginSec','120','会面求解','会面时间窗余量秒，窗口=[t*, t*+余量]'),
('rendezvousEpsilonSec','1.0','会面求解','不动点迭代收敛阈值'),
('rendezvousMaxIter','50','会面求解','不动点迭代最大次数'),
('windSpeedMs','0.0','会面求解','代表风况风速 m/s（data.weather 无消费端，先不修正有效速度）'),
('windDirDeg','0.0','会面求解','代表风况风向度')
ON DUPLICATE KEY UPDATE `param_value`=VALUES(`param_value`),`description`=VALUES(`description`);
DELETE FROM `goods` WHERE `goods_id` LIKE 'GDS-%';
DELETE FROM `order_task` WHERE `order_id` LIKE 'DEMO-%';
INSERT INTO `order_task` (`order_id`,`ship_mmsi`,`ship_lon`,`ship_lat`,`create_time`,`goods_weight`,`goods_type`,`expected_time`,`priority`,`status`,`drone_id`,`meet_lon`,`meet_lat`,`meet_time`,`fail_reason`) VALUES
('DEMO-ORD-01','123456789',114.2980000,30.6010000,DATE_SUB(NOW(), INTERVAL 52 MINUTE),1.20,'医疗急件',DATE_ADD(NOW(), INTERVAL 40 MINUTE),'EMERGENCY','待调度',NULL,NULL,NULL,NULL,NULL),
('DEMO-ORD-02','123456789',114.3020000,30.6012000,DATE_SUB(NOW(), INTERVAL 47 MINUTE),2.00,'生鲜冷链',DATE_ADD(NOW(), INTERVAL 55 MINUTE),'URGENT','待调度',NULL,NULL,NULL,NULL,NULL),
('DEMO-ORD-03','123456789',114.3120000,30.6015000,DATE_SUB(NOW(), INTERVAL 38 MINUTE),2.40,'工业零件',DATE_ADD(NOW(), INTERVAL 90 MINUTE),'NORMAL','待调度',NULL,NULL,NULL,NULL,NULL),
('DEMO-ORD-04','123456789',114.3260000,30.6018000,DATE_SUB(NOW(), INTERVAL 30 MINUTE),0.40,'电商小件',DATE_ADD(NOW(), INTERVAL 120 MINUTE),'NORMAL','待受理',NULL,NULL,NULL,NULL,NULL),
('DEMO-ORD-05','123456789',114.3410000,30.6021000,DATE_SUB(NOW(), INTERVAL 22 MINUTE),0.30,'医药样品',DATE_ADD(NOW(), INTERVAL 75 MINUTE),'URGENT','已受理待唤醒',NULL,NULL,NULL,NULL,NULL),
('DEMO-ORD-06','123456789',114.3580000,30.6024000,DATE_SUB(NOW(), INTERVAL 66 MINUTE),1.80,'冷链医药',DATE_SUB(NOW(), INTERVAL 5 MINUTE),'URGENT','去程中','UAV-MA300-03',114.3720000,30.6085000,DATE_SUB(NOW(), INTERVAL 20 MINUTE),NULL),
('DEMO-ORD-07','123456789',114.3720000,30.6027000,DATE_SUB(NOW(), INTERVAL 95 MINUTE),2.20,'精密仪器',DATE_SUB(NOW(), INTERVAL 30 MINUTE),'NORMAL','已完成','UAV-M350-02',114.3860000,30.6150000,DATE_SUB(NOW(), INTERVAL 70 MINUTE),NULL),
('DEMO-ORD-08','123456789',114.3860000,30.6030000,DATE_SUB(NOW(), INTERVAL 120 MINUTE),5.00,'超载压测件',DATE_ADD(NOW(), INTERVAL 60 MINUTE),'NORMAL','失败',NULL,NULL,NULL,NULL,'无满足载重阈值的可用无人机（最大载重 2.50kg < 5.00kg）');
INSERT INTO `goods` (`goods_id`,`order_id`,`weight`,`volume`,`type`,`status`,`drone_id`,`location`) VALUES
('GDS-DEMO-01','DEMO-ORD-01',1.20,2.40,'医疗急件','未就绪',NULL,'NEST-HUB-02'),
('GDS-DEMO-02','DEMO-ORD-02',2.00,4.00,'生鲜冷链','未就绪',NULL,'NEST-HUB-02'),
('GDS-DEMO-03','DEMO-ORD-03',2.40,3.60,'工业零件','未就绪',NULL,'NEST-HUB-03'),
('GDS-DEMO-04','DEMO-ORD-04',0.40,1.20,'电商小件','未就绪',NULL,'NEST-HUB-03'),
('GDS-DEMO-05','DEMO-ORD-05',0.30,0.60,'医药样品','未就绪',NULL,'NEST-HUB-04'),
('GDS-DEMO-06','DEMO-ORD-06',1.80,3.00,'冷链医药','已装机','UAV-MA300-03','空中'),
('GDS-DEMO-07','DEMO-ORD-07',2.20,5.50,'精密仪器','已交付','UAV-M350-02','UAV-M350-02'),
('GDS-DEMO-08','DEMO-ORD-08',5.00,8.00,'超载压测件','未就绪',NULL,'NEST-HUB-05');