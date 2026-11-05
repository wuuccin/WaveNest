// 模块二 调度引擎（M2 是唯一调度主控）。
// ★ 唯一例外：本模块编译期依赖 m3，且只能调 m3 的 facade（包结构设计§二铁律 2），
//   m3 不反向依赖 m2——船侧预测快照走端口反转，接口由 m3 声明、m2 实现、wl-app 装配。
// MED-BUG.1（11-05）：m3 依赖改 api——m2 对外的公开签名（编排/会面门面装配）暴露了
//   m3 类型，implementation 会让下游（wl-app 装配层）编译期拿不到传递依赖。
dependencies {
    api(project(":wl-contract"))
    api(project(":wl-algo"))
    api(project(":wl-storage"))
    implementation(libs.bundles.web.common)
    api(project(":wl-m3-rendezvous"))
    implementation(libs.bundles.mq)
}
