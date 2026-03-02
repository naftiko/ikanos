# Framework Engine

## Usage
There are 3 ways to use the Framework Engine, depending on your goal.
* If you want to simply use it as is: use a Docker instance. No environment configuration is required.
* If you want to integrate it to your project: use a maven dependency. Add the dependency, and use the library seamlessly in your Java project.
* If you want to contribute to the source code: pull the repository and access the source code directly.

### 1 - Docker usage
#### Prerequisites
* You need Docker or, if you are on macOS or Windows their Docker Desktop version. To do so, follow the official documentation:
  * [For Mac](https://docs.docker.com/desktop/setup/install/mac-install/)
  * [For Linux](https://docs.docker.com/desktop/setup/install/linux/)
  * [For Windows](https://docs.docker.com/desktop/setup/install/windows-install/)

* Be sure that Docker (or Docker Desktop) is running

#### Pull the Naftiko Framework Engine docker image
* Naftiko provides a docker image hosted in GitHub packages plateform. It is public, so you can easily pull it locally.
  ```bash
  docker pull ghcr.io/naftiko/framework:latest
  ```
  Then, you should see the image 'ghcr.io/naftiko/framework' in your Docker Desktop with the tag 'latest'. You can also display local images with this command:
  ```bash
  docker image ls
  ```

#### Configure your own capability
* Create your capability configuration file.\
  The Framework Engine runs capabilities. For that, it uses a capability configuration file. You first have to create this file locally according to the [specification](/src/main/resources/schemas/README.md). This file must be a yaml or json file (yaml, yml, and json extensions are supported).

* Local hosts in your capability configuration file.\
  If your capability reffers to some local hosts, be carefull to not use 'localhost', but 'host.docker.internal' instead. This is because your capability will run into an isolated docker container, so 'localhost' will reffer to the container and not your local machine.
  For example:
  ```bash
  baseUri: "http://host.docker.internal:8080/api/"
  ```

#### Run the Framework Engine as a docker container
* Use a docker volume.\
  As you have to provide your local capability configuration file to the docker container, you must use a volume. This will be done using the '-v' option of the docker run command.

* Use port formwarding.\
  According to your configuration file, your capability will be exposed on a given port. Keep in mind that the framework engine runs in a container context, so this port won't be accessible from your local machine. You must use the port forwarding. This will be done using the '-p' option of the docker run command.

* Run Framework Engine using your capability.\
  Given a capability configuration file 'test.capability.yaml' and an exposition on port 8081, here is the command you have to execute to run the Framework Engine:
  ```bash
  docker run -p 8081:8081 -v full_path_to_your_capability_folder/test.capability.yaml:/app/test.capability.yaml ghcr.io/naftiko/framework:latest /app/test.capability.yaml
  ```
  Then you should be able to request your capability at http://localhost:8081

### 2 - Maven dependency usage
***Not supported yet***

### 3 - Source code usage
#### Prerequisites
* You must have java installed.
* You must have maven installed.
* You must have git installed.

#### Pull the Naftiko Framework Engine source code
```bash
git clone https://github.com/naftiko/framework.git
```

#### Install dependencies
* Be sure to be at the root of the project you cloned.
  ```bash
  cd framework
  ```
* You must install the project dependencies.
  ```bash
  mvn clean install
  ```
  It should generate several jar files in the target folder. One of them should be capability.jar.

#### Create your capability
* Create your capability configuration file according to the [specification](/src/main/resources/schemas/README.md). This file must be a yaml or json file (yaml, yml, and json extensions are supported).

#### Run the Framework Engine
```bash
java -jar path_to_your_jar_folder/capability.jar path_to_your_capability_folder/test.capability.yaml
```
Then you should be able to request your capability at http://localhost:your_exposed_port
