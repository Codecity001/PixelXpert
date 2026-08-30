val taskGroup = "pixelxpert"

tasks.register("buildCanary") {
	group = taskGroup
	description = "Builds a Canary APK with count-based versioning"

	finalizedBy("incrementCanaryVersion")
		.finalizedBy("assembleRelease")
			.finalizedBy("createZip")
}

tasks.register("buildStable") {
	group = taskGroup
	description = "Builds a Stable APK with Git Tag versioning"

	finalizedBy("incrementStableVersion")
		.finalizedBy("assembleRelease")
			.finalizedBy("createZip")
}

tasks.register<IncrementVersionTask>("incrementCanaryVersion") {
	group = taskGroup
	description = "Increments the canary version and saves it to the related files"

	versionFile.set(rootProject.file("version.properties"))

	stable.set(false)

	filesToUpdate.from(
		rootProject.file("MagiskModBase/module.prop"),
		rootProject.file("latestCanary.json"),
		rootProject.file("MagiskModuleUpdate_Xposed.json"),
		rootProject.file("MagiskModuleUpdate_Full.json")
	)
	targetVersionName.set(getVersionName())
}

tasks.register<IncrementVersionTask>("incrementStableVersion") {
	group = taskGroup
	description = "Increments the stable version and saves it to the related files"

	versionFile.set(rootProject.file("version.properties"))
	stable.set(true)
	filesToUpdate.from(
		rootProject.file("MagiskModBase/module.prop"),
		rootProject.file("latestStable.json"),
		rootProject.file("MagiskModuleUpdate.json"),
		rootProject.file("MagiskModuleUpdate_Full.json"),
		rootProject.file("MagiskModuleUpdate_Xposed.json"),
	)
	targetVersionName.set(getVersionName())
}

tasks.register<Zip>("createZip") {
	group = taskGroup
	description = "Creates the final Magisk module file"

	mustRunAfter("incrementCanaryVersion")
	mustRunAfter("incrementStableVersion")
	mustRunAfter("assembleRelease")

	from(file("../MagiskModBase"))
	from(file("build/outputs/apk/release/PixelXpert.apk")){into("system/priv-app/PixelXpert")}

	destinationDirectory.set(file("../output"))
	archiveFileName.set(getVersionNameProvider().map { "PixelXpert-$it.zip" })

	doLast {
		val versionName = getVersionNameProvider().get()
		val generatedZip = file("../output/PixelXpert-$versionName.zip")
		val genericZip = file("../output/PixelXpert.zip")
		if (generatedZip.exists()) {
			generatedZip.copyTo(genericZip, overwrite = true)
		}
	}
}