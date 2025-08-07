/*
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
package pl.net.was;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

public final class OpenApiSpecUtil
{
    public static final String UNWRAP_SPEC_EXTENSION = "x-unwrap";
    public static final String UNWRAP_RESULTS_PATH = "resultParam";
    public static final String UNWRAP_INCLUDE_ROOT = "includeRoot";
    public static final String PAGE_SPEC_EXTENSION = "x-trino";
    public static final String LEGACY_SPEC_EXTENSION = "x-pagination";
    public static final String PAGINATION_RESULTS_PATH = "resultsPath";
    public static final String ERROR_PATH = "errorPath";
    public static final String PAGINATION_PAGE_PARAM = "pageParam";
    public static final String PAGINATION_PAGE_SIZE_PARAM = "limitParam";

    private OpenApiSpecUtil()
    {
    }

    public static Map<String, String> getMapOfStrings(Object object)
    {
        if (!(object instanceof Map<?, ?>)) {
            return ImmutableMap.of();
        }

        return ((Map<?, ?>) object).entrySet().stream()
                .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                .collect(toImmutableMap(entry -> (String) entry.getKey(), entry -> (String) entry.getValue()));
    }
}
