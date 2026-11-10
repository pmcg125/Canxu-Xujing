/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.Image: ImageVector
    get() {
        if (_image != null) {
            return _image!!
        }
        _image = materialIcon(name = "Outlined.Image") {
            materialPath {
                // frame: outer clockwise, inner counter-clockwise -> ring
                moveTo(4.0f, 4.0f)
                lineTo(20.0f, 4.0f)
                lineTo(20.0f, 20.0f)
                lineTo(4.0f, 20.0f)
                close()
                moveTo(6.0f, 6.0f)
                lineTo(6.0f, 18.0f)
                lineTo(18.0f, 18.0f)
                lineTo(18.0f, 6.0f)
                close()
                // sun
                moveTo(9.0f, 10.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                close()
                // mountain
                moveTo(6.6f, 17.2f)
                lineTo(10.2f, 12.4f)
                lineTo(12.4f, 15.2f)
                lineTo(14.4f, 12.8f)
                lineTo(17.4f, 17.2f)
                close()
            }
        }
        return _image!!
    }

private var _image: ImageVector? = null
