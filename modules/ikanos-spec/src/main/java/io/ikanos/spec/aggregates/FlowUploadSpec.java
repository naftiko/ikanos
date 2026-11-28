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
package io.ikanos.spec.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Flow Upload Specification Element (experimental).
 *
 * <p>Says that the file inputs of a flow are stored by an upstream that issues presigned upload
 * URLs. {@code presign} asks that upstream for a URL per file, and {@code verify} confirms that the
 * bytes arrived before the flow runs. The flow then sees each file with an {@code uploadId}, the
 * server-chosen name of the stored object, instead of its bytes.</p>
 */
public class FlowUploadSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile UploadPresignSpec presign;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile UploadVerifySpec verify;

    public FlowUploadSpec() {}

    public FlowUploadSpec(UploadPresignSpec presign, UploadVerifySpec verify) {
        this.presign = presign;
        this.verify = verify;
    }

    /** @return the call that returns a presigned upload URL for one file */
    public UploadPresignSpec getPresign() {
        return presign;
    }

    public void setPresign(UploadPresignSpec presign) {
        this.presign = presign;
    }

    /** @return the call that confirms the bytes of one file arrived */
    public UploadVerifySpec getVerify() {
        return verify;
    }

    public void setVerify(UploadVerifySpec verify) {
        this.verify = verify;
    }
}
