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

import java.util.Locale
import org.gradle.api.GradleException
import org.gradle.api.initialization.Settings

object SkikoSetup {
    fun setupSkiko(settings: Settings) {
        settings.dependencyResolutionManagement {
            it.versionCatalogs {
                val libs = it.findByName("libs") ?: it.create("libs")
                libs.apply {
                    val os = System.getProperty("os.name").lowercase(Locale.US)
                    val currentOsArtifact =
                        if (os.contains("mac os x") ||
                            os.contains("darwin") ||
                            os.contains("osx")
                        ) {
                            val arch = System.getProperty("os.arch")
                            if (arch == "aarch64") {
                                "skiko-awt-runtime-macos-arm64"
                            } else {
                                "skiko-awt-runtime-macos-x64"
                            }
                        } else if (os.startsWith("win")) {
                            "skiko-awt-runtime-windows-x64"
                        } else if (os.startsWith("linux")) {
                            val arch = System.getProperty("os.arch")
                            if (arch == "aarch64") {
                                "skiko-awt-runtime-linux-arm64"
                            } else {
                                "skiko-awt-runtime-linux-x64"
                            }
                        } else {
                            throw GradleException("Unsupported operating system $os")
                        }
                    library(
                        "skikoCurrentOs",
                        "org.jetbrains.skiko",
                        currentOsArtifact
                    ).versionRef("skiko")
                }
            }
        }
    }
}