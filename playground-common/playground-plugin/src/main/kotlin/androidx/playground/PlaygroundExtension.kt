/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.playground

import androidx.build.SettingsParser
import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.slf4j.LoggerFactory

@Suppress("SyntheticAccessor")
open class PlaygroundExtension @Inject constructor(
    private val settings: Settings,
) {
    private var supportRootDir: File? = null
    private lateinit var snapshotSwapper: SnapshotSwapper
    private val logger = LoggerFactory.getLogger("playgroundExtension")
    private lateinit var repoConfig: PlaygroundRepositoryConfiguration

    /**
     * Projects that should be built on CI.
     * playgrundBuildOnServer task will only run these
     */
    private val ciTargetProjects = mutableSetOf<SettingsParser.IncludedProject>()

    init {
        settings.gradle.beforeProject {
            if (it.rootProject == it) {
                snapshotSwapper = SnapshotSwapper(it, repoConfig)
                it.extensions.extraProperties.set(
                    "ciTargetProjects",
                    ciTargetProjects.map {
                        it.gradlePath
                    }
                )
            }
            repoConfig.configureRepositories(it)
        }
        settings.gradle.afterProject {
            configurePlaygroundBuildOnServer(it)
        }
    }

    private val projectSelection by lazy {
        val supportRootDir = this@PlaygroundExtension.supportRootDir ?: error(
            """
            Must call setupPlayground first to initialize
        """.trimIndent()
        )
        val supportSettingsFile = File(supportRootDir, "settings.gradle")
        val availableProjects = SettingsParser.findProjects(supportSettingsFile)
        ProjectSelection(supportRootDir, availableProjects)
    }

    /**
     * Includes the project if it does not already exist.
     * This is invoked from `includeProject` to ensure all parent projects are included. If they are
     * not, gradle will use the root project path to set the projectDir, which might conflict in
     * playground. Instead, this method checks if another project in that path exists and if so,
     * changes the project dir to avoid the conflict.
     * see b/197253160 for details.
     */
    private fun includeFakeParentProjectIfNotExists(
        name: String,
        projectDir: File,
        fakeIfIncompatible: Boolean
    ) {
        if (name.isEmpty()) return
        if (settings.findProject(name) != null) {
            return
        }
        val actualProjectDir: File = if (settings.findProject(projectDir) != null) {
            // Project directory conflicts with an existing project (possibly root). Move it
            // to another directory to avoid the conflict.
            File(projectDir.parentFile, ".ignore-${projectDir.name}")
        } else {
            projectDir
        }
        includeProjectAt(name, actualProjectDir, fakeIfIncompatible)
        // Set it to a gradle file that does not exist.
        // We must always include projects starting with root, if we are including nested projects.
        settings.project(name).buildFileName = FAKE_PROJECT_GRADLE_FILE_NAME
    }

    private fun includeProjectAt(name: String, projectDir: File, fakeIfIncompatible: Boolean) {
        if (settings.findProject(name) != null) {
            throw GradleException("Cannot include project twice: $name is already included.")
        }
        logger.info("including project $name at $projectDir.")
        val parentPath = name.substring(0, name.lastIndexOf(":"))
        val parentDir = projectDir.parentFile
        // Make sure parent is created first. see: b/197253160 for details
        includeFakeParentProjectIfNotExists(
            parentPath,
            parentDir,
            fakeIfIncompatible
        )
        settings.include(name)
        val incompatibility = PlaygroundCompatibility.findIncompatibility(name).takeIf {
            fakeIfIncompatible
        }
        if (incompatibility != null) {
            logger.info("creating a fake project for $name from prebuilts")
            settings.project(name).buildFileName = FAKE_PROJECT_GRADLE_FILE_NAME
            settings.gradle.afterProject {
                if (it.path == name) {
                    snapshotSwapper.configureFakeProject(
                        project = it,
                        incompatibility = incompatibility
                    )
                }
            }
        } else {
            settings.project(name).projectDir = projectDir
        }
    }

    /**
     * Includes a project by name, with a path relative to the root of AndroidX.
     */
    fun includeProject(name: String, filePath: String, fakeIfIncompatible: Boolean) {
        if (supportRootDir == null) {
            throw GradleException("Must call setupPlayground() first.")
        }
        includeProjectAt(
            name, File(supportRootDir, filePath),
            fakeIfIncompatible = fakeIfIncompatible
        )
    }

    /**
     * Initializes the playground project to use public repositories as well as other internal
     * projects that cannot be found in public repositories.
     *
     * @param relativePathToRoot The relative path of the project to the root AndroidX project
     */
    @JvmOverloads
    fun setupPlayground(
        relativePathToRoot: String,
        block: (ProjectSelectionScope.() -> Unit)? = null
    ) {
        // gradlePluginPortal has a variety of unsigned binaries that have proper signatures
        // in mavenCentral, so don't use gradlePluginPortal() if you can avoid it
        settings.pluginManagement.repositories {
            it.mavenCentral()
            it.gradlePluginPortal().content {
                it.includeModule(
                    "org.jetbrains.kotlinx",
                    "kotlinx-benchmark-plugin"
                )
                it.includeModule(
                    "org.jetbrains.kotlinx.benchmark",
                    "org.jetbrains.kotlinx.benchmark.gradle.plugin"
                )
                it.includeModule(
                    "org.jetbrains.kotlin.plugin.serialization",
                    "org.jetbrains.kotlin.plugin.serialization.gradle.plugin"
                )
            }
        }
        val projectDir = settings.rootProject.projectDir
        val supportRoot = File(projectDir, relativePathToRoot).canonicalFile
        this.supportRootDir = supportRoot
        val buildFile = File(supportRoot, "playground-common/playground-build.gradle")
        val relativePathToBuild = projectDir.toPath().relativize(buildFile.toPath()).toString()

        val playgroundProperties = Properties()
        val propertiesFile = File(supportRoot, "playground-common/playground.properties")
        playgroundProperties.load(propertiesFile.inputStream())
        repoConfig = PlaygroundRepositoryConfiguration(playgroundProperties)
        settings.gradle.beforeProject { project ->
            // load playground properties. These are not kept in the playground projects to prevent
            // AndroidX build from reading them.
            playgroundProperties.forEach {
                project.extensions.extraProperties[it.key as String] = it.value
            }
        }

        settings.rootProject.buildFileName = relativePathToBuild
        SkikoSetup.setupSkiko(settings)
        REQUIRED_PROJECTS.forEach {
            projectSelection.addProject(
                projectSelection.requireProject(it),
                includeDependencies = true
            )
        }

        // allow public repositories
        System.setProperty("ALLOW_PUBLIC_REPOS", "true")

        // specify out dir location
        System.setProperty("CHECKOUT_ROOT", supportRoot.path)

        if (block != null) {
            selectProjects(block)
            projectSelection.finalize().forEach {
                includeProject(it.gradlePath, it.filePath, fakeIfIncompatible = true)
            }
        }
    }

    private fun configurePlaygroundBuildOnServer(
        project: Project
    ) {
        if (project.rootProject == project) {
            project.tasks.register(PLAYGROUND_BUILD_ON_SERVER_TASK) {
                it.dependsOn(project.tasks.named(BUILD_ON_SERVER_TASK))
            }
        } else {
            project.tasks.register(PLAYGROUND_BUILD_ON_SERVER_TASK) {
                val enableOnCi = when {
                    project.buildFile.name == FAKE_PROJECT_GRADLE_FILE_NAME -> false
                    ciTargetProjects.isEmpty() -> true
                    ciTargetProjects.any { it.gradlePath == project.path } -> true
                    else -> false
                }
                if (enableOnCi && project.plugins.hasPlugin("AndroidXPlugin")) {
                    it.dependsOn(project.tasks.named(BUILD_ON_SERVER_TASK))
                    project.tasks.findByName("test")?.let { testTask ->
                        it.dependsOn(testTask)
                    }
                }
            }
        }
    }

    private fun selectProjects(block: ProjectSelectionScope.() -> Unit) {
        val scope = object : ProjectSelectionScope {
            override fun includeProjectsWithPrefix(prefix: String) {
                val matching = projectSelection.allProjectsInSettings.filter {
                    it.gradlePath.startsWith(prefix)
                }
                check(matching.isNotEmpty()) {
                    "No projects matched prefix $prefix"
                }
                ciTargetProjects.addAll(matching)
                matching.forEach {
                    projectSelection.addProject(it, includeDependencies = true)
                }
            }
        }
        scope.block()
    }

    /**
     * A convenience method to include projects from the main AndroidX build using a filter.
     *
     * @param filter This filter will be called with the project name (project path in gradle).
     *               If filter returns true, it will be included in the build.
     */
    fun selectProjectsFromAndroidX(filter: (String) -> Boolean) {
        projectSelection.allProjectsInSettings.filter {
            filter(it.gradlePath)
        }.forEach { includedProject ->
            projectSelection.addProject(includedProject, includeDependencies = false)
        }
        projectSelection.finalize().forEach {
            includeProject(it.gradlePath, it.filePath, fakeIfIncompatible = false)
        }
    }

    /**
     * Checks if a project is necessary for playground projects that involve compose.
     */
    fun isNeededForComposePlayground(name: String): Boolean {
        if (name == ":compose:lint:common") return true
        if (name == ":compose:lint:internal-lint-checks") return true
        if (name == ":compose:test-utils") return true
        if (name == ":compose:lint:common-test") return true
        if (name == ":test:screenshot:screenshot") return true
        if (name == ":test:screenshot:screenshot-proto") return true
        return false
    }

    companion object {
        private const val PLAYGROUND_BUILD_ON_SERVER_TASK = "playgroundBuildOnServer"
        private const val BUILD_ON_SERVER_TASK = "buildOnServer"
        private const val FAKE_PROJECT_GRADLE_FILE_NAME = "ignored.gradle"
        private val REQUIRED_PROJECTS = setOf(
            ":lint-checks",
            ":internal-testutils-common",
            ":internal-testutils-gradle-plugin"
        )
    }
}

interface ProjectSelectionScope {
    fun includeProjectsWithPrefix(
        prefix: String
    )
}