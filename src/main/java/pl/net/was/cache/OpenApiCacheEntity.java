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
package pl.net.was.cache;

import com.fasterxml.jackson.core.JsonPointer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import pl.net.was.HttpPath;
import pl.net.was.OpenApiColumn;
import pl.net.was.OpenApiTableHandle;

import java.util.List;
import java.util.Map;

public record OpenApiCacheEntity(
        OpenAPI openApi,
        Map<String, List<OpenApiColumn>> tables,
        Map<String, OpenApiTableHandle> handles,
        Map<String, Map<HttpPath, JsonPointer>> errorPointers,
        Map<String, Map<PathItem.HttpMethod, List<SecurityRequirement>>> pathSecurityRequirements,
        Map<String, SecurityScheme> securitySchemas,
        List<SecurityRequirement> securityRequirements,
        boolean isFallback)
{
    public static final OpenApiCacheEntity FALLBACK = new OpenApiCacheEntity();

    public OpenApiCacheEntity(OpenAPI openApi,
            Map<String, List<OpenApiColumn>> tables,
            Map<String, OpenApiTableHandle> handles,
            Map<String, Map<HttpPath, JsonPointer>> errorPointers,
            Map<String, Map<PathItem.HttpMethod, List<SecurityRequirement>>> pathSecurityRequirements,
            Map<String, SecurityScheme> securitySchemas,
            List<SecurityRequirement> securityRequirements)
    {
        this(openApi, tables, handles, errorPointers, pathSecurityRequirements, securitySchemas, securityRequirements,
                false);
    }

    public OpenApiCacheEntity()
    {
        this(null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), true);
    }
}
