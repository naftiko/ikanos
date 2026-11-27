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
package io.ikanos.spec;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An icon a client may show for the capability, in the shape of the MCP {@code Icon}: an image
 * URL, with an optional media type, sizes and theme. Used by MCP Server Cards (SEP-2127).
 */
public class IconSpec {

    private final AtomicReference<String> src = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<String> mimeType = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final AtomicReference<List<String>> sizes = new AtomicReference<>(List.of());

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<String> theme = new AtomicReference<>();

    public IconSpec() {
        // for Jackson
    }

    public IconSpec(String src) {
        this.src.set(src);
    }

    /** @return the image URL, {@code https:} or {@code data:} */
    public String getSrc() {
        return src.get();
    }

    public void setSrc(String src) {
        this.src.set(src);
    }

    /** @return the image media type, such as {@code image/png}, or {@code null} */
    public String getMimeType() {
        return mimeType.get();
    }

    public void setMimeType(String mimeType) {
        this.mimeType.set(mimeType);
    }

    /** @return the sizes the image fits, such as {@code 48x48} or {@code any}; never null */
    public List<String> getSizes() {
        return sizes.get();
    }

    public void setSizes(List<String> sizes) {
        this.sizes.set(sizes != null ? List.copyOf(sizes) : List.of());
    }

    /** @return {@code light} or {@code dark}, the background the icon is designed for, or null */
    public String getTheme() {
        return theme.get();
    }

    public void setTheme(String theme) {
        this.theme.set(theme);
    }
}
