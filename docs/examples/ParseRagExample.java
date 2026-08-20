/*
 * ParseRAG — parse a PDF from Java, with OkHttp and Jackson.
 *
 *   Dependencies:
 *     com.squareup.okhttp3:okhttp:4.12.0
 *     com.fasterxml.jackson.core:jackson-databind:2.18.2
 *
 *   Run:
 *     export API_KEY="your-rapidapi-key"
 *     java -cp "okhttp.jar:okio.jar:kotlin-stdlib.jar:jackson-databind.jar:\
 *               jackson-core.jar:jackson-annotations.jar:." ParseRagExample document.pdf
 *
 * BASE_URL defaults to a local run, which expects the self-hosted X-API-Key header.
 * Through the marketplace: BASE_URL=https://parserag.p.rapidapi.com.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.time.Duration;

public class ParseRagExample {

    private static final String BASE_URL =
            System.getenv().getOrDefault("BASE_URL", "http://localhost:8080");
    private static final String API_KEY = System.getenv("API_KEY");

    private static final MediaType PDF = MediaType.parse("application/pdf");

    /** Through RapidAPI the key travels as X-RapidAPI-Key; a self-hosted instance expects X-API-Key. */
    private static final String KEY_HEADER =
            BASE_URL.contains("rapidapi.com") ? "X-RapidAPI-Key" : "X-API-Key";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /*
     * Parsing is synchronous, and a large scanned document goes through a vision model page by
     * page: the default 10 s read timeout would cut the call long before the server is done.
     */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofMinutes(5))
            .build();

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java ParseRagExample <file.pdf>");
            System.exit(1);
        }
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("Set API_KEY first: export API_KEY=your-api-key");
            System.exit(1);
        }

        JsonNode result = parse(new File(args[0]));

        System.out.printf("%s — %d pages, language %s, %d ms%n",
                result.get("document_id").asText(),
                result.get("pages").asInt(),
                result.get("language").asText(),
                result.get("processing_ms").asLong());

        int flagged = 0;
        for (JsonNode chunk : result.get("chunks")) {
            // Chunks flagged for review came out of a page the extractor is unsure about:
            // index them separately, or not at all.
            boolean review = chunk.get("manual_review_needed").asBoolean();
            if (review) flagged++;

            String preview = chunk.get("text").asText().replace('\n', ' ');
            preview = preview.substring(0, Math.min(80, preview.length()));

            System.out.printf("  p%-3d %-15s conf=%.2f%s  %s%n",
                    chunk.get("page").asInt(),
                    chunk.get("type").asText(),
                    chunk.get("confidence").asDouble(),
                    review ? " [review]" : "",
                    preview);

            if ("TABLE".equals(chunk.get("type").asText())) {
                JsonNode table = chunk.get("table_json");
                System.out.printf("       table: %d rows x %d columns%n",
                        table.get("rows").size(), table.get("headers").size());
            }
        }

        System.out.printf("%n%d chunks, %d flagged for manual review%n",
                result.get("chunks").size(), flagged);

    }

    /** Uploads a PDF and returns the parsed response, or throws with the API error code. */
    private static JsonNode parse(File pdf) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", pdf.getName(), RequestBody.create(pdf, PDF))
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/v1/parse")
                .addHeader(KEY_HEADER, API_KEY)
                .post(body)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            // Present on every response, 429 or not: pace yourself without waiting for a rejection.
            String remaining = response.header("x-ratelimit-requests-remaining");
            if (remaining != null) {
                System.err.println("[rate limit] " + remaining + " requests left this minute");
            }
            return readOrThrow(response);
        }
    }

    /**
     * Every ParseRAG error shares one shape: {"error": CODE, "message": ..., "status": int}.
     * Branch on the code, which is stable — never on the message.
     */
    private static JsonNode readOrThrow(Response response) throws IOException {
        ResponseBody body = response.body();
        JsonNode json = MAPPER.readTree(body != null ? body.string() : "{}");

        if (response.isSuccessful()) {
            return json;
        }

        String code = json.path("error").asText("UNKNOWN");
        String message = json.path("message").asText("");

        // Quota exhausted (from the marketplace) or traffic guard (from ParseRAG). Only the
        // second is worth retrying, and it says how long to wait.
        if (response.code() == 429) {
            String retryAfter = response.header("Retry-After");
            if (retryAfter != null) {
                throw new IOException(code + ": " + message + " (retry after " + retryAfter + "s)");
            }
            throw new IOException("Quota exhausted - check your plan on RapidAPI");
        }

        throw new IOException(code + ": " + message);
    }
}
