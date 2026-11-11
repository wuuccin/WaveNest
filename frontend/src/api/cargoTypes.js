// 离线演示使用的配送品类字典，取值与后端 CargoType 默认全集同源。
// 离线演示不依赖后端字典接口，后端枚举调整时本常量必须同步。
export const DEMO_CARGO_TYPES = ['医疗物资', '应急药品', '食品', '生活物资']

// 演示脚本与本地样例订单使用的默认品类，取自上面的同源字典。
export const DEMO_DEFAULT_CARGO_TYPE = DEMO_CARGO_TYPES[1]
