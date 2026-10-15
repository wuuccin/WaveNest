// WaveNest 后端多模块骨架。
// 九模块边界与依赖方向照《包结构设计》§一/§二：模块边界＝Gradle 模块边界，
// 依赖单向无环，m2 → m3 是唯一允许的编译期跨模块调用。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "WaveNest"

include(
    "wl-common",
    "wl-contract",
    "wl-algo",
    "wl-storage",
    "wl-m1-collector",
    "wl-m2-dispatch",
    "wl-m3-rendezvous",
    "wl-m4-monitor",
    "wl-app",
)

project(":wl-common").projectDir = file("backend/wl-common")
project(":wl-contract").projectDir = file("backend/wl-contract")
project(":wl-algo").projectDir = file("backend/wl-algo")
project(":wl-storage").projectDir = file("backend/wl-storage")
project(":wl-m1-collector").projectDir = file("backend/wl-m1-collector")
project(":wl-m2-dispatch").projectDir = file("backend/wl-m2-dispatch")
project(":wl-m3-rendezvous").projectDir = file("backend/wl-m3-rendezvous")
project(":wl-m4-monitor").projectDir = file("backend/wl-m4-monitor")
project(":wl-app").projectDir = file("backend/wl-app")