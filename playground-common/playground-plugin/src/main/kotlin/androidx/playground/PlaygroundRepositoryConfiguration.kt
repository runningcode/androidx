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

import java.net.URI
import java.util.Properties
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler

class PlaygroundRepositoryConfiguration(
    properties: Properties
) {
    private val config = PlaygroundProperties(properties)
    val snapshotBuildId
        get() = config.snapshotBuildId

    /**
     * List of snapshot repositories to fetch AndroidX artifacts
     */
    val repos = PlaygroundRepositories(config)

    fun configureRepositories(project: Project) {
        // configure repositories
        println("configuring repositories for ${project.path}")
        project.repositories.addPlaygroundRepositories()
    }

    private fun RepositoryHandler.addPlaygroundRepositories() {
        repos.all.forEach { playgroundRepository ->
            maven { repository ->
                repository.url = URI(playgroundRepository.url)
                repository.metadataSources {
                    it.mavenPom()
                    it.artifact()
                }
                repository.content {
                    it.includeGroupByRegex(playgroundRepository.includeGroupRegex)
                    if (playgroundRepository.includeModuleRegex != null) {
                        it.includeModuleByRegex(
                            playgroundRepository.includeGroupRegex,
                            playgroundRepository.includeModuleRegex
                        )
                    }
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    data class PlaygroundProperties(
        val snapshotBuildId: String,
        val metalavaBuildId: String,
    ) {

        constructor(properties: Properties) : this(
            snapshotBuildId = properties.getProperty("androidx.playground.snapshotBuildId"),
            metalavaBuildId = properties.getProperty("androidx.playground.metalavaBuildId")
        )
    }

    data class PlaygroundRepository(
        val url: String,
        val includeGroupRegex: String,
        val includeModuleRegex: String? = null
    )

    class PlaygroundRepositories(
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