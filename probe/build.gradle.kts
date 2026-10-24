plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("ai/xybrid/mediator/Conversation.kt", "ai/xybrid/mediator/AnalysisCodec.kt",
            "ai/xybrid/mediator/GemmaAnalysis.kt", "ai/xybrid/mediator/ModelProbe.kt")
    }
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.0")
    implementation("com.google.code.gson:gson:2.14.0")
}
application { mainClass.set("ai.xybrid.mediator.ModelProbeKt") }
tasks.named<JavaExec>("run") { workingDir(rootProject.projectDir) }
