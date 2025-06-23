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
        boolean isFallback
)
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
