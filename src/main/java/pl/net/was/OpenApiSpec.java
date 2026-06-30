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

import com.fasterxml.jackson.core.JsonPointer;
import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.trino.spi.connector.SchemaNotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;
import pl.net.was.cache.OpenApiCacheEntity;
import pl.net.was.exception.OpenApiException;
import pl.net.was.exception.OpenApiParsingException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static pl.net.was.OpenApiSpecUtil.ERROR_PATH;
import static pl.net.was.OpenApiSpecUtil.LEGACY_SPEC_EXTENSION;
import static pl.net.was.OpenApiSpecUtil.PAGE_SPEC_EXTENSION;
import static pl.net.was.OpenApiSpecUtil.PAGINATION_PAGE_PARAM;
import static pl.net.was.OpenApiSpecUtil.PAGINATION_PAGE_SIZE_PARAM;
import static pl.net.was.OpenApiSpecUtil.PAGINATION_RESULTS_PATH;
import static pl.net.was.OpenApiSpecUtil.UNWRAP_RESULTS_PATH;
import static pl.net.was.OpenApiSpecUtil.UNWRAP_SPEC_EXTENSION;
import static pl.net.was.OpenApiSpecUtil.getMapOfStrings;
import static pl.net.was.OpenApiSpecUtil.isUseUnwrapWithRootNodes;

public class OpenApiSpec
{
    private static final Logger log = Logger.get(OpenApiSpec.class);

    public static final String SCHEMA_NAME = "default";
    public static final String ROW_ID = "__trino_row_id";
    public static final String HTTP_OK = "200";
    public static final String MIME_JSON = "application/json";
    private static final TypeTuple FALLBACK_TYPE = new TypeTuple(VARCHAR, new StringSchema());
    private final LoadingCache<String, OpenApiCacheEntity> openApiCache;
    private final OpenApiConfig config;
    private static final Pattern JSON_POINTER_PATTERN = Pattern.compile("\\$response\\.body#(/.*)");

    @Inject
    public OpenApiSpec(OpenApiConfig config)
    {
        this.config = requireNonNull(config, "config is null");
        openApiCache = CacheBuilder.newBuilder().build(CacheLoader.from(this::loadOpenApi));
        log.info("OpenApiSpec loaded");
    }

    private OpenApiCacheEntity loadOpenApi()
    {
        try {
            return getOpenApiCacheEntity();
        }
        catch (Exception e) {
            log.error("%s", e.getMessage(), e);
            return OpenApiCacheEntity.FALLBACK;
        }
    }

    private OpenApiCacheEntity getOpenApiCacheEntity()
    {
        OpenAPI openApi = parse(config.getSpecLocation());
        OpenApiSpecInfo openApiSpecInfo = getTables(openApi);
        logTableHandles(openApiSpecInfo.tables(), openApiSpecInfo.handles());
        return new OpenApiCacheEntity(
                openApi,
                openApiSpecInfo.tables(),
                openApiSpecInfo.handles(),
                openApiSpecInfo.errorPointers(),
                openApiSpecInfo.resultsPointers(),
                getPathSecurityRequirements(openApi),
                getSecuritySchemas(openApi),
                getSecurityRequirements(openApi));
    }

    public Map<String, List<OpenApiColumn>> getTables()
    {
        return getOpenApi().tables();
    }

    public Map<String, Map<PathItem.HttpMethod, List<SecurityRequirement>>> getPathSecurityRequirements()
    {
        return getOpenApi().pathSecurityRequirements();
    }

    public Map<String, SecurityScheme> getSecuritySchemas()
    {
        return getOpenApi().securitySchemas();
    }

    public List<SecurityRequirement> getSecurityRequirements()
    {
        return getOpenApi().securityRequirements();
    }

    private OpenApiCacheEntity getOpenApi()
    {
        String openApiVersion = getOpenApiVersion();
        OpenApiCacheEntity entity = openApiCache.getUnchecked(openApiVersion);
        if (entity.isFallback()) {
            OpenApiCacheEntity afterFallbackEntity = getOpenApiCacheEntity();
            openApiCache.put(openApiVersion, afterFallbackEntity);
            return afterFallbackEntity;
        }
        return entity;
    }

    private String getOpenApiVersion()
    {
        return parse(config.getSpecLocation()).getInfo().getVersion();
    }

    private OpenApiSpecInfo getTables(OpenAPI openApi)
    {
        /*
        Path params are assumed to be primary keys, so paths without any params are merged with same path with params.
        For example, /orgs and /orgs/{org} will be represented by a single table named orgs.
        If there are more paths with different params, they'll only be merged with the base path.
        For example, the following paths:
        * /orgs
        * /orgs/{org}
        * /orgs/{security_product}/{enablement}
        Will be merged into two tables:
        * orgs_org
        * orgs_security_product_enablement
        Where both tables will include columns created from the /orgs path response.
         */
        Map<String, List<Map.Entry<String, PathItem>>> pathGroups = openApi.getPaths().entrySet().stream()
                .filter(entry -> hasOpsWithJson(entry.getValue()))
                .filter(entry -> !getIdentifier(stripPathParams(entry.getKey())).isEmpty())
                // TODO group paths by the response type, otherwise it's not possible to create both unique and easy to use table names
                .collect(groupingBy(entry -> getIdentifier(stripPathParams(entry.getKey()))));
        ImmutableMap.Builder<String, List<OpenApiColumn>> tables = ImmutableMap.builder();
        ImmutableMap.Builder<String, OpenApiTableHandle> handles = ImmutableMap.builder();
        ImmutableMap.Builder<String, Map<HttpPath, JsonPointer>> errorPointers = ImmutableMap.builder();
        ImmutableMap.Builder<String, Map<HttpPath, JsonPointer>> resultsPointers = ImmutableMap.builder();
        for (Map.Entry<String, List<Map.Entry<String, PathItem>>> groupEntry : pathGroups.entrySet()) {
            List<PathItem> pathItems = groupEntry.getValue().stream()
                    .map(Map.Entry::getValue)
                    .toList();
            if (pathItems.size() == 1) {
                // create a new entry with the value unwrapped out of a list
                Map.Entry<String, PathItem> firstEntry = groupEntry.getValue().getFirst();
                tables.put(Map.entry(
                        groupEntry.getKey(),
                        mergeColumns(getColumns(openApi, pathItems.getFirst(), firstEntry.getKey()))));
                Map<PathItem.HttpMethod, List<String>> tablePaths =
                        methodsToPaths(firstEntry.getValue(), firstEntry.getKey());
                errorPointers.put(groupEntry.getKey(), errorPointers(firstEntry.getValue(), firstEntry.getKey()));
                resultsPointers.put(groupEntry.getKey(), resultsPointers(firstEntry.getValue(), firstEntry.getKey()));
                Map<PathItem.HttpMethod, Map<String, Object>> methodExtensions =
                        getMethodExtensionsMap(firstEntry.getValue(), firstEntry.getKey());
                handles.put(groupEntry.getKey(), tableHandle(groupEntry.getKey(), tablePaths, methodExtensions));
                continue;
            }
            Map.Entry<String, PathItem> baseEntry = groupEntry.getValue().stream()
                    .min(comparingInt(entry -> entry.getKey().length()))
                    .orElseThrow();
            List<OpenApiColumn> baseColumns = getColumns(openApi, baseEntry.getValue(), baseEntry.getKey());
            Map<PathItem.HttpMethod, List<String>> baseMethods =
                    methodsToPaths(baseEntry.getValue(), baseEntry.getKey());
            Map<PathItem.HttpMethod, Map<String, Object>> baseMethodExtensions =
                    getMethodExtensionsMap(baseEntry.getValue(), baseEntry.getKey());
            Map<HttpPath, JsonPointer> baseErrorPointers = errorPointers(baseEntry.getValue(), baseEntry.getKey());
            Map<HttpPath, JsonPointer> baseResultsPointers = resultsPointers(baseEntry.getValue(), baseEntry.getKey());
            // treat all combinations of path params as primary keys, which means every path with params is mapped to a separate table,
            // but combine it with columns from the base path
            groupEntry.getValue().stream()
                    .filter(entry -> !entry.equals(baseEntry))
                    .forEach(entry -> {
                        String tableName = getIdentifier(pathItems.size() == 2 ? groupEntry.getKey() : entry.getKey());
                        tables.put(
                                tableName,
                                mergeColumns(Stream.concat(
                                                baseColumns.stream(),
                                                getColumns(openApi, entry.getValue(), entry.getKey()).stream())
                                        .distinct()
                                        .toList()));
                        Map<PathItem.HttpMethod, List<String>> tablePaths = Stream.concat(
                                        baseMethods.entrySet().stream(),
                                        methodsToPaths(entry.getValue(), entry.getKey()).entrySet().stream())
                                .collect(toImmutableMap(
                                        Map.Entry::getKey,
                                        Map.Entry::getValue,
                                        (x, y) -> Stream.concat(x.stream(), y.stream()).distinct()
                                                .collect(toImmutableList())));
                        errorPointers.put(tableName, Stream.concat(
                                        baseErrorPointers.entrySet().stream(),
                                        errorPointers(entry.getValue(), entry.getKey()).entrySet().stream())
                                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
                        resultsPointers.put(tableName, Stream.concat(
                                        baseResultsPointers.entrySet().stream(),
                                        resultsPointers(entry.getValue(), entry.getKey()).entrySet().stream())
                                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));

                        handles.put(tableName, tableHandle(tableName, tablePaths, baseMethodExtensions));
                    });
        }
        return new OpenApiSpecInfo(
                tables.buildOrThrow(),
                handles.buildOrThrow(),
                errorPointers.buildOrThrow(),
                resultsPointers.buildOrThrow());
    }

    private Map<PathItem.HttpMethod, Map<String, Object>> getMethodExtensionsMap(PathItem pathItem, String path)
    {
        return pathItem.readOperationsMap().entrySet().stream()
                .filter(entry -> filterPath(path, entry.getKey()) && entry.getValue().getExtensions() != null)
                .collect(toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().getExtensions()));
    }

    private Map<String, Map<PathItem.HttpMethod, List<SecurityRequirement>>> getPathSecurityRequirements(
            OpenAPI openApi)
    {
        return openApi.getPaths().entrySet().stream()
                .map(pathEntry -> Map.entry(
                        pathEntry.getKey(),
                        pathEntry.getValue().readOperationsMap().entrySet().stream()
                                .filter(opEntry -> opEntry.getValue().getSecurity() != null)
                                .map(opEntry -> Map.entry(
                                        opEntry.getKey(),
                                        opEntry.getValue().getSecurity()))
                                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue))))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void logTableHandles(Map<String, List<OpenApiColumn>> tables,
            Map<String, OpenApiTableHandle> handles)
    {
        handles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> Stream.concat(Stream.of(
                                "SELECT FROM " + entry.getKey() + " maps to: " +
                                        pathsToString(entry.getValue().getSelectMethod(), entry.getValue().getSelectPaths()),
                                "INSERT INTO " + entry.getKey() + " maps to: " +
                                        pathsToString(entry.getValue().getInsertMethod(), entry.getValue().getInsertPaths()),
                                "UPDATE " + entry.getKey() + " maps to: " +
                                        pathsToString(entry.getValue().getUpdateMethod(), entry.getValue().getUpdatePaths()),
                                "DELETE FROM " + entry.getKey() + " maps to: " +
                                        pathsToString(entry.getValue().getDeleteMethod(), entry.getValue().getDeletePaths())),
                        tables.get(entry.getKey()).stream()
                                .filter(column -> !column.getRequiresPredicate().isEmpty() ||
                                        !column.getOptionalPredicate().isEmpty())
                                .map(column -> entry.getKey() + "." + column.getName() + " is " +
                                        (column.isPageNumber() ? "the page number, " : "") +
                                        "required for: " + column.getRequiresPredicate() + ", " +
                                        "optional for: " + column.getOptionalPredicate())))
                .forEach(log::info);
    }

    private Map<String, SecurityScheme> getSecuritySchemas(OpenAPI openApi)
    {
        return openApi.getComponents().getSecuritySchemes();
    }

    private List<SecurityRequirement> getSecurityRequirements(OpenAPI openApi)
    {
        return openApi.getSecurity();
    }

    private static String pathsToString(PathItem.HttpMethod method, List<String> paths)
    {
        return Optional.of(String.join(
                        ", ",
                        paths.stream().map(path -> method + " " + path).toList()))
                .filter(value -> !value.isBlank())
                .orElse("<none>");
    }

    private String stripPathParams(String key)
    {
        return key.replaceAll("/\\{[^\\}]+\\}", "");
    }

    private OpenAPI parse(String specLocation)
    {
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specLocation, null, parseOptions);
        OpenAPI openAPI = result.getOpenAPI();

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            throw new OpenApiParsingException(String.join(", ", result.getMessages()));
        }

        return openAPI;
    }

    private static boolean hasOpsWithJson(PathItem pathItem)
    {
        return pathItem.readOperations().stream().anyMatch(OpenApiSpec::hasJsonResponse);
    }

    private static boolean hasJsonResponse(Operation op)
    {
        return op != null && (op.getDeprecated() == null || !op.getDeprecated()) &&
                op.getResponses().get(HTTP_OK) != null &&
                op.getResponses().get(HTTP_OK).getContent() != null &&
                op.getResponses().get(HTTP_OK).getContent().get(MIME_JSON) != null;
    }

    public OpenApiTableHandle getTableHandle(SchemaTableName name)
    {
        if (!name.getSchemaName().equals(SCHEMA_NAME)) {
            throw new SchemaNotFoundException(name.getSchemaName());
        }
        OpenApiTableHandle handle = getOpenApi().handles().get(name.getTableName());
        if (handle == null) {
            throw new TableNotFoundException(name);
        }
        return handle.cloneWithBaseFields();
    }

    public Map<HttpPath, JsonPointer> getErrorPointers(SchemaTableName name)
    {
        if (!name.getSchemaName().equals(SCHEMA_NAME)) {
            throw new SchemaNotFoundException(name.getSchemaName());
        }
        Map<HttpPath, JsonPointer> result = getOpenApi().errorPointers().get(name.getTableName());
        if (result == null) {
            throw new TableNotFoundException(name);
        }
        return result;
    }

    public Map<HttpPath, JsonPointer> getResultsPointers(SchemaTableName name)
    {
        if (!name.getSchemaName().equals(SCHEMA_NAME)) {
            throw new SchemaNotFoundException(name.getSchemaName());
        }
        Map<HttpPath, JsonPointer> result = getOpenApi().resultsPointers().get(name.getTableName());
        if (result == null) {
            throw new TableNotFoundException(name);
        }
        return result;
    }

    private List<OpenApiColumn> getColumns(OpenAPI openApi, PathItem pathItem, String path)
    {
        Stream<OpenApiColumn> columns = pathItem.readOperationsMap().entrySet().stream()
                .flatMap(entry -> getColumn(openApi, path, entry))
                .distinct();
        if (pathItem.getPost() != null || pathItem.getPut() != null || pathItem.getDelete() != null) {
            // the ROW_ID column is required for MERGE operation, including UPDATE and DELETE
            return Stream.concat(
                            Stream.of(OpenApiColumn.builder()
                                    .setName(ROW_ID)
                                    .setType(VARCHAR)
                                    .setSourceType(new StringSchema())
                                    .setIsHidden(true)
                                    .build()),
                            columns)
                    .toList();
        }
        return columns.toList();
    }

    private Stream<OpenApiColumn> getColumn(OpenAPI openApi, String path, Map.Entry<PathItem.HttpMethod, Operation> entry)
    {
        PathItem.HttpMethod method = entry.getKey();
        Operation op = entry.getValue();
        List<OpenApiColumn> result = new ArrayList<>();
        Map<String, String> pageSpecExtension = getPageSpecExtension(op);
        Map<String, String> unwrapSpecExtension = getUnwrapSpecExtension(op);
        boolean useUnwrapExtension = !unwrapSpecExtension.isEmpty();
        Schema<?> schema = getResponseSchema(op);
        if (schema != null) {
            List<String> requiredProperties = schema.getRequired() != null ? schema.getRequired() : List.of();
            if (useUnwrapExtension) {
                initColumnsWithEnabledUnwrapSpecExtension(openApi,
                        unwrapSpecExtension,
                        pageSpecExtension,
                        schema,
                        requiredProperties,
                        result);
            }
            else {
                initColumns(openApi, pageSpecExtension, schema, requiredProperties, result);
            }
        }
        schema = getRequestSchema(op);
        if (schema != null) {
            initColumnsFromRequiredProperties(openApi, path, result, schema, method, pageSpecExtension);
        }
        if (op.getParameters() != null && filterPath(path, method)) {
            initColumnsFromRequiredParams(openApi, path, result, op, method, pageSpecExtension);
        }
        return result.stream();
    }

    private void initColumnsWithEnabledUnwrapSpecExtension(OpenAPI openApi,
            Map<String, String> unwrapSpecExtension,
            Map<String, String> pageSpecExtension,
            Schema<?> schema, List<String> requiredProperties, List<OpenApiColumn> result)
    {
        JsonPointer resultsPointer = getJsonPointerFromUnwrapSpec(pageSpecExtension, unwrapSpecExtension);
        boolean includeRootColumns = isUseUnwrapWithRootNodes(unwrapSpecExtension);
        if (includeRootColumns) {
            JsonPointer parentJsonPointer = getParentJsonPointer(resultsPointer);
            String tailPropertyName = getJsonPointerTailPropertyName(resultsPointer);
            getResultsSchema(schema, parentJsonPointer)
                    .entrySet().stream()
                    .filter(propEntry -> !tailPropertyName.matches(propEntry.getKey()))
                    .map(propEntry -> getResultColumn(openApi,
                            propEntry.getKey(),
                            JsonPointer.compile((parentJsonPointer.getMatchingProperty() ==
                                    null ? "" : parentJsonPointer.toString()) + "/" + propEntry.getKey()),
                            propEntry.getValue(),
                            !requiredProperties.contains(propEntry.getKey()),
                            propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_PARAM)),
                            propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_SIZE_PARAM))))
                    .filter(Optional::isPresent)
                    .forEach(column -> result.add(column.get()));
        }
        getResultsSchema(schema, resultsPointer)
                .entrySet().stream()
                .map(propEntry -> getResultColumn(openApi,
                        propEntry.getKey(),
                        resultsPointer,
                        propEntry.getValue(),
                        !requiredProperties.contains(propEntry.getKey()),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_PARAM)),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_SIZE_PARAM)),
                        true))
                .filter(Optional::isPresent)
                .forEach(column -> result.add(column.get()));
    }

    private void initColumns(OpenAPI openApi,
            Map<String, String> pageSpecExtension,
            Schema<?> schema,
            List<String> requiredProperties,
            List<OpenApiColumn> result)
    {
        JsonPointer resultsPointer = getJsonPointerFromPageSpec(pageSpecExtension);
        getSchemaProperties(schema)
                .entrySet().stream()
                .filter(propEntry -> !resultsPointer.matchesProperty(propEntry.getKey()))
                .map(propEntry -> getResultColumn(openApi,
                        propEntry.getKey(),
                        propEntry.getValue(),
                        !requiredProperties.contains(propEntry.getKey()),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_PARAM)),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_SIZE_PARAM))))
                .filter(Optional::isPresent)
                .forEach(column -> result.add(column.get()));
        getResultsSchema(schema, resultsPointer)
                .entrySet().stream()
                .map(propEntry -> getResultColumn(openApi,
                        propEntry.getKey(),
                        resultsPointer,
                        propEntry.getValue(),
                        !requiredProperties.contains(propEntry.getKey()),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_PARAM)),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_SIZE_PARAM))))
                .filter(Optional::isPresent)
                .forEach(column -> result.add(column.get()));
    }

    private void initColumnsFromRequiredProperties(
            OpenAPI openApi,
            String path,
            List<OpenApiColumn> result,
            Schema<?> schema,
            PathItem.HttpMethod method,
            Map<String, String> pageSpecExtension)
    {
        ListMultimap<String, OpenApiColumn.PrimaryKey> keys = result.stream()
                .collect(ArrayListMultimap::create,
                        (map, element) -> map.put(element.getName(), element.getPrimaryKey()),
                        ArrayListMultimap::putAll);
        List<String> requiredProperties = schema.getRequired() != null ? schema.getRequired() : List.of();
        getSchemaProperties(schema)
                .entrySet().stream()
                .map(propEntry -> getPredicateColumn(openApi,
                        propEntry.getKey(),
                        propEntry.getValue(),
                        requiredProperties.contains(propEntry.getKey()) ? ImmutableMap.of(
                                new HttpPath(method, path), ParameterLocation.BODY) : ImmutableMap.of(),
                        !requiredProperties.contains(propEntry.getKey()) ? ImmutableMap.of(
                                new HttpPath(method, path), ParameterLocation.BODY) : ImmutableMap.of(),
                        !requiredProperties.contains(propEntry.getKey()),
                        false,
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_PARAM)),
                        propEntry.getKey().equals(pageSpecExtension.get(PAGINATION_PAGE_SIZE_PARAM))))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(column -> {
                    while (hasAmbiguousName(column, keys)) {
                        // if the request param is also a response field,
                        // append `_req` to its name to disambiguate it,
                        // otherwise it'll get a number suffix, like `_2`
                        column = OpenApiColumn.builderFrom(column)
                                .setName(column.getName() + "_req")
                                .build();
                    }
                    return column;
                })
                .forEach(result::add);
    }

    private void initColumnsFromRequiredParams(OpenAPI openApi,
            String path,
            List<OpenApiColumn> result,
            Operation op,
            PathItem.HttpMethod method,
            Map<String, String> pageSpecExtension)
    {
        ListMultimap<String, OpenApiColumn.PrimaryKey> keys = result.stream()
                .collect(ArrayListMultimap::create,
                        (map, element) -> map.put(element.getName(), element.getPrimaryKey()),
                        ArrayListMultimap::putAll);
        // add required parameters as columns, so they can be set as predicates;
        // predicate values will be saved in the table handle and copied to result rows
        op.getParameters().stream()
                .map(parameter -> {
                    ParameterLocation parameterLocation =
                            parameter.getIn() == null ? ParameterLocation.NONE : ParameterLocation.valueOf(
                                    parameter.getIn().toUpperCase(Locale.ENGLISH));
                    return getPredicateColumn(openApi,
                            parameter.getName(),
                            parameter.getSchema(),
                            parameter.getRequired() ? ImmutableMap.of(new HttpPath(method, path),
                                    parameterLocation) : ImmutableMap.of(),
                            !parameter.getRequired() ? ImmutableMap.of(new HttpPath(method, path),
                                    parameterLocation) : ImmutableMap.of(),
                            // always nullable, because they're only required as predicates, not in INSERT statements
                            true,
                            // keep pagination parameters as hidden columns, so it's possible to
                            // see the page number (how many requests were made) and change the default per-page limit
                            pageSpecExtension.containsValue(parameter.getName()),
                            parameter.getName().equals(pageSpecExtension.get(PAGINATION_PAGE_PARAM)),
                            parameter.getName().equals(pageSpecExtension.get(PAGINATION_PAGE_SIZE_PARAM)));
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(column -> {
                    while (hasAmbiguousName(column, keys)) {
                        // if the request param is also a response field,
                        // append `_req` to its name to disambiguate it,
                        // otherwise it'll get a number suffix, like `_2`
                        column = OpenApiColumn.builderFrom(column)
                                .setName(column.getName() + "_req")
                                .build();
                    }
                    return column;
                })
                .forEach(result::add);
    }

    private Map<String, String> getPageSpecExtension(Operation op)
    {
        return op.getExtensions() == null ?
                ImmutableMap.of() :
                getMapOfStrings(firstNonNull(
                        op.getExtensions().get(PAGE_SPEC_EXTENSION),
                        firstNonNull(op.getExtensions().get(LEGACY_SPEC_EXTENSION), ImmutableMap.of())));
    }

    private Map<String, String> getUnwrapSpecExtension(Operation op)
    {
        return op.getExtensions() == null ?
                ImmutableMap.of() :
                getMapOfStrings(firstNonNull(
                        op.getExtensions().get(UNWRAP_SPEC_EXTENSION), ImmutableMap.of()));
    }

    private JsonPointer getJsonPointerFromUnwrapSpec(Map<String, String> pageSpecExtension,
            Map<String, String> unwrapSpecExtension)
    {
        if (!pageSpecExtension.isEmpty() && !pageSpecExtension.get(PAGINATION_RESULTS_PATH)
                .equals(unwrapSpecExtension.get(UNWRAP_RESULTS_PATH))) {
            throw new OpenApiException(
                    "Invalid value of %s extension %s. It must be the same as %s extension value of %s".formatted(
                            PAGE_SPEC_EXTENSION,
                            PAGINATION_RESULTS_PATH,
                            UNWRAP_SPEC_EXTENSION,
                            UNWRAP_RESULTS_PATH));
        }
        JsonPointer resultsPointer =
                getResultsJsonPointer(UNWRAP_RESULTS_PATH, unwrapSpecExtension.get(UNWRAP_RESULTS_PATH));
        if (resultsPointer.head().getMatchingProperty() != null) {
            //check that unwrapped element is taken from root
            throw new OpenApiException(
                    "The unwrap column '%s' can only be from the top-level structure".formatted(
                            resultsPointer.toString()));
        }
        return resultsPointer;
    }

    private JsonPointer getJsonPointerFromPageSpec(Map<String, String> pageSpecExtension)
    {
        return getResultsJsonPointer(PAGINATION_RESULTS_PATH, pageSpecExtension.get(PAGINATION_RESULTS_PATH));
    }

    private String getJsonPointerTailPropertyName(JsonPointer resultsPointer)
    {
        String path = resultsPointer.toString();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static JsonPointer getResultsJsonPointer(String resultsPathName, String resultsPath)
    {
        JsonPointer resultsPointer;
        try {
            resultsPointer = parseJsonPointer(resultsPath);
        }
        catch (IllegalArgumentException e) {
            throw new OpenApiException("Invalid value of %s: %s. %s".formatted(resultsPathName,
                    resultsPath, e.getMessage()));
        }
        return resultsPointer;
    }

    public JsonPointer getParentJsonPointer(JsonPointer pointer)
    {
        String path = pointer.toString();
        String parentPath = path.substring(0, path.lastIndexOf('/'));
        return JsonPointer.compile(parentPath);
    }

    private static JsonPointer parseJsonPointer(String expression)
    {
        if (expression == null) {
            return JsonPointer.empty();
        }
        Matcher matcher = JSON_POINTER_PATTERN.matcher(expression);
        if (matcher.matches()) {
            return JsonPointer.compile(matcher.group(1));
        }
        if (!expression.contains("/") && (expression.contains(".") || expression.contains("["))) {
            // it might be a JSON path, which are not supported, so ignore them
            return JsonPointer.empty();
        }
        if (expression.startsWith("$")) {
            throw new OpenApiException(
                    "Complex JSON pointer or JSON path expressions are not supported");
        }
        if (!expression.startsWith("/")) {
            expression = "/" + expression;
        }
        return JsonPointer.compile(expression);
    }

    private static Schema<?> getResponseSchema(Operation op)
    {
        if (op.getResponses() == null
                || op.getResponses().get(HTTP_OK) == null
                || op.getResponses().get(HTTP_OK).getContent() == null
                || op.getResponses().get(HTTP_OK).getContent().get(MIME_JSON) == null) {
            return null;
        }
        return op.getResponses()
                .get(HTTP_OK)
                .getContent()
                .get(MIME_JSON)
                .getSchema();
    }

    private static Schema<?> getRequestSchema(Operation op)
    {
        if (op.getRequestBody() == null
                || op.getRequestBody().getContent() == null
                || op.getRequestBody().getContent().get(MIME_JSON) == null
                || op.getRequestBody().getContent().get(MIME_JSON).getSchema() == null) {
            return null;
        }
        return op.getRequestBody()
                .getContent()
                .get(MIME_JSON)
                .getSchema();
    }

    private static Map<String, Schema> getSchemaProperties(Schema<?> schema)
    {
        Map<String, Schema> properties;
        if (schema instanceof ArraySchema || schema.getItems() != null) {
            properties = schema.getItems().getProperties();
        }
        else {
            properties = schema.getProperties();
        }
        if (properties == null) {
            return Map.of();
        }
        return properties;
    }

    private static Map<String, Schema> getResultsSchema(Schema<?> schema, JsonPointer resultsPointer)
    {
        while (resultsPointer != JsonPointer.empty()) {
            if (resultsPointer.getMatchingIndex() != -1) {
                // skip over arrays
                resultsPointer = resultsPointer.tail();
                continue;
            }
            String name = resultsPointer.getMatchingProperty();
            schema = getSchemaProperties(schema).get(name);
            if (schema == null) {
                throw new OpenApiException("Column %s not found".formatted(name));
            }
            // TODO validate that the schema is an array?
            resultsPointer = resultsPointer.tail();
        }
        return getSchemaProperties(schema);
    }

    private static boolean hasAmbiguousName(OpenApiColumn column, ListMultimap<String, OpenApiColumn.PrimaryKey> keys)
    {
        return keys.get(column.getName()).stream().anyMatch(existingKey -> !existingKey.equals(column.getPrimaryKey()));
    }

    private Optional<OpenApiColumn> getResultColumn(
            OpenAPI openApi,
            String sourceName,
            Schema<?> schema,
            boolean isNullable,
            boolean isPageNumber,
            boolean isPageSize)
    {
        String name = getIdentifier(sourceName);
        return convertType(openApi, schema).map(type -> OpenApiColumn.builder()
                .setName(name)
                .setSourceName(sourceName)
                .setType(type.type())
                .setSourceType(type.schema())
                .setIsNullable(Optional.ofNullable(schema.getNullable()).orElse(isNullable))
                .setIsHidden(false)
                .setIsPageNumber(isPageNumber)
                .setIsPageSize(isPageSize)
                .setIsUnwrapped(false)
                .setComment(schema.getDescription())
                .build());
    }

    private Optional<OpenApiColumn> getResultColumn(
            OpenAPI openApi,
            String sourceName,
            JsonPointer resultsPointer,
            Schema<?> schema,
            boolean isNullable,
            boolean isPageNumber,
            boolean isPageSize)
    {
        return getResultColumn(
                openApi,
                sourceName,
                resultsPointer,
                schema,
                isNullable,
                isPageNumber,
                isPageSize,
                false);
    }

    private Optional<OpenApiColumn> getResultColumn(
            OpenAPI openApi,
            String sourceName,
            JsonPointer resultsPointer,
            Schema<?> schema,
            boolean isNullable,
            boolean isPageNumber,
            boolean isPageSize,
            boolean isUnwrapped)
    {
        String name = getIdentifier(sourceName);
        return convertType(openApi, schema).map(type -> OpenApiColumn.builder()
                .setName(name)
                .setSourceName(sourceName)
                .setResultsPointer(resultsPointer)
                .setType(type.type())
                .setSourceType(type.schema())
                .setIsNullable(Optional.ofNullable(schema.getNullable()).orElse(isNullable))
                .setIsHidden(false)
                .setIsPageNumber(isPageNumber)
                .setIsPageSize(isPageSize)
                .setIsUnwrapped(isUnwrapped)
                .setComment(schema.getDescription())
                .build());
    }

    private Optional<OpenApiColumn> getPredicateColumn(
            OpenAPI openApi,
            String sourceName,
            Schema<?> schema,
            Map<HttpPath, ParameterLocation> requiredPredicate,
            Map<HttpPath, ParameterLocation> optionalPredicate,
            boolean isNullable,
            boolean isHidden,
            boolean isPageNumber,
            boolean isPageSize)
    {
        String name = getIdentifier(sourceName);
        return convertType(openApi, schema).map(type -> OpenApiColumn.builder()
                .setName(name)
                .setSourceName(sourceName)
                .setType(type.type())
                .setSourceType(type.schema())
                .setRequiresPredicate(requiredPredicate)
                .setOptionalPredicate(optionalPredicate)
                .setIsNullable(Optional.ofNullable(schema.getNullable()).orElse(isNullable))
                .setIsHidden(isHidden)
                .setIsPageNumber(isPageNumber)
                .setIsPageSize(isPageSize)
                .setComment(schema.getDescription())
                .build());
    }

    private Map<PathItem.HttpMethod, List<String>> methodsToPaths(PathItem pathItem, String path)
    {
        return pathItem.readOperationsMap().keySet().stream()
                .filter(method -> filterPath(path, method))
                .collect(toImmutableMap(identity(), method -> ImmutableList.of(path)));
    }

    private Map<HttpPath, JsonPointer> errorPointers(PathItem pathItem, String path)
    {
        return pointers(pathItem, path, ERROR_PATH);
    }

    private Map<HttpPath, JsonPointer> resultsPointers(PathItem pathItem, String path)
    {
        return pointers(pathItem, path, PAGINATION_RESULTS_PATH);
    }

    private Map<HttpPath, JsonPointer> pointers(PathItem pathItem, String path, String extensionName)
    {
        return pathItem.readOperationsMap().entrySet().stream()
                .filter(entry -> entry.getValue().getExtensions() != null &&
                        entry.getValue().getExtensions().containsKey(PAGE_SPEC_EXTENSION))
                .collect(toImmutableMap(
                        entry -> new HttpPath(entry.getKey(), path),
                        entry -> {
                            Map<String, String> specExtension =
                                    getMapOfStrings(entry.getValue().getExtensions().get(PAGE_SPEC_EXTENSION));
                            try {
                                return parseJsonPointer(specExtension.get(extensionName));
                            }
                            catch (IllegalArgumentException e) {
                                throw new OpenApiException("Invalid value of %s: %s. %s".formatted(
                                        extensionName,
                                        specExtension.get(extensionName),
                                        e.getMessage()));
                            }
                        }));
    }

    private boolean filterPath(String path, PathItem.HttpMethod method)
    {
        // ignore PUT operations on paths without parameters, because UPDATE always require a predicate and the required parameter will be the primary key
        // TODO what if there's no PUT, only POST, on a parametrized endpoint?
        return !method.equals(PathItem.HttpMethod.PUT) || path.contains("{");
    }

    public static String getIdentifier(String string)
    {
        return CaseFormat.LOWER_CAMEL.to(
                CaseFormat.LOWER_UNDERSCORE,
                string
                        .replaceAll("^/", "")
                        .replaceAll("[{}]", "")
                        .replace('/', '_')
                        .replace('-', '_'));
    }

    private Optional<TypeTuple> convertType(OpenAPI openApi, Schema<?> property)
    {
        if (property.getOneOf() != null
                || property.getAnyOf() != null
                || property.getAllOf() != null) {
            // TODO oneOf types can be incompatible (object and an array), so it would require generating separate fields for every type
            // TODO allOf and anyOf types could be merged into a single type
            return Optional.of(new TypeTuple(VARCHAR, property));
        }
        if (property instanceof ArraySchema array) {
            return convertType(openApi, array.getItems()).map(elementType -> new TypeTuple(
                    new ArrayType(elementType.type()),
                    array.items(elementType.schema())));
        }
        if (property instanceof MapSchema map && map.getAdditionalProperties() instanceof Schema<?> valueSchema) {
            Optional<TypeTuple> mapType = convertType(openApi, valueSchema);
            if (mapType.isEmpty()) {
                // fallback for invalid types - the value will be serialized json,
                // which can be later processed using SQL json functions
                return Optional.of(FALLBACK_TYPE);
            }
            return mapType.map(type -> new TypeTuple(
                    new MapType(VARCHAR, type.type(), new TypeOperators()),
                    map.additionalProperties(type.schema())));
        }
        Optional<String> format = Optional.ofNullable(property.getFormat());
        if (property instanceof IntegerSchema) {
            if (format.filter("int32"::equals).isPresent()) {
                return Optional.of(new TypeTuple(INTEGER, property));
            }
            return Optional.of(new TypeTuple(BIGINT, property));
        }
        if (property instanceof NumberSchema) {
            if (format.filter("float"::equals).isPresent()) {
                return Optional.of(new TypeTuple(REAL, property));
            }
            if (format.filter("double"::equals).isPresent()) {
                return Optional.of(new TypeTuple(DOUBLE, property));
            }
            // arbitrary scale and precision but should fit most numbers
            return Optional.of(new TypeTuple(createDecimalType(18, 8), property));
        }
        if (property instanceof StringSchema) {
            return Optional.of(new TypeTuple(VARCHAR, property));
        }
        if (property instanceof DateSchema) {
            return Optional.of(new TypeTuple(DATE, property));
        }
        if (property instanceof DateTimeSchema) {
            // according to ISO-8601 can be any precision actually so might not fit
            return Optional.of(new TypeTuple(TIMESTAMP_MILLIS, property));
        }
        if (property instanceof BooleanSchema) {
            return Optional.of(new TypeTuple(BOOLEAN, property));
        }
        if (property instanceof ObjectSchema object) {
            // composite type
            Map<String, Schema> properties = object.getProperties();
            if (properties == null) {
                return Optional.of(FALLBACK_TYPE);
            }
            Map<String, TypeTuple> fieldTypes = properties.entrySet().stream()
                    .map(prop -> Map.entry(prop.getKey(), convertType(openApi, prop.getValue())))
                    .filter(entry -> entry.getValue().isPresent())
                    .collect(toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().get(),
                            (a, b) -> a,
                            LinkedHashMap::new));
            List<RowType.Field> fields = fieldTypes.entrySet().stream()
                    .map(prop -> RowType.field(prop.getKey(), prop.getValue().type()))
                    .toList();
            if (fields.isEmpty()) {
                return Optional.of(FALLBACK_TYPE);
            }
            Map<String, Schema> newProperties = fieldTypes.entrySet().stream()
                    .collect(toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().schema(),
                            (a, b) -> a,
                            LinkedHashMap::new));
            return Optional.of(new TypeTuple(RowType.from(fields), object.properties(newProperties)));
        }
        String type = property.getType();
        if (type == null && property.getTypes() != null && property.getTypes().size() == 1) {
            type = property.getTypes().iterator().next();
        }
        if (type == null) {
            return Optional.of(FALLBACK_TYPE);
        }
        if (type.equals("string")) {
            if (format.filter("date"::equals).isPresent()) {
                return Optional.of(new TypeTuple(DATE, property));
            }
            if (format.filter("date-time"::equals).isPresent()) {
                return Optional.of(new TypeTuple(TIMESTAMP_MILLIS, property));
            }
            return Optional.of(new TypeTuple(VARCHAR, property));
        }
        if (type.equals("object") && property.getAdditionalProperties() instanceof Schema<?> valueSchema) {
            Optional<TypeTuple> mapType = convertType(openApi, valueSchema);
            if (mapType.isEmpty()) {
                // fallback for invalid types - the value will be serialized json,
                // which can be later processed using SQL json functions
                return Optional.of(FALLBACK_TYPE);
            }
            return mapType.map(convertedType -> new TypeTuple(
                    new MapType(VARCHAR, convertedType.type(), new TypeOperators()),
                    new MapSchema().type("string").additionalProperties(convertedType.schema())));
        }
        if (type.equals("array")) {
            return convertType(openApi, property.getItems()).map(elementType -> new TypeTuple(
                    new ArrayType(elementType.type()),
                    new ArraySchema().items(elementType.schema())));
        }
        if (type.equals("number")) {
            // arbitrary scale and precision but should fit most numbers
            return Optional.of(new TypeTuple(createDecimalType(18, 8), property));
        }
        if (type.equals("float")) {
            return Optional.of(new TypeTuple(REAL, property));
        }
        if (type.equals("int") || type.equals("integer")) {
            return Optional.of(new TypeTuple(INTEGER, property));
        }
        Schema<?> referenced = openApi.getComponents().getSchemas().get(type);
        if (referenced != null) {
            return convertType(openApi, referenced).map(convertedType -> new TypeTuple(convertedType.type(), referenced));
        }
        // unknown and unsupported types will be returned as strings, which at least can be parsed with json functions
        return Optional.of(FALLBACK_TYPE);
    }

    private record TypeTuple(Type type, Schema<?> schema) {}

    private List<OpenApiColumn> mergeColumns(List<OpenApiColumn> columns)
    {
        return columns.stream()
                // merge all columns with same name and data type
                .collect(groupingBy(OpenApiColumn::getPrimaryKey, LinkedHashMap::new, toList()))
                .values().stream()
                .map(sameColumns -> OpenApiColumn.builderFrom(sameColumns.get(0))
                        .setIsNullable(sameColumns.stream().anyMatch(column -> column.getMetadata().isNullable()))
                        .setRequiresPredicate(sameColumns.stream()
                                .map(OpenApiColumn::getRequiresPredicate)
                                .flatMap(map -> map.entrySet().stream())
                                .collect(
                                        toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)))
                        .setOptionalPredicate(sameColumns.stream()
                                .map(OpenApiColumn::getOptionalPredicate)
                                .flatMap(map -> map.entrySet().stream())
                                .collect(
                                        toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)))
                        .build())
                .collect(groupingBy(OpenApiColumn::getName, LinkedHashMap::new, toList()))
                .values().stream()
                // make sure column names are also unique, append incrementing suffixes for columns of different types
                .flatMap(sameColumns -> IntStream
                        .range(0, sameColumns.size())
                        .mapToObj(i -> OpenApiColumn.builderFrom(sameColumns.get(i))
                                .setName(sameColumns.get(i).getName() + (i > 0 ? "_" + (i + 1) : ""))
                                .build()))
                .toList();
    }

    private static OpenApiTableHandle tableHandle(String tableName,
            Map<PathItem.HttpMethod, List<String>> tablePaths,
            Map<PathItem.HttpMethod, Map<String, Object>> methodExtensions)
    {
        return new OpenApiTableHandle(
                SchemaTableName.schemaTableName(SCHEMA_NAME, tableName),
                // some APIs use POST to query resources
                tablePaths.containsKey(PathItem.HttpMethod.GET) ? tablePaths.get(
                        PathItem.HttpMethod.GET) : firstNonNull(tablePaths.get(PathItem.HttpMethod.POST),
                        ImmutableList.of()),
                tablePaths.containsKey(PathItem.HttpMethod.GET) ? PathItem.HttpMethod.GET : PathItem.HttpMethod.POST,
                firstNonNull(tablePaths.get(PathItem.HttpMethod.POST), ImmutableList.of()),
                PathItem.HttpMethod.POST,
                // some APIs use POST to update resources, or both PUT and POST, with an identifier as a required query parameter or in the body
                tablePaths.containsKey(PathItem.HttpMethod.PUT) ? tablePaths.get(
                        PathItem.HttpMethod.PUT) : firstNonNull(tablePaths.get(PathItem.HttpMethod.POST),
                        ImmutableList.of()),
                tablePaths.containsKey(PathItem.HttpMethod.PUT) ? PathItem.HttpMethod.PUT : PathItem.HttpMethod.POST,
                firstNonNull(tablePaths.get(PathItem.HttpMethod.DELETE), ImmutableList.of()),
                PathItem.HttpMethod.DELETE,
                methodExtensions,
                TupleDomain.none(),
                OptionalLong.empty());
    }

    private record OpenApiSpecInfo(
            Map<String, List<OpenApiColumn>> tables,
            Map<String, OpenApiTableHandle> handles,
            Map<String, Map<HttpPath, JsonPointer>> errorPointers,
            Map<String, Map<HttpPath, JsonPointer>> resultsPointers
    ) {}
}
