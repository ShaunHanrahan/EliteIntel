package elite.intel.ai.brain.vega.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import elite.intel.ai.brain.actions.ActionParameterSpec;
import elite.intel.ai.brain.inference.anthropic.AnthropicClient;
import elite.intel.ai.brain.vega.llm.AnthropicLlmAdapter;
import elite.intel.ai.brain.vega.model.llm.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Render and parse coverage for the native Anthropic Messages-API adapter.
 */
class AnthropicLlmAdapterTest {

    private final AnthropicLlmAdapter adapter = new AnthropicLlmAdapter();

    private static LlmToolDefinition speakTool() {
        return new LlmToolDefinition("speak", "Speak to the commander", "",
                List.of(new ActionParameterSpec("text", "string", true, "the words", List.of(), null)));
    }

    private static LlmRequest request(List<LlmMessage> messages, List<LlmToolDefinition> tools) {
        return new LlmRequest("req-1", messages, tools, PromptCacheProfile.COMMANDER);
    }

    @Test
    void hoistsSystemToTopLevelAndKeepsUserInMessages() {
        JsonObject json = JsonParser.parseString(adapter.buildRequestBody(request(
                List.of(LlmMessage.of(LlmMessageRole.SYSTEM, "rules"),
                        LlmMessage.of(LlmMessageRole.USER, "say hi")),
                List.of(speakTool())))).getAsJsonObject();

        assertEquals("claude-haiku-5-5", json.get("model").getAsString());
        assertTrue(json.has("max_tokens"), "Anthropic requires max_tokens");
        JsonArray system = json.getAsJsonArray("system");
        assertEquals(1, system.size());
        assertEquals("rules", system.get(0).getAsJsonObject().get("text").getAsString());

        JsonArray messages = json.getAsJsonArray("messages");
        assertEquals(1, messages.size(), "the system message must not appear in messages");
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void cachesToolsAndSystemWithOneBreakpointOnTheSystemBlock() {
        JsonObject json = JsonParser.parseString(adapter.buildRequestBody(request(
                List.of(LlmMessage.of(LlmMessageRole.SYSTEM, "rules"),
                        LlmMessage.of(LlmMessageRole.USER, "say hi")),
                List.of(speakTool())))).getAsJsonObject();

        JsonObject systemBlock = json.getAsJsonArray("system").get(0).getAsJsonObject();
        assertEquals("ephemeral", systemBlock.getAsJsonObject("cache_control").get("type").getAsString());
        // A top-level marker would land after the per-request user turn: a write on every call, never a read.
        assertFalse(json.has("cache_control"));
        assertFalse(json.getAsJsonArray("tools").get(0).getAsJsonObject().has("cache_control"));
        assertFalse(json.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").isJsonArray(),
                "the user turn stays a plain string with no marker");
    }

    @Test
    void sendsEffortAndNoSamplingOrThinkingParameters() {
        JsonObject json = JsonParser.parseString(adapter.buildRequestBody(request(
                List.of(LlmMessage.of(LlmMessageRole.USER, "hi")), List.of(speakTool())))).getAsJsonObject();

        assertFalse(json.has("temperature"), "Haiku 5.5 rejects any temperature but its default");
        assertFalse(json.has("top_p"));
        assertFalse(json.has("top_k"));
        assertFalse(json.has("thinking"), "thinking stays unset so the model runs adaptive");
        assertEquals(AnthropicClient.COMMAND_EFFORT,
                json.getAsJsonObject("output_config").get("effort").getAsString());
    }

    @Test
    void buildsToolsWithInputSchemaAndForcedToolChoice() {
        JsonObject json = JsonParser.parseString(adapter.buildRequestBody(request(
                List.of(LlmMessage.of(LlmMessageRole.USER, "hi")), List.of(speakTool())))).getAsJsonObject();

        assertEquals("any", json.getAsJsonObject("tool_choice").get("type").getAsString());
        JsonObject tool = json.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("speak", tool.get("name").getAsString());
        JsonObject schema = tool.getAsJsonObject("input_schema");
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean(), "tool schemas must be closed");
        assertEquals("string", schema.getAsJsonObject("properties").getAsJsonObject("text").get("type").getAsString());
        assertEquals("text", schema.getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void omitsToolsAndChoiceWhenNoToolsOffered() {
        JsonObject json = JsonParser.parseString(adapter.buildRequestBody(request(
                List.of(LlmMessage.of(LlmMessageRole.USER, "summarize")), List.of()))).getAsJsonObject();

        assertFalse(json.has("tools"));
        assertFalse(json.has("tool_choice"));
    }

    @Test
    void rendersAssistantToolUseAndCoalescesConsecutiveToolResults() {
        JsonObject argsA = new JsonObject();
        argsA.addProperty("text", "hi");
        String firstId = "da2ea247-86cc-45d2-a272-9e99af14a88d";
        String secondId = "gateway-classify-1";
        LlmRequest req = request(List.of(
                LlmMessage.of(LlmMessageRole.USER, "go"),
                LlmMessage.assistantToolCalls(List.of(
                        new LlmToolInvocation(firstId, "speak", argsA),
                        new LlmToolInvocation(secondId, "scan", new JsonObject()))),
                LlmMessage.toolResult(firstId, "{\"status\":\"spoken\"}"),
                LlmMessage.toolResult(secondId, "{\"status\":\"scanned\"}")), List.of());

        JsonArray messages = JsonParser.parseString(adapter.buildRequestBody(req)).getAsJsonObject()
                .getAsJsonArray("messages");

        // user(go), assistant(2 tool_use), user(2 tool_result coalesced) - three turns, alternating roles.
        assertEquals(3, messages.size());
        JsonObject assistant = messages.get(1).getAsJsonObject();
        assertEquals("assistant", assistant.get("role").getAsString());
        JsonArray blocks = assistant.getAsJsonArray("content");
        assertEquals("tool_use", blocks.get(0).getAsJsonObject().get("type").getAsString());
        String firstWireId = blocks.get(0).getAsJsonObject().get("id").getAsString();
        String secondWireId = blocks.get(1).getAsJsonObject().get("id").getAsString();
        assertTrue(firstWireId.matches("[A-Za-z0-9]{9}"));
        assertTrue(secondWireId.matches("[A-Za-z0-9]{9}"));
        assertNotEquals(firstWireId, secondWireId);
        assertEquals("hi", blocks.get(0).getAsJsonObject().getAsJsonObject("input").get("text").getAsString());

        JsonObject toolTurn = messages.get(2).getAsJsonObject();
        assertEquals("user", toolTurn.get("role").getAsString());
        JsonArray results = toolTurn.getAsJsonArray("content");
        assertEquals(2, results.size(), "consecutive tool results must coalesce into one user turn");
        assertEquals("tool_result", results.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(firstWireId, results.get(0).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals(secondWireId, results.get(1).getAsJsonObject().get("tool_use_id").getAsString());
    }

    @Test
    void historyAndRepairContinuationStillEndOnAUserTurn() {
        // Haiku 5.5 rejects a request ending in an assistant turn (prefill). Replayed history and the gateway's
        // repair continuation (rejected tool_use + its tool_result) must both leave a user turn last.
        JsonObject args = new JsonObject();
        args.addProperty("text", "hi");
        LlmRequest req = request(List.of(
                LlmMessage.of(LlmMessageRole.SYSTEM, "rules"),
                LlmMessage.of(LlmMessageRole.USER, "where are we"),
                LlmMessage.of(LlmMessageRole.ASSISTANT, "in Sol"),
                LlmMessage.of(LlmMessageRole.USER, "say hi"),
                LlmMessage.assistantToolCalls(List.of(new LlmToolInvocation("repair-1", "speak", args))),
                LlmMessage.toolResult("repair-1", "{\"error\":\"rejected\"}")), List.of(speakTool()));

        JsonArray messages = JsonParser.parseString(adapter.buildRequestBody(req)).getAsJsonObject()
                .getAsJsonArray("messages");

        assertEquals("user", messages.get(messages.size() - 1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void parsesToolUseBlocksIntoInvocations() {
        JsonObject input = new JsonObject();
        input.addProperty("text", "hi");
        LlmResult result = adapter.parse(responseWithToolUse("tool-1", "speak", input));

        assertTrue(result.isValid());
        assertEquals(1, result.toolInvocations().size());
        assertEquals("speak", result.toolInvocations().get(0).name());
        assertEquals("tool-1", result.toolInvocations().get(0).id());
        assertEquals("hi", result.toolInvocations().get(0).arguments().get("text").getAsString());
    }

    @Test
    void parseSkipsALeadingThinkingBlock() {
        JsonObject input = new JsonObject();
        input.addProperty("text", "hi");
        LlmResult result = adapter.parse(response("tool_use", thinkingBlock(), toolUseBlock("tool-1", "speak", input)));

        assertTrue(result.isValid());
        assertEquals("speak", result.toolInvocations().get(0).name());
    }

    @Test
    void refusedOrTruncatedToolCallIsNeverExecuted() {
        for (String stop : List.of("refusal", "max_tokens")) {
            LlmResult result = adapter.parse(response(stop, toolUseBlock("tool-1", "speak", new JsonObject())));

            assertEquals(LlmResult.Status.INVALID_RESPONSE, result.status(), stop);
            assertEquals(stop, result.finishReason());
        }
    }

    @Test
    void rejectsResponseWithoutToolUse() {
        assertEquals(LlmResult.Status.INVALID_RESPONSE, adapter.parse(responseWithText("just chatting")).status());
        assertEquals(LlmResult.Status.INVALID_RESPONSE, adapter.parse(new JsonObject()).status());
    }

    @Test
    void rejectsNonObjectToolInput() {
        JsonObject response = responseWithToolUse("tool-1", "speak", new JsonObject());
        response.getAsJsonArray("content").get(0).getAsJsonObject().addProperty("input", "not-an-object");

        assertEquals(LlmResult.Status.INVALID_RESPONSE, adapter.parse(response).status());
    }

    @Test
    void parsesTextBlockForCompression() {
        assertEquals("a compact summary", adapter.parseText(responseWithText("  a compact summary  ")));
    }

    @Test
    void parseTextReturnsNullForMissingText() {
        assertNull(adapter.parseText(new JsonObject()));
    }

    @Test
    void parseTextSkipsThinkingAndIgnoresRefusedOrTruncatedText() {
        assertEquals("OK", adapter.parseText(response("end_turn", thinkingBlock(), textBlock("OK"))));
        assertNull(adapter.parseText(response("refusal", textBlock("I can't help with that"))));
        assertNull(adapter.parseText(response("max_tokens", textBlock("half an ans"))));
    }

    @Test
    void extractTextSkipsALeadingThinkingBlock() {
        AnthropicClient client = AnthropicClient.getInstance();

        assertEquals("{\"text_to_speech_response\":\"Docked.\"}", client.extractText(response("end_turn",
                thinkingBlock(), textBlock("{\"text_to_speech_response\":\"Docked.\"}"))));
        assertNull(client.extractText(response("max_tokens", thinkingBlock())), "thinking alone carries no answer");
    }

    private static JsonObject responseWithText(String text) {
        return response(null, textBlock(text));
    }

    private static JsonObject responseWithToolUse(String id, String name, JsonObject input) {
        return response(null, toolUseBlock(id, name, input));
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static JsonObject toolUseBlock(String id, String name, JsonObject input) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", id);
        block.addProperty("name", name);
        block.add("input", input);
        return block;
    }

    /** A Haiku 5.5 thinking block as it arrives by default: empty text, signature only. */
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
        if (stopReason != null) {
            response.addProperty("stop_reason", stopReason);
        }
        return response;
    }
}
