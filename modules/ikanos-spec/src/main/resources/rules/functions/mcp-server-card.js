/**
 * Polychro rule function: mcp-server-card
 *
 * Mirrors the engine's load-time checks on MCP Server Cards (SEP-2127, #1165), so a
 * card that would fail the load, or be served with a misleading URL, is reported
 * before the capability starts. Applies to every `mcp` exposes entry that declares
 * `card`, in `capability.exposes` and in root-level `exposes`.
 *
 * functionOptions.check selects one rule:
 *   - name:          the card name must come from `card.name` or `info.publisher`,
 *                    and be reverse-DNS with exactly one slash, 3 to 200 characters
 *   - version:       `card.version` or `info.version` must be set, and not be a range
 *   - description:   `card.description` or the entry's `description` must be set,
 *                    100 characters at most (the engine never truncates it)
 *   - url:           a wildcard `address` without `card.url` advertises the machine's
 *                    host name, which clients elsewhere may not resolve
 *   - authenticated: `card.authenticated: true` has no effect without `authentication`
 *   - repository:    a repository outside github.com, gitlab.com and bitbucket.org is
 *                    left off the card
 */
const NAME = /^[a-zA-Z0-9.-]+\/[a-zA-Z0-9._-]+$/;
const VERSION_RANGE = /^\s*[\^~><=]|\|\||\s-\s|(^|\.)[xX*](\.|$)/;
const WILDCARDS = new Set(["0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0"]);
const FORGES = new Set(["github.com", "gitlab.com", "bitbucket.org"]);

export default function mcpServerCard(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = options && options.check;
  const info = targetVal.info && typeof targetVal.info === "object" ? targetVal.info : {};
  const results = [];
  scan(targetVal.exposes, ["exposes"], info, check, results);
  if (targetVal.capability && typeof targetVal.capability === "object") {
    scan(targetVal.capability.exposes, ["capability", "exposes"], info, check, results);
  }
  return results;
}

function scan(exposes, basePath, info, check, results) {
  if (!Array.isArray(exposes)) {
    return;
  }
  exposes.forEach((adapter, index) => {
    if (!adapter || adapter.type !== "mcp" || !adapter.card || typeof adapter.card !== "object") {
      return;
    }
    const card = adapter.card;
    const at = (...rest) => [...basePath, index, ...rest];
    const report = (message, path) => results.push({ message, path });
    const ns = "MCP adapter '" + adapter.namespace + "'";

    switch (check) {
      case "name": {
        if (card.name === undefined && !info.publisher) {
          report(ns + " has no card name: set info.publisher, or card.name.", at("card"));
          return;
        }
        const name = card.name !== undefined ? card.name : info.publisher + "/" + adapter.namespace;
        if (typeof name !== "string" || name.length < 3 || name.length > 200 || !NAME.test(name)) {
          report(ns + " card name '" + name + "' must be reverse-DNS with exactly one slash,"
            + " 3 to 200 characters.", at("card"));
        }
        return;
      }
      case "version": {
        const version = card.version !== undefined ? card.version : info.version;
        if (typeof version !== "string" || version.trim() === "") {
          report(ns + " has no card version: set info.version, or card.version.", at("card"));
        } else if (VERSION_RANGE.test(version)) {
          report(ns + " card version '" + version + "' is a range; use one exact version.",
            at("card"));
        }
        return;
      }
      case "description": {
        const description = card.description !== undefined ? card.description : adapter.description;
        if (typeof description !== "string" || description.trim() === "") {
          report(ns + " has no card description: set its description, or card.description.",
            at("card"));
        } else if (description.trim().length > 100) {
          report(ns + " description is " + description.trim().length + " characters long, over"
            + " the 100 a server card allows; set a shorter card.description.", at("card"));
        }
        return;
      }
      case "url": {
        if (card.url === undefined && typeof adapter.address === "string"
            && WILDCARDS.has(adapter.address.trim())) {
          report(ns + " listens on '" + adapter.address + "', so its card advertises the"
            + " machine's host name; set card.url to the URL clients reach it at.", at("card"));
        }
        return;
      }
      case "authenticated": {
        if (card.authenticated === true && !adapter.authentication) {
          report(ns + " sets card.authenticated: true without authentication; the card stays"
            + " public.", at("card", "authenticated"));
        }
        return;
      }
      case "repository": {
        const url = card.repositoryUrl !== undefined ? card.repositoryUrl : info.repositoryUrl;
        if (typeof url !== "string") {
          return;
        }
        const host = hostOf(url);
        if (host === null || !FORGES.has(host.replace(/^www\./, ""))) {
          report(ns + " repository '" + url + "' is not on github.com, gitlab.com or"
            + " bitbucket.org, so the card leaves it out.", at("card"));
        }
        return;
      }
      default:
        return;
    }
  });
}

function hostOf(url) {
  const match = /^https?:\/\/([^/:?#]+)/i.exec(url);
  return match ? match[1].toLowerCase() : null;
}
