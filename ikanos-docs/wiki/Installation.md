To use Ikanos, you need to install and then run the Ikanos Engine, passing a Ikanos YAML file to it. A command-line interface is also provided.

## Ikanos Engine
### Prerequisites
* You need Docker or, if you are on macOS or Windows their Docker Desktop version. To do so, follow the official documentation:
  * [For Mac](https://docs.docker.com/desktop/setup/install/mac-install/)
  * [For Linux](https://docs.docker.com/desktop/setup/install/linux/)
  * [For Windows](https://docs.docker.com/desktop/setup/install/windows-install/)

* Be sure that Docker (or Docker Desktop) is running

### Pull Ikanos's Docker image
* Ikanos provides a docker image hosted in GitHub packages platform. It is public, so you can easily pull it locally.
  ```bash
  # {{RELEASE_TAG}}
  docker pull ghcr.io/naftiko/ikanos:{{RELEASE_TAG}}

  # Or if you prefer to play with the last snapshot
  docker pull ghcr.io/naftiko/ikanos:latest
  ```
  Then, you should see the image 'ghcr.io/naftiko/ikanos' in your Docker Desktop.\
  <img src="https://Ikanos.github.io/docs/images/technology/framework/docker-install-step-1.png" width="600">

  You can also display local images in your terminal with this command:
  ```bash
  docker image ls
  ```

### Configure your own capability
The Ikanos Engine runs capabilities. For that, it uses a capability configuration file. You first have to create this file locally.\
  
* The simpler to start is to download the following capability file example on your local machine: [step-1-shipyard-first-capability.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-1-shipyard-first-capability.yml).

* Advanced usage\
You can use more complex capability files from [Tutorial - Part 1](https://github.com/naftiko/ikanos/wiki/Tutorial-%E2%80%90-Part-1) and [Tutorial - Part 2](https://github.com/naftiko/ikanos/wiki/Tutorial-%E2%80%90-Part-2), and then move to the comprehensive [Specification - Schema](https://github.com/naftiko/ikanos/wiki/Specification-%E2%80%90-Schema) and [Specification - Rules](https://github.com/naftiko/ikanos/wiki/Specification-%E2%80%90-Rules). This file must be a YAML file (yaml and yml extensions are supported).

  * ⚠️ Localhost references in your capability configuration file.
    * If your capability refers to some local hosts, be careful to not use 'localhost', but 'host.docker.internal' instead. This is because your capability will run into an isolated docker container, so 'localhost' will refer to the container and not your local machine.\
    For example:
      ```bash
      baseUri: "http://host.docker.internal:8080/api"
      ```
    * In the same way, if your capability expose a local host, be careful to not use 'localhost', but '0.0.0.0' instead. Else requests to localhost coming from outside of the container won't succeed.\
    For example:
      ```bash
      address: "0.0.0.0"
      ```

### Run Ikanos Engine as a Docker container
Let's assume you downloaded [step-1-shipyard-first-capability.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-1-shipyard-first-capability.yml) in your downloads folder. Then you should run:
```bash
# For Linux and Mac
docker run -p 8081:3001 -v ~/Downloads/step-1-shipyard-first-capability.yml:/app/test.capability.yaml ghcr.io/naftiko/ikanos:{{RELEASE_TAG}} /app/test.capability.yaml

# For windows
docker run -p 8081:3001 -v %USERPROFILE%/Downloads/step-1-shipyard-first-capability.yml:/app/test.capability.yaml ghcr.io/naftiko/ikanos:{{RELEASE_TAG}} /app/test.capability.yaml
```
Then you should be able to request your capability at http://localhost:8081 (the step-1-shipyard-first-capability.yml exposes an MCP tool adapter).

#### Options detail
* Use of a Docker volume.\
  As you have to provide your local capability configuration file to the docker container, you must use a volume. This is done using the '-v' option of the docker run command.

* Use of port forwarding.\
  According to your configuration file, your capability will be exposed on a given port (`port` field of the `expose` item). Keep in mind that the framework engine runs in a container context, so this port won't be accessible from your local machine. You must use the port forwarding. This will be done using the '-p' option of the docker run command.

* Run your capability with Ikanos Engine.\
  Given a capability configuration file 'test.capability.yaml' and an exposition on port 8081, here is the command you have to execute to run the Framework Engine:
  ```bash
  docker run -p 8081:8081 -v full_path_to_your_capability_folder/test.capability.yaml:/app/test.capability.yaml ghcr.io/naftiko/ikanos:latest /app/test.capability.yaml
  ```

## Ikanos CLI
The Ikanos also includes a CLI tool.\
The goal of this CLI is to simplify configuration and validation. While everything can be done manually, the CLI provides helper commands.

## Installation
### macOS
For the moment, CLI is only provided for Apple Silicon (with M chip).
**Apple Silicon (M1/M2/M3/M4):**
```bash
# Download the binary
curl -L https://github.com/naftiko/ikanos/releases/download/{{RELEASE_TAG}}/ikanos-cli-macos-arm64 -o Ikanos

# Set binary as executable
chmod +x Ikanos

# Delete the macOS quarantine (temporary step, because the binary is not signed yet)
xattr -d com.apple.quarantine Ikanos

# Install
sudo mv Ikanos /usr/local/bin/
```
### Linux
```bash
# Download the binary
curl -L https://github.com/naftiko/ikanos/releases/download/{{RELEASE_TAG}}/ikanos-cli-linux-amd64 -o Ikanos

# Set binary as executable
chmod +x Ikanos

# Install
sudo mv Ikanos /usr/local/bin/
```
### Windows
PowerShell installation is recommended.

**Open PowerShell as admin and execute:**
```powershell
# Create installation folder
New-Item -ItemType Directory -Force -Path "C:\Program Files\Ikanos"

# Download the binary
Invoke-WebRequest -Uri "https://github.com/naftiko/ikanos/releases/download/{{RELEASE_TAG}}/ikanos-cli-windows-amd64.exe" -OutFile "C:\Program Files\Ikanos\Ikanos.exe"

# Add to the system PATH
$oldPath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$newPath = $oldPath + ';C:\Program Files\Ikanos'
[Environment]::SetEnvironmentVariable('Path', $newPath, 'Machine')
```

## Test
After installation, you may have to restart your terminal. Then run this command to check the CLI is well installed:
```bash
ikanos --help
```
You should see the help of the command.

## Use
There are two available features for the moment: the creation of a "minimum" valid capability configuration file, and the validation of a capability file.
### Create a capability configuration file
```bash
ikanos create capability
# You can also use aliases like:
Ikanos cr cap
Ikanos c cap
```
The terminal will then ask you several questions. Finally, the file will be generated in your current directory.
### Validate a capability configuration file
The capabilities configuration file generated by the previous command should be valid. However, you can then complete it or even create it from scratch.\
The validation command allows you to check your file.
```bash
ikanos validate path_to_your_capability_file
# You can also use aliases like:
Ikanos val path_to_your_capability_file
ikanos v path_to_your_capability_file
```
By default, validation is performed on the latest schema version. If you want to test validation on a previous schema version, you can specify it as the second argument.
```bash
# Validate the capability configuration file with the schema v0.5
ikanos validate path_to_your_capability_file 0.5
```
The result will tell you if the file is valid or if there are any errors.

> **💡 Tip:** For inline validation as you type, install the free [Naftiko Extension for VS Code](https://github.com/naftiko/fleet/wiki/Naftiko-Extension-for-VS-Code). It validates both JSON Schema structure and Spectral rules directly in your editor.

### Import an OpenAPI specification
Bootstrap a Ikanos `consumes` adapter from an existing OpenAPI 3.0 or 3.1 document:
```bash
ikanos import openapi path_to_openapi_file
# You can also use aliases like:
Ikanos im oas path_to_openapi_file
Ikanos i oas path_to_openapi_file
```
By default, the generated capability is written to `./<namespace>-consumes.yml`. Use `-o` to choose a different output path:
```bash
# Import with custom output path
ikanos import openapi petstore.yaml -o my-petstore-capability.yaml
```
The importer maps OAS authentication schemes (bearer, basic, API key, digest) to Ikanos `authentication` blocks, derives a namespace from the API title, and converts operations into `consumes` resources.

### Export a REST adapter as an OpenAPI specification
Generate an OpenAPI document from an existing Ikanos capability's REST adapter:
```bash
ikanos export openapi path_to_capability_file
# You can also use aliases like:
Ikanos ex oas path_to_capability_file
Ikanos e oas path_to_capability_file
```
By default, the OpenAPI document is written to `./openapi.yaml` in OAS 3.0 format. Use `--spec-version` to produce OAS 3.1, `-o` for a custom output path, and `-f` for JSON output:
```bash
# Export as OAS 3.1 YAML
ikanos export openapi capability.yaml --spec-version 3.1

# Export as OAS 3.0 JSON
ikanos export openapi capability.yaml -f json -o api-spec.json
```
If the capability has multiple REST adapters, use `--adapter` (or `-a`) to select a specific namespace:
```bash
# Export only the "public-api" REST adapter
ikanos export openapi capability.yaml -a public-api
```
When `--adapter` is omitted, the first REST adapter found is exported.

### Manage scripting governance
Query and update the scripting governance configuration on a running capability's Control Port:
```bash
# Display current scripting config and execution stats
ikanos scripting

# Update a scripting setting at runtime
ikanos scripting --set timeout=60000

# Disable scripting
ikanos scripting --set enabled=false

# Restrict allowed languages
ikanos scripting --set allowedLanguages=javascript,python
```

> **Note:** Requires a running Control Port with `management.scripting` configured in the capability. The CLI connects to the control port address and port defined in the capability.