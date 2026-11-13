/**
 * Spectral custom function: exposes-authorization
 *
 * Checks the `authorization` blocks of an exposed REST or MCP adapter (issue #999): on the adapter, on
 * its REST resources and operations, and on its MCP tools. Selected by `functionOptions.check`:
 *
 * - `oauth2`: an `authorization` reads the claims of a validated OAuth 2.1 token, so the adapter must use
 *   `authentication: oauth2`, and an MCP adapter must not use the stdio transport, which carries
 *   no token. The schema checks the adapter-level `authorization`; this check covers every level. The
 *   engine refuses to start on the same problems.
 * - `scopes`: every scope an `authorization` lists must be one the adapter recognizes, in
 *   `authentication.scopes`, so Protected Resource Metadata and the OpenAPI export advertise it.
 * - `unenforced`: an `oauth2` adapter lists `scopes` but declares no `authorization`. The scopes are
 *   only the ones it recognizes and are not required on any call, which an older capability may
 *   have relied on.
 *
 * Resources, operations and tools are read as maps keyed by name, or as lists of named entries.
 */
export default function exposesAuthorization(adapter, options, context) {
  if (!adapter || typeof adapter !== "object") {
    return;
  }
  const check = options && options.check;
  const declared = collectAuthorization(adapter, context.path);
  const authentication = adapter.authentication;
  const isOAuth2 = authentication && authentication.type === "oauth2";

  if (check === "unenforced") {
    const scopes = isOAuth2 && authentication.scopes;
    if (Array.isArray(scopes) && scopes.length > 0 && declared.length === 0) {
      return [{
        message: "'authentication.scopes' only lists the scopes this adapter recognizes: no call "
          + "requires them. Add 'authorization' to the adapter, a resource, an operation or a tool "
          + "to require scopes or claims.",
        path: [...context.path, "authentication", "scopes"],
      }];
    }
    return;
  }

  const results = [];
  if (check === "oauth2") {
    const stdio = adapter.type === "mcp" && adapter.transport === "stdio";
    declared.forEach(({ where, path }) => {
      if (stdio) {
        results.push({
          message: `'authorization' on ${where} cannot be enforced on the stdio transport, which `
            + "carries no access token.",
          path,
        });
      } else if (!isOAuth2) {
        results.push({
          message: `'authorization' on ${where} needs 'authentication: oauth2' on the adapter.`,
          path,
        });
      }
    });
  } else if (check === "scopes" && isOAuth2) {
    const recognized = Array.isArray(authentication.scopes) ? authentication.scopes : [];
    declared.forEach(({ where, path, authorization }) => {
      const scopes = Array.isArray(authorization.scopes) ? authorization.scopes : [];
      scopes.forEach((scope, index) => {
        if (!recognized.includes(scope)) {
          results.push({
            message: `Scope '${scope}' required on ${where} is not listed in `
              + "'authentication.scopes'.",
            path: [...path, "scopes", index],
          });
        }
      });
    });
  }
  return results;
}

/** Every `authorization` of the adapter, with where it is declared and its document path. */
function collectAuthorization(adapter, adapterPath) {
  const declared = [];
  const add = (where, owner, path) => {
    if (owner && owner.authorization && typeof owner.authorization === "object") {
      declared.push({ where, authorization: owner.authorization, path: [...path, "authorization"] });
    }
  };
  add("the adapter", adapter, adapterPath);
  if (adapter.type === "rest") {
    entries(adapter.resources).forEach(([resourceKey, resource, resourceName]) => {
      const resourcePath = [...adapterPath, "resources", resourceKey];
      add(`resource '${resourceName}'`, resource, resourcePath);
      entries(resource && resource.operations).forEach(([operationKey, operation, name]) => {
        add(`operation '${resourceName}.${name}'`, operation,
          [...resourcePath, "operations", operationKey]);
      });
    });
  } else if (adapter.type === "mcp") {
    entries(adapter.tools).forEach(([toolKey, tool, toolName]) => {
      add(`tool '${toolName}'`, tool, [...adapterPath, "tools", toolKey]);
    });
  }
  return declared;
}

/** [key, value, display name] of a map keyed by name, or of a list of named entries. */
function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, index) =>
      [index, value, (value && (value.name || value.path)) || String(index)]);
  }
  if (collection && typeof collection === "object") {
    return Object.entries(collection).map(([key, value]) => [key, value, key]);
  }
  return [];
}
