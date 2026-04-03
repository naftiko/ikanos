# Delimited Output Formats: TSV and PSV Support
## Extending `outputRawFormat` with Tab- and Pipe-Separated Value Parsing

**Status**: Proposal  
**Date**: April 2, 2026  
**Key Concept**: Replace the single `csv` format with a family of three delimited formats — `csv` (comma), `tsv` (tab), `psv` (pipe) — sharing a single parametrized converter method.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current Pipeline](#current-pipeline)
3. [Motivation: Where Delimited Data Lives](#motivation-where-delimited-data-lives)
4. [Design: Unified Delimited Converter](#design-unified-delimited-converter)
5. [Schema Changes](#schema-changes)
6. [Capability YAML Examples](#capability-yaml-examples)
7. [Implementation Changes](#implementation-changes)
8. [Converter Method Sketch](#converter-method-sketch)
9. [Test Plan](#test-plan)
10. [Backward Compatibility](#backward-compatibility)
11. [Tradeoffs vs. Alternatives](#tradeoffs-vs-alternatives)

---

## Executive Summary

### Problem

Naftiko supports `outputRawFormat: "csv"` for comma-separated responses, but many real-world APIs and data sources return tab-separated (TSV) or pipe-separated (PSV) data. Teams currently have no native way to consume these formats without deploying a pre-processing proxy to convert them to CSV or JSON before mediation.

### Proposal

Add `"tsv"` and `"psv"` as first-class `outputRawFormat` enum values alongside the existing `"csv"`. Internally, refactor the CSV-specific `convertCsvToJson(Reader)` into a shared `convertDelimitedToJson(Reader, char)` method that accepts a column separator. The three format keywords route to the same converter with different delimiters:

| Format | Delimiter | Character |
|--------|-----------|-----------|
| `csv`  | Comma     | `,`       |
| `tsv`  | Tab       | `\t`      |
| `psv`  | Pipe      | `\|`      |

### Key Benefit

- Zero new spec concepts — uses the same `outputRawFormat` pattern as every other format
- Full backward compatibility — `csv` continues to mean comma-separated
- Consistent JSONPath extraction — all three produce the same `ArrayNode` of row objects

---

## Current Pipeline

The delimited conversion sits in Stage 1 of the standard mediation chain:

```text
HTTP response (raw bytes)
  -> Converter.convertToJson(format, schema, entity)        // Stage 1: normalize to JsonNode
    -> Resolver.resolveOutputMappings(spec, root, mapper)   // Stage 2: JSONPath extraction
      -> Converter.jsonPathExtract(root, "$.some.path")     // Stage 3: shape output
        -> REST / MCP JSON response
```

Today, only `csv` is supported:

| Format | Delimiter | Parser |
|--------|-----------|--------|
| `csv`  | `,`       | Jackson CsvMapper with `CsvSchema.emptySchema().withHeader()` |

The current `convertCsvToJson` has the delimiter hardcoded via Jackson's default `CsvSchema`, which uses comma. There is no way to configure the delimiter from the capability YAML.

---

## Motivation: Where Delimited Data Lives

| Source | Format | Separator |
|--------|--------|-----------|
| IANA-registered `text/tab-separated-values` | TSV | Tab |
| Database export tools (pg_dump, MySQL OUTFILE) | TSV | Tab |
| Bioinformatics APIs (NCBI, UniProt) | TSV | Tab |
| Log aggregation and SIEM exports | PSV | Pipe |
| Legacy mainframe file transfers | PSV | Pipe |
| Financial data feeds (SWIFT, payment rails) | PSV | Pipe |
| Open data portals and government datasets | CSV / TSV | Comma / Tab |
| Spreadsheet exports (Excel, Google Sheets) | CSV / TSV | Comma / Tab |

TSV is especially common because tab characters rarely appear in data fields, making escaping simpler. PSV is prevalent in financial and telco systems where both commas and tabs can appear in field values.

---

## Design: Unified Delimited Converter

All three formats follow identical parsing rules:

1. First row contains column headers.
2. Each subsequent row becomes a JSON object keyed by those headers.
3. Values are preserved as strings (consistent with current CSV behavior).
4. The result is an `ArrayNode` of row objects.

```json
[
  { "id": "1", "name": "Alice Smith", "email": "alice@example.com" },
  { "id": "2", "name": "Bob Johnson", "email": "bob@example.com" }
]
```

The only variable is the column separator character passed to `CsvSchema.withColumnSeparator(char)`.

---

## Schema Changes

In `naftiko-schema.json`, the `outputRawFormat` enum gains two values:

```json
"outputRawFormat": {
  "type": "string",
  "enum": [
    "json",
    "xml",
    "avro",
    "protobuf",
    "csv",
    "tsv",
    "psv",
    "yaml",
    "html",
    "markdown"
  ],
  "default": "json",
  "description": "The raw format of the response from the consumed API. Delimited formats: csv (comma), tsv (tab), psv (pipe). Default value is json."
}
```

No new properties are introduced. No changes to `outputSchema`, `outputParameters`, or any other spec object.

---

## Capability YAML Examples

### Tab-Separated Values

```yaml
consumes:
  - type: "http"
    namespace: "gene-db"
    baseUri: "https://api.example.com"
    resources:
      - path: "export/genes"
        operations:
          - method: "GET"
            name: "get-genes"
            outputRawFormat: "tsv"
            outputParameters:
              - name: "gene-id"
                mapping: "$.gene_id"
              - name: "symbol"
                mapping: "$.symbol"
```

### Pipe-Separated Values

```yaml
consumes:
  - type: "http"
    namespace: "payment-feed"
    baseUri: "https://feeds.example.com"
    resources:
      - path: "transactions/daily"
        operations:
          - method: "GET"
            name: "get-transactions"
            outputRawFormat: "psv"
            outputParameters:
              - name: "transaction-id"
                mapping: "$.txn_id"
              - name: "amount"
                mapping: "$.amount"
```

### Mixed Delimited Formats in One Capability

```yaml
consumes:
  - type: "http"
    namespace: "data-export"
    baseUri: "https://api.example.com"
    resources:
      - path: "users"
        operations:
          - method: "GET"
            name: "get-users-csv"
            outputRawFormat: "csv"
      - path: "logs"
        operations:
          - method: "GET"
            name: "get-logs-tsv"
            outputRawFormat: "tsv"
      - path: "transactions"
        operations:
          - method: "GET"
            name: "get-transactions-psv"
            outputRawFormat: "psv"
```

---

## Implementation Changes

### Files Modified

| File | Change |
|------|--------|
| `src/main/resources/schemas/naftiko-schema.json` | Add `"tsv"` and `"psv"` to `outputRawFormat` enum; update description |
| `src/main/java/io/naftiko/engine/Converter.java` | Refactor `convertCsvToJson` → `convertDelimitedToJson(Reader, char)`; add routing for TSV/PSV in `convertToJson` |
| `src/test/java/io/naftiko/engine/ConverterTest.java` | Add unit tests for TSV and PSV conversion |
| `src/test/resources/schemas/sample-users.tsv` | New: tab-separated test data |
| `src/test/resources/schemas/sample-users.psv` | New: pipe-separated test data |

### Files NOT Modified

- `OperationSpec.java` — `outputRawFormat` is already a free-form `String`; no enum change needed
- `ResourceRestlet.java` — calls `Converter.convertToJson()` generically; no change needed
- Spectral rules — no format-specific linting rules exist for delimiter types

---

## Converter Method Sketch

### Refactored shared method

```java
public static JsonNode convertDelimitedToJson(Reader reader, char separator) throws IOException {
    CsvMapper csvMapper = new CsvMapper();
    CsvSchema schema = CsvSchema.emptySchema()
            .withHeader()
            .withColumnSeparator(separator);

    MappingIterator<JsonNode> it =
            csvMapper.readerFor(JsonNode.class).with(schema).readValues(reader);

    ObjectMapper mapper = new ObjectMapper();
    ArrayNode arr = mapper.createArrayNode();
    while (it.hasNext()) {
        arr.add(it.next());
    }
    return arr;
}
```

### Updated routing in `convertToJson`

```java
} else if ("CSV".equalsIgnoreCase(format)) {
    root = Converter.convertDelimitedToJson(entity.getReader(), ',');
} else if ("TSV".equalsIgnoreCase(format)) {
    root = Converter.convertDelimitedToJson(entity.getReader(), '\t');
} else if ("PSV".equalsIgnoreCase(format)) {
    root = Converter.convertDelimitedToJson(entity.getReader(), '|');
}
```

### Backward-compatible delegation

```java
public static JsonNode convertCsvToJson(Reader csvReader) throws IOException {
    return convertDelimitedToJson(csvReader, ',');
}
```

This keeps the existing `convertCsvToJson` method available for any direct callers (including integration tests).

---

## Test Plan

### Unit Tests (ConverterTest)

| Test | Description |
|------|-------------|
| `convertDelimitedToJsonShouldParseComma` | Verifies CSV parsing via the new method |
| `convertDelimitedToJsonShouldParseTab` | Parses TSV data with tab separators |
| `convertDelimitedToJsonShouldParsePipe` | Parses PSV data with pipe separators |
| `convertToJsonShouldRouteTsvFormat` | Routes `"tsv"` format through `convertToJson` |
| `convertToJsonShouldRoutePsvFormat` | Routes `"psv"` format through `convertToJson` |

### Integration Tests (CsvIntegrationTest)

Extend the existing `CsvIntegrationTest` or create `DelimitedIntegrationTest` to validate:
- Loading a capability YAML with `outputRawFormat: "tsv"` and `"psv"`
- Parsing sample TSV/PSV files through the full conversion chain
- JSONPath extraction producing identical structure to CSV

### Test Data Files

**sample-users.tsv**
```
id	name	email
1	Alice Smith	alice@example.com
2	Bob Johnson	bob@example.com
3	Carol White	carol@example.com
```

**sample-users.psv**
```
id|name|email
1|Alice Smith|alice@example.com
2|Bob Johnson|bob@example.com
3|Carol White|carol@example.com
```

---

## Backward Compatibility

| Concern | Status |
|---------|--------|
| Existing `outputRawFormat: "csv"` capabilities | No change — comma delimiter preserved |
| `convertCsvToJson(Reader)` public method | Kept as delegation to `convertDelimitedToJson(reader, ',')` |
| CsvIntegrationTest | Passes without modification |
| Schema validation | `"csv"` remains valid; `"tsv"` and `"psv"` are additive |

---

## Tradeoffs vs. Alternatives

| Alternative | Pros | Cons |
|-------------|------|------|
| **Separate `outputDelimiter` property** | Maximum flexibility (any char) | Adds spec complexity; couples two fields; only benefits delimited formats; requires schema + validation changes |
| **`csv-tab` / `csv-pipe` naming** | Keeps CSV prefix family | Non-standard names; harder to discover; breaks the pattern of short lowercase format names |
| **`tsv` / `psv` as enum values** (this proposal) | Standard names (TSV is IANA-registered); minimal spec change; self-documenting; follows existing pattern | Fixed to three delimiters (no custom char) |
| **External pre-processing proxy** | No framework changes | Deployment overhead; conversion drift; defeats the purpose of spec-driven integration |

The fixed delimiter set covers the vast majority of real-world delimited formats. Exotic separators (semicolon, tilde) can be added later as additional enum values if demand arises.
