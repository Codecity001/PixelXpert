plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.2.21-2.3.6")
    implementation(project(":annotations"))
}

kotlin {
    jvmToolchain(17)
}
