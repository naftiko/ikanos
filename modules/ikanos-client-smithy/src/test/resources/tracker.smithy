$version: "2"

namespace example.tracker

use aws.auth#sigv4
use aws.protocols#restJson1

/// An issue tracker, answered in-process by the tests. The signing name of the sigv4 trait
/// differs from the namespace, so a test can tell where the signing name came from.
@restJson1
@sigv4(name: "issues")
service Tracker {
    version: "2024-01-01"
    operations: [GetIssue, CreateIssue, SlowCall, SlowBodyCall, EchoHeaders, DownloadAttachment,
        UploadAttachment, WatchIssues]
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

        review: Review
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

/// The response head comes at once, the body only after a delay.
@readonly
@http(method: "GET", uri: "/slow-body")
operation SlowBodyCall {
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
        signatureValid: Boolean
    }
}

/// A streaming blob output, which the adapter refuses at load time.
@readonly
@http(method: "GET", uri: "/attachments/{attachmentId}")
operation DownloadAttachment {
    input := {
        @required
        @httpLabel
        attachmentId: String
    }
    output := {
        @required
        @httpPayload
        data: AttachmentData
    }
}

/// A streaming blob input, which the adapter refuses at load time.
@http(method: "PUT", uri: "/attachments/{attachmentId}")
operation UploadAttachment {
    input := {
        @required
        @httpLabel
        attachmentId: String

        @required
        @httpPayload
        data: AttachmentData
    }
    output := {
        size: Long
    }
}

@streaming
blob AttachmentData

/// An event stream output, which the adapter refuses at load time.
@readonly
@http(method: "GET", uri: "/events")
operation WatchIssues {
    output := {
        @httpPayload
        events: IssueEvents
    }
}

@streaming
union IssueEvents {
    changed: IssueChanged
}

structure IssueChanged {
    id: String
}

structure Details {
    assignee: String
    estimate: BigDecimal
}

structure Review {
    @required
    reviewer: String

    note: String
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
