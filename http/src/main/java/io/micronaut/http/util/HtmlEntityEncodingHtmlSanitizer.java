/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.util;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Given an HTML string, it encodes the following characters: {@code &} to {@code &amp;}, {@code <} to {@code &lt;}, {@code >} to {@code &gt;}, {@code "} to {@code &quot;}, and {@code '} to {@code &#x27;}.
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html">Cross site Scripting Prevention Cheat Sheet</a>
 */
@Singleton
@Requires(missingBeans = HtmlSanitizer.class)
public class HtmlEntityEncodingHtmlSanitizer implements HtmlSanitizer {
    private final Map<String, String> encodedMap;

    public HtmlEntityEncodingHtmlSanitizer() {
        encodedMap = new LinkedHashMap<>();
        encodedMap.put("&", "&amp;");
        encodedMap.put("<", "&lt;");
        encodedMap.put(">", "&gt;");
        encodedMap.put("\"", "&quot;");
        encodedMap.put("'", "&#x27;");
    }

    @Override
    @NonNull
    public String sanitize(@Nullable String html) {
        if (html == null) {
            return "";
        }
        String sanitized = html;
        for (Map.Entry<String, String> entry : encodedMap.entrySet()) {
            sanitized = sanitized.replaceAll(entry.getKey(), entry.getValue());
        }
        return sanitized;
    }
}
