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

class ProjectSelection(
    private val supportRootDir: File,
    projects: List<SettingsParser.IncludedProject>
) {
    private val selectedProjects = mutableSetOf<SettingsParser.IncludedProject>()
    private val buildFileParser = BuildFileParser(supportRootDir, projects)
    private val projectCompatibilityCache = mutableMapOf<SettingsParser.IncludedProject, Boolean>()

    val selection
        get() = selectedProjects.toList()

    fun addProject(
        includedProject: SettingsParser.IncludedProject
    ): Boolean {
        println("add project :$includedProject")
        if (!isCompatible(includedProject)) {
            error("""
                Cannot add project $includedProject because it depends on an incompatible project.
                Dependency: ${findIncompatibilityPath(includedProject)}
            """.trimIndent())
        }
        return addProjectAlreadyChecked(includedProject)
    }

    private fun addProjectAlreadyChecked(
        includedProject: SettingsParser.IncludedProject
    ): Boolean {
        println("add project internal :$includedProject")

        if (selectedProjects.contains(includedProject)) {
            println("already included: $includedProject")
            return false
        }
        selectedProjects.add(includedProject)
        val incompatibility = PlaygroundCompatibility.findIncompatibility(includedProject.gradlePath)
        if (incompatibility?.strategy == ExcludeProjectWithDependants) {
            error("should never try to include include project")
        }
        if (incompatibility == null) {
            buildFileParser.getDependencyProjects(includedProject).forEach {
                addProjectAlreadyChecked(it)
            }
        }
        return true
    }

    private fun findIncompatibilityPath(includedProject: SettingsParser.IncludedProject,
                                        stack: List<SettingsParser.IncludedProject> = emptyList()
    ): String? {
        if (includedProject in stack) return null
        val incompatibility = PlaygroundCompatibility.findIncompatibility(includedProject.gradlePath)
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
            val incompatibility = PlaygroundCompatibility.findIncompatibility(includedProject.gradlePath)
            if (incompatibility == null) {
                // temporarily set it to true to avoid recursion
                projectCompatibilityCache.put(includedProject, true)
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

    companion object {
        private val INCOMPATIBLE_PROJECTS = PlaygroundCompatibility.incompatibilities.filter {
            it.strategy == ExcludeProjectWithDependants
        }.map { it.gradlePath }
    }
}