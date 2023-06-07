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

/**
 * Structure to track incompatibilities with the playground setup.
 *
 * Some androidx projects are not possible to build outside the aosp
 * (repo) setup as they need private prebuilt files.
 *
 * This object tracks these incompatibilities with a strategy
 * to replace or reject them. See [IncompatibilityStrategy] for details.
 */
object PlaygroundCompatibility {
    private val incompatibilities = listOf(
        Incompatibility(
            gradlePath = ":benchmark:benchmark-common",
            strategy = IncompatibilityStrategy.Replace.WithPrebuilt(
                group = "androidx.benchmark",
                module = "benchmark-common"
            )
        ),
        Incompatibility(
            gradlePath = ":emoji2:emoji2",
            strategy = IncompatibilityStrategy.Replace.WithPrebuilt(
                group = "androidx.emoji2",
                module = "emoji2"
            )
        ),
        Incompatibility(
            gradlePath = ":noto-emoji-compat-font",
            strategy = IncompatibilityStrategy.ExcludeProjectWithDependants
        ),
        Incompatibility(
            gradlePath = ":noto-emoji-compat-flatbuffers",
            strategy = IncompatibilityStrategy.ExcludeProjectWithDependants
        ),
        // cannot build these
        Incompatibility(
            gradlePath = ":inspection:inspection",
            strategy = IncompatibilityStrategy.Replace.WithPrebuilt(
                group = "androidx.inspection",
                module = "inspection"
            )
        ),
        Incompatibility(
            gradlePath = ":icing",
            strategy = IncompatibilityStrategy.ExcludeProjectWithDependants
        ),
        Incompatibility(
            gradlePath = ":icing:nativeLib",
            strategy = IncompatibilityStrategy.ExcludeProjectWithDependants
        ),
        Incompatibility(
            gradlePath = ":external:libyuv",
            strategy = IncompatibilityStrategy.ExcludeProjectWithDependants
        ),
    )

    /**
     * Returns the incompatibility for the project match [gradlePath].
     */
    fun findIncompatibility(
        gradlePath: String
    ) = incompatibilities.find { it.gradlePath == gradlePath }

    /**
     * Returns `true` if this project has some incompatibility such that
     * it cannot be built on Github.
     */
    fun isIncompatible(
        gradlePath: String
    ) = incompatibilities.any { it.gradlePath == gradlePath }

    /**
     * Defines a project incompatibility for playground.
     * See [strategy] on how to handle the incompatibility.
     */
    class Incompatibility(
        /**
         * The gradle path of the project.
         */
        val gradlePath: String,
        /**
         * The solution to apply when this project is included.
         */
        val strategy: IncompatibilityStrategy
    )

    /**
     * Defines a strategy to resolve an incompatibility.
     */
    sealed interface IncompatibilityStrategy {
        /**
         * Any project that depends on this project will automatically be
         * excluded from the playground build.
         */
        object ExcludeProjectWithDependants : IncompatibilityStrategy

        /**
         * The project will be replaced with a fake project that will resolve
         * its configurations to the prebuilt.
         */
        sealed interface Replace : IncompatibilityStrategy {
            data class WithPrebuilt(
                val group: String,
                val module: String
            ) : Replace {
                /**
                 * Returns the maven coordinates for the project
                 */
                fun coordinates(
                    version: String
                ) = "$group:$module:$version"
            }

            data class WithPublic(
                val coordinates: String
            ) : Replace
        }
    }
}