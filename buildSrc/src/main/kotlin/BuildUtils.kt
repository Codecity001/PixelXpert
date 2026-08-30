
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File
import java.util.Properties


fun bumpFileStandalone(file: File, newVersionCode: Int, newVersionName: String) {
    if (!file.exists()) return

    var contents = file.readText()

    contents = replaceSectionContents(contents, "version", newVersionName)
    contents = replaceSectionContents(contents, "versionCode", newVersionCode.toString())

    val newZipUrl = "https://github.com/Codecity001/PixelXpert/releases/download/$newVersionName/PixelXpert-$newVersionName.zip"
    contents = replaceSectionContents(contents, "zipUrl", newZipUrl)
    contents = replaceSectionContents(contents, "zipUrl_Xposed", newZipUrl)

    file.writeText(contents)
}

fun replaceSectionContents(contents: String, section : String, newVersionName: String): String {
    var result = contents
    val regex = Regex("""($section.*[:=][\s"]*)([^,"\r\n]+)""")
    val match = regex.find(contents)

    if (match != null) {
        val prefix = match.groupValues[1]

        result = contents.replaceFirst("$prefix${match.groupValues[2]}", "$prefix$newVersionName")
    }
    return result
}

fun Project.getVersionName(): String {
    return getVersionNameProvider().get()
}

fun Project.getVersionCode(): Int {
    return getVersionCodeProvider().get()
}

fun Project.getVersionCodeProvider(): Provider<Int> {
    val versionFile = rootProject.layout.projectDirectory.file("version.properties")
    val isStableProvider = providers.gradleProperty("channel").map { it == "stable" }.orElse(false)
    
    return providers.fileContents(versionFile).asText.zip(isStableProvider) { text, isStable ->
        val props = Properties()
        if (text.isNotEmpty()) props.load(text.reader())
        val code = props.getProperty("VERSION_CODE", "1").toInt()
        if (isStable) code else code + 1
    }
}

fun Project.getVersionNameProvider(): Provider<String> {
    val isStableProvider = providers.gradleProperty("channel").map { it == "stable" }.orElse(false)
    val gitVersionProvider = providers.of(GitTagProvider::class.java) {}
    
    return getVersionCodeProvider().flatMap { code ->
        isStableProvider.map { isStable ->
            if (isStable) {
                gitVersionProvider.get()
            } else {
                getCanaryVersionName(code)
            }
        }
    }
}

fun getCanaryVersionName(versionCode: Int): String {
    return "canary-$versionCode"
}

fun incrementVersionLogic(
    isStable: Boolean,
    vFile: File,
    filesToUpdate: List<File>,
    targetVersionName: String) {
    val props = Properties()

    if (vFile.exists()) {
        vFile.inputStream().use { props.load(it) }
    }

    val oldCode = props.getProperty("VERSION_CODE", "0").toInt()
    val newCode = oldCode + (if (isStable) 0 else 1)
    
    if (!isStable) {
        props.setProperty("VERSION_CODE", newCode.toString())
        props.setProperty("VERSION_NAME", targetVersionName)
        vFile.outputStream().use {
            props.store(it, "Updated via Gradle Task")
        }
    }

    val versionName = if (isStable) targetVersionName else getCanaryVersionName(newCode)

    filesToUpdate.forEach {
        bumpFileStandalone(it, newCode, versionName)
    }
}