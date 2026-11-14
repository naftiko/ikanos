/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * #957 review, point 4: a path value with {@code ../} cannot leave the forwarded prefix of an S3
 * bucket. {@link PathSegmentEncoder} sends the value as one segment, {@code ..%2F}, and the
 * signature names the literal key S3 and MinIO read, with the {@code ..} kept: AWS keeps keys
 * literal and MinIO refuses {@code ..}, so neither resolves it to another object.
 */
class AwsSigV4EncodedPathTest {

    private static final String TEMPLATE = "/bucket/public/";

    private static String sentPath(String value) {
        return PathSegmentEncoder.encodeMarkedValues(TEMPLATE + PathSegmentEncoder.mark(value));
    }

    @Test
    void sendsADotDotSlashValueAsOneEncodedSegment() {
        assertEquals("/bucket/public/..%2Fprivate%2Fpayroll.csv",
                sentPath("../private/payroll.csv"));
    }

    @Test
    void signsTheLiteralKeyForS3WithoutResolvingTheDotDot() {
        assertEquals("/bucket/public/../private/payroll.csv",
                AwsSigV4Helper.getCanonicalUri(sentPath("../private/payroll.csv"), false));
    }

    @Test
    void signsTheEncodedSegmentForTheOtherServicesWithoutResolvingIt() {
        assertEquals("/bucket/public/..%252Fprivate%252Fpayroll.csv",
                AwsSigV4Helper.getCanonicalUri(sentPath("../private/payroll.csv"), true));
    }

    /** An already-encoded slash in the value is encoded again, so it never becomes a separator. */
    @Test
    void sendsAnEncodedSlashInAValueAsData() {
        assertEquals("/bucket/public/..%252Fprivate", sentPath("..%2Fprivate"));
        assertEquals("/bucket/public/..%252Fprivate",
                AwsSigV4Helper.getCanonicalUri(sentPath("..%2Fprivate"), false));
    }
}
