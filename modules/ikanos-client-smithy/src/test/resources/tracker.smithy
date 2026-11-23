$version: "2"

namespace example.tracker

use aws.protocols#restJson1

/// An issue tracker, answered in-process by the tests.
@restJson1
service Tracker {
    version: "2024-01-01"
    operations: [GetIssue, CreateIssue, SlowCall, EchoHeaders]
    errors: [ServerFault]
}

@readonly
@http(method: "GET", uri: "/issues/{issueId}")
operation GetIssue {
    input := {
        @required
        @httpLabel
        issueId: String

        @httpQuery("verbose")
        verbose: Boolean
    }
    output := {
        issue: Issue
    }
    errors: [IssueNotFound]
}

@http(method: "POST", uri: "/projects/{projectId}/issues")
operation CreateIssue {
    input := {
        @required
        @httpLabel
        projectId: String

        @httpQuery("notify")
        notify: Boolean

        @required
        title: String

        @jsonName("prio")
        priority: Integer

        details: Details

        labels: LabelMap

        fields: FieldMap
    }
    output := {
        @required
        issue: Issue

        received: String
    }
}

@readonly
@http(method: "GET", uri: "/slow")
operation SlowCall {
    output := {
        done: Boolean
    }
}

@readonly
@http(method: "GET", uri: "/headers")
operation EchoHeaders {
    output := {
        authorization: String
        tenant: String
        traceparent: Boolean
    }
}

structure Details {
    assignee: String
    estimate: BigDecimal
}

map LabelMap {
    key: String
    value: String
}

/// Custom fields, typed like DynamoDB attribute values: a map of unions.
map FieldMap {
    key: String
    value: FieldValue
}

union FieldValue {
    text: String
    number: Integer
}

structure Issue {
    @required
    id: String

    title: String

    priority: Integer

    createdAt: Timestamp

    attachment: Blob

    points: BigInteger

    ratio: BigDecimal
}

@error("client")
@httpError(404)
structure IssueNotFound {
    message: String
}

@error("server")
structure ServerFault {
    message: String
}
