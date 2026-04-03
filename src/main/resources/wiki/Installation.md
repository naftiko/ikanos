To use Naftiko Framework, you must install and then run its engine.

## Docker usage
### Prerequisites
* You need Docker or, if you are on macOS or Windows their Docker Desktop version. To do so, follow the official documentation:
  * [For Mac](https://docs.docker.com/desktop/setup/install/mac-install/)
  * [For Linux](https://docs.docker.com/desktop/setup/install/linux/)
  * [For Windows](https://docs.docker.com/desktop/setup/install/windows-install/)

* Be sure that Docker (or Docker Desktop) is running

### Pull Naftiko's Docker image
* Naftiko provides a docker image hosted in GitHub packages platform. It is public, so you can easily pull it locally.
  ```bash
  # v1.0.0alpha1
  docker pull ghcr.io/naftiko/framework:sha-770e515

  # If you want to play with the last snapshot
  docker pull ghcr.io/naftiko/framework:latest
  ```
  Then, you should see the image 'ghcr.io/naftiko/framework' in your Docker Desktop. You can also display local images with this command:
  ```bash
  docker image ls
  ```

### Configure your own capability
* Create your capability configuration file.\
  The Naftiko Engine runs capabilities. For that, it uses a capability configuration file. You first have to create this file locally. You can use [this first Shipyard example](https://github.com/naftiko/framework/blob/main/src/main/resources/tutorial/step-1-shipyard-first-capability.yml) to start with, continue with [Tutorial - Part 1](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-1) and [Tutorial - Part 2](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-2), and then move to the comprehensive [Specification - Schema](https://github.com/naftiko/framework/wiki/Specification-Schema) and [Specification - Rules](https://github.com/naftiko/framework/wiki/Specification-Rules). This file must be a YAML file (yaml and yml extensions are supported).

* Localhost in your capability configuration file.
  * If your capability reffers to some local hosts, be carefull to not use 'localhost', but 'host.docker.internal' instead. This is because your capability will run into an isolated docker container, so 'localhost' will reffer to the container and not your local machine.\
    For example:
    ```bash
    baseUri: "http://host.docker.internal:8080/api/"
    ```
  * In the same way, if your capability expose a local host, be careful to not use 'localhost', but '0.0.0.0' instead. Else requests to localhost coming from outside of the container won't succeed.\
    For example:
    ```bash
    address: "0.0.0.0"
    ```

### Run Naftiko Engine as a Docker container
* Use a Docker volume.\
  As you have to provide your local capability configuration file to the docker container, you must use a volume. This will be done using the '-v' option of the docker run command.

* Use port forwarding.\
  According to your configuration file, your capability will be exposed on a given port. Keep in mind that the framework engine runs in a container context, so this port won't be accessible from your local machine. You must use the port forwarding. This will be done using the '-p' option of the docker run command.

* Run your capability with Naftiko Engine.\
  Given a capability configuration file 'test.capability.yaml' and an exposition on port 8081, here is the command you have to execute to run the Framework Engine:
  ```bash
  docker run -p 8081:8081 -v full_path_to_your_capability_folder/test.capability.yaml:/app/test.capability.yaml ghcr.io/naftiko/framework:latest /app/test.capability.yaml
  ```
  Then you should be able to request your capability at http://localhost:8081

## CLI tool
The Naftiko framework provides a CLI tool.\
The goal of this CLI is to simplify configuration and validation. While everything can be done manually, the CLI provides helper commands.

## Installation
### macOS
For the moment, CLI is only provided for Apple Silicon (with M chip).
**Apple Silicon (M1/M2/M3/M4):**
```bash
# Download the binary
curl -L https://github.com/naftiko/framework/releases/download/v1.0.0-alpha1/naftiko-cli-macos-arm64 -o naftiko

# Set binary as executable
chmod +x naftiko

# Delete the macOS quarantine (temporary step, because the binary is not signed yet)
xattr -d com.apple.quarantine naftiko

# Install
sudo mv naftiko /usr/local/bin/
```
### Linux
```bash
# Download the binary
curl -L https://github.com/naftiko/framework/releases/download/v1.0.0-alpha1/naftiko-cli-linux-amd64 -o naftiko

# Set binary as executable
chmod +x naftiko

# Install
sudo mv naftiko /usr/local/bin/
```
### Windows
PowerShell installation is recommended.

**Open PowerShell as admin and execute:**
```powershell
# Create installation folder
New-Item -ItemType Directory -Force -Path "C:\Program Files\Naftiko"

# Download the binary
Invoke-WebRequest -Uri "https://github.com/naftiko/framework/releases/download/v1.0.0-alpha1/naftiko-cli-windows-amd64.exe" -OutFile "C:\Program Files\Naftiko\naftiko.exe"

# Add to the system PATH
$oldPath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$newPath = $oldPath + ';C:\Program Files\Naftiko'
[Environment]::SetEnvironmentVariable('Path', $newPath, 'Machine')
```

## Test
After installation, you may have to restart your terminal. Then run this command to check the CLI is well installed:
```bash
naftiko --help
```
You should see the help of the command.

## Use
There are two available features for the moment: the creation of a "minimum" valid capability configuration file, and the validation of a capability file.
### Create a capability configuration file
```bash
naftiko create capability
# You can also use aliases like:
naftiko cr cap
naftiko c cap
```
The terminal will then ask you several questions. Finally, the file will be generated in your current directory.
### Validate a capability configuration file
The capabilities configuration file generated by the previous command should be valid. However, you can then complete it or even create it from scratch.\
The validation command allows you to check your file.
```bash
naftiko validate path_to_your_capability_file
# You can also use aliases like:
naftiko val path_to_your_capability_file
naftiko v path_to_your_capability_file
```
By default, validation is performed on the latest schema version. If you want to test validation on a previous schema version, you can specify it as the second argument.
```bash
# Validate the capability configuration file with the schema v0.5
naftiko validate path_to_your_capability_file 0.5
```
The result will tell you if the file is valid or if there are any errors.
