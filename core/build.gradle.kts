plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(21) }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }

tasks.register<JavaExec>("regressionChecks") {
    group = "verification"
    description = "Runs focused import, preset and summary regression checks without external test dependencies."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.xujing.core.RegressionChecksKt")
    maxHeapSize = "192m"
    jvmArgs("-XX:ReservedCodeCacheSize=48m")
}
