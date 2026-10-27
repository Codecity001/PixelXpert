plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.devtools.ksp) apply false
}

tasks {
	register("clean", Delete::class) {
		delete(project.layout.buildDirectory)
	}
}
