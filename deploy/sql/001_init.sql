-- =====================================================================
-- WaveNest 数据库初始化 · 14 张表
-- 权威依据：37-M6-数据库 §5.1–§5.14（表名、字段语义）；23-状态机定义（状态枚举值）
-- 约定：
--   · 表名一字不改照 37；
--   · 列名 snake_case，实体字段 camelCase（MyBatis-Plus 驼峰映射）；
--   · 状态字段落库值一律用 23 的中文状态词（23 §9 禁用表）；
--   · 主键：有业务标识的实体用 VARCHAR 编码，高频时序表（weather/hydro/ship_ais/task_log）用自增；
--   · 演示阶段不加外键约束（逻辑外键 + 索引），避免插入顺序耦合；
--   · 字符集 utf8mb4，引擎 InnoDB。
-- 未决项（37 §8）在本文件中以 TODO 标注，不臆造取值。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `wavenest` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `wavenest`;

-- ---------------------------------------------------------------------
-- 5.1 机巢表 nest
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `nest` (
    `nest_id`           VARCHAR(64)  NOT NULL COMMENT '机巢标识（37 §5.1 nestId）',
    `lon`               DECIMAL(10,7) NOT NULL COMMENT '经度（CGCS2000/WGS84）',
    `lat`               DECIMAL(10,7) NOT NULL COMMENT '纬度（CGCS2000/WGS84）',
    `capacity`          INT          NOT NULL DEFAULT 4 COMMENT '容量 C：同一时段可容纳无人机总数，参数化（sys_param nestCapacity 初始化）',
    `supported_models`  VARCHAR(255) NULL COMMENT '支持机型列表',
    `owner`             VARCHAR(255) NULL COMMENT '归属：运营方/性质',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '可用' COMMENT '状态：可用/维护中（23 §10）',
    PRIMARY KEY (`nest_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='机巢表（37 §5.1）';

-- ---------------------------------------------------------------------
-- 5.2 无人机表 drone
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `drone` (
    `drone_id`          VARCHAR(64)  NOT NULL COMMENT '无人机标识（37 §5.2 droneId）',
    `home_nest_id`      VARCHAR(64)  NOT NULL COMMENT '归属机巢（稳定「家」）',
    `current_nest_id`   VARCHAR(64)  NULL COMMENT '当前所在机巢（动态；空中/迫降点时为 NULL）',
    `position_type`     VARCHAR(32)  NOT NULL DEFAULT '原巢' COMMENT '当前位置类型：原巢/空中/他巢临时停靠/迫降点',
    `model`             VARCHAR(64)  NULL COMMENT '机型标识',
    `max_payload`       DECIMAL(8,2) NULL COMMENT '最大载重（kg）',
    `max_endurance`     INT          NULL COMMENT '最大续航（秒）',
    `wind_limit_ms`     DECIMAL(5,2) NULL COMMENT '机型抗风上限（m/s）：机型能力包线「抗风等级」，62 判据 windclass_match 用；同一 model 取同值，NULL＝未登记（候选链按缺失即排除）',
    `cruise_speed_ms`   DECIMAL(5,2) NULL COMMENT '机型巡航速度（m/s）：机型能力包线「巡航速度」；NULL 时会面链回落 sys_param.droneCruiseSpeedMs',
    `battery`           DECIMAL(5,2) NULL COMMENT '当前电量（百分比 0–100）',
    `health_status`     VARCHAR(32)  NOT NULL DEFAULT '健康' COMMENT '健康状态：健康/告警',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '空闲' COMMENT '状态：空闲/已预占/执行任务中/返航中/迫停/迫降/故障/维护/失联（23 §7）',
    PRIMARY KEY (`drone_id`),
    KEY `idx_drone_home` (`home_nest_id`),
    KEY `idx_drone_cur` (`current_nest_id`),
    KEY `idx_drone_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='无人机表（37 §5.2）';

-- ---------------------------------------------------------------------
-- 5.3 货物表 goods
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `goods` (
    `goods_id`          VARCHAR(64)  NOT NULL COMMENT '货物标识（37 §5.3 goodsId）',
    `order_id`          VARCHAR(64)  NOT NULL COMMENT '关联订单',
    `weight`            DECIMAL(8,2) NULL COMMENT '重量（kg）',
    `volume`            DECIMAL(8,2) NULL COMMENT '体积',
    `type`              VARCHAR(64)  NULL COMMENT '货物类型',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '未就绪' COMMENT '状态：未就绪/已就绪/已装机/已交付/已回收/灭失（23 §6）',
    `drone_id`          VARCHAR(64)  NULL COMMENT '当前载体（装机后的承载无人机）',
    `location`          VARCHAR(255) NULL COMMENT '当前位置：所在巢/迫降点/空中',
    PRIMARY KEY (`goods_id`),
    KEY `idx_goods_order` (`order_id`),
    KEY `idx_goods_drone` (`drone_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货物表（37 §5.3）';

-- ---------------------------------------------------------------------
-- 5.4 订单表 order_task
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_task` (
    `order_id`          VARCHAR(64)  NOT NULL COMMENT '订单标识（37 §5.4 orderId）',
    `ship_mmsi`         VARCHAR(16)  NOT NULL COMMENT '目标船 MMSI',
    `ship_lon`          DECIMAL(10,7) NULL COMMENT '目标船舶经度（下单时）',
    `ship_lat`          DECIMAL(10,7) NULL COMMENT '目标船舶纬度（下单时）',
    `create_time`       DATETIME     NOT NULL COMMENT '下单时间',
    `goods_weight`      DECIMAL(8,2) NULL COMMENT '物资载重（kg）',
    `goods_type`        VARCHAR(64)  NULL COMMENT '物资类型',
    `expected_time`     DATETIME     NULL COMMENT '期望送达时间',
    `priority`          VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '优先级（订单级）：NORMAL/URGENT/EMERGENCY（C-09）',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '待受理' COMMENT '订单状态：待受理/已受理待唤醒/待调度/调度中/已派发/去程中/末段交接中/返航中/已完成/已拒绝/失败/异常终止（23 §3）',
    `drone_id`          VARCHAR(64)  NULL COMMENT '关联无人机',
    `meet_lon`          DECIMAL(10,7) NULL COMMENT '会面点经度',
    `meet_lat`          DECIMAL(10,7) NULL COMMENT '会面点纬度',
    `meet_time`         DATETIME     NULL COMMENT '组合会面起飞时间（回填）',
    `fail_reason`       VARCHAR(255) NULL COMMENT '失败/终止原因',
    PRIMARY KEY (`order_id`),
    KEY `idx_order_mmsi` (`ship_mmsi`),
    KEY `idx_order_status` (`status`),
    KEY `idx_order_drone` (`drone_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表（37 §5.4）';

-- ---------------------------------------------------------------------
-- 5.5 占位时隙台账 occupancy_slot
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `occupancy_slot` (
    `slot_id`           VARCHAR(64)  NOT NULL COMMENT '占用标识（37 §5.5 slotId）',
    `object_type`       VARCHAR(32)  NOT NULL COMMENT '对象类型：机占用/起飞时段/会面窗口/归巢时隙/迫停占用',
    `object_id`         VARCHAR(64)  NOT NULL COMMENT '关联对象 Id：droneId/nestId/orderId/迫降点Id',
    `t1`                DATETIME     NOT NULL COMMENT '占用开始时刻 t₁',
    `t2`                DATETIME     NOT NULL COMMENT '占用结束时刻 t₂（半开区间 [t1,t2)，对应「占用时长 d 或 t₂」）',
    `type`              VARCHAR(32)  NOT NULL DEFAULT '常规驻留' COMMENT '类型：常规驻留/应急迫停/迫降',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '有效' COMMENT '状态：有效/已释放/已过期（23 §10）',
    PRIMARY KEY (`slot_id`),
    KEY `idx_occ_key` (`object_type`, `object_id`, `status`),
    KEY `idx_occ_time` (`t1`, `t2`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='占位时隙台账（37 §5.5；区间重叠判据 a.t1<b.t2 AND b.t1<a.t2）';

-- ---------------------------------------------------------------------
-- 5.6 应急迫降点台账 emergency_landing_point
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `emergency_landing_point` (
    `elp_id`            VARCHAR(64)  NOT NULL COMMENT '迫降点标识（37 §5.6 elpId）',
    `lon`               DECIMAL(10,7) NOT NULL COMMENT '经度',
    `lat`               DECIMAL(10,7) NOT NULL COMMENT '纬度',
    `capacity`          INT          NOT NULL DEFAULT 0 COMMENT '容量：可容纳迫降机数',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '可用' COMMENT '状态：可用/占用/维护（23 §10）',
    `applicable_models` VARCHAR(255) NULL COMMENT '适用机型与限制',
    PRIMARY KEY (`elp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应急迫降点台账（37 §5.6）';

-- ---------------------------------------------------------------------
-- 5.7 气象表 weather
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `weather` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（时序数据无自然键）',
    `ts`                DATETIME     NOT NULL COMMENT '观测时刻',
    `region`            VARCHAR(64)  NULL COMMENT '区域/网格索引',
    `lon`               DECIMAL(10,7) NULL COMMENT '经度',
    `lat`               DECIMAL(10,7) NULL COMMENT '纬度',
    `wind_speed`        DECIMAL(8,2) NULL COMMENT '风速（m/s）',
    `wind_dir`          DECIMAL(5,2) NULL COMMENT '风向（度）',
    `gust`              DECIMAL(8,2) NULL COMMENT '阵风（m/s）',
    `wind_level`        INT          NULL COMMENT '风级（由风速换算；换算方未定见 53 §2）',
    `temperature`       DECIMAL(5,2) NULL COMMENT '温度（℃）',
    `visibility`        DECIMAL(8,2) NULL COMMENT '能见度（m）',
    PRIMARY KEY (`id`),
    KEY `idx_weather_ts` (`ts`),
    KEY `idx_weather_region` (`region`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='气象表（37 §5.7）';

-- ---------------------------------------------------------------------
-- 5.8 水文表 hydro
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `hydro` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `ts`                DATETIME     NOT NULL COMMENT '观测时刻',
    `region`            VARCHAR(64)  NULL COMMENT '区域/断面标识',
    `lon`               DECIMAL(10,7) NULL COMMENT '经度',
    `lat`               DECIMAL(10,7) NULL COMMENT '纬度',
    `water_level`       DECIMAL(8,2) NULL COMMENT '水位（m）',
    `flow_speed`        DECIMAL(8,2) NULL COMMENT '流速（m/s）',
    `flow_dir`          DECIMAL(5,2) NULL COMMENT '流向（度）',
    `water_temp`        DECIMAL(5,2) NULL COMMENT '水温（℃）',
    PRIMARY KEY (`id`),
    KEY `idx_hydro_ts` (`ts`),
    KEY `idx_hydro_region` (`region`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='水文表（37 §5.8；主流程暂不消费可降级仅缓存）';

-- ---------------------------------------------------------------------
-- 5.9 空域与航道限制表 airspace_restriction
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `airspace_restriction` (
    `restriction_id`    VARCHAR(64)  NOT NULL COMMENT '限制项标识（37 §5.9 restrictionId）',
    `type`              VARCHAR(32)  NOT NULL COMMENT '类型：禁飞区/限飞区/限高/航道管制',
    `scope`             TEXT         NULL COMMENT '空间范围：几何区域（多边形/圆/线段）或桥梁净空高度',
    `effective_start`   DATETIME     NULL COMMENT '生效开始（长期有效则为空）',
    `effective_end`     DATETIME     NULL COMMENT '生效结束（长期有效则为空）',
    `source`            TEXT         NULL COMMENT '来源与原文：公告来源、原文链接',
    `status`            VARCHAR(32)  NOT NULL DEFAULT '生效' COMMENT '状态：生效/已失效（23 §10）',
    PRIMARY KEY (`restriction_id`),
    KEY `idx_airspace_type` (`type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空域与航道限制表（37 §5.9）';

-- ---------------------------------------------------------------------
-- 5.10 船舶 AIS 轨迹表 ship_ais
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ship_ais` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（同一船多条轨迹记录）',
    `ship_mmsi`         VARCHAR(16)  NOT NULL COMMENT '船舶标识',
    `ts`                DATETIME     NOT NULL COMMENT '时间戳',
    `lon`               DECIMAL(10,7) NOT NULL COMMENT '经度（已归一 CGCS2000/WGS84）',
    `lat`               DECIMAL(10,7) NOT NULL COMMENT '纬度（已归一）',
    `sog`               DECIMAL(8,2) NULL COMMENT '航速（已归一 m/s）',
    `cog`               DECIMAL(5,2) NULL COMMENT '航向（已归一 度）',
    PRIMARY KEY (`id`),
    KEY `idx_ais_mmsi_ts` (`ship_mmsi`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='船舶 AIS 轨迹表（37 §5.10；原始 AIS 值，M1 唯一写入方）';

-- ---------------------------------------------------------------------
-- 5.11 航迹点表 flight_point
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flight_point` (
    `point_id`          VARCHAR(64)  NOT NULL COMMENT '航迹点标识（37 §5.11 pointId）',
    `drone_id`          VARCHAR(64)  NOT NULL COMMENT '关联无人机',
    `order_id`          VARCHAR(64)  NULL COMMENT '关联订单',
    `ts`                DATETIME     NOT NULL COMMENT '采样时刻',
    `lon`               DECIMAL(10,7) NOT NULL COMMENT '经度（已归一）',
    `lat`               DECIMAL(10,7) NOT NULL COMMENT '纬度（已归一）',
    `alt`               DECIMAL(8,2) NULL COMMENT '高度（m，已归一）',
    `speed`             DECIMAL(8,2) NULL COMMENT '速度（m/s）',
    `heading`           DECIMAL(5,2) NULL COMMENT '航向（度）',
    `segment_type`      VARCHAR(32)  NULL COMMENT '航段类型：去程/返程/迫降',
    PRIMARY KEY (`point_id`),
    KEY `idx_fp_drone_ts` (`drone_id`, `ts`),
    KEY `idx_fp_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='航迹点表（37 §5.11；只记实际飞行点）';

-- ---------------------------------------------------------------------
-- 5.12 告警表 alert
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `alert` (
    `alert_id`          VARCHAR(64)  NOT NULL COMMENT '告警标识（37 §5.12 alertId）',
    `drone_id`          VARCHAR(64)  NULL COMMENT '告警对象（无人机）',
    `order_id`          VARCHAR(64)  NULL COMMENT '告警对象（订单）',
    `alert_type`        VARCHAR(32)  NOT NULL COMMENT '告警类型：失联/低电量/偏航/气象超限/链路异常（四类由 M4 检测，U-02）',
    `level`             VARCHAR(32)  NOT NULL DEFAULT '提示' COMMENT '级别：提示/警告/严重',
    `trigger_time`      DATETIME     NOT NULL COMMENT '触发时刻',
    `handle_status`     VARCHAR(32)  NOT NULL DEFAULT '未处理' COMMENT '处理状态：未处理/已确认/已处置/已忽略（23 §10）',
    `handle_record`     TEXT         NULL COMMENT '处置记录：处理人、动作、时间',
    PRIMARY KEY (`alert_id`),
    KEY `idx_alert_drone` (`drone_id`),
    KEY `idx_alert_order` (`order_id`),
    KEY `idx_alert_status` (`handle_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警表（37 §5.12；M4 产生、M5 展示）';

-- ---------------------------------------------------------------------
-- 5.13 任务日志表 task_log
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `task_log` (
    `log_id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志标识（37 §5.13 logId）',
    `order_id`          VARCHAR(64)  NOT NULL COMMENT '关联订单',
    `op_type`           VARCHAR(64)  NULL COMMENT '操作类型',
    `from_status`       VARCHAR(32)  NULL COMMENT '变更前状态',
    `to_status`         VARCHAR(32)  NULL COMMENT '变更后状态（覆盖订单/无人机/货物/占位变更）',
    `ts`                DATETIME     NOT NULL COMMENT '时间戳',
    PRIMARY KEY (`log_id`),
    KEY `idx_log_order_ts` (`order_id`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务日志表（37 §5.13；状态流转溯源）';

-- ---------------------------------------------------------------------
-- 5.14 系统参数表 sys_param
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_param` (
    `param_key`         VARCHAR(64)  NOT NULL COMMENT '参数键（37 §5.14 paramKey；camelCase，见 §5.14.1）',
    `param_value`       VARCHAR(255) NOT NULL COMMENT '参数值',
    `category`          VARCHAR(32)  NULL COMMENT '分类：阈值/风级/窗口/容量/权重/间隔/上限/TTL/收敛/流程/限流/公平',
    `description`       VARCHAR(255) NULL COMMENT '说明',
    PRIMARY KEY (`param_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统参数表（37 §5.14；阈值全参数化）';
