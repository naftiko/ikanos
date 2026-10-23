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
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.util.Resolver;

public class PathSegmentEncoderTest {

    @Test
    public void encodeShouldKeepPathCharactersAndEncodeTheRest() {
        assertEquals("a%2Fb%3Fc%23d%25e%20f-._~!$&'()*+,;=:@",
                PathSegmentEncoder.encode("a/b?c#d%e f-._~!$&'()*+,;=:@"));
    }

    @Test
    public void encodeShouldWriteNonAsciiAsUtf8PercentBytes() {
        assertEquals("caf%C3%A9%F0%9F%9A%80", PathSegmentEncoder.encode("caf\u00e9\uD83D\uDE80"));
    }

    @Test
    public void encodeQueryComponentShouldEncodeParameterDelimiters() {
        assertEquals("a%26b%3Dc%2Bd%3Be%23f", PathSegmentEncoder.encodeQueryComponent("a&b=c+d;e#f"));
    }

    @Test
    public void encodeMarkedValuesShouldEncodeValuesAfterLiteralQuestionMarkAsQueryComponents() {
        String rendered = Resolver.resolveMustacheTemplate(
                "https://api.example.com/things/{{id}}?status={{status}}",
                Map.of("id", "a/b", "status", "open&admin=true"), PathSegmentEncoder::mark);

        assertEquals("https://api.example.com/things/a%2Fb?status=open%26admin%3Dtrue",
                PathSegmentEncoder.encodeMarkedValues(rendered),
                "A value in a query template must not add a query parameter");
    }

    @Test
    public void encodeMarkedValuesShouldKeepQuestionMarkInsideValueInPathPosition() {
        String rendered = Resolver.resolveMustacheTemplate("/things/{{id}}/{{child}}",
                Map.of("id", "a?b", "child", "c"), PathSegmentEncoder::mark);

        assertEquals("/things/a%3Fb/c", PathSegmentEncoder.encodeMarkedValues(rendered),
                "A '?' inside a value must not switch later values to query encoding");
    }

    @Test
    public void markShouldDropForgedMarkersFromCallerValue() {
        String forged = "x" + PathSegmentEncoder.VALUE_END + "/../admin"
                + PathSegmentEncoder.VALUE_START + "y";
        String rendered = Resolver.resolveMustacheTemplate("/pages/{{id}}", Map.of("id", forged),
                PathSegmentEncoder::mark);

        assertEquals("/pages/x%2F..%2Fadminy", PathSegmentEncoder.encodeMarkedValues(rendered),
                "A caller value must not be able to close its own marker");
    }

    @Test
    public void encodeMarkedValuesShouldReturnUnmarkedUriUnchanged() {
        assertEquals("https://api.example.com/v1/users/me",
                PathSegmentEncoder.encodeMarkedValues("https://api.example.com/v1/users/me"));
    }
}
