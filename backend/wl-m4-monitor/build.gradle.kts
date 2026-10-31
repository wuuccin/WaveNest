// 模块四 飞行任务监控（彭紫情/宋冰鑫/吴可欣三人）。
// U-03：共享数据源由本模块写（模块一解码后发 MQ 交付本模块写共享数据源）。
dependencies {
    api(project(":wl-contract"))
    api(project(":wl-algo"))
    api(project(":wl-storage"))
    api(libs.bundles.web.common)
    implementation(libs.bundles.mq)
    implementation(libs.spring.boot.starter.data.redis)
}
