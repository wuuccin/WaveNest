// 离线演示使用的配送品类字典，取值与后端 CargoType 默认全集同源。
// 离线演示不依赖后端字典接口，后端枚举调整时本常量必须同步。
// 同步要求是双向的：本常量的取值与声明顺序须与
// backend/wl-contract/src/main/java/com/waterlogistics/contract/order/CargoType.java
// 的枚举声明同批修改，由 tests/cargo-type-sync.mjs 守卫，前端单独漂移会直接失败。
export const DEMO_CARGO_TYPES = ['医疗物资', '应急药品', '食品', '生活物资']

// 演示脚本与本地样例订单使用的默认品类，取自上面的同源字典。
export const DEMO_DEFAULT_CARGO_TYPE = DEMO_CARGO_TYPES[1]
