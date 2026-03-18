## Introduction

Welcome to the tutorial for Naftiko Framework. Starting with the simplest "Hello World!" capability, it offers a hands-on, progressive journey to learn some on the key features offered to do Spec-Driven Integration.

Please read the [installation instructions](https://github.com/naftiko/framework/wiki/Installation) to know how to run the Naftiko Engine with your capability file.

## 1. My first capability
Start with a very basic capability returning "Hello, World!", using only an API server adapter with constants.
```
naftiko: "0.5"
capability:
  exposes:
    - type: "api"
      port: 8081
      namespace: "tutorial"
      resources:
        - path: "/hello"
          name: "hello"
          label: "My first resource"
          description: "This is a resource to demonstrate a simple Hello, World! API endpoint"
          operations:
            - method: "GET"
              outputParameters:
                - type: "string"
                  const: "Hello, World!"
```
Then run the Naftiko Engine with this capability, and execute a GET request on http://localhost:8081/hello. \
It should display the following JSON response
```
{
  "value": "Hello, World!"
}
```
Congrats, you ran your first capability!

## 2. Forwarding API resource
Add a "consumes" section to forward an existing API (Notion API in our example) and expose it as a capability.
```
naftiko: "0.5"
info:
  label: "Tutorial - Step 2 - Forwarding API Resource"

capability:
  exposes:
    - type: "api"
      port: 8081
      namespace: "sample"
      resources:
        - path: "/notion/{{path}}"
          description: "This resource forwards requests to the Notion API, allowing access to any Notion endpoint by specifying the path parameter. For example, a request to /notion/pages would be forwarded to https://api.notion.com/v1/pages."
          forward:
            targetNamespace: notion
            trustedHeaders:
            - Notion-Version

  consumes:
    - type: "http"
      description: "Forwarded requests from the /notion/{path} resource, to be sent to the Notion API"
      namespace: "notion"
      baseUri: "https://api.notion.com/v1/"
```
Execute a GET request on http://localhost:8081/notion/users/me. You must add a "Authorization: Bearer your_notion_api_key" and a "Notion-Version: 2025-09-03" header. Note the {{path}} keyword that allows you to access all API resources (and not only the /users/me of this example). The value of the keyword will be paste after https://api.notion.com/v1/ \
It should display a JSON response similar to:
```
{
  "object": "user",
  "id": "9c1061dc-0350-4aa0-afb0-xxxxxxxxxxxx",
  "name": "Capability",
  "avatar_url": null,
  "type": "bot",
  "bot": {
    "owner": {
      "type": "workspace",
      "workspace": true
    },
    "workspace_name": "Naftiko",
    "workspace_id": "39d4adce-3d02-81a1-afeb-xxxxxxxxxxxx",
    "workspace_limits": {
      "max_file_upload_size_in_bytes": 5368709120
    }
  },
  "request_id": "a3d33d51-d51f-4f3b-a616-xxxxxxxxxxxx"
}
```

## 3. Encapsulating Headers
Let's say you don't want to add headers when requesting the capability. For that you can define these headers as input parameters in the "consumes" section.
```
naftiko: "0.5"
info:
  label: "Tutorial - Step 3 - Encapsulating Headers"
  description: "This is a sample capability specification to demonstrate the features of Naftiko"

capability:
  exposes:
    - type: "api"
      port: 8081
      namespace: "sample"
      resources:
        - path: "/notion/{{path}}"
          description: "This resource forwards requests to the Notion API, allowing access to any Notion endpoint by specifying the path parameter. For example, a request to /notion/pages would be forwarded to https://api.notion.com/v1/pages."
          forward:
            targetNamespace: notion

  consumes:
    - type: "http"
      description: "Forwarded requests from the /notion/{path} resource, to be sent to the Notion API"
      namespace: "notion"
      baseUri: "https://api.notion.com/v1/"
      authentication:
        type: "bearer"
        token: "{{notion_api_key}}"
      inputParameters:
        - name: "notion_api_key"
          in: "environment"
        - name: "Notion-Version"
          in: "header"
          const: "2025-09-03"
```
Note that the "Notion-Version" value is a constant, whereas the "notion_api_key" value comes from an environment variable. So you have to pass this environment variable to your Framework Engine. If you use Docker with the docker run command, you can do it with the option --env notion_api_key="your_notion_api_key"\
Even now you can request your capability without any header.

## 4. Filter response
If you want to get a more concise response with only the fields you need. for example: id, name, and type. You can define the output parameters for a specific operation.
```
naftiko: "0.5"
info:
  label: "Tutorial - Step 4 - Filter response"
  description: "This is a sample capability specification to demonstrate the features of Naftiko"
  tags:
    - Naftiko
    - Tutorial

capability:
  exposes:
    - type: "api"
      port: 8081
      namespace: "sample"
      resources:
        - path: "/notion/users/me"
          description: "This resource is for the specific path /notion/users/me and restricted to nested operations."
          forward:
            targetNamespace: notion
          operations:
            - method: "GET"
              call: "notion.get-me"
              outputParameters:
                - type: "object"
                  properties:
                    id:
                      type: "string"
                      mapping: "$.id"
                    name:
                      type: "string"
                      mapping: "$.name"
                    type:
                      type: "string"
                      mapping: "$.type"

  consumes:
    - type: "http"
      description: "Forwarded requests from the /notion/{path} resource, to be sent to the Notion API"
      namespace: "notion"
      baseUri: "https://api.notion.com/v1/"
      authentication:
        type: "bearer"
        token: "{{notion_api_key}}"
      inputParameters:
        - name: "notion_api_key"
          in: "environment"
        - name: "Notion-Version"
          in: "header"
          const: "2025-09-03"
      resources:
        - path: "users/me"
          operations:
            - method: "GET"
              name: "get-me"
```
In this case, if you execute the previous GET request on http://localhost:8081/notion/users/me, it should display a smaller JSON response similar to:
```
{
  "id": "9c1061dc-0350-4aa0-afb0-xxxxxxxxxxxx",
  "name": "Capability",
  "type": "bot"
}
```

## 5. Multi steps
You can define a specific capability that calls several endpoints to provide a consolidate result. For this example, you define a "GET my full user" capability which first call "users/me" (get me), then use the result userId to call "users/{{userId}}" (get user by id).
```
naftiko: "0.5"
info:
  label: "Tutorial - Step 5 - Multi steps"
  description: "This capability is based on two resources called one after the other. The second step takes an output value of the first one as argument (user_id)"
  tags:
    - Naftiko
    - Tutorial
  created: "2026-02-26"
  modified: "2026-03-04"

capability:
  exposes:
    - type: "api"
      port: 8081
      namespace: "my-capability"
      resources:
        - path: "/notion/my-full-user"
          description: "This resource is for the specific path /notion/my-full-user and restricted to nested operations."
          forward:
            targetNamespace: notion
          operations:
            - method: "GET"
              steps:
                - name: "fetch-me-user"
                  type: "call"
                  call: "notion.get-me"
                - name: "fetch-user-by-id"
                  type: "call"
                  call: "notion.get-user"
                  with:
                    user_id: "{{fetch-me-user.userid}}"

  consumes:
    - type: "http"
      description: "Forwarded requests from the /notion/{path} resource, to be sent to the Notion API"
      namespace: "notion"
      baseUri: "https://api.notion.com/v1/"
      authentication:
        type: "bearer"
        token: "{{notion_api_key}}"
      inputParameters:
        - name: "notion_api_key"
          in: "environment"
        - name: "Notion-Version"
          in: "header"
          const: "2025-09-03"
        - name: "user_id"
          in: "path"
      resources:
        - path: "users/me"
          name: "users-me"
          operations:
            - method: "GET"
              name: "get-me"
              outputParameters:
                - name: "userid"
                  type: "string"
                  value: "$.id"
        - path: "users/{{user_id}}"
          name: "users-by-id"
          inputParameters:
            - name: "user_id"
              in: "path"
          operations:
            - method: "GET"
              name: "get-user"
```
You can execute a GET request on http://localhost:8081/notion/my-full-user and you'll get your user infos.

## 6. MCP
If you want to expose your capability as MCP tool, this is possible.
```
naftiko: "0.5"
info:
  label: "Tutorial - Step 6 - MCP"
  description: "This is a sample capability specification to demonstrate the MCP exposition feature"
  tags:
    - Naftiko
    - Tutorial
  created: "2026-03-05"
  modified: "2026-03-05"
  stakeholders:
    - role: "editor"
      fullName: "Navi"
      email: "navi@naftiko.io"

capability:
  exposes:
    - type: "mcp"
      address: "localhost"
      port: 9091
      namespace: "notion-mcp"
      description: "MCP server exposing Notion database query capabilities for pre-release participant management."

      tools:
        - name: "query-database"
          description: "Query the Notion pre-release participants database to retrieve committed participants with their name, company, title, location, owner, participation status and comments."
          call: "notion.query-db"
          with:
            datasource_id: "2fe4adce-3d02-8028-bec8-000bfb5cafa2"
          outputParameters:
            - type: "array"
              mapping: "$.results"
              items:
                - type: "object"
                  properties:
                    name:
                      type: "string"
                      mapping: "$.properties.Name.title[0].text.content"
                    company:
                      type: "string"
                      mapping: "$.properties.Company.rich_text[0].text.content"
                    title:
                      type: "string"
                      mapping: "$.properties.Title.rich_text[0].text.content"
                    location:
                      type: "string"
                      mapping: "$.properties.Location.rich_text[0].text.content"
                    owner:
                      type: "string"
                      mapping: "$.properties.Owner.people[0].name"
                    participation_status:
                      type: "string"
                      mapping: "$.properties.Participation Status.select.name"
                    comments:
                      type: "string"
                      mapping: "$.properties.Comments.rich_text[0].text.content"

  consumes:
    - type: "http"
      description: "Notion API integration for accessing pre-release participant data stored in Notion."
      namespace: "notion"
      baseUri: "https://api.notion.com/v1/"
      authentication:
        type: "bearer"
        token: "{{notion_api_key}}"
      inputParameters:
        - name: "notion_api_key"
          in: "environment"
        - name: "Notion-Version"
          in: "header"
          const: "2025-09-03"
      resources:
        - path: "data_sources/{{datasource_id}}/query"
          name: "query"
          label: "Query database resource"
          operations:
            - method: "POST"
              name: "query-db"
              label: "Query Database"
              body: |
                {
                  "filter": {
                    "property": "Participation Status",
                    "select": {
                      "equals": "Committed"
                    }
                  }
                }
```
You can execute an MCP request on http://localhost:9091 and you should be able to execute the tool.