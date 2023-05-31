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
import java.util.regex.Pattern

class BuildFileParser(
    private val supportRootDir: File,
    projects: List<SettingsParser.IncludedProject>
) {
    private val projectsByGradlePath = projects.associateBy { it.gradlePath }
    private val dependencies =
        mutableMapOf<SettingsParser.IncludedProject, Set<SettingsParser.IncludedProject>>()

    fun getDependencyProjects(
        project: SettingsParser.IncludedProject
    ): Set<SettingsParser.IncludedProject> {
        return dependencies.getOrPut(project) {
            resolveDependencyProjects(project)
        }
    }

    private fun resolveDependencyProjects(
        project: SettingsParser.IncludedProject
    ): Set<SettingsParser.IncludedProject> {
        val buildGradle = supportRootDir.resolve(project.filePath).resolve("build.gradle")
        if (buildGradle.exists()) {
            val contents = buildGradle.readText(Charsets.UTF_8)
            val links = mutableSetOf<String>()
            contents.lines().forEach { line ->
                val m = projectReferencePattern.matcher(line)
                if (m.find()) {
                    links.add(m.group("name"))
                }
                if (multilineProjectReference.matcher(line).find()) {
                    throw IllegalStateException(
                        "Multi-line project() references are not supported." +
                                "Please fix ${buildGradle.absolutePath}"
                    )
                }
                val matcherInspection = inspection.matcher(line)
                if (matcherInspection.find()) {
                    links.add(matcherInspection.group(1))
                }
                if (composePlugin.matcher(line).find()) {
                    links.add(":compose:compiler:compiler")
                    links.add(":compose:lint:internal-lint-checks")
                }
                if (paparazziPlugin.matcher(line).find()) {
                    links.add(":test:screenshot:screenshot-proto")
                    links.add(":internal-testutils-paparazzi")
                }
                if (iconGenerator.matcher(line).find()) {
                    links.add(":compose:material:material:icons:generator")
                }
            }
            return links.map {
                projectsByGradlePath[it] ?: error("Cannot find project with path $it")
            }.toSet()
        } else {
            error("cannot find build file $buildGradle")
        }
    }

    companion object {
        private val projectReferencePattern = Pattern.compile(
            "(project|projectOrArtifact)" +
                    "\\((path: )?[\"'](?<name>\\S*)[\"'](, configuration: .*)?\\)"
        )
        private val multilineProjectReference = Pattern.compile("project\\(\$")
        private val inspection = Pattern.compile("packageInspector\\(project, \"(.*)\"\\)")
        private val composePlugin = Pattern.compile("id\\(\"AndroidXComposePlugin\"\\)")
        private val paparazziPlugin = Pattern.compile("id\\(\"AndroidXPaparazziPlugin\"\\)")
        private val iconGenerator = Pattern.compile("IconGenerationTask\\.register")
    }
}