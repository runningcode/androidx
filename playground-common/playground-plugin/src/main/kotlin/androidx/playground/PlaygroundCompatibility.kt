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

object PlaygroundCompatibility {
    val incompatibilities = listOf(
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

    fun findIncompatibility(
        gradlePath: String
    ) = incompatibilities.find { it.gradlePath == gradlePath }

    fun isIncompatible(
        gradlePath: String
    ) = incompatibilities.any { it.gradlePath == gradlePath }

    class Incompatibility(
        val gradlePath: String,
        val strategy: IncompatibilityStrategy
    )
    sealed interface IncompatibilityStrategy {
        // any project that depends on this will be ignored in settings
        object ExcludeProjectWithDependants : IncompatibilityStrategy
        // we have a way to replace.
        sealed interface Replace: IncompatibilityStrategy {
            data class WithPrebuilt(
                val group: String,
                val module: String
            ): Replace
            data class WithPublic(
                val coordinates: String
            ): Replace
        }
    }
}