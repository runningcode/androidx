/*
 * Copyright 2023 The Android Open Source Project
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

import androidx.playground.PlaygroundCompatibility.IncompatibilityStrategy.Replace
import groovy.xml.DOMBuilder
import java.net.URL
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.attributes.Attribute

/**
 * Replaces dependencies on incompatible projects with their prebuilts.
 */
class SnapshotSwapper(
    private val rootProject: Project
) {
    private val config = PlaygroundProperties.load(rootProject)

    /**
     * List of snapshot repositories to fetch AndroidX artifacts
     */
    private val repos = PlaygroundRepositories(config)

    fun swapIncompatibleProjectDependencies2(
        project: Project
    ) {
        val replacements = PlaygroundCompatibility.incompatibilities.mapNotNull {
            when (val strategy = it.strategy) {
                PlaygroundCompatibility.IncompatibilityStrategy.ExcludeProjectWithDependants -> null
                is Replace.WithPrebuilt -> {
                    it.gradlePath to listOf(
                        strategy.group,
                        strategy.module,
                        findSnapshotVersion(strategy.group, strategy.module)
                    ).joinToString(":")
                }

                is Replace.WithPublic -> {
                    it.gradlePath to strategy.coordinates
                }
            }
        }
    }

    fun swapIncompatibleProjectDependencies(
        project: Project
    ) {
        if (true) return

        project.configurations.all { configuration ->
            val doLog = configuration.name == "debugUnitTestRuntimeClasspath"
            configuration.resolutionStrategy.eachDependency { details ->
                println("CHECKING ${details.requested}")
                val requested = details.requested
                if (requested.version == "unspecified") {
                    val pathPrefix = requested.group.substringAfter(
                        "${project.rootProject.name}.",
                        missingDelimiterValue = ""
                    ).replace(".", ":")
                    if (pathPrefix != "") {
                        val projectPath = ":$pathPrefix:${requested.name}"
                        val incompatibility = findIncompatibility(projectPath)
                        if (incompatibility != null) {
                            applyReplacementStrategy(details, incompatibility)
                        }
                    }
                }
            }
        }
    }

    private fun applyReplacementStrategy(
        details: DependencyResolveDetails,
        incompatibility: PlaygroundCompatibility.Incompatibility,
    ) {
        when (val strategy = incompatibility.strategy) {
            PlaygroundCompatibility.IncompatibilityStrategy.ExcludeProjectWithDependants -> {
                error("we should've never included this project")
            }

            is Replace.WithPrebuilt -> {
                val snapshotVersion = findSnapshotVersion(
                    group = strategy.group,
                    module = strategy.module
                )
                details.useTarget(
                    "${strategy.group}:${strategy.module}:$snapshotVersion"
                )
            }

            is Replace.WithPublic -> {
                details.useTarget(
                    strategy.coordinates
                )
            }
        }
    }

    private fun findIncompatibility(projectPath: String): PlaygroundCompatibility.Incompatibility? {
        return PlaygroundCompatibility.incompatibilities.find {
            it.gradlePath == projectPath
        }
    }

    private fun artifactCoordinates(strategy: Replace): String {
        return when (strategy) {
            is Replace.WithPrebuilt -> {
                val version = findSnapshotVersion(
                    strategy.group,
                    strategy.module
                )
                return "${strategy.group}:${strategy.module}:$version"
            }

            is Replace.WithPublic -> {
                return strategy.coordinates
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
        val snapshotVersionCache = rootProject.buildDir.resolve(
            "snapshot-version-cache/${config.snapshotBuildId}"
        )
        val groupPath = group.replace('.', '/')
        val modulePath = module.replace('.', '/')
        val metadataCacheFile = snapshotVersionCache.resolve("$groupPath/$modulePath/version.txt")
        return if (metadataCacheFile.exists()) {
            metadataCacheFile.readText(Charsets.UTF_8)
        } else {
            val metadataUrl = "${repos.snapshots.url}/$groupPath/$modulePath/maven-metadata.xml"
            URL(metadataUrl).openStream().use {
                val parsedMetadata = DOMBuilder.parse(it.reader())
                val versionNodes = parsedMetadata.getElementsByTagName("latest")
                if (versionNodes.length != 1) {
                    throw GradleException(
                        "AndroidXPlaygroundRootImplPlugin#findSnapshotVersion expected exactly " +
                                " one latest version in $metadataUrl, " +
                                "but got ${versionNodes.length}"
                    )
                }
                val snapshotVersion = versionNodes.item(0).textContent
                metadataCacheFile.parentFile.mkdirs()
                metadataCacheFile.writeText(snapshotVersion, Charsets.UTF_8)
                snapshotVersion
            }
        }
    }

    fun configureAritfacts(
        project: Project,
        incompatibility: PlaygroundCompatibility.Incompatibility
    ) {
        project.configurations.create(
            "prebuiltDependencyConfiguration"
        ) { configuration ->
            configuration.attributes.apply {
                attribute(
                    createAttribute("com.android.build.api.attributes.AgpVersionAttr"),
                    "8.1.0-beta02"
                )
            }
            val strategy = incompatibility.strategy as Replace
            configuration.dependencies.add(
                project.dependencies.create(
                    artifactCoordinates(strategy)
                )
            )
        }
    }

    private fun createAttribute(
        name: String
    ): Attribute<String> {
        return Attribute.of(name, String::class.java)
    }

    private data class PlaygroundProperties(
        val snapshotBuildId: String,
        val metalavaBuildId: String,
    ) {
        companion object {
            fun load(project: Project): PlaygroundProperties {
                return PlaygroundProperties(
                    snapshotBuildId = project
                        .requireProperty("androidx.playground.snapshotBuildId"),
                    metalavaBuildId = project
                        .requireProperty("androidx.playground.metalavaBuildId"),
                )
            }

            private fun Project.requireProperty(name: String): String {
                return checkNotNull(findProperty(name)) {
                    "missing $name property. It must be defined in the gradle.properties file"
                }.toString()
            }
        }
    }

    private data class PlaygroundRepository(
        val url: String,
        val includeGroupRegex: String,
        val includeModuleRegex: String? = null
    )

    private class PlaygroundRepositories(
        props: PlaygroundProperties
    ) {
        val sonatypeSnapshot = PlaygroundRepository(
            url = "https://oss.sonatype.org/content/repositories/snapshots",
            includeGroupRegex = """com\.pinterest.*""",
            includeModuleRegex = """ktlint.*"""
        )
        val snapshots = PlaygroundRepository(
            "https://androidx.dev/snapshots/builds/${props.snapshotBuildId}/artifacts/repository",
            includeGroupRegex = """androidx\..*"""
        )
        val metalava = PlaygroundRepository(
            "https://androidx.dev/metalava/builds/${props.metalavaBuildId}/artifacts" +
                    "/repo/m2repository",
            includeGroupRegex = """com\.android\.tools\.metalava"""
        )
        val prebuilts = PlaygroundRepository(
            "https://androidx.dev/storage/prebuilts/androidx/internal/repository",
            includeGroupRegex = """androidx\..*"""
        )
        val dokka = PlaygroundRepository(
            "https://maven.pkg.jetbrains.space/kotlin/p/dokka/dev",
            includeGroupRegex = """org\.jetbrains\.dokka"""
        )
        val all = listOf(sonatypeSnapshot, snapshots, metalava, dokka, prebuilts)
    }
}