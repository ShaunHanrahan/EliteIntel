package elite.intel.ai.brain.inference.anthropic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import elite.intel.ai.brain.AiTransportResult;
import elite.intel.ai.brain.BaseAiClient;
import elite.intel.ai.brain.Client;
import elite.intel.eventbus.UiBus;
import elite.intel.session.SystemSession;
import elite.intel.ui.event.AppLogEvent;
import elite.intel.ui.event.LlmUsageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class AnthropicClient extends BaseAiClient implements Client {

    private static final Logger log = LogManager.getLogger(AnthropicClient.class);

    public static final String MODEL_COMMAND_MODEL = "claude-haiku-5-5";
    public static final String MODEL_ANALYSIS_MODEL = "claude-haiku-5-5";

    /**
     * {@code output_config.effort} per request path. Haiku 5.5 thinks adaptively by default (at "medium"); "low"
     * keeps voice replies fast. Raise one of these if its answers get too shallow.
     */
    public static final String COMMAND_EFFORT = "low";
    public static final String ANALYSIS_EFFORT = "low";

    /** {@code stop_reason} values whose content must not be used: a declined request, or output cut off at max_tokens. */
    public static final String STOP_REFUSAL = "refusal";
    public static final String STOP_MAX_TOKENS = "max_tokens";

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    // Thinking tokens count toward max_tokens, so the analysis path leaves room for them ahead of its JSON answer.
    private static final int ANALYSIS_MAX_TOKENS = 2048;

    private static final AnthropicClient INSTANCE = new AnthropicClient();

    private AnthropicClient() {
        // singleton
    }

    public static AnthropicClient getInstance() {
        return INSTANCE;
    }

    @Override public JsonObject createPrompt(int model, float temp) {
        return createPrompt(model == 1 ? MODEL_COMMAND_MODEL : MODEL_ANALYSIS_MODEL, temp);
    }

    @Override
    public JsonObject createPrompt(String model, float temp) {
        // temp is ignored: Haiku 5.5 rejects any temperature other than its default with HTTP 400.
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("max_tokens", ANALYSIS_MAX_TOKENS);
        request.add("output_config", outputConfig(ANALYSIS_EFFORT));
        return request;
    }

    /**
     * An {@code output_config} carrying the given effort. {@code thinking} is deliberately left unset, so Haiku 5.5
     * runs adaptive thinking and the effort decides how much of it there is.
     */
    public static JsonObject outputConfig(String effort) {
        JsonObject config = new JsonObject();
        config.addProperty("effort", effort);
        return config;
    }

    @Override
    public JsonObject createErrorResponse(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("text_to_speech_response", message);
        return err;
    }

    /**
     * Sends a VEGA request without converting a transport failure into legacy speech JSON.
     */
    public AiTransportResult sendVegaRequest(String request) {
        long t0 = System.nanoTime();
        AiTransportResult outcome = sendTransportRequest(buildRequest(request));
        if (outcome instanceof AiTransportResult.Success success) {
            reportResponse(success.response(), System.nanoTime() - t0);
        }
        return outcome;
    }

    @Override
    public JsonObject sendJsonRequest(String request) {
        try {
            long t0 = System.nanoTime();
            JsonObject response = super.sendJsonRequest(buildRequest(request));
            reportResponse(response, System.nanoTime() - t0);
            return response;
        } catch (Exception e) {
            log.error("Request failed", e);
            return createErrorResponse(e.getMessage());
        }
    }

    private void reportResponse(JsonObject response, long elapsed) {
        if (response != null && response.has("usage")) {
            JsonObject usage = response.getAsJsonObject("usage");
            int in = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
            int out = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
            int cached = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").getAsInt() : 0;
            int written = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").getAsInt() : 0;
            UiBus.publish(new AppLogEvent(
                    "Claude – in= " + in + " out= " + out +
                            (cached > 0 ? " cache_read= " + cached : "") +
                            (written > 0 ? " cache_written= " + written : "") +
                            " tokens"));
            String model = response.has("model") ? response.get("model").getAsString() : MODEL_COMMAND_MODEL;
            UiBus.publish(new LlmUsageEvent("Claude", model, in, out, cached, written, wallClockTps(elapsed, out)));
        }
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("x-api-key", SystemSession.getInstance().getAiApiKey())
                .header("anthropic-version", API_VERSION)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * The text of the first {@code text} block, or null. Read by type, not position: a Haiku 5.5 reply can open
     * with a {@code thinking} block.
     */
    public String extractText(JsonObject response) {
        try {
            for (JsonElement element : response.getAsJsonArray("content")) {
                JsonObject block = element.getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                    return block.get("text").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
