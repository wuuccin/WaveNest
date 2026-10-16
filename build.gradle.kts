// WaveNest 后端根构建脚本。
// 四条纪律（照《技术栈与运行环境》§六）：只用 Wrapper、只用 Kotlin DSL、
// 版本只写在 gradle/libs.versions.toml、构建脚本改动须全绿后再提交。
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

// 【勘误】此处原有注释称"混版 JDK（17/21）编译产物一致"，与当时的配置不符：
// toolchain languageVersion = 17 会强制 Gradle 使用 17 工具链，机器上只有 JDK 21
// （或 org.gradle.java.home 指向 21）时直接构建失败，JDK 21 根本无法参与编译。
// 【现方案】移除 toolchain，改用编译任务的 options.release = 17（见下方 subprojects）：
// 编译 JDK 由运行 Gradle 的 JVM 决定（17/21 皆可），但字节码版本与可调用 API 面
// 统一压在 17，混版 JDK 产物这才真正一致。
allprojects {
    group = "com.waterlogistics"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // --release 17：JDK 17/21 任一编译器产出的字节码与 API 面完全一致。
        // 不再走 toolchain（那是把编译 JDK 写死为 17）；指定编译 JDK 请用
        // $GRADLE_USER_HOME/gradle.properties 的 org.gradle.java.home，严禁入库。
        options.release.set(17)
        // -parameters 保留方法参数名，Spring 的构造注入与 Jackson 序列化依赖它
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}