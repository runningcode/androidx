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
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute

/**
 * Replaces dependencies on incompatible projects with their prebuilts.
 *
 * This is done by creating a configuration that depends on the replaced project's prebuilt.
 */
class SnapshotSwapper(
    private val rootProject: Project,
    private val config: PlaygroundRepositoryConfiguration
) {
    private val agpVersion by lazy {
        val libs = rootProject.extensions.getByType(
            VersionCatalogsExtension::class.java
        ).find("libs").get()
        val version = libs.findVersion("androidGradlePlugin")
        if (version.isPresent) {
            version.get().requiredVersion
        } else {
            throw GradleException("Could not find a version for androidGradlePlugin")
        }
    }

    private fun artifactCoordinates(strategy: Replace): String {
        return when (strategy) {
            is Replace.WithPrebuilt -> {
                val version = findSnapshotVersion(
                    strategy.group,
                    strategy.module
                )
                strategy.coordinates(version)
            }

            is Replace.WithPublic -> {
                strategy.coordinates
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
            "snapshot-version-cache${config.snapshotBuildId}"
        )
        val groupPath = group.replace('.', '/')
        val modulePath = module.replace('.', '/')
        val metadataCacheFile = snapshotVersionCache.resolve("$groupPath/$modulePath/version.txt")
        return if (metadataCacheFile.exists()) {
            metadataCacheFile.readText(Charsets.UTF_8)
        } else {
            val metadataUrl =
                "${config.repos.snapshots.url}/$groupPath/$modulePath/maven-metadata.xml"
            URL(metadataUrl).openStream().use {
                val parsedMetadata = DOMBuilder.parse(it.reader())
                val versionNodes = parsedMetadata.getElementsByTagName("latest")
                if (versionNodes.length != 1) {
                    throw GradleException(
                        "PlaygroundPlugin#findSnapshotVersion expected exactly " +
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

    fun configureFakeProject(
        project: Project,
        incompatibility: PlaygroundCompatibility.Incompatibility
    ) {
        project.configurations.create(
            "prebuiltDependencyConfiguration"
        ) { configuration ->
            configuration.attributes.apply {
                attribute(
                    AGP_VERSION_ATTR,
                    agpVersion
                )
            }
            require(incompatibility.strategy is Replace) {
                "Snapshot swapper only supports Replace strategy: ${incompatibility.strategy}"
            }
            configuration.dependencies.add(
                project.dependencies.create(
                    artifactCoordinates(incompatibility.strategy)
                )
            )
        }
    }

    companion object {
        private val AGP_VERSION_ATTR = Attribute.of(
            "com.android.build.api.attributes.AgpVersionAttr",
            String::class.java
        )
    }
}