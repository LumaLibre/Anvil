import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URI
import javax.inject.Inject
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

plugins {
    id("java-library")
    id("io.freefair.lombok") version "9.2.0"
    id("com.gradleup.shadow") version "9.3.1"
}

// TODO: Configure
group = "dev.lumas.templates"
version = "0.0.0"

repositories {
    // TODO: Configure
}

dependencies {
    // TODO: Configure
}

// TODO: Configure
tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set(rootProject.name)

        manifest {
            attributes(
                "Implementation-Title" to rootProject.name,
                "Implementation-Version" to project.version,
            )
        }
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}

// TODO: Configure
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// TODO: Configure
val decompileConfig = DecompileConfig(
    inputJar = "sources/Template.jar",
    vineflowerVersion = "1.11.2",
    packageMappings = mapOf(
        "dev/lumas/templates" to "."
    ),
    resourceMappings = mapOf(
        "plugin.yml" to "."
    )
)




data class DecompileConfig(
    val inputJar: String = "sources/Decompile-Patcher-Template.jar",
    val vineflowerVersion: String = "1.11.2",
    val decompilerDir: String = "build/decompiler",
    val generatedDir: String = "sources/generated",
    val patchesDir: String = "patches",
    val packageMappings: Map<String, String> = emptyMap(),
    val resourceMappings: Map<String, String> = emptyMap(),
)

val decompilerDir = layout.buildDirectory.dir(decompileConfig.decompilerDir.removePrefix("build/"))
val inputJarFile = layout.projectDirectory.file(decompileConfig.inputJar)
val generatedOutputDir = layout.projectDirectory.dir(decompileConfig.generatedDir)
val patchesDirPath = layout.projectDirectory.dir(decompileConfig.patchesDir)

fun resolveModuleDir(moduleTarget: String): File? {
    if (moduleTarget == ".") return project.projectDir
    return try {
        project(":$moduleTarget").projectDir
    } catch (e: Exception) {
        null
    }
}

fun resolveModuleSrcDir(moduleTarget: String, packagePath: String): File? {
    val moduleDir = resolveModuleDir(moduleTarget) ?: return null
    return moduleDir.resolve("src/main/java/$packagePath")
}

fun resolveModuleResourcesDir(moduleTarget: String): File? {
    val moduleDir = resolveModuleDir(moduleTarget) ?: return null
    return moduleDir.resolve("src/main/resources")
}

// ============================================================================
// GIT-BASED PATCH MANAGEMENT SYSTEM
// ============================================================================

// Helper class to encapsulate git operations with ExecOperations
abstract class GitOperationsService @Inject constructor(
    private val execOps: ExecOperations
) : BuildService<BuildServiceParameters.None> {
    fun init(workingDir: File) {
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "init")
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    }

    fun config(workingDir: File, key: String, value: String) {
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "config", key, value)
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    }

    fun add(workingDir: File, path: String = ".") {
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "add", path)
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    }

    fun commit(workingDir: File, message: String): Int {
        val result = execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "commit", "-m", message)
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return result.exitValue
    }

    fun status(workingDir: File): String {
        val output = ByteArrayOutputStream()
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "status", "--short")
            standardOutput = output
        }
        return output.toString()
    }

    fun diffStat(workingDir: File): String {
        val output = ByteArrayOutputStream()
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "diff", "--cached", "--stat")
            standardOutput = output
        }
        return output.toString()
    }

    fun diffCached(workingDir: File, outputFile: File) {
        outputFile.outputStream().use { output ->
            execOps.exec {
                this.workingDir = workingDir
                commandLine("git", "diff", "--cached")
                standardOutput = output
            }
        }
    }

    fun apply(
        workingDir: File,
        patchFile: File,
        verbose: Boolean = false,
        threeWay: Boolean = false,
        reject: Boolean = false
    ): Pair<Int, String> {
        val errorOutput = ByteArrayOutputStream()
        val stdOutput = ByteArrayOutputStream()
        val args = mutableListOf("git", "apply")
        if (verbose) args.add("--verbose")
        if (threeWay) args.add("--3way")
        if (reject) args.add("--reject")
        args.add(patchFile.absolutePath)

        val result = execOps.exec {
            this.workingDir = workingDir
            commandLine(args)
            standardOutput = stdOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        return Pair(result.exitValue, errorOutput.toString() + stdOutput.toString())
    }

    fun hasConflictMarkers(workingDir: File): List<File> {
        return workingDir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "yml") }
            .filter { file ->
                try {
                    file.readLines().any { line ->
                        line.startsWith("<<<<<<<") ||
                                line.startsWith("=======") ||
                                line.startsWith(">>>>>>>")
                    }
                } catch (e: Exception) {
                    false
                }
            }
            .toList()
    }

    fun setupRepo(workingDir: File) {
        init(workingDir)
        config(workingDir, "user.name", "Patcher")
        config(workingDir, "user.email", "patch@local")
    }
}


val gitOpsService = gradle.sharedServices.registerIfAbsent("gitOps", GitOperationsService::class.java) {}

tasks.register("setupVineFlower") {
    doLast {
        decompilerDir.get().asFile.mkdirs()
        inputJarFile.asFile.parentFile.mkdirs()
        generatedOutputDir.asFile.mkdirs()

        if (!inputJarFile.asFile.exists()) {
            println("Input JAR not found at: ${decompileConfig.inputJar}. Nothing to decompile.")
            return@doLast
        }

        val pinnedVersion = decompileConfig.vineflowerVersion
        val expectedJarName = "vineflower-$pinnedVersion.jar"
        val vineflowerJar = decompilerDir.get().file(expectedJarName).asFile

        if (vineflowerJar.exists() && vineflowerJar.length() > 0) {
            println("✓ Vineflower $pinnedVersion already present: ${vineflowerJar.name}")
            return@doLast
        }

        // Clean out any old/stale vineflower jars so we don't ever accidentally
        // pick up the wrong version in the decompile task.
        decompilerDir.get().asFile.listFiles()
            ?.filter { it.name.startsWith("vineflower-") && it.name.endsWith(".jar") }
            ?.forEach {
                println("🗑 Removing stale ${it.name}")
                it.delete()
            }

        val downloadUrl = "https://github.com/Vineflower/vineflower/releases/download/$pinnedVersion/vineflower-$pinnedVersion.jar"
        println("Downloading Vineflower $pinnedVersion from $downloadUrl ...")

        try {
            URI(downloadUrl).toURL().openStream().use { input ->
                Files.copy(input, vineflowerJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Some releases are published with a -slim variant instead of the plain jar.
            val slimJarName = "vineflower-$pinnedVersion-slim.jar"
            val slimUrl = "https://github.com/Vineflower/vineflower/releases/download/$pinnedVersion/$slimJarName"
            println("Plain jar not found, trying slim variant: $slimUrl")
            val slimJar = decompilerDir.get().file(slimJarName).asFile
            URI(slimUrl).toURL().openStream().use { input ->
                Files.copy(input, slimJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        println("✓ Vineflower $pinnedVersion ready")
    }
}

val decompile by tasks.registering(Exec::class) {
    dependsOn("setupVineFlower")

    inputs.file(inputJarFile)
    inputs.property("vineflowerVersion", decompileConfig.vineflowerVersion)
    outputs.dir(generatedOutputDir)

    doFirst {
        val pinnedVersion = decompileConfig.vineflowerVersion
        // Look for the pinned version specifically — never just "the first jar in the dir."
        val vineflowerJar = decompilerDir.get().asFile.listFiles()
            ?.firstOrNull { it.name.startsWith("vineflower-$pinnedVersion") && it.name.endsWith(".jar") }
            ?: error("Vineflower $pinnedVersion jar not found. Run './gradlew setupVineFlower' first.")

        generatedOutputDir.asFile.mkdirs()

        commandLine(
            "java",
            "-jar",
            vineflowerJar.absolutePath,
            inputJarFile.asFile.absolutePath,
            generatedOutputDir.asFile.absolutePath
        )
    }
}

tasks.register("distributeSources") {
    dependsOn(decompile)

    doLast {
        val generatedDir = generatedOutputDir.asFile
        if (!generatedDir.exists()) {
            println("No generated sources found. Run 'decompile' task first.")
            return@doLast
        }

        println("Distributing decompiled sources to modules...")


        decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
            val sourcePackageDir = generatedDir.resolve(packagePath)

            if (!sourcePackageDir.exists()) {
                println("⚠ Package directory not found: $packagePath")
                return@forEach
            }

            val targetSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
            if (targetSrcDir == null) {
                println("❌ Module not found: $moduleTarget")
                return@forEach
            }

            println("📦 Copying $packagePath -> $moduleTarget")
            targetSrcDir.mkdirs()

            var fileCount = 0
            sourcePackageDir.walkTopDown().forEach { sourceFile ->
                if (sourceFile.isFile && sourceFile.extension == "java") {
                    val relativePath = sourceFile.relativeTo(sourcePackageDir)
                    val targetFile = targetSrcDir.resolve(relativePath)
                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    fileCount++
                }
            }
            println("✓ Copied $fileCount files to $moduleTarget")
        }


        if (decompileConfig.resourceMappings.isNotEmpty()) {
            val byModule = decompileConfig.resourceMappings.entries.groupBy({ it.value }, { it.key })

            byModule.forEach { (moduleTarget, resourceNames) ->
                println("\n📦 Copying resources to $moduleTarget...")

                val resourcesDir = resolveModuleResourcesDir(moduleTarget)
                if (resourcesDir == null) {
                    println("❌ Module not found: $moduleTarget")
                    return@forEach
                }
                resourcesDir.mkdirs()

                var resourceCount = 0
                resourceNames.forEach { resourceName ->
                    val sourceResource = generatedDir.resolve(resourceName)
                    if (sourceResource.exists()) {
                        val targetResource = resourcesDir.resolve(resourceName)
                        if (sourceResource.isDirectory) {
                            sourceResource.copyRecursively(targetResource, overwrite = true)
                            val count = sourceResource.walkTopDown().count { it.isFile }
                            println("  ✓ Copied directory: $resourceName ($count files)")
                            resourceCount += count
                        } else {
                            targetResource.parentFile.mkdirs()
                            sourceResource.copyTo(targetResource, overwrite = true)
                            println("  ✓ Copied file: $resourceName")
                            resourceCount++
                        }
                    } else {
                        println("  ⚠ Resource not found: $resourceName")
                    }
                }
                println("✓ Copied $resourceCount resource files to $moduleTarget")
            }
        }

        println("\n✓ Source distribution complete!")
    }
}

tasks.register("cleanDistributedSources") {
    doLast {
        println("Cleaning distributed sources from modules...")

        decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
            try {
                val targetSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
                if (targetSrcDir != null && targetSrcDir.exists()) {
                    targetSrcDir.deleteRecursively()
                    println("🗑 Cleaned $moduleTarget/src/main/java/$packagePath")
                }
            } catch (e: Exception) {
                println("⚠ Could not clean $moduleTarget - ${e.message}")
            }
        }

        val byModule = decompileConfig.resourceMappings.entries.groupBy({ it.value }, { it.key })
        byModule.forEach { (moduleTarget, resourceNames) ->
            try {
                val resourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
                resourceNames.forEach { resourceName ->
                    val targetResource = resourcesDir.resolve(resourceName)
                    if (targetResource.exists()) {
                        if (targetResource.isDirectory) {
                            targetResource.deleteRecursively()
                        } else {
                            targetResource.delete()
                        }
                        println("🗑 Cleaned $moduleTarget/src/main/resources/$resourceName")
                    }
                }
            } catch (e: Exception) {
                println("⚠ Could not clean $moduleTarget resources - ${e.message}")
            }
        }

        println("✓ Clean complete!")
    }
}

tasks.register("cleanGenerated") {
    doLast {
        if (generatedOutputDir.asFile.exists()) {
            generatedOutputDir.asFile.deleteRecursively()
            println("🗑 Cleaned ${decompileConfig.generatedDir}")
        }
    }
}

// Holds the in-progress patch directory so finishPatch knows where to look
// when a human has manually resolved conflicts.
val conflictWorkspaceDir = project.projectDir.resolve(".patch-conflicts")

// Copies the patched files from `gitDir` back into the module sources and
// generated sources. Extracted so both applyPatch / applyAllPatches and
// finishPatch can share the logic.
fun copyPatchedFilesBack(gitDir: File): String {
    val sb = StringBuilder()
    decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
        val gitSourceDir = gitDir.resolve(packagePath)
        val moduleTargetDir = resolveModuleSrcDir(moduleTarget, packagePath) ?: return@forEach
        val generatedTargetDir = generatedOutputDir.asFile.resolve(packagePath)

        if (moduleTargetDir.exists()) moduleTargetDir.deleteRecursively()
        if (gitSourceDir.exists()) gitSourceDir.copyRecursively(moduleTargetDir, overwrite = true)

        if (generatedTargetDir.exists()) generatedTargetDir.deleteRecursively()
        if (gitSourceDir.exists()) {
            gitSourceDir.copyRecursively(generatedTargetDir, overwrite = true)
            sb.appendLine("  ✓ Updated $moduleTarget (+ generated sources)")
        } else {
            sb.appendLine("  ✓ Updated $moduleTarget (package deleted)")
        }
    }
    decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) ->
        val gitResource = gitDir.resolve("__resources__/$resourceName")
        val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
        val moduleResource = moduleResourcesDir.resolve(resourceName)
        val generatedResource = generatedOutputDir.asFile.resolve(resourceName)

        if (moduleResource.exists()) {
            if (moduleResource.isDirectory) moduleResource.deleteRecursively()
            else moduleResource.delete()
        }
        if (generatedResource.exists()) {
            if (generatedResource.isDirectory) generatedResource.deleteRecursively()
            else generatedResource.delete()
        }

        if (gitResource.exists()) {
            if (gitResource.isDirectory) {
                gitResource.copyRecursively(moduleResource, overwrite = true)
                gitResource.copyRecursively(generatedResource, overwrite = true)
            } else {
                moduleResource.parentFile.mkdirs()
                generatedResource.parentFile.mkdirs()
                gitResource.copyTo(moduleResource, overwrite = true)
                gitResource.copyTo(generatedResource, overwrite = true)
            }
            sb.appendLine("  ✓ Updated resource: $resourceName")
        }
    }
    return sb.toString()
}

// Helper used by applyPatch and applyAllPatches when 3-way merge applies the
// patch but leaves conflict markers needing human resolution.
fun handleConflictsAndStash(gitDir: File, patchName: String, conflicts: List<File>) {
    if (conflictWorkspaceDir.exists()) conflictWorkspaceDir.deleteRecursively()
    gitDir.copyRecursively(conflictWorkspaceDir, overwrite = true)
    conflictWorkspaceDir.resolve(".patch-name").writeText(patchName)

    println()
    println("⚠️  Patch applied with conflicts. Manual resolution needed.")
    println()
    println("Files with conflict markers:")
    conflicts.forEach { file ->
        println("  - ${file.relativeTo(conflictWorkspaceDir)}")
    }
    println()
    println("👉 Resolve conflicts in this directory:")
    println("   ${conflictWorkspaceDir.absolutePath}")
    println()
    println("   Open it in your IDE, fix the <<<<<<< / ======= / >>>>>>> markers,")
    println("   then run:")
    println()
    println("   ./gradlew finishPatch")
    println()
    println("   To abort instead:")
    println("   ./gradlew abortPatch")
    println("=".repeat(60))
}

tasks.register("createPatch") {
    usesService(gitOpsService)

    doLast {
        val patchNameValue = project.findProperty("patchName") as String?
            ?: throw GradleException("Please specify patch name: -PpatchName=\"name\"")

        val gitOps = gitOpsService.get()
        val patchesDirFile = patchesDirPath.asFile
        patchesDirFile.mkdirs()

        val patchFile = patchesDirFile.resolve("$patchNameValue.patch")

        println("\n🔧 Creating patch: $patchNameValue.patch")
        println("=".repeat(60))

        val gitDir = project.projectDir.resolve(".patch-git")
        if (gitDir.exists()) gitDir.deleteRecursively()
        gitDir.mkdirs()

        try {
            gitOps.setupRepo(gitDir)

            println("📋 Setting up git workspace...")
            decompileConfig.packageMappings.forEach { (packagePath, _) ->
                val sourceDir = generatedOutputDir.asFile.resolve(packagePath)
                if (sourceDir.exists()) {
                    val targetDir = gitDir.resolve(packagePath)
                    sourceDir.copyRecursively(targetDir, overwrite = true)
                }
            }
            decompileConfig.resourceMappings.forEach { (resourceName, _) -> // resources into __resources__ subdir
                val sourceResource = generatedOutputDir.asFile.resolve(resourceName)
                if (sourceResource.exists()) {
                    val targetResource = gitDir.resolve("__resources__/$resourceName")
                    if (sourceResource.isDirectory) {
                        sourceResource.copyRecursively(targetResource, overwrite = true)
                    } else {
                        targetResource.parentFile.mkdirs()
                        sourceResource.copyTo(targetResource, overwrite = true)
                    }
                }
            }

            gitOps.add(gitDir)
            gitOps.commit(gitDir, "Original decompiled sources")

            println("📝 Comparing with modified sources...")
            decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
                val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
                val targetDir = gitDir.resolve(packagePath)

                if (targetDir.exists()) targetDir.deleteRecursively()

                if (moduleSrcDir != null && moduleSrcDir.exists() && moduleSrcDir.walkTopDown().any { it.isFile }) {
                    moduleSrcDir.copyRecursively(targetDir, overwrite = true)
                }
            }
            decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) -> // compare module resources
                val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
                val moduleResource = moduleResourcesDir.resolve(resourceName)
                val targetResource = gitDir.resolve("__resources__/$resourceName")

                if (targetResource.exists()) {
                    if (targetResource.isDirectory) targetResource.deleteRecursively()
                    else targetResource.delete()
                }

                if (moduleResource.exists()) {
                    if (moduleResource.isDirectory) {
                        moduleResource.copyRecursively(targetResource, overwrite = true)
                    } else {
                        targetResource.parentFile.mkdirs()
                        moduleResource.copyTo(targetResource, overwrite = true)
                    }
                }
            }

            val hasChanges = gitOps.status(gitDir).isNotBlank()
            if (!hasChanges) {
                println("=".repeat(60))
                println("ℹ️  No changes detected - no patch created")
                return@doLast
            }

            gitOps.add(gitDir, "-A")
            println(gitOps.diffStat(gitDir))
            gitOps.diffCached(gitDir, patchFile)

            println("\n📦 Updating generated sources...")
            decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
                val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
                val generatedTargetDir = generatedOutputDir.asFile.resolve(packagePath)

                if (generatedTargetDir.exists()) generatedTargetDir.deleteRecursively()

                if (moduleSrcDir != null && moduleSrcDir.exists() && moduleSrcDir.walkTopDown().any { it.isFile }) {
                    moduleSrcDir.copyRecursively(generatedTargetDir, overwrite = true)
                    println("  ✓ Updated generated: $packagePath")
                } else if (generatedTargetDir.exists()) {
                    println("  ✓ Removed from generated: $packagePath")
                }
            }
            decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) -> // sync resources to generated
                val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
                val moduleResource = moduleResourcesDir.resolve(resourceName)
                val generatedResource = generatedOutputDir.asFile.resolve(resourceName)

                if (generatedResource.exists()) {
                    if (generatedResource.isDirectory) generatedResource.deleteRecursively()
                    else generatedResource.delete()
                }

                if (moduleResource.exists()) {
                    if (moduleResource.isDirectory) {
                        moduleResource.copyRecursively(generatedResource, overwrite = true)
                    } else {
                        generatedResource.parentFile.mkdirs()
                        moduleResource.copyTo(generatedResource, overwrite = true)
                    }
                    println("Updated generated resource: $resourceName")
                }
            }

            println("\n" + "=".repeat(60))
            println("✨ Created patch: ${decompileConfig.patchesDir}/$patchNameValue.patch")

        } finally {
            gitDir.deleteRecursively()
        }
    }
}

tasks.register("applyPatch") {
    usesService(gitOpsService)

    doLast {
        val patchNameValue = project.findProperty("patchName") as String?
            ?: throw GradleException("Please specify patch name: -PpatchName=\"name\"")

        val gitOps = gitOpsService.get()
        val patchesDirFile = patchesDirPath.asFile
        val patchFile = patchesDirFile.resolve("$patchNameValue.patch")

        if (!patchFile.exists()) {
            println("❌ Patch not found: $patchNameValue.patch")
            println("\nAvailable patches:")
            patchesDirFile.listFiles()
                ?.filter { it.extension == "patch" }
                ?.forEach { println("  - ${it.nameWithoutExtension}") }
            return@doLast
        }

        println("\n🔧 Applying patch: $patchNameValue.patch")
        println("=".repeat(60))

        val gitDir = project.projectDir.resolve(".patch-git")
        if (gitDir.exists()) gitDir.deleteRecursively()
        gitDir.mkdirs()

        var keepGitDir = false

        try {
            gitOps.setupRepo(gitDir)

            println("📋 Setting up workspace...")
            decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
                val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath) ?: return@forEach
                if (!moduleSrcDir.exists()) return@forEach
                val targetDir = gitDir.resolve(packagePath)
                moduleSrcDir.copyRecursively(targetDir, overwrite = true)
            }
            decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) -> // setup resources
                val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
                val moduleResource = moduleResourcesDir.resolve(resourceName)
                if (!moduleResource.exists()) return@forEach
                val targetResource = gitDir.resolve("__resources__/$resourceName")
                if (moduleResource.isDirectory) {
                    moduleResource.copyRecursively(targetResource, overwrite = true)
                } else {
                    targetResource.parentFile.mkdirs()
                    moduleResource.copyTo(targetResource, overwrite = true)
                }
            }

            gitOps.add(gitDir)
            gitOps.commit(gitDir, "Current state")

            println("📝 Applying patch (clean)...")
            var (exitCode, output) = gitOps.apply(gitDir, patchFile, verbose = true)

            if (exitCode != 0) {
                println("⚠️  Clean apply failed, attempting 3-way merge...")
                val (retryExit, retryOutput) = gitOps.apply(gitDir, patchFile, threeWay = true)
                exitCode = retryExit
                output = retryOutput

                if (exitCode == 0) {
                    val conflicts = gitOps.hasConflictMarkers(gitDir)
                    if (conflicts.isNotEmpty()) {
                        keepGitDir = true
                        handleConflictsAndStash(gitDir, patchNameValue, conflicts)
                        return@doLast
                    }
                    println("✓ Applied via 3-way merge cleanly")
                }
            }

            if (exitCode != 0) {
                println("❌ Failed to apply patch (both clean and 3-way):")
                println(output)
                println("\n💡 Try './gradlew applyPatchWithReject -PpatchName=\"$patchNameValue\"' to write .rej files")
                return@doLast
            }

            println("📦 Copying patched files back to modules and generated sources...")
            println(copyPatchedFilesBack(gitDir))

            println("=".repeat(60))
            println("✨ Patch applied successfully!")

        } catch (e: Exception) {
            println("❌ Error applying patch: ${e.message}")
            e.printStackTrace()
        } finally {
            if (!keepGitDir) gitDir.deleteRecursively()
        }
    }
}

tasks.register("finishPatch") {
    doLast {
        if (!conflictWorkspaceDir.exists()) {
            println("ℹ️  No in-progress patch to finish (no ${conflictWorkspaceDir.name} directory).")
            return@doLast
        }

        val patchNameFile = conflictWorkspaceDir.resolve(".patch-name")
        val patchName = if (patchNameFile.exists()) patchNameFile.readText().trim() else "unknown"

        println("\n🔧 Finishing patch: $patchName")
        println("=".repeat(60))

        // Make sure they actually resolved everything.
        val remaining = conflictWorkspaceDir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "yml") }
            .filter { file ->
                try {
                    file.readLines().any { line ->
                        line.startsWith("<<<<<<<") ||
                                line.startsWith("=======") ||
                                line.startsWith(">>>>>>>")
                    }
                } catch (e: Exception) { false }
            }
            .toList()

        if (remaining.isNotEmpty()) {
            println("❌ Conflict markers still present in:")
            remaining.forEach { println("  - ${it.relativeTo(conflictWorkspaceDir)}") }
            println("\nResolve all <<<<<<< / ======= / >>>>>>> markers and run again.")
            return@doLast
        }

        // Drop the marker file so it doesn't sneak into the source tree.
        patchNameFile.delete()

        println("📦 Copying resolved files back to modules and generated sources...")
        println(copyPatchedFilesBack(conflictWorkspaceDir))

        conflictWorkspaceDir.deleteRecursively()

        println("=".repeat(60))
        println("✨ Patch '$patchName' finalized.")
        println("💡 Consider running:")
        println("   ./gradlew createPatch -PpatchName=\"$patchName\"")
        println("   to refresh the patch file against the new decompile baseline.")
    }
}

tasks.register("abortPatch") {
    doLast {
        if (!conflictWorkspaceDir.exists()) {
            println("ℹ️  No in-progress patch to abort.")
            return@doLast
        }
        conflictWorkspaceDir.deleteRecursively()
        println("🗑 Aborted in-progress patch and cleaned ${conflictWorkspaceDir.name}.")
    }
}

tasks.register("applyPatchWithReject") {
    usesService(gitOpsService)

    doLast {
        val patchNameValue = project.findProperty("patchName") as String?
            ?: throw GradleException("Please specify patch name: -PpatchName=\"name\"")

        val gitOps = gitOpsService.get()
        val patchFile = patchesDirPath.asFile.resolve("$patchNameValue.patch")
        if (!patchFile.exists()) {
            println("❌ Patch not found: $patchNameValue.patch")
            return@doLast
        }

        val gitDir = project.projectDir.resolve(".patch-git")
        if (gitDir.exists()) gitDir.deleteRecursively()
        gitDir.mkdirs()

        try {
            gitOps.setupRepo(gitDir)

            decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
                val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath) ?: return@forEach
                if (moduleSrcDir.exists()) {
                    moduleSrcDir.copyRecursively(gitDir.resolve(packagePath), overwrite = true)
                }
            }

            gitOps.add(gitDir)
            gitOps.commit(gitDir, "Current state")

            val (exitCode, output) = gitOps.apply(gitDir, patchFile, reject = true)

            // --reject returns non-zero whenever any hunk fails, but it does
            // write the successful hunks and leave .rej files for the rest.
            val rejFiles = gitDir.walkTopDown().filter { it.name.endsWith(".rej") }.toList()

            // Stage the half-applied result so the user can poke at it.
            if (conflictWorkspaceDir.exists()) conflictWorkspaceDir.deleteRecursively()
            gitDir.copyRecursively(conflictWorkspaceDir, overwrite = true)
            conflictWorkspaceDir.resolve(".patch-name").writeText(patchNameValue)

            println("Apply exited with $exitCode")
            println(output)
            if (rejFiles.isNotEmpty()) {
                println("\nRejected hunks written to:")
                rejFiles.forEach { println("  - ${it.relativeTo(gitDir)}") }
            }
            println("\n👉 Inspect / fix up the partial state here:")
            println("   ${conflictWorkspaceDir.absolutePath}")
            println("\nWhen done: ./gradlew finishPatch")
            println("To abort:  ./gradlew abortPatch")

        } finally {
            gitDir.deleteRecursively()
        }
    }
}

tasks.register("applyAllPatches") {
    usesService(gitOpsService)

    doLast {
        val gitOps = gitOpsService.get()
        val patchesDirFile = patchesDirPath.asFile

        if (!patchesDirFile.exists() || patchesDirFile.listFiles()?.isEmpty() != false) {
            println("❌ No patches found in ${decompileConfig.patchesDir}/")
            return@doLast
        }

        if (conflictWorkspaceDir.exists()) {
            println("⚠️  An in-progress patch is already pending resolution:")
            println("    ${conflictWorkspaceDir.absolutePath}")
            println("    Run './gradlew finishPatch' or './gradlew abortPatch' first.")
            return@doLast
        }

        println("\n🔧 Applying all patches...")
        println("=".repeat(60))

        var successCount = 0
        var failedCount = 0
        var threeWayCount = 0
        val failedPatches = mutableListOf<String>()

        // Pause and let the user resolve if a 3-way merge leaves conflicts.
        // We return early from the loop in that case so we don't trample state
        // by trying later patches against a half-resolved tree.
        var stoppedForConflict = false

        patchesDirFile.listFiles()
            ?.filter { it.extension == "patch" }
            ?.sortedBy { it.name }
            ?.forEach inner@{ patchFile ->
                if (stoppedForConflict) return@inner

                println("\n📄 Applying: ${patchFile.nameWithoutExtension}")

                val gitDir = project.projectDir.resolve(".patch-git")
                if (gitDir.exists()) gitDir.deleteRecursively()
                gitDir.mkdirs()

                var keepGitDir = false

                try {
                    gitOps.setupRepo(gitDir)

                    decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
                        val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath) ?: return@forEach
                        if (moduleSrcDir.exists()) {
                            val targetDir = gitDir.resolve(packagePath)
                            moduleSrcDir.copyRecursively(targetDir, overwrite = true)
                        }
                    }
                    decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) ->
                        val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
                        val moduleResource = moduleResourcesDir.resolve(resourceName)
                        if (moduleResource.exists()) {
                            val targetResource = gitDir.resolve("__resources__/$resourceName")
                            if (moduleResource.isDirectory) {
                                moduleResource.copyRecursively(targetResource, overwrite = true)
                            } else {
                                targetResource.parentFile.mkdirs()
                                moduleResource.copyTo(targetResource, overwrite = true)
                            }
                        }
                    }

                    gitOps.add(gitDir)
                    gitOps.commit(gitDir, "Current state")

                    // Try clean apply first.
                    var (exitCode, errorMsg) = gitOps.apply(gitDir, patchFile)

                    if (exitCode != 0) {
                        // Fall back to 3-way. Small drift in surrounding lines
                        // no longer kills the patch.
                        val (retryExit, retryOut) = gitOps.apply(gitDir, patchFile, threeWay = true)
                        exitCode = retryExit
                        errorMsg = retryOut

                        if (exitCode == 0) {
                            val conflicts = gitOps.hasConflictMarkers(gitDir)
                            if (conflicts.isNotEmpty()) {
                                // 3-way produced conflict markers — stop everything
                                // and hand off to the human.
                                keepGitDir = true
                                stoppedForConflict = true
                                handleConflictsAndStash(gitDir, patchFile.nameWithoutExtension, conflicts)
                                return@inner
                            }
                            println("  ⚠️  Applied via 3-way (no conflicts)")
                            threeWayCount++
                        }
                    }

                    if (exitCode != 0) {
                        println("  ❌ Failed: ${errorMsg.take(200)}")
                        failedPatches.add(patchFile.nameWithoutExtension)
                        failedCount++
                    } else {
                        copyPatchedFilesBack(gitDir)
                        println("  ✓ Success (+ updated generated sources)")
                        successCount++
                    }
                } catch (e: Exception) {
                    println("  ❌ Error: ${e.message}")
                    failedPatches.add(patchFile.nameWithoutExtension)
                    failedCount++
                } finally {
                    if (!keepGitDir) gitDir.deleteRecursively()
                }
            }

        println("\n" + "=".repeat(60))
        println("✨ Clean: ${successCount - threeWayCount} | 3-way: $threeWayCount | Failed: $failedCount")

        if (failedPatches.isNotEmpty()) {
            println("\n❌ Failed patches (couldn't apply even with 3-way merge):")
            failedPatches.forEach { println("  - $it") }
            println("\n💡 Try './gradlew applyPatchWithReject -PpatchName=\"<name>\"' to see")
            println("   exactly which hunks failed, then resolve manually.")
        }

        if (stoppedForConflict) {
            println("\n⏸  Stopped — resolve the in-progress patch before running this again.")
        } else if (threeWayCount > 0) {
            println("\n💡 Some patches applied via 3-way merge. Consider regenerating them:")
            println("   for each patch: ./gradlew createPatch -PpatchName=\"<name>\"")
            println("   This refreshes the patch against the current decompile baseline,")
            println("   so future runs apply cleanly.")
        }
    }
}

tasks.register("syncSourceToGenerated") {
    group = "patching"
    description = "Copies module source (and resources) back into generated sources so the current state becomes the new patching baseline. Useful after manually resolving patches in your IDE."

    doLast {
        println("\n🔄 Syncing module sources -> generated baseline...")
        println("=".repeat(60))

        var fileCount = 0
        var resourceCount = 0

        decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
            val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
            val generatedTargetDir = generatedOutputDir.asFile.resolve(packagePath)

            if (moduleSrcDir == null || !moduleSrcDir.exists()) {
                // Module side is gone — wipe the generated copy to match.
                if (generatedTargetDir.exists()) {
                    generatedTargetDir.deleteRecursively()
                    println("🗑  Removed from generated (no module source): $packagePath")
                }
                return@forEach
            }

            // Wipe and re-copy so deletions in the module are reflected.
            if (generatedTargetDir.exists()) generatedTargetDir.deleteRecursively()
            generatedTargetDir.mkdirs()

            var copied = 0
            moduleSrcDir.walkTopDown().forEach { sourceFile ->
                if (sourceFile.isFile && sourceFile.extension == "java") {
                    val relativePath = sourceFile.relativeTo(moduleSrcDir)
                    val targetFile = generatedTargetDir.resolve(relativePath)
                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    copied++
                }
            }
            fileCount += copied
            println("📦 $moduleTarget/$packagePath -> generated/$packagePath ($copied files)")
        }

        decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) ->
            val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
            val moduleResource = moduleResourcesDir.resolve(resourceName)
            val generatedResource = generatedOutputDir.asFile.resolve(resourceName)

            if (generatedResource.exists()) {
                if (generatedResource.isDirectory) generatedResource.deleteRecursively()
                else generatedResource.delete()
            }

            if (!moduleResource.exists()) {
                println("🗑  Removed resource from generated (no module copy): $resourceName")
                return@forEach
            }

            if (moduleResource.isDirectory) {
                moduleResource.copyRecursively(generatedResource, overwrite = true)
                val count = moduleResource.walkTopDown().count { it.isFile }
                resourceCount += count
                println("📋 Resource dir: $resourceName ($count files)")
            } else {
                generatedResource.parentFile.mkdirs()
                moduleResource.copyTo(generatedResource, overwrite = true)
                resourceCount++
                println("📋 Resource file: $resourceName")
            }
        }

        println("\n" + "=".repeat(60))
        println("✓ Synced $fileCount java files and $resourceCount resource files.")
        println()
        println("💡 The generated baseline now matches your current module sources.")
        println("   './gradlew patchStatus' should report no modifications.")
        println("   You can now make new edits and './gradlew createPatch -PpatchName=\"...\"'")
        println("   will diff against this state.")
    }
}

tasks.register("listPatches") {
    doLast {
        val patchesDirFile = patchesDirPath.asFile

        if (!patchesDirFile.exists() || patchesDirFile.listFiles()?.isEmpty() != false) {
            println("❌ No patches found in ${decompileConfig.patchesDir}/")
            return@doLast
        }

        println("\n📋 Available Patches")
        println("=".repeat(60))

        patchesDirFile.listFiles()
            ?.filter { it.extension == "patch" }
            ?.sortedBy { it.name }
            ?.forEach { patchFile ->
                val lines = patchFile.readLines()
                val filesChanged = lines.count { it.startsWith("diff --git") }
                val additions = lines.count { it.startsWith("+") && !it.startsWith("+++") }
                val deletions = lines.count { it.startsWith("-") && !it.startsWith("---") }
                val sizeKb = patchFile.length() / 1024

                println("📄 ${patchFile.nameWithoutExtension}")
                println("   Files: $filesChanged | +$additions -$deletions | ${sizeKb}KB")
            }

        println("=".repeat(60))
    }
}

tasks.register("patchStatus") {
    doLast {
        println("\n📊 Patch Status - Modified Files")
        println("=".repeat(60))

        var modifiedCount = 0
        var newFilesCount = 0
        var deletedCount = 0

        decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
            val moduleSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
            val generatedDir = generatedOutputDir.asFile.resolve(packagePath)

            if (moduleSrcDir == null) return@forEach


            if (!moduleSrcDir.exists() && generatedDir.exists()) {
                val deletedFiles = generatedDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .count()
                if (deletedFiles > 0) {
                    println("\n📦 $moduleTarget")
                    println("  🗑️  DELETED ENTIRE PACKAGE ($deletedFiles files)")
                    deletedCount += deletedFiles
                }
                return@forEach
            }

            if (!generatedDir.exists()) return@forEach

            val moduleModified = mutableListOf<String>()
            val moduleNew = mutableListOf<String>()
            val moduleDeleted = mutableListOf<String>()

            if (moduleSrcDir.exists()) {
                moduleSrcDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .forEach { modifiedFile ->
                        val relativePath = modifiedFile.relativeTo(moduleSrcDir)
                        val originalFile = generatedDir.resolve(relativePath)

                        if (!originalFile.exists()) {
                            moduleNew.add(relativePath.path)
                            newFilesCount++
                        } else if (originalFile.readText() != modifiedFile.readText()) {
                            moduleModified.add(relativePath.path)
                            modifiedCount++
                        }
                    }
            }

            generatedDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { originalFile ->
                    val relativePath = originalFile.relativeTo(generatedDir)
                    val moduleFile = if (moduleSrcDir.exists()) moduleSrcDir.resolve(relativePath) else null

                    if (moduleFile == null || !moduleFile.exists()) {
                        moduleDeleted.add(relativePath.path)
                        deletedCount++
                    }
                }

            if (moduleModified.isNotEmpty() || moduleNew.isNotEmpty() || moduleDeleted.isNotEmpty()) {
                println("\n📦 $moduleTarget")
                moduleModified.forEach { println("  ✏️  Modified: $it") }
                moduleNew.forEach { println("  ➕ New: $it") }
                moduleDeleted.forEach { println("  🗑️  Deleted: $it") }
            }
        }

        // check resources
        val resourceModified = mutableListOf<String>()
        val resourceNew = mutableListOf<String>()
        val resourceDeleted = mutableListOf<String>()

        decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) ->
            val moduleResourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
            val moduleResource = moduleResourcesDir.resolve(resourceName)
            val generatedResource = generatedOutputDir.asFile.resolve(resourceName)

            if (moduleResource.isDirectory || generatedResource.isDirectory) {
                // directory resource - compare files inside
                val moduleFiles = if (moduleResource.exists())
                    moduleResource.walkTopDown().filter { it.isFile }.toSet() else emptySet()
                val generatedFiles = if (generatedResource.exists())
                    generatedResource.walkTopDown().filter { it.isFile }.toSet() else emptySet()

                moduleFiles.forEach { modFile ->
                    val relPath = modFile.relativeTo(moduleResource)
                    val genFile = generatedResource.resolve(relPath)
                    if (!genFile.exists()) {
                        resourceNew.add("$resourceName/$relPath")
                        newFilesCount++
                    } else if (genFile.readText() != modFile.readText()) {
                        resourceModified.add("$resourceName/$relPath")
                        modifiedCount++
                    }
                }
                generatedFiles.forEach { genFile ->
                    val relPath = genFile.relativeTo(generatedResource)
                    val modFile = moduleResource.resolve(relPath)
                    if (!modFile.exists()) {
                        resourceDeleted.add("$resourceName/$relPath")
                        deletedCount++
                    }
                }
            } else {
                // single file resource
                if (!moduleResource.exists() && generatedResource.exists()) {
                    resourceDeleted.add(resourceName)
                    deletedCount++
                } else if (moduleResource.exists() && !generatedResource.exists()) {
                    resourceNew.add(resourceName)
                    newFilesCount++
                } else if (moduleResource.exists() && generatedResource.exists()) {
                    if (moduleResource.readText() != generatedResource.readText()) {
                        resourceModified.add(resourceName)
                        modifiedCount++
                    }
                }
            }
        }

        if (resourceModified.isNotEmpty() || resourceNew.isNotEmpty() || resourceDeleted.isNotEmpty()) {
            println("\n📋 Resources")
            resourceModified.forEach { println("  ✏️  Modified: $it") }
            resourceNew.forEach { println("  ➕ New: $it") }
            resourceDeleted.forEach { println("  🗑️  Deleted: $it") }
        }

        println("\n" + "=".repeat(60))
        println("Summary: $modifiedCount modified, $newFilesCount new, $deletedCount deleted")

        if (modifiedCount > 0 || newFilesCount > 0 || deletedCount > 0) {
            println("\n💡 Run './gradlew createPatch -PpatchName=\"name\"' to save these changes")
        } else {
            println("\nℹ️  No modifications detected")
        }
    }
}

tasks.register("decompileAndApplyPatches") {
    dependsOn(decompile, "distributeSources")
    finalizedBy("applyAllPatches")

    doLast {
        println("\n✨ Decompilation complete! Patches will be applied next...")
    }
}

tasks.register("inspectDecompiledStructure") {
    doLast {
        val generatedDir = generatedOutputDir.asFile
        if (!generatedDir.exists()) {
            println("❌ No generated sources found. Run 'decompile' task first.")
            return@doLast
        }

        println("\n📂 Inspecting decompiled package structure...")
        println("=".repeat(60))

        generatedDir.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { dir ->
            fun printTree(file: File, prefix: String, depth: Int) {
                if (depth > 4) return
                val javaCount = file.walkTopDown().count { it.isFile && it.extension == "java" }
                if (javaCount == 0) return
                println("$prefix📦 ${file.relativeTo(generatedDir)} ($javaCount files)")
                file.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { child ->
                    printTree(child, "$prefix   ", depth + 1)
                }
            }
            printTree(dir, "", 0)
        }

        val resources = generatedDir.listFiles()?.filter { !it.isDirectory || it.name !in listOf("com", "org", "net") }
        if (resources != null && resources.isNotEmpty()) {
            println("\n📋 Resources at root:")
            resources.sorted().forEach { res ->
                if (res.isDirectory) {
                    val count = res.walkTopDown().count { it.isFile }
                    println("  📁 ${res.name}/ ($count files)")
                } else {
                    println("  📄 ${res.name}")
                }
            }
        }

        println("\n" + "=".repeat(60))
        println("💡 Use this information to configure packageMappings and resourceMappings")
    }
}