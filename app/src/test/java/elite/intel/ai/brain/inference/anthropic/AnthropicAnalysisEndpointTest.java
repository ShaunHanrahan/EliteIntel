package elite.intel.ai.brain.inference.anthropic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import elite.intel.ai.brain.actions.handlers.queries.struct.AiData;
import elite.intel.ai.brain.actions.handlers.queries.struct.AiDataStruct;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Request-shape and response-handling coverage for the Anthropic analysis endpoint (no network).
 */
class AnthropicAnalysisEndpointTest {

    private final AnthropicAnalysisEndpoint endpoint = AnthropicAnalysisEndpoint.getInstance();

    /** "General Chat" takes the endpoint's built-in minimal prompt, so no prompt-factory state is needed. */
    private static AiData generalChat() {
        return new AiDataStruct("General Chat", () -> "");
    }

    @Test
    void buildsAHaiku55RequestThatEndsOnAUserTurn() {
        JsonObject body = endpoint.buildRequest("hello", generalChat());

        assertEquals("claude-haiku-5-5", body.get("model").getAsString());
        assertEquals(2048, body.get("max_tokens").getAsInt(), "thinking counts toward max_tokens");
        assertEquals(AnthropicClient.ANALYSIS_EFFORT, body.getAsJsonObject("output_config").get("effort").getAsString());
        assertFalse(body.has("temperature"), "Haiku 5.5 rejects any temperature but its default");
        assertFalse(body.has("thinking"));
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals("user", messages.get(messages.size() - 1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void readsTheAnswerPastALeadingThinkingBlock() {
        JsonObject answer = endpoint.interpret(response("end_turn",
                thinkingBlock(), textBlock("{\"text_to_speech_response\":\"Docked.\"}")));

        assertEquals("Docked.", answer.get("text_to_speech_response").getAsString());
    }

    @Test
    void refusalAndTruncationEndInTheSpokenError() {
        for (String stop : List.of("refusal", "max_tokens")) {
            // The partial text is not valid JSON: parsing it would throw, so this also proves it is never parsed.
            JsonObject answer = endpoint.interpret(response(stop, textBlock("{\"text_to_speech_response\":\"Dock")));

            assertEquals("Analysis failed – check logs", answer.get("text_to_speech_response").getAsString(), stop);
        }
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static JsonObject thinkingBlock() {
        JsonObject block = new JsonObject();
        block.addProperty("type", "thinking");
        block.addProperty("thinking", "");
        block.addProperty("signature", "sig");
        return block;
    }

    private static JsonObject response(String stopReason, JsonObject... blocks) {
        JsonArray content = new JsonArray();
        for (JsonObject block : blocks) {
            content.add(block);
        }
        JsonObject response = new JsonObject();
        response.add("content", content);
        response.addProperty("stop_reason", stopReason);
        return response;
    }
}
