
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File
import java.util.Properties


fun bumpFileStandalone(file: File, newVersionCode: Int, newVersionName: String) {
    if (!file.exists()) return

    var contents = file.readText()

    contents = replaceSectionContents(contents, "version", newVersionName)
    contents = replaceSectionContents(contents, "versionCode", newVersionCode.toString())

    val newZipUrl = "https://github.com/Codecity001/PixelXpert/releases/download/$newVersionName/PixelXpertFork-$newVersionName.zip"
    contents = replaceSectionContents(contents, "zipUrl_Xposed", newZipUrl)
    contents = replaceSectionContents(contents, "zipUrl", newZipUrl)

    file.writeText(contents)
}

fun replaceSectionContents(contents: String, section: String, newVersionName: String): String {
    var result = contents
    val regex = Regex("""(("$section"|\b$section\b)\s*[:=]\s*"?)([^",\r\n]+)("?)""")
    val match = regex.find(contents)

    if (match != null) {
        val prefix = match.groupValues[1]
        val suffix = match.groupValues[4]

        result = contents.substring(0, match.range.first) + "$prefix$newVersionName$suffix" + contents.substring(match.range.last + 1)
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
        if (isStable) {
            val codeStr = props.getProperty("STABLE_VERSION_CODE")
                ?: props.getProperty("VERSION_CODE", "600")
            codeStr.toInt()
        } else {
            val codeStr = props.getProperty("CANARY_VERSION_CODE")
                ?: props.getProperty("VERSION_CODE", "500")
            codeStr.toInt() + 1
        }
    }
}

fun Project.getVersionNameProvider(): Provider<String> {
    val versionNameProp = providers.gradleProperty("versionName")
    val isStableProvider = providers.gradleProperty("channel").map { it == "stable" }.orElse(false)
    val gitVersionProvider = providers.of(GitTagProvider::class.java) {}
    val versionFile = rootProject.layout.projectDirectory.file("version.properties")
    
    val computedProvider = getVersionCodeProvider().flatMap { code ->
        providers.fileContents(versionFile).asText.zip(isStableProvider) { text, isStable ->
            if (isStable) {
                val gitTag = gitVersionProvider.get()
                if (gitTag != "Error" && gitTag.isNotBlank() && gitTag.startsWith("v")) {
                    gitTag
                } else {
                    val props = Properties()
                    if (text.isNotEmpty()) props.load(text.reader())
                    props.getProperty("STABLE_VERSION_NAME")
                        ?: props.getProperty("VERSION_NAME", "v6.0.0")
                }
            } else {
                getCanaryVersionName(code)
            }
        }
    }

    return versionNameProp.orElse(computedProvider)
}

fun getCanaryVersionName(versionCode: Int): String {
    return "canary-$versionCode"
}

fun updateVersionProperties(vFile: File, updates: Map<String, String>) {
    if (!vFile.exists()) {
        val initialContent = updates.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
        vFile.writeText(initialContent)
        return
    }

    var content = vFile.readText()
    for ((key, value) in updates) {
        val regex = Regex("""(?m)^$key\s*=.*$""")
        if (regex.containsMatchIn(content)) {
            content = regex.replace(content, "$key=$value")
        } else {
            content = content.trimEnd() + "\n$key=$value\n"
        }
    }
    vFile.writeText(content.trimEnd() + "\n")
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

    if (isStable) {
        val code = (props.getProperty("STABLE_VERSION_CODE") ?: props.getProperty("VERSION_CODE", "600")).toInt()
        val versionName = if (targetVersionName.isNotBlank() && targetVersionName != "Error") {
            targetVersionName
        } else {
            props.getProperty("STABLE_VERSION_NAME") ?: props.getProperty("VERSION_NAME", "v6.0.0")
        }

        updateVersionProperties(vFile, mapOf(
            "STABLE_VERSION_CODE" to code.toString(),
            "STABLE_VERSION_NAME" to versionName
        ))

        filesToUpdate.forEach {
            bumpFileStandalone(it, code, versionName)
        }
    } else {
        val oldCode = (props.getProperty("CANARY_VERSION_CODE") ?: props.getProperty("VERSION_CODE", "0")).toInt()
        val newCode = oldCode + 1
        val versionName = getCanaryVersionName(newCode)

        updateVersionProperties(vFile, mapOf(
            "CANARY_VERSION_CODE" to newCode.toString(),
            "CANARY_VERSION_NAME" to versionName
        ))

        filesToUpdate.forEach {
            bumpFileStandalone(it, newCode, versionName)
        }
    }
}