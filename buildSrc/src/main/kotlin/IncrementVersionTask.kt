
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

abstract class IncrementVersionTask : DefaultTask() {
	@get:InputFile
	abstract val versionFile: RegularFileProperty

	@get:OutputFiles
	abstract val filesToUpdate: ConfigurableFileCollection

	@get:Input
	abstract val targetVersionName: Property<String>

	@get:Input
	abstract val stable: Property<Boolean>

	@TaskAction
	fun increment() {
		incrementVersionLogic(
			stable.get(),
			versionFile.get().asFile,
			filesToUpdate.files.toList(),
			targetVersionName.get(),
		)
	}
}
