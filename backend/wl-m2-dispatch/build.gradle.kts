// 模块二 调度引擎（M2 是唯一调度主控）。
// ★ 唯一例外：本模块编译期依赖 m3，且只能调 m3 的 facade（包结构设计§二铁律 2），
//   m3 不反向依赖 m2——船侧预测快照走端口反转，接口由 m3 声明、m2 实现、wl-app 装配。
dependencies {
    api(project(":wl-contract"))
    api(project(":wl-algo"))
    api(project(":wl-storage"))
    api(libs.bundles.web.common)
    implementation(project(":wl-m3-rendezvous"))
    implementation(libs.bundles.mq)
}
