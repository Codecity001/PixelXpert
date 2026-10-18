package sh.siava.pixelxpert.registry

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import sh.siava.pixelxpert.annotations.BaseModPack
import sh.siava.pixelxpert.annotations.ChildProcessModPack
import sh.siava.pixelxpert.annotations.MainProcessModPack
import sh.siava.pixelxpert.annotations.ModPackPriority
import java.io.OutputStreamWriter

class ModPackProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        val baseModPackSymbols = resolver.getSymbolsWithAnnotation(BaseModPack::class.qualifiedName!!)
        val modPackAnnotations = baseModPackSymbols.filterIsInstance<KSClassDeclaration>().toList()
        if (modPackAnnotations.isEmpty()) return emptyList()

        val entries = mutableListOf<ModPackEntry>()

        for (annotation in modPackAnnotations) {
            val baseAnnotation = annotation.annotations.first {
                it.shortName.asString() == "BaseModPack"
            }
            val targetPackage = baseAnnotation.arguments.firstOrNull { it.name?.asString() == "targetPackage" }?.value as? String ?: ""

            val annotatedClasses = resolver.getSymbolsWithAnnotation(annotation.qualifiedName!!.asString())
                .filterIsInstance<KSClassDeclaration>()

            for (clazz in annotatedClasses) {
                val priority = clazz.annotations.firstOrNull { it.shortName.asString() == "ModPackPriority" }
                    ?.arguments?.firstOrNull()?.value as? Int ?: 99

                val childAnnotation = clazz.annotations.firstOrNull { it.shortName.asString() == "ChildProcessModPack" }
                val childProcess = childAnnotation != null
                val childProcessName = childAnnotation?.arguments?.firstOrNull { it.name?.asString() == "processNameContains" }?.value as? String ?: ""
                val mainProcess = if (childProcess) {
                    clazz.annotations.any { it.shortName.asString() == "MainProcessModPack" }
                } else {
                    true
                }

                entries.add(
                    ModPackEntry(
                        qualifiedName = clazz.qualifiedName!!.asString(),
                        targetPackage = targetPackage,
                        mainProcess = mainProcess,
                        childProcess = childProcess,
                        childProcessName = childProcessName,
                        priority = priority
                    )
                )
            }
        }

        if (entries.isNotEmpty()) {
            invoked = true
            // Sort by priority ascending, then class name
            val sortedEntries = entries.distinctBy { it.qualifiedName + it.targetPackage }
                .sortedWith(compareBy({ it.priority }, { it.qualifiedName }))
            generateRegistry(sortedEntries)
        }

        return emptyList()
    }

    private fun generateRegistry(entries: List<ModPackEntry>) {
        val file = codeGenerator.createNewFile(
            Dependencies(false),
            "sh.siava.pixelxpert.xposed",
            "ModPacks"
        )
        OutputStreamWriter(file).use { writer ->
            writer.write("package sh.siava.pixelxpert.xposed\n\n")
            writer.write("import sh.siava.pixelxpert.annotations.ModPackData\n\n")
            writer.write("object ModPacks {\n")
            writer.write("    @JvmStatic\n")
            writer.write("    fun getModPacks(): List<ModPackData> = listOf(\n")
            for (entry in entries) {
                writer.write("        ModPackData(${entry.qualifiedName}::class.java, \"${entry.targetPackage}\", ${entry.mainProcess}, ${entry.childProcess}, \"${entry.childProcessName}\"),\n")
            }
            writer.write("    )\n")
            writer.write("}\n")
        }
    }

    private data class ModPackEntry(
        val qualifiedName: String,
        val targetPackage: String,
        val mainProcess: Boolean,
        val childProcess: Boolean,
        val childProcessName: String,
        val priority: Int,
    )
}

class ModPackProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ModPackProcessor(environment.codeGenerator, environment.logger)
    }
}
