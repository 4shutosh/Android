/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.apps

internal class VpnExclusionList {

    companion object {
        fun isDdgApp(packageName: String): Boolean {
            return packageName.startsWith(DDG_PACKAGE_PREFIX)
        }

        private const val DDG_PACKAGE_PREFIX = "com.duckduckgo.mobile"
    }
}
