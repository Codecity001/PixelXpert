plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.kotlin.android) apply false
	alias(libs.plugins.devtools.ksp) apply false
	alias(libs.plugins.hilt.android) apply false
}

tasks {
	register("clean", Delete::class) {
		delete(project.layout.buildDirectory)
	}
}