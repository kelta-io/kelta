package io.kelta.jsonapi;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Redacts sensitive values from HTTP bodies before they are recorded as observability data
 * (span attributes, debug logs). Shared by the gateway and the worker so both apply the same rules.
 *
 * <p>Rules, applied to a JSON body:
 * <ul>
 *   <li><b>Key-based.</b> A member whose key names secret material has every string value in its
 *       subtree replaced with {@value #REDACTED}. Keys are normalised (lower-cased, separators
 *       removed) and match when they <em>contain</em> one of {@link #SENSITIVE_KEY_WORDS}, so
 *       {@code clientSecret}, {@code client_secret}, {@code refreshToken} and {@code x-api-key} are
 *       all covered; {@code ssn} must match exactly. Numbers, booleans and nulls are kept (they
 *       carry counters such as token usage, not secret material).</li>
 *   <li><b>Value-based.</b> Any string, regardless of key, has embedded personal access tokens
 *       ({@code klt_…}) and JWTs (three base64url segments starting {@code eyJ}) replaced.</li>
 *   <li><b>Redact, then truncate.</b> The body is streamed token-by-token into a redacted copy and
 *       cut at the size limit afterwards, so a cut can never land inside an unredacted value. Only
 *       as much input as needed to fill the limit is parsed.</li>
 *   <li><b>Fail closed.</b> A body that is not valid JSON yields {@link Optional#empty()}; callers
 *       record its size only, never its content.</li>
 * </ul>
 *
 * <p>Uses only the Jackson streaming API (no reflection), so no native-image hints are needed.
 */
public final class SensitiveBodyRedactor {

    /** Replacement written in place of a redacted value. */
    public static final String REDACTED = "[REDACTED]";

    /** Normalised key fragments that mark a member as secret material. */
    static final List<String> SENSITIVE_KEY_WORDS = List.of(
            "password", "passwd", "secret", "token", "apikey", "authorization",
            "creditcard", "privatekey", "cookie");

    /** Normalised keys that are only sensitive as an exact match (to avoid e.g. {@code classname}). */
    static final List<String> SENSITIVE_EXACT_KEYS = List.of("ssn");

    /**
     * Path segments whose request and response bodies are never captured: these endpoints accept
     * secrets under generic keys (for example a credential's {@code value} or {@code data}) that a
     * key-based rule cannot recognise.
     */
    static final List<String> SENSITIVE_PATH_SEGMENTS = List.of("credentials");

    private static final Pattern TOKEN_VALUE_PATTERN = Pattern.compile(
            "klt_[A-Za-z0-9_-]{8,}"
                    + "|eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private SensitiveBodyRedactor() {
    }

    /**
     * A redacted body, possibly cut at the size limit.
     *
     * @param body      the redacted (and possibly truncated) JSON text
     * @param truncated whether the redacted text was cut at the limit
     */
    public record Result(String body, boolean truncated) {
    }

    /**
     * Redacts a JSON body and truncates the redacted text to {@code maxChars}.
     *
     * @param content  raw UTF-8 body bytes
     * @param maxChars maximum length of the returned text
     * @return the redacted body, or empty when {@code content} is empty or not valid JSON
     */
    public static Optional<Result> redactJson(byte[] content, int maxChars) {
        if (content == null || content.length == 0 || maxChars <= 0) {
            return Optional.empty();
        }
        StringWriter out = new StringWriter(Math.min(content.length, maxChars) + 64);
        try (JsonParser parser = MAPPER.createParser(content);
             JsonGenerator gen = MAPPER.createGenerator(out)) {
            boolean truncated = copyRedacted(parser, gen, out, maxChars);
            gen.flush();
            String text = out.toString();
            if (truncated || text.length() > maxChars) {
                return Optional.of(new Result(text.substring(0, Math.min(text.length(), maxChars)), true));
            }
            return Optional.of(new Result(text, false));
        } catch (JacksonException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    /** Convenience overload for an already-decoded body. */
    public static Optional<Result> redactJson(String content, int maxChars) {
        if (content == null) {
            return Optional.empty();
        }
        return redactJson(content.getBytes(StandardCharsets.UTF_8), maxChars);
    }

    /** Whether a member key names secret material (see class docs for the matching rules). */
    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        StringBuilder normalised = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalised.append(Character.toLowerCase(c));
            }
        }
        String k = normalised.toString();
        if (SENSITIVE_EXACT_KEYS.contains(k)) {
            return true;
        }
        for (String word : SENSITIVE_KEY_WORDS) {
            if (k.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Replaces any embedded token-shaped substrings (PATs, JWTs) in a string value. */
    public static String redactTokenValues(String value) {
        if (value == null || value.length() < 12) {
            return value;
        }
        return TOKEN_VALUE_PATTERN.matcher(value).replaceAll(REDACTED);
    }

    /**
     * Whether a request path addresses an endpoint whose bodies must never be captured.
     * Matches whole path segments, case-insensitively, anywhere in the path.
     */
    public static boolean isSensitivePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String segment : path.toLowerCase(Locale.ROOT).split("/")) {
            if (SENSITIVE_PATH_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Streams one root JSON value from {@code parser} to {@code gen}, redacting as it goes.
     *
     * @return {@code true} if copying stopped early because the output reached {@code maxChars}
     */
    private static boolean copyRedacted(JsonParser parser, JsonGenerator gen, StringWriter out, int maxChars) {
        // One frame per open container: whether it is an object, and whether its subtree is sensitive.
        Deque<boolean[]> frames = new ArrayDeque<>();
        String pendingKey = null;

        JsonToken token = parser.nextToken();
        if (token == null) {
            throw new IllegalStateException("empty body");
        }
        while (token != null) {
            boolean[] parent = frames.peek();
            boolean parentSensitive = parent != null && parent[1];
            boolean keySensitive = parent != null && parent[0] && isSensitiveKey(pendingKey);

            switch (token) {
                case START_OBJECT -> {
                    frames.push(new boolean[]{true, parentSensitive || keySensitive});
                    gen.writeStartObject();
                }
                case START_ARRAY -> {
                    frames.push(new boolean[]{false, parentSensitive || keySensitive});
                    gen.writeStartArray();
                }
                case END_OBJECT -> {
                    frames.pop();
                    gen.writeEndObject();
                }
                case END_ARRAY -> {
                    frames.pop();
                    gen.writeEndArray();
                }
                case PROPERTY_NAME -> {
                    pendingKey = parser.currentName();
                    gen.writeName(pendingKey);
                }
                case VALUE_STRING -> {
                    if (parentSensitive || keySensitive) {
                        gen.writeString(REDACTED);
                    } else {
                        gen.writeString(redactTokenValues(parser.getString()));
                    }
                }
                default -> gen.copyCurrentEvent(parser);
            }
            if (token != JsonToken.PROPERTY_NAME) {
                pendingKey = null;
            }

            if (frames.isEmpty()) {
                // Root value complete; anything after it makes the body invalid JSON.
                if (parser.nextToken() != null) {
                    throw new IllegalStateException("trailing content after root value");
                }
                return false;
            }

            gen.flush();
            if (out.getBuffer().length() > maxChars) {
                return true;
            }
            token = parser.nextToken();
        }
        // Input ended with containers still open.
        throw new IllegalStateException("unterminated JSON");
    }
}
