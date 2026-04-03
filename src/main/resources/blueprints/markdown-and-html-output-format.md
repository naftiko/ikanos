# Unified HTML + Markdown Output Format Support Proposal
## Native Table-Aware Conversion to JSON for the HTTP Consumes Adapter

**Status**: Proposal  
**Date**: March 26, 2026  
**Key Concept**: Add both `html` and `markdown` as native `outputRawFormat` values in the HTTP consumes adapter, with one shared table conversion contract that always emits `$.tables[N]` as arrays of row objects.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current Pipeline](#current-pipeline)
3. [Motivation: Where HTML and Markdown Data Lives](#motivation-where-html-and-markdown-data-lives)
4. [Unified Design: Common Table Conversion Contract](#unified-design-common-table-conversion-contract)
5. [Format-Specific Structure](#format-specific-structure)
6. [Capability YAML Examples](#capability-yaml-examples)
7. [Implementation Changes](#implementation-changes)
8. [Converter Method Sketch](#converter-method-sketch)
9. [Tradeoffs vs. External Proxy](#tradeoffs-vs-external-proxy)
10. [Limitations and Future Extensions](#limitations-and-future-extensions)
11. [Open Questions for Discussion](#open-questions-for-discussion)

---

## Executive Summary

### Problem

Naftiko currently lacks native support for two web content formats that frequently contain structured data:
- HTML pages with operational tables
- Markdown documents with front matter, tables, and sections

Without native support, teams often deploy custom proxy services to transform these formats into JSON before mediation.

### Proposal

Add both `"html"` and `"markdown"` to `outputRawFormat` and define a single canonical table contract:
- every extracted table is emitted under `tables`
- `tables[N]` is always an array of objects
- keys come from header cells
- values are preserved as strings for deterministic behavior across formats

Format-specific enrichments are still supported:
- HTML: optional CSS scoping via `outputSchema`
- Markdown: `frontMatter` and `sections`

### Key Benefit

Capabilities can switch between HTML and Markdown sources while keeping table JSONPath mappings unchanged.

---

## Current Pipeline

Every format in Naftiko follows the same mediation chain:

```text
HTTP response (raw bytes)
  -> Converter.convertToJson(format, schema, entity)        // Stage 1: normalize to JsonNode
    -> Resolver.resolveOutputMappings(spec, root, mapper)   // Stage 2: JSONPath extraction
      -> Converter.jsonPathExtract(root, "$.some.path")     // Stage 3: shape output
        -> REST / MCP JSON response
```

Currently supported formats:

| Format | `outputRawFormat` | Schema Required | Parser |
|--------|-------------------|-----------------|--------|
| JSON | `json` (default) | No | Jackson ObjectMapper |
| XML | `xml` | No | Jackson XmlMapper |
| CSV | `csv` | No | Jackson CsvMapper |
| YAML | `yaml` | No | Jackson YAMLFactory |
| Protobuf | `protobuf` | Yes (`outputSchema`) | Jackson ProtobufMapper |
| Avro | `avro` | Yes (`outputSchema`) | Apache Avro + AvroMapper |

Stages 2 and 3 are format-agnostic. The combined proposal only extends Stage 1.

---

## Motivation: Where HTML and Markdown Data Lives

| Source | What It Returns | Structured Data |
|--------|-----------------|-----------------|
| Public dashboards and directories | HTML pages | Tables and list-like records |
| Internal portals and legacy apps | HTML fragments | Operational tables |
| GitHub README/content APIs | Markdown | Front matter, tables, headings |
| CMS and static-site repositories | Markdown files | Metadata and sectioned content |
| LLM tool outputs | Markdown-formatted text | Tables and structured sections |

Treating these two formats together avoids conversion drift.

---

## Unified Design: Common Table Conversion Contract

Both converters must enforce the same table JSON shape:

```json
{
  "tables": [
    [
      { "Name": "Widget", "Price": "$42", "Stock": "150" },
      { "Name": "Gadget", "Price": "$99", "Stock": "30" }
    ]
  ]
}
```

Rules for both HTML and Markdown:
1. Detect source tables in document order.
2. Resolve header cells to field names.
3. Convert each row to an object keyed by headers.
4. Emit one table per `tables[N]` entry.
5. Keep cell values as strings (no type inference in v1).

This guarantees JSONPath parity such as `$.tables[0][*].Price` across both formats.

### Example input (HTML)

```html
<table>
  <thead>
    <tr><th>Name</th><th>Price</th><th>Stock</th></tr>
  </thead>
  <tbody>
    <tr><td>Widget</td><td>$42</td><td>150</td></tr>
    <tr><td>Gadget</td><td>$99</td><td>30</td></tr>
  </tbody>
</table>
```

### Example input (Markdown)

```markdown
| Name   | Price | Stock |
|--------|-------|-------|
| Widget | $42   | 150   |
| Gadget | $99   | 30    |
```

---

## Format-Specific Structure

### HTML scoping via `outputSchema`

`outputSchema` can carry a CSS selector to scope extraction:

```yaml
outputRawFormat: "html"
outputSchema: "table.results"
```

Without `outputSchema`, all `table` elements are considered.

### Markdown enrichments

Markdown emits additional optional structures:

```json
{
  "frontMatter": {
    "title": "Release Notes",
    "version": "2.1.0"
  },
  "tables": [ ... ],
  "sections": [
    {
      "heading": "Overview",
      "level": 2,
      "content": "This release introduces..."
    }
  ]
}
```

Defaults when absent:
- `frontMatter: {}`
- `sections: []`

---

## Capability YAML Examples

### HTML capability

```yaml
# yaml-language-server: $schema=../../main/resources/schemas/naftiko-schema.json
---
naftiko: "1.0.0-alpha1"
info:
  label: "Product Catalog Scraper"
  description: "Extracts product data from an HTML catalog page"

capability:
  exposes:
    - type: "rest"
      address: "localhost"
      port: 9090
      namespace: "catalog"
      resources:
        - path: "/products"
          name: "products"
          operations:
            - method: "GET"
              call: "vendor.get-catalog"
              outputParameters:
                - type: "array"
                  mapping: "$.tables[0]"
                  items:
                    - type: "object"
                      properties:
                        name:
                          type: "string"
                          mapping: "$.Name"
                        price:
                          type: "string"
                          mapping: "$.Price"
                        stock:
                          type: "string"
                          mapping: "$.Stock"

  consumes:
    - type: "http"
      namespace: "vendor"
      baseUri: "https://vendor.example.com"
      resources:
        - path: "/catalog"
          name: "catalog"
          operations:
            - method: "GET"
              name: "get-catalog"
              outputRawFormat: "html"
              outputSchema: "table.products"
```

### Markdown capability

```yaml
# yaml-language-server: $schema=../../main/resources/schemas/naftiko-schema.json
---
naftiko: "1.0.0-alpha1"
info:
  label: "GitHub README Reader"
  description: "Extracts structured data from a GitHub README"

capability:
  exposes:
    - type: "rest"
      address: "localhost"
      port: 9090
      namespace: "readme"
      resources:
        - path: "/features"
          name: "features"
          operations:
            - method: "GET"
              call: "github.get-readme"
              outputParameters:
                - type: "array"
                  mapping: "$.tables[0]"
                  items:
                    - type: "object"
                      properties:
                        feature:
                          type: "string"
                          mapping: "$.Feature"
                        status:
                          type: "string"
                          mapping: "$.Status"

  consumes:
    - type: "http"
      namespace: "github"
      baseUri: "https://raw.githubusercontent.com"
      resources:
        - path: "/naftiko/framework/main/README.md"
          name: "readme"
          operations:
            - method: "GET"
              name: "get-readme"
              outputRawFormat: "markdown"
```

Both capabilities use the same table mapping shape.

---

## Implementation Changes

| Component | Change |
|---|---|
| `pom.xml` | Add `org.jsoup:jsoup`, `org.commonmark:commonmark`, and `org.commonmark:commonmark-ext-gfm-tables` |
| `Converter.java` | Add `convertHtmlToJson(Reader, String)` and `convertMarkdownToJson(Reader)` with shared table-row mapping behavior |
| `naftiko-schema.json` | Add `"html"` and `"markdown"` to `outputRawFormat` enum |
| `ConverterTest.java` | Add cross-format unit tests asserting identical `$.tables` shape |
| `HtmlIntegrationTest.java` | End-to-end test with mock HTML endpoint |
| `MarkdownIntegrationTest.java` | End-to-end test with mock Markdown endpoint |
| Fixtures | Add `html-capability.yaml`, `markdown-capability.yaml`, `sample-products.html`, `sample-readme.md` |

---

## Converter Method Sketch

```java
public static JsonNode convertHtmlToJson(Reader htmlReader, String cssSelector)
        throws IOException {
    // Parse with JSoup, scope by selector when provided,
    // then map tables using the shared table contract.
}

public static JsonNode convertMarkdownToJson(Reader markdownReader)
        throws IOException {
    // Parse front matter, parse Markdown AST with GFM tables,
    // map tables using the same shared contract,
    // then emit frontMatter + tables + sections.
}
```

Integration in `convertToJson()`:

```java
} else if ("HTML".equalsIgnoreCase(format)) {
    root = Converter.convertHtmlToJson(entity.getReader(), schema);
} else if ("MARKDOWN".equalsIgnoreCase(format)) {
    root = Converter.convertMarkdownToJson(entity.getReader());
}
```

---

## Tradeoffs vs. External Proxy

| Dimension | Native `html` + `markdown` formats | Proxy service |
|---|---|---|
| Setup | Zero, declarative config in capability YAML | Deploy and maintain service |
| Developer experience | Same pattern as `xml` and `csv` | Write and host custom code |
| Flexibility | Structured extraction targets | Full custom parsing freedom |
| Dependencies | JSoup + commonmark-java | External runtime and ops |
| Infrastructure | None | Extra network hop and monitoring |

---

## Limitations and Future Extensions

### Current scope

- Shared table contract across HTML and Markdown
- Static HTML only (no JavaScript rendering)
- Markdown sections emit plain text content
- Table cell values remain strings

### Potential extensions

- List extraction from HTML and Markdown
- HTML attribute extraction (for example `href`)
- Markdown code block and link extraction
- Optional typed coercion for numeric/date columns

---

## Open Questions for Discussion

1. Is reusing `outputSchema` for HTML selectors acceptable long-term?
2. How should tables without headers be handled (index keys or skip)?
3. Should Markdown sections include raw markdown content alongside plain text?
4. Should typed coercion be introduced as opt-in behavior?
5. Do JSoup and commonmark-java need native-image reflection configuration?
