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
    ) : Boolean {
        if (selectedProjects.contains(includedProject)) {
            println("already included: $includedProject")
            return false
        }
        if (!isCompatible(includedProject)) {
            println("incompatible project: $includedProject")
            return false
        }
        selectedProjects.add(includedProject)
        buildFileParser.getDependencyProjects(includedProject).forEach {
            addProject(it)
        }
        return true
    }

    private fun isCompatible(includedProject: SettingsParser.IncludedProject): Boolean {
        return projectCompatibilityCache.getOrPut(includedProject) {
            if (includedProject.gradlePath in INCOMPATIBLE_PROJECTS) {
                false
            } else {
                // temporarily set it to true to avoid recursion
                projectCompatibilityCache.put(includedProject, true)
                buildFileParser.getDependencyProjects(includedProject).all {
                    isCompatible(it)
                }
            }
        }
    }

    companion object {
        val INCOMPATIBLE_PROJECTS = setOf(
            ":benchmark:benchmark-common"
        )
    }
}