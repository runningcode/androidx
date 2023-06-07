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

import androidx.build.SettingsParser
import androidx.playground.PlaygroundCompatibility.IncompatibilityStrategy.ExcludeProjectWithDependants
import androidx.playground.PlaygroundCompatibility.IncompatibilityStrategy.Replace
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Defines the logic for selecting playground projects.
 */
class ProjectSelection(
    supportRootDir: File,
    val allProjectsInSettings: List<SettingsParser.IncludedProject>,
) {
    private var finalized: Boolean = false
    private val logger = LoggerFactory.getLogger("playgroundProjectSelection")
    private val selectedProjects = mutableSetOf<SettingsParser.IncludedProject>()
    private val buildFileParser = BuildFileParser(supportRootDir, allProjectsInSettings)
    private val projectCompatibilityCache = mutableMapOf<SettingsParser.IncludedProject, Boolean>()

    fun finalize() = selectedProjects.toList().also {
        finalized = true
    }

    /**
     * Adds the project to the selection if possible.
     * Will return `true` if it is added,
     * return `false` if it is already added,
     * throw an exception if it is impossible to add the project.
     */
    fun addProject(
        includedProject: SettingsParser.IncludedProject,
        includeDependencies: Boolean
    ): Boolean {
        check(!finalized) {
            "Cannot add projects after project selection is finalized"
        }
        if (!isCompatible(includedProject)) {
            error(
                """
                Cannot add project $includedProject because it depends on an incompatible project.
                Dependency: ${findIncompatibilityPath(includedProject)}
            """.trimIndent()
            )
        }
        return addProjectAlreadyChecked(includedProject, includeDependencies)
    }

    private fun addProjectAlreadyChecked(
        includedProject: SettingsParser.IncludedProject,
        includeDependencies: Boolean
    ): Boolean {
        logger.info("add project internal :$includedProject")

        if (selectedProjects.contains(includedProject)) {
            logger.info("already included: $includedProject")
            return false
        }
        val incompatibility =
            PlaygroundCompatibility.findIncompatibility(includedProject.gradlePath)
        if (incompatibility?.strategy == ExcludeProjectWithDependants) {
            error("should never try to include include project")
        }
        selectedProjects.add(includedProject)
        if (incompatibility == null && includeDependencies) {
            buildFileParser.getDependencyProjects(includedProject).forEach {
                addProjectAlreadyChecked(it, includeDependencies)
            }
        }
        return true
    }

    private fun findIncompatibilityPath(
        includedProject: SettingsParser.IncludedProject,
        stack: List<SettingsParser.IncludedProject> = emptyList()
    ): String? {
        if (includedProject in stack) return null
        val incompatibility =
            PlaygroundCompatibility.findIncompatibility(includedProject.gradlePath)
        if (incompatibility?.strategy == ExcludeProjectWithDependants) {
            return includedProject.gradlePath
        } else if (incompatibility?.strategy is Replace) {
            return null
        }
        val subPath = stack + includedProject
        buildFileParser.getDependencyProjects(includedProject).forEach {
            val subIncompatible = findIncompatibilityPath(it, stack = subPath)
            if (subIncompatible != null) {
                return "${includedProject.gradlePath} -> $subIncompatible"
            }
        }
        return null
    }

    private fun isCompatible(includedProject: SettingsParser.IncludedProject): Boolean {
        return projectCompatibilityCache.getOrPut(includedProject) {
            val incompatibility =
                PlaygroundCompatibility.findIncompatibility(includedProject.gradlePath)
            if (incompatibility == null) {
                // temporarily set it to true to avoid recursion
                projectCompatibilityCache[includedProject] = true
                // check dependencies
                buildFileParser.getDependencyProjects(includedProject).all {
                    isCompatible(it)
                }
            } else {
                // known project
                val strategy = incompatibility.strategy
                strategy != ExcludeProjectWithDependants
            }
        }
    }

    fun findProject(path: String): SettingsParser.IncludedProject? {
        return allProjectsInSettings.find { it.gradlePath == path }
    }

    fun requireProject(path: String): SettingsParser.IncludedProject {
        return checkNotNull(findProject(path)) {
            "Cannot find project with path $path"
        }
    }
}