// 模块六 数据库与缓存：14 表实体、Repository、Redis、sys_param 集中加载。
// M6 只保证写得对、读得一致、可追溯，不做业务判断（20 §3）。
dependencies {
    api(project(":wl-contract"))
    // 4.x 必须用 spring-boot4 starter；坐标与版本统一走版本目录，模块内不写版本号。
    api(libs.mybatis.plus.spring.boot4.starter)
    api(libs.spring.boot.starter.data.redis)
    // 实体用 @Data；仅编译期需要，不进运行时 classpath。
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
