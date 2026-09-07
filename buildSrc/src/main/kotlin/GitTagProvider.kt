import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

abstract class GitTagProvider : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        return try {
            val stdout = ByteArrayOutputStream()

            execOperations.exec {
                commandLine("git", "describe", "--tags", "--match=v*", "--abbrev=0", "HEAD")
                standardOutput = stdout
            }
            val tag = stdout.toString().trim()
            if (tag.isNotEmpty()) tag else "Error"
        } catch (_: Exception) {
            "Error"
        }
    }
}