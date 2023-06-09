/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.build

import androidx.build.dependencyTracker.AffectedModuleDetector
import androidx.build.gradle.isRoot
import groovy.xml.DOMBuilder
import java.net.URL
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * This plugin is used in Playground projects and adds functionality like resolving to snapshot
 * artifacts instead of projects or allowing access to public maven repositories.
 */
@Suppress("unused") // used in Playground Projects
class AndroidXPlaygroundRootImplPlugin : Plugin<Project> {
    private lateinit var rootProject: Project

    /**
     * Maven url for the snapshots repository. It will be used to find snapshot versions
     * of modules that are included via `projectOrArtifact`.
     */
    private lateinit var snapshotRepoUrl: String

    private lateinit var snapshotBuildId: String

    override fun apply(target: Project) {
        if (!target.isRoot) {
            throw GradleException("This plugin should only be applied to root project")
        }
        if (!target.plugins.hasPlugin(AndroidXRootImplPlugin::class.java)) {
            throw GradleException(
                "Must apply AndroidXRootImplPlugin before applying AndroidXPlaygroundRootImplPlugin"
            )
        }
        rootProject = target
        @Suppress("UNCHECKED_CAST")
        snapshotBuildId = target.findProperty(
            PLAYGROUND_SNAPSHOT_BUILD_ID
        )?.toString() ?: error("Cannot find $PLAYGROUND_SNAPSHOT_BUILD_ID in project")
        snapshotRepoUrl = "https://androidx.dev/snapshots/builds/" +
            "$snapshotBuildId/artifacts/repository"
        GradleTransformWorkaround.maybeApply(rootProject)
        val ciTargetProjects = getTargetProjectPaths(target)
        rootProject.subprojects {
            configureSubProject(it, ciTargetProjects)
        }
        createPlaygroundBuildOnServer(rootProject, ciTargetProjects)
    }

    private fun createPlaygroundBuildOnServer(
        rootProject: Project,
        ciTargetProjects: Set<String>?
    ) {
        rootProject.tasks.register(PLAYGROUND_BUILD_ON_SERVER_TASK) {
            it.dependsOn(rootProject.tasks.named(BUILD_ON_SERVER_TASK))
        }
        rootProject.subprojects { subProject ->
            subProject.plugins.withType(AndroidXImplPlugin::class.java) {
                subProject.tasks.register(PLAYGROUND_BUILD_ON_SERVER_TASK) {
                    val enableOnCi = ciTargetProjects.isNullOrEmpty() ||
                        ciTargetProjects.contains(subProject.path)
                    if (enableOnCi) {
                        it.dependsOn(subProject.tasks.named(BUILD_ON_SERVER_TASK))
                        kotlin.runCatching {
                            // is this a good way to avoid triggering task creation ?
                            subProject.tasks.named("test")
                        }.getOrNull()?.let { testTask ->
                            it.dependsOn(testTask)
                        }
                    }
                }
            }
        }
    }

    private fun configureSubProject(project: Project, ciTargetProjects: Set<String>?) {
        project.configurations.all { configuration ->
            configuration.resolutionStrategy.eachDependency { details ->
                val requested = details.requested
                if (requested.version == SNAPSHOT_MARKER) {
                    val snapshotVersion = findSnapshotVersion(requested.group, requested.name)
                    details.useVersion(snapshotVersion)
                }
            }
        }
        if (!ciTargetProjects.isNullOrEmpty()) {
            project.plugins.withType(AndroidXImplPlugin::class.java) {
                // add a task guard for BuildOnServer
                project.tasks.named(BUILD_ON_SERVER_TASK).configure {
                    AffectedModuleDetector.configureTaskGuard(it)
                }
            }
        }
    }

    /**
     * Finds the snapshot version from the AndroidX snapshot repository.
     *
     * This is initially done by reading the maven-metadata from the snapshot repository.
     * The result of that query is cached in the build file so that subsequent build requests will
     * not need to access the network.
     */
    private fun findSnapshotVersion(group: String, module: String): String {
        val snapshotVersionCache = rootProject.buildDir
            .resolve("snapshot-version-cache/$snapshotBuildId")
        val groupPath = group.replace('.', '/')
        val modulePath = module.replace('.', '/')
        val metadataCacheFile = snapshotVersionCache.resolve("$groupPath/$modulePath/version.txt")
        return if (metadataCacheFile.exists()) {
            metadataCacheFile.readText(Charsets.UTF_8)
        } else {
            val metadataUrl = "$snapshotRepoUrl/$groupPath/$modulePath/maven-metadata.xml"
            URL(metadataUrl).openStream().use {
                val parsedMetadata = DOMBuilder.parse(it.reader())
                val versionNodes = parsedMetadata.getElementsByTagName("latest")
                if (versionNodes.length != 1) {
                    throw GradleException(
                        "AndroidXPlaygroundRootImplPlugin#findSnapshotVersion expected exactly " +
                            " one latest version in $metadataUrl, but got ${versionNodes.length}"
                    )
                }
                val snapshotVersion = versionNodes.item(0).textContent
                metadataCacheFile.parentFile.mkdirs()
                metadataCacheFile.writeText(snapshotVersion, Charsets.UTF_8)
                snapshotVersion
            }
        }
    }

    companion object {
        fun getTargetProjectPaths(project: Project): Set<String>? {
            check(ProjectLayoutType.isPlayground(project)) {
                "Should not call this method outside playground projects"
            }
            @Suppress("UNCHECKED_CAST")
            return (project.rootProject.extensions.extraProperties.get(
                "ciTargetProjects"
            ) as? List<String>)?.toSet()

        }

        /**
         * Returns a `project` if exists or the latest artifact coordinates if it doesn't.
         *
         * This can be used for optional dependencies in the playground settings.gradle files.
         *
         * @param path The project path
         * @return A Project instance if it exists or coordinates of the artifact if the project is not
         *         included in this build.
         */
        fun projectOrArtifact(rootProject: Project, path: String): Any {
            val requested = rootProject.findProject(path)
            if (requested != null) {
                return requested
            } else {
                val sections = path.split(":")

                if (sections[0].isNotEmpty()) {
                    throw GradleException(
                        "Expected projectOrArtifact path to start with empty section but got $path"
                    )
                }

                // Typically androidx projects have 3 sections, compose has 4.
                if (sections.size >= 3) {
                    val group = sections
                        // Filter empty sections as many declarations start with ':'
                        .filter { it.isNotBlank() }
                        // Last element is the artifact.
                        .dropLast(1)
                        .joinToString(".")
                    return "androidx.$group:${sections.last()}:$SNAPSHOT_MARKER"
                }

                throw GradleException("projectOrArtifact cannot find/replace project $path")
            }
        }

        const val SNAPSHOT_MARKER = "REPLACE_WITH_SNAPSHOT"
        const val INTERNAL_PREBUILTS_REPO_URL =
            "https://androidx.dev/storage/prebuilts/androidx/internal/repository"

        // build on server task for playground that only depends on BUILD_ON_SERVER task
        private const val PLAYGROUND_BUILD_ON_SERVER_TASK = "playgroundBuildOnServer"
    }
}
