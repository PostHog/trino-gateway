/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.handler;

/**
 * Keeps catalog properties out of the query text the Gateway records. A {@code CREATE CATALOG}
 * statement carries connector configuration, typically including a password, and the Gateway
 * stores each query's text in its history and writes it to logs. Text that may hold such a
 * statement is recorded as a fixed placeholder instead. The request forwarded to Trino is never
 * changed.
 */
public final class QueryTextRedaction
{
    public static final String REDACTED_STATEMENT = "<statement redacted: it may define catalog properties>";

    private QueryTextRedaction() {}

    /**
     * The text to record for a query: the text itself, or the placeholder when it may define
     * catalog properties.
     */
    public static String recordedText(String text)
    {
        if (text == null || !mayDefineCatalogProperties(text)) {
            return text;
        }
        return REDACTED_STATEMENT;
    }

    /**
     * True if the word CREATE is followed anywhere later by the word CATALOG. It is applied to the
     * whole text, string literals, comments and request JSON included, so that it also sees a
     * statement executed from a string, and it errs on the side of matching. One linear pass,
     * since any client can send text to this check.
     */
    public static boolean mayDefineCatalogProperties(String text)
    {
        int length = text.length();
        boolean seenCreate = false;
        int position = 0;
        while (position < length) {
            if (!isWordCharacter(text.charAt(position))) {
                position++;
                continue;
            }
            int start = position;
            while (position < length && isWordCharacter(text.charAt(position))) {
                position++;
            }
            int wordLength = position - start;
            if (seenCreate && wordLength == 7 && text.regionMatches(true, start, "CATALOG", 0, 7)) {
                return true;
            }
            seenCreate |= wordLength == 6 && text.regionMatches(true, start, "CREATE", 0, 6);
        }
        return false;
    }

    private static boolean isWordCharacter(char character)
    {
        return character == '_' || (character < 128 && Character.isLetterOrDigit(character));
    }
}
