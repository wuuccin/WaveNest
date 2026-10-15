// 模块一 数据采集（含仿真器）。全链源头，杨添爻。
// Paho MQTT 客户端仅本模块接入一次（20 §3 已裁决），Netty 供真实 AIS 连接器，
// 演示期由 Sim 数据源替代（连接器接口位已在，坐标已登记，接真环境零重构）。
dependencies {
    api(project(":wl-contract"))
    api(project(":wl-algo"))
    api(project(":wl-storage"))
    api(libs.bundles.web.common)
    api(libs.paho.mqttv3.client)
    api(libs.netty.all)
    implementation(libs.bundles.mq)
}
