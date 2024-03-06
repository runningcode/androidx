/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.foundation.text.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Stable

/**
 * A function ([transformOutput]) that transforms the text presented to a user by a
 * [BasicTextField].
 */
@Stable
fun interface OutputTransformation {

    /**
     * Given a [TextFieldBuffer] that contains the contents of a [TextFieldState], modifies the
     * text. After this function returns, the contents of the buffer will be presented to the user
     * as the contents of the text field instead of the raw contents of the [TextFieldState].
     *
     * Note that the contents of the [TextFieldState] remain completely unchanged. This is a one-way
     * transformation that only affects what is presented to the user.
     */
    @ExperimentalFoundationApi
    fun TextFieldBuffer.transformOutput()
}
