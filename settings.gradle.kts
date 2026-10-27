pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		maven { setUrl("https://jitpack.io") }
		maven { setUrl("https://api.xposed.info/") }
	}
}

rootProject.name = "PixelXpert"
include(":app")
include(":modPackProcessor")
