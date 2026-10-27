plugins {
	alias(libs.plugins.kotlin.jvm)
}

dependencies {
	implementation(libs.symbol.processing.api)
}

kotlin {
	compilerOptions {
		jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
	}
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}
