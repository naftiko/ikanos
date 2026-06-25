---
name: binary-content-reference
description: >
  "Return binary content (images, audio, PDFs, arbitrary bytes)" reference: use this
  when a consumed HTTP operation returns non-text bytes (image/*, audio/*,
  application/pdf, application/zip, application/octet-stream, …) and the user wants
  to expose those bytes faithfully through `exposes.type: rest` and/or
  `exposes.type: mcp` (tools and resources) instead of having them UTF-8 decoded and
  corrupted. Covers the `outputRawFormat: binary` opt-in, the `maxBinarySize` cap,
  and how the engine maps bytes onto REST responses and MCP content blocks.

---

## When to activate this reference

Use this reference when the user wants to:

- expose an upstream endpoint that returns **bytes** — photos, scans, audio clips,
  PDFs, ZIPs — through a Naftiko capability;
- build a **photo / media library** or **document workflow** capability where agents
  must "see", "view", or "download" a binary;
- serve **static binary files** (a brand logo PNG, a fixture PDF) from an MCP resource;
- cap the size of buffered binary payloads to protect the engine from OOMs.

Do not use it if:

- the upstream returns text/JSON/XML/YAML — the default text path already handles those;
- the user wants to *generate* a new image with an agent (that is a generative tool, not
  binary passthrough);
- the user needs streaming of multi-gigabyte payloads — binaries are buffered in memory
  (see `maxBinarySize`), streaming is out of scope.

## Core concept: declare binary once on `consumes`, expose anywhere

Binary handling is **declarative and opt-in**. The engine never sniffs response bodies.
A consumed HTTP operation opts in with a single flag:

```yaml
consumes:
  photos:
    type: http
    baseUri: https://api.photolibrary.example.com
    resources:
      photoBytes:
        path: /api/v1/photos/{id}/binary
        operations:
          download:
            method: GET
            outputRawFormat: binary       # ← do not UTF-8 decode; buffer raw bytes
            outputMediaType: image/jpeg   # ← optional: pin the contract media type
            maxBinarySize: 5MiB           # ← optional per-op cap (engine default 10MiB)
```

Once an operation is flagged binary, the raw bytes flow unchanged to whatever exposes it.
The bytes are base64-encoded only where the wire protocol requires it (MCP). Output
mappings (`outputParameters`) are **skipped** for binary responses — they are meaningless
for opaque bytes — and the engine emits an INFO log when they are present.

### Media-type precedence

The effective media type is resolved in this order:

1. `outputMediaType` declared on the consumed operation (or aggregate flow);
2. the upstream response `Content-Type`;
3. for REST exposures, the declared `responseBinary.mediaType` / `responses.<code>` content key;
4. `application/octet-stream` as a last resort.

### `maxBinarySize`

A hard cap on the buffered payload. Grammar: `^\d+(\.\d+)?(B|KiB|MiB|GiB)?$`
(e.g. `512KiB`, `5MiB`, `1GiB`). Engine default is **10 MiB**. Above the cap the call fails
cleanly rather than truncating or OOM-ing:

- **MCP tool** → `CallToolResult` with `isError: true` and the limit in the message;
- **REST** → `500` server error;
- **MCP resource** → JSON-RPC error.

Declare it per operation (`consumes.…operations.<op>.maxBinarySize`) or per MCP adapter
(`exposes[].maxBinarySize`).

## Recipe 1 — REST endpoint returning image bytes

A REST operation backed by a binary consumed op returns the raw bytes with the resolved
`Content-Type`, and the OpenAPI export describes it as `type: string, format: binary`.

```yaml
exposes:
  api:
    type: rest
    port: 8080
    maxBinarySize: 10MiB
    resources:
      photoImage:
        path: /photos/{id}/image
        operations:
          get:
            description: Return the original-resolution photo bytes.
            call: photos.download
            with:
              id: "{{id}}"
            responseBinary:               # shorthand for a single binary 200 response
              status: 200
              mediaType: image/jpeg
              description: Original image bytes
```

Exported OpenAPI (truncated):

```yaml
paths:
  /photos/{id}/image:
    get:
      responses:
        '200':
          description: Original image bytes
          content:
            image/jpeg:
              schema:
                type: string
                format: binary
```

> For richer contracts (multiple status codes, several media types), use the full
> `responses:` map instead of the `responseBinary` shorthand; mark the binary content
> entry with `binary: true`.

## Recipe 2 — MCP tool returning an image, audio clip, or PDF

A tool whose call (or terminal step) yields binary returns the MCP content block that
matches the MIME type. **You do not annotate the tool output shape** — you annotate that
the *upstream* is binary, and the engine dispatches on `Content-Type`:

| Upstream `Content-Type` | MCP content block |
|-------------------------|-------------------|
| `image/*`               | `ImageContent` (`data` = base64, `mimeType`) |
| `audio/*`               | `AudioContent` (`data` = base64, `mimeType`) |
| anything else           | `EmbeddedResource` → `BlobResourceContents` with a transient `ikanos://transient/…` URI |

```yaml
exposes:
  mcp:
    type: mcp
    transport: http
    port: 8765
    namespace: photos
    maxBinarySize: 10MiB
    tools:
      get-photo:
        display: Get photo bytes
        description: |
          Return the original-resolution image for a photo. Use this when the
          user asks to "see", "view", or "describe" a photo.
        call: photos.download
        with:
          id: "{{id}}"
        inputParameters:
          id: { type: string, required: true }
        hints:
          readOnly: true
          openWorld: false
```

Tool-call wire output (truncated):

```json
{
  "result": {
    "content": [
      { "type": "image", "mimeType": "image/jpeg", "data": "/9j/4AAQSkZJRgABAQ..." }
    ],
    "isError": false
  }
}
```

A PDF tool is identical except the upstream returns `application/pdf`; the result is an
`EmbeddedResource` whose `resource.uri` is a synthetic `ikanos://transient/<namespace>/<tool>/<uuid>`.

## Recipe 3 — MCP dynamic resource returning a blob

A dynamic resource backed by a binary consumed op returns `BlobResourceContents`
(`blob` = base64 + `mimeType`) instead of text:

```yaml
exposes:
  mcp:
    type: mcp
    transport: http
    port: 8765
    namespace: photos
    resources:
      photoBytes:
        name: photo
        display: Photo bytes
        uri: "photos://library/{id}/bytes"
        description: Original-resolution image bytes for a single photo.
        mimeType: image/jpeg
        binary: true                      # opt-in; must point at a binary consumed op
        call: photos.download
        with:
          id: "{id}"
```

Resource-read wire output (truncated):

```json
{
  "result": {
    "contents": [
      {
        "uri": "photos://library/img-2026-05-21-001/bytes",
        "mimeType": "image/jpeg",
        "blob": "/9j/4AAQSkZJRgABAQ..."
      }
    ]
  }
}
```

> If `binary: true` is declared but the target operation does **not** declare
> `outputRawFormat: binary`, spec load fails fast — this catches the common mistake of
> declaring binary on one side but not the other.

## Recipe 4 — MCP static binary file

Serve a file from disk. The variant (`text` vs `blob`) is chosen automatically from the
file's media type; image/audio/PDF/ZIP/octet-stream files return a base64 `blob`, text
files keep the text path:

```yaml
exposes:
  mcp:
    type: mcp
    resources:
      logo:
        name: logo
        uri: "files://brand/logo"
        description: Naftiko brand logo.
        location: file:///opt/naftiko/assets/logo.png
        mimeType: image/png               # optional — probed from extension if absent
```

> Before this feature static binary files were read as UTF-8 and silently corrupted.
> They now return correct bytes; existing static **text** resources are unchanged.

## Design checklist

- The consumed operation that produces bytes declares `outputRawFormat: binary`.
- Pin `outputMediaType` when the upstream `Content-Type` is generic or unreliable
  (many APIs return `application/octet-stream` for real images).
- Set a `maxBinarySize` appropriate to the payload (per op or per MCP adapter); the
  engine default is 10 MiB.
- Do **not** declare `outputParameters` on a binary tool/operation — they are skipped.
- For MCP dynamic resources, set `binary: true` **and** ensure the called op is binary.
- For REST, use `responseBinary` (single 200) or a full `responses:` map with
  `binary: true` on the content entry.
- Choose the MCP surface by intent: a **tool** for an agent action that returns bytes,
  a **resource** for addressable readable bytes, a **static** resource for files on disk.

## References

- Blueprint: `blueprints/capability-binary-content.md` (full design, validation rules §11,
  worked examples §9)
- Ikanos JSON Schema: `ikanos-spec/src/main/resources/schemas/ikanos-schema.json`
- Related recipe: `references/wrap-api-as-mcp.md` (general MCP exposition)
- Related recipe: `references/chain-api-calls.md` (orchestrated multi-step calls)
