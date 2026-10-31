#!/usr/bin/env python3
"""Sync agents-shared skills from the agents-shared MCP server into .agents/skills/.

Version 1.0.0 (HELPER_VERSION below). Source: naftiko/golden-repo-naftiko,
agents-shared/instructions/agents-shared-sync.py. Consumer repos copy this file verbatim and
never edit their copy; the server also publishes it as the ``agents-shared-sync`` skill, so
each copy can tell when it is outdated (see --check-helper).

The agents-shared capability serves its skills as Agent Skills over MCP (SEP-2640).
This helper is the consumer side of that protocol: for each requested skill it calls
``skills/get``, reads every listed resource with ``resources/read``, checks each file
against the ``sha256:`` digest and size the server announced, and only then writes the
files under ``<dest>/<skill>/``. Local files the server no longer publishes are removed;
dot-entries (e.g. a skill's ``.work/`` scratch directory) are left alone.

Usage:
    python agents-shared-sync.py --list
    python agents-shared-sync.py pr-review [bugfix ...]
    python agents-shared-sync.py --check pr-review [bugfix ...]
    python agents-shared-sync.py --check-helper

Options:
    --url URL    server URL (default: $AGENTS_SHARED_URL, else http://localhost:9700)
    --dest DIR   target directory (default: .agents/skills, relative to the current
                 directory, i.e. run it from the consumer repo root)
    --check      compare the local copies with the server and write nothing;
                 exit 1 when a copy is missing or stale
    --list       print the skills the server publishes, with their version
    --check-helper
                 compare this helper with the one the server publishes and write
                 nothing; exit 1 when this copy is outdated. Every other mode also
                 compares it, and prints a warning on stderr when it differs.

Exit codes: 0 success / up to date, 1 stale or missing (--check, --check-helper), 2 error.

Requirements: Python 3.8+, standard library only. Works on Windows, Linux and macOS.
"""

import argparse
import base64
import hashlib
import http.client
import json
import os
import sys
import urllib.parse
from pathlib import Path, PurePosixPath

PROTOCOL_VERSION = "2026-07-28"
DEFAULT_URL = "http://localhost:9700"
SCHEME = "skill://"

# Keep equal to metadata.version of the agents-shared-sync skill in agents-shared.yml.
HELPER_VERSION = "1.0.0"
HELPER_SKILL = "agents-shared-sync"
HELPER_URI = f"{SCHEME}{HELPER_SKILL}/agents-shared-sync.py"


class SyncError(Exception):
    """A failure that aborts the sync of a skill without touching its local copy."""


class Client:
    """Minimal stateless MCP client: one JSON-RPC POST per call, to the server root.

    Calls share one keep-alive connection: a skill can publish hundreds of files, and a new
    connection per call is slow where ``localhost`` first resolves to an unbound ``::1``.
    """

    def __init__(self, url):
        self.url = url.rstrip("/") + "/"
        parts = urllib.parse.urlsplit(self.url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise SyncError(f"unsupported server URL {url!r}")
        self.parts = parts
        self.path = parts.path or "/"
        self.connection = None
        self.next_id = 0

    def connect(self):
        factory = (http.client.HTTPSConnection if self.parts.scheme == "https"
                   else http.client.HTTPConnection)
        return factory(self.parts.hostname, self.parts.port, timeout=60)

    def post(self, body, headers):
        """POST once, retrying on a fresh connection if the kept-alive one was closed."""
        for attempt in (1, 2):
            if self.connection is None:
                self.connection = self.connect()
            try:
                self.connection.request("POST", self.path, body=body, headers=headers)
                response = self.connection.getresponse()
                return response.status, response.getheader("Content-Type", ""), response.read()
            except (http.client.RemoteDisconnected, BrokenPipeError, ConnectionResetError) as e:
                self.connection.close()
                self.connection = None
                if attempt == 2:
                    raise SyncError(f"connection to {self.url} failed ({e})")
            except (http.client.HTTPException, OSError) as e:
                self.connection.close()
                self.connection = None
                raise SyncError(f"cannot reach the agents-shared server at {self.url} ({e}); "
                                "is `ikanos serve agents-shared.yml` running?")

    def call(self, method, params=None, name=None):
        self.next_id += 1
        params = dict(params or {})
        params["_meta"] = {"io.modelcontextprotocol/protocolVersion": PROTOCOL_VERSION}
        body = json.dumps({"jsonrpc": "2.0", "id": self.next_id, "method": method,
                           "params": params}).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTOCOL_VERSION,
            "Mcp-Method": method,
        }
        if name is not None:
            headers["Mcp-Name"] = name
        status, content_type, payload = self.post(body, headers)
        raw = payload.decode("utf-8", "replace")
        if status >= 400 and "json" not in content_type:
            raise SyncError(f"{method}: HTTP {status} from {self.url}: {raw[:200]}")
        message = parse_message(raw, content_type)
        if "error" in message:
            error = message["error"]
            raise SyncError(f"{method}: {error.get('message', error)}")
        return message.get("result", {})


def parse_message(raw, content_type):
    """Return the JSON-RPC message of a JSON or a single-event SSE response."""
    if "text/event-stream" in content_type:
        data = [line[5:].strip() for line in raw.splitlines() if line.startswith("data:")]
        raw = data[-1] if data else "{}"
    return json.loads(raw)


def relative_path(name, uri):
    """Map ``skill://<name>/<path>`` to a safe relative path inside the skill directory."""
    prefix = f"{SCHEME}{name}/"
    if not uri.startswith(prefix):
        raise SyncError(f"resource {uri} is not part of skill {name}")
    path = PurePosixPath(uri[len(prefix):])
    if not path.parts or path.is_absolute() or any(p in ("", ".", "..") for p in path.parts):
        raise SyncError(f"resource {uri} has an unsafe path")
    return Path(*path.parts)


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def fetch_manifest(client, name):
    return client.call("skills/get", {"uri": f"{SCHEME}{name}/SKILL.md"})["skill"]


def read_resource(client, resource):
    """Read one announced resource and verify it against its digest and size."""
    uri = resource["uri"]
    contents = client.call("resources/read", {"uri": uri}, name=uri).get("contents", [])
    if len(contents) != 1:
        raise SyncError(f"{uri}: expected one content entry, got {len(contents)}")
    content = contents[0]
    if "blob" in content:
        data = base64.b64decode(content["blob"])
    else:
        data = content.get("text", "").encode("utf-8")
    if len(data) != resource.get("size", len(data)) or digest(data) != resource["digest"]:
        raise SyncError(f"{uri}: content does not match the announced digest/size")
    return data


def fetch_files(client, name, manifest):
    """Read every resource of a skill and verify it; return {relative path: bytes}."""
    files = {}
    for resource in manifest.get("resources", []):
        files[relative_path(name, resource["uri"])] = read_resource(client, resource)
    if Path("SKILL.md") not in files:
        raise SyncError(f"skill {name} publishes no SKILL.md")
    return files


def is_dot_path(path):
    return any(part.startswith(".") for part in path.parts)


def write_skill(target, files):
    """Write the verified files, then prune local files the server no longer publishes."""
    for rel, data in files.items():
        path = target / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for path in sorted(target.rglob("*"), reverse=True):
        rel = path.relative_to(target)
        if is_dot_path(rel):
            continue
        if path.is_file() and rel not in files:
            path.unlink()
        elif path.is_dir() and not any(path.iterdir()):
            path.rmdir()


def check_skill(target, manifest, name):
    """Return the list of differences between the local copy and the server manifest."""
    if not target.is_dir():
        return ["missing"]
    expected = {relative_path(name, r["uri"]): r["digest"] for r in manifest.get("resources", [])}
    issues = []
    for rel, want in expected.items():
        path = target / rel
        if not path.is_file():
            issues.append(f"missing {rel.as_posix()}")
        elif digest(path.read_bytes()) != want:
            issues.append(f"changed {rel.as_posix()}")
    for path in target.rglob("*"):
        rel = path.relative_to(target)
        if path.is_file() and not is_dot_path(rel) and rel not in expected:
            issues.append(f"extra {rel.as_posix()}")
    return issues


def version_of(manifest):
    return (manifest.get("frontmatter") or {}).get("metadata", {}).get("version", "?")


def helper_status(client):
    """Compare this file with the helper the server publishes; return (up to date, version).

    Line endings depend on each checkout (core.autocrlf), so the text is compared, not the
    bytes.
    """
    manifest = fetch_manifest(client, HELPER_SKILL)
    resource = next((r for r in manifest.get("resources", []) if r.get("uri") == HELPER_URI),
                    None)
    if resource is None:
        raise SyncError(f"the server does not publish {HELPER_URI}")
    published = read_resource(client, resource).replace(b"\r\n", b"\n")
    local = Path(__file__).read_bytes().replace(b"\r\n", b"\n")
    return local == published, version_of(manifest)


def warn_if_outdated(client):
    """Print a warning when this helper differs from the published one; never fail on it."""
    try:
        up_to_date, version = helper_status(client)
    except (SyncError, KeyError, ValueError, OSError):
        return  # an older server that does not publish the helper, or an unreachable one
    if not up_to_date:
        print(f"warning: this agents-shared-sync.py ({HELPER_VERSION}) differs from the one the "
              f"server publishes ({version}); refresh it as the {HELPER_SKILL} skill explains",
              file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("skills", nargs="*", help="skill names, e.g. pr-review")
    parser.add_argument("--url", default=os.environ.get("AGENTS_SHARED_URL") or DEFAULT_URL)
    parser.add_argument("--dest", default=os.path.join(".agents", "skills"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="report stale copies, write nothing")
    mode.add_argument("--list", action="store_true", help="list the served skills")
    mode.add_argument("--check-helper", action="store_true",
                      help="report whether this helper is outdated, write nothing")
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):  # a Windows console may not encode every message
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    try:
        client = Client(args.url)
    except SyncError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    if args.list:
        try:
            skills = client.call("skills/list").get("skills", [])
        except SyncError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        for skill in skills:
            name = skill["uri"][len(SCHEME):].split("/", 1)[0]
            print(f"{name}\t{version_of(skill)}")
        return 0
    if args.check_helper:
        try:
            up_to_date, version = helper_status(client)
        except (SyncError, KeyError, ValueError, OSError) as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        print(f"agents-shared-sync.py {HELPER_VERSION}: " + (
            "up to date" if up_to_date else
            f"outdated (the server publishes {version}; see the {HELPER_SKILL} skill)"))
        return 0 if up_to_date else 1
    if not args.skills:
        parser.error("name at least one skill, or use --list")

    warn_if_outdated(client)
    status = 0
    for name in args.skills:
        target = Path(args.dest) / name
        try:
            manifest = fetch_manifest(client, name)
            if args.check:
                issues = check_skill(target, manifest, name)
                print(f"{name}: " + ("up to date" if not issues else "stale (" + ", ".join(issues) + ")"))
                status = max(status, 1 if issues else 0)
                continue
            files = fetch_files(client, name, manifest)
            write_skill(target, files)
            print(f"{name} {version_of(manifest)}: {len(files)} files verified and synced into "
                  f"{target.as_posix()}/")
        except (SyncError, KeyError, ValueError, OSError) as e:
            print(f"error: {name}: {e}", file=sys.stderr)
            status = 2
    return status


if __name__ == "__main__":
    sys.exit(main())
