# OpenAPI Interoperability — Implementation Plan

**Status**: Draft  
**Date**: April 15, 2026  
**Parent Blueprint**: [openapi-interoperability.md](openapi-interoperability.md)  
**Scope**: Changes to the Naftiko Framework repo only (no Backstage plugin, no external repos)

---

## Phase 1 — Maven Dependencies + Package Skeleton + Spec Refactor

**What:** Add `swagger-parser` and `swagger-models` to `pom.xml`, create the `io.naftiko.spec.openapi` package, and refactor existing spec classes into subpackages that mirror the `engine` layout.

### 1a — Spec package refactoring

Move aggregate-related and utility spec classes into dedicated subpackages for consistency with the existing `io.naftiko.engine.aggregates` and `io.naftiko.engine.util` packages.

**`io.naftiko.spec.aggregates`** — aggregate domain model specs:

| Class | Current package | New package |
|-------|----------------|-------------|
| `AggregateSpec` | `io.naftiko.spec` | `io.naftiko.spec.aggregates` |
| `AggregateFunctionSpec` | `io.naftiko.spec` | `io.naftiko.spec.aggregates` |
| `SemanticsSpec` | `io.naftiko.spec` | `io.naftiko.spec.aggregates` |

**`io.naftiko.spec.util`** — shared utility specs:

| Class | Current package | New package |
|-------|----------------|-------------|
| `BindingSpec` | `io.naftiko.spec` | `io.naftiko.spec.util` |
| `BindingKeysSpec` | `io.naftiko.spec` | `io.naftiko.spec.util` |
| `ExecutionContext` | `io.naftiko.spec` | `io.naftiko.spec.util` |
| `StructureSpec` | `io.naftiko.spec` | `io.naftiko.spec.util` |

Update all `import` statements across production and test code. The remaining classes (`NaftikoSpec`, `CapabilitySpec`, `InfoSpec`, `InputParameterSpec`, `OutputParameterSpec`, `OperationSpec`, `ResourceSpec`, `StakeholderSpec`, and their serializers) stay in `io.naftiko.spec`.

### 1b — Maven dependencies + native-image config

| Change | File |
|--------|------|
| Add `io.swagger.parser.v3:swagger-parser:2.1.25` | `pom.xml` |
| Add `io.swagger.core.v3:swagger-models:2.2.28` (explicit) | `pom.xml` |
| Add GraalVM reflection entries for Swagger Parser's `ServiceLoader` implementations | `src/main/resources/META-INF/native-image/reflect-config.json` |
| Add Swagger resource patterns to native-image resource config | `src/main/resources/META-INF/native-image/resource-config.json` |

**Validation:** `mvn clean test` — all existing tests pass after the refactor; compile succeeds with new dependencies.

---

## Phase 2 — OAS Import Converter (`OasImportConverter`)

**What:** Core conversion logic — OpenAPI `OpenAPI` POJO → `HttpClientSpec` object tree. No CLI wiring yet; unit-testable in isolation.

| New class | Package | Responsibility |
|-----------|---------|----------------|
| `OasImportConverter` | `io.naftiko.spec.openapi` | Stateless converter: `convert(OpenAPI) → OasImportResult` |
| `OasImportResult` | `io.naftiko.spec.openapi` | Value object: `HttpClientSpec` + `List<String> warnings` |

### Conversion Logic (per §3 and §4.2 of the blueprint)

1. **Namespace** — `info.title` → kebab-case slug
2. **BaseUri** — `servers[0].url` (strip trailing slash); placeholder + warning if empty
3. **Resources** — group operations by first tag (fallback: first path segment)
4. **Operations** — `operationId` (kebab-cased) or synthesized `{method}-{slug}`
5. **Input parameters** — path/query/header/cookie → corresponding `in:` values; request body properties → `in: body`
6. **Output parameters** — success response schema → `OutputParameterSpec` tree with `mapping` (JsonPath); handle scalar, object, array, `$ref` (pre-resolved by Swagger Parser), `allOf` (merge), `oneOf` (first variant + warning)
7. **Authentication** — `apiKey`/`basic`/`bearer`/`digest` direct map; `oauth2`/`openIdConnect` → warning
8. **Edge cases** — no servers, no operationId, circular refs (depth cap), `additionalProperties`, `writeOnly` exclusion

### Tests

`OasImportConverterTest` — all 18 unit tests from §11.1 of the blueprint. Build small in-memory `OpenAPI` POJOs and assert the resulting `HttpClientSpec` fields. No YAML fixtures needed at this stage.

**Validation:** All new unit tests pass via `mvn test`.

---

## Phase 3 — OAS Export Builder (`OasExportBuilder`)

**What:** Core conversion logic — `CapabilitySpec` + REST adapter → `OpenAPI` POJO. No CLI wiring yet.

| New class | Package | Responsibility |
|-----------|---------|----------------|
| `OasExportBuilder` | `io.naftiko.spec.openapi` | `build(NaftikoSpec, String adapterNamespace) → OasExportResult` |
| `OasExportResult` | `io.naftiko.spec.openapi` | Value object: `OpenAPI` + `List<String> warnings` |

### Conversion Logic (per §3 and §5.2)

1. **Info** — `info.label` → `title`, `info.description` → `description`
2. **Server** — `address`/`port` → `http://localhost:{port}` (normalize `0.0.0.0`)
3. **Paths** — one `PathItem` per `RestServerResourceSpec`
4. **Operations** — `name` → `operationId`, `description`, `tags: [resource.name]`
5. **Parameters** — `in: query/path/header/cookie` → OAS `Parameter`; `in: body` → `requestBody`
6. **Responses** — `outputParameters` tree → 200 response JSON schema; no outputs → 204
7. **Security** — `AuthenticationSpec` subtypes → OAS `securitySchemes` + top-level `security`
8. **Aggregate `ref` resolution** — resolve before export using existing `AggregateRefResolver` patterns; export the concrete surface

### Dependencies

Uses `RestServerSpec`, `RestServerResourceSpec`, `RestServerOperationSpec`, `InputParameterSpec`, `OutputParameterSpec`, `AuthenticationSpec` from `io.naftiko.spec`. Also needs `AggregateRefResolver` to flatten refs before building the OAS model.

### Tests

`OasExportBuilderTest` — all 15 unit tests from §11.2. Build `NaftikoSpec` / `RestServerSpec` objects programmatically and assert the resulting `OpenAPI` POJO.

**Validation:** All new unit tests pass.

---

## Phase 4 — YAML / JSON Serialization (`OasYamlWriter`)

**What:** Serialize the `OpenAPI` POJO to YAML or JSON on disk. Also serialize `HttpClientSpec` to Naftiko-format YAML (for import output).

| New class | Package | Responsibility |
|-----------|---------|----------------|
| `OasYamlWriter` | `io.naftiko.spec.openapi` | `writeYaml(OpenAPI, Path)`, `writeJson(OpenAPI, Path)` |

The import side uses the existing Jackson `ObjectMapper(new YAMLFactory())` with `@JsonInclude(NON_EMPTY)` to serialize `HttpClientSpec` → YAML. Swagger Core's built-in `Yaml.pretty()` / `Json.pretty()` serializers handle the export side.

### Tests

Verify round-trip: build an `OpenAPI` POJO → write YAML → re-parse via Swagger Parser → assert equality.

---

## Phase 5 — CLI Commands (`import openapi`, `export openapi`)

**What:** Wire the converters into picocli commands, extending the CLI hierarchy from `create`/`validate` to include `import` and `export` groups.

| New class | Package | Responsibility |
|-----------|---------|----------------|
| `ImportCommand` | `io.naftiko.cli` | `@Command(name="import")` group — parent for format-specific subcommands |
| `ImportOpenApiCommand` | `io.naftiko.cli` | `@Command(name="openapi")` under `import` — options: `<source>`, `--output`, `--namespace`, `--format` |
| `ExportCommand` | `io.naftiko.cli` | `@Command(name="export")` group |
| `ExportOpenApiCommand` | `io.naftiko.cli` | `@Command(name="openapi")` under `export` — options: `<capability>`, `--output`, `--format`, `--adapter`, `--server-url` |

| Modified file | Change |
|---------------|--------|
| `src/main/java/io/naftiko/Cli.java` | Add `ImportCommand.class` and `ExportCommand.class` to `subcommands` |

### Import Flow

1. Parse `<source>` (file path or URL) via `OpenAPIV3Parser().read(source)`
2. Validate parse result; print errors and exit 1 on failure
3. Call `OasImportConverter.convert(openApi)` → `OasImportResult`
4. Print warnings to stderr
5. Serialize `HttpClientSpec` to YAML at `--output` (default: `./<namespace>-consumes.yml`)

### Export Flow

1. Deserialize `<capability>` YAML via existing `Capability` loader → `NaftikoSpec`
2. Call `OasExportBuilder.build(naftikoSpec, adapterNamespace)` → `OasExportResult`
3. Print warnings to stderr
4. Serialize `OpenAPI` to YAML/JSON at `--output` (default: `./openapi.yaml`)

### Tests

- `ImportOpenApiCommandTest` / `ExportOpenApiCommandTest` — invoke via picocli's `CommandLine.execute()` with temp files; assert exit codes and output file contents.
- Extend `ValidateCommandTest` pattern for consistency.

**Validation:** `mvn test`; also manual smoke test: `java -jar target/framework.jar import openapi <fixture>`.

---

## Phase 6 — Integration Tests + Test Fixtures

**What:** End-to-end tests that load real OAS fixtures, import/export, and validate the results.

### New Test Fixtures

| File | Location | Purpose |
|------|----------|---------|
| `petstore-3.0.yaml` | `src/test/resources/openapi/` | Standard Petstore OAS 3.0 for import tests |
| `petstore-3.1.yaml` | `src/test/resources/openapi/` | Petstore OAS 3.1 (JSON Schema vocabulary) for import tests |
| `complex-api.yaml` | `src/test/resources/openapi/` | OAS with `allOf`, `oneOf`, nested objects, arrays, multiple auth schemes |
| `no-servers.yaml` | `src/test/resources/openapi/` | OAS with empty `servers` array (edge case) |
| `no-operation-ids.yaml` | `src/test/resources/openapi/` | OAS without `operationId` fields (edge case) |
| `expected-petstore-consumes.yml` | `src/test/resources/openapi/` | Expected Naftiko consumes output for Petstore import |
| `expected-notion-openapi.yaml` | `src/test/resources/openapi/` | Expected OAS output for Notion capability export |

### New Test Classes

| Class | Package | Scope |
|-------|---------|-------|
| `OasImportIntegrationTest` | `io.naftiko.spec.openapi` | Petstore OAS → import → validate output via JSON Schema |
| `OasExportIntegrationTest` | `io.naftiko.spec.openapi` | Notion capability → export → validate via Swagger Parser |
| `OasRoundTripIntegrationTest` | `io.naftiko.spec.openapi` | Export → Import → compare: operations, parameters, auth match |

### Test Scenarios (per §11.3)

- Petstore OAS → import → validate output via JSON Schema (`naftiko-schema.json`)
- Complex OAS (nested objects, arrays, `allOf`) → valid consumes
- Notion capability → export → validate via Swagger Parser
- Aggregate-using capability → fully resolved OAS
- Export → Import → compare: operations, parameters, auth match

**Validation:** `mvn clean test` — all green.

---

## Phase 7 — GraalVM Native-Image Validation

**What:** Verify import/export works in the native binary.

| Change | File |
|--------|------|
| Add Swagger Parser `ServiceLoader` entries | `src/main/resources/META-INF/native-image/reflect-config.json` |
| Add Swagger resource patterns if needed | `src/main/resources/META-INF/native-image/resource-config.json` |

**Validation:** `mvn -B clean package -Pnative` builds successfully; run the native binary with `naftiko import openapi petstore.yaml` and `naftiko export openapi notion.yml` — both produce correct output.

---

## Dependency Graph

```
Phase 1 (deps)
   │
   ├──► Phase 2 (import converter) ──► Phase 4 (serialization) ──► Phase 5 (CLI) ──► Phase 6 (integration)
   │                                         ▲                                              │
   └──► Phase 3 (export builder) ────────────┘                                              ▼
                                                                                      Phase 7 (native)
```

Phases 2 and 3 are independent and can be worked in parallel.

---

## Out of Scope

- **Backstage plugin** (`@naftiko/backstage-plugin-openapi`) — separate repo, TypeScript
- **Runtime OAS endpoint** — explicitly a non-goal
- **OAS 2.0 / GraphQL / gRPC / AsyncAPI** — not in this iteration
- **Selective path import** — future iteration
- **Automatic `exposes` generation from imported `consumes`** — non-goal

---

## New File Summary

| Type | Count |
|------|-------|
| Production Java classes | 9 (`OasImportConverter`, `OasImportResult`, `OasExportBuilder`, `OasExportResult`, `OasYamlWriter`, `ImportCommand`, `ImportOpenApiCommand`, `ExportCommand`, `ExportOpenApiCommand`) |
| Test Java classes | 7 (`OasImportConverterTest`, `OasExportBuilderTest`, `OasImportIntegrationTest`, `OasExportIntegrationTest`, `OasRoundTripIntegrationTest`, `ImportOpenApiCommandTest`, `ExportOpenApiCommandTest`) |
| Test fixtures (YAML) | 7 |
| Modified files | 3 (`pom.xml`, `Cli.java`, native-image configs) |
