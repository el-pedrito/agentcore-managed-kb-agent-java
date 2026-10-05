package com.example.techagent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * What the tools did during one question: calls, documents read and content returned. Passed to
 * the tools through the Spring AI {@link ToolContext}, so it is specific to each request.
 *
 * <p>Two uses: show the trace during the demo, and give the grounding check its source of truth
 * (everything the tools returned to the model).
 */
public class ToolTrace {

    public static final String KEY = "toolTrace";
    private static final Logger log = LoggerFactory.getLogger(ToolTrace.class);

    private final InterventionTools.Intervention intervention;
    private final List<String> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> documents = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sources = Collections.synchronizedList(new ArrayList<>());

    /**
     * @param intervention intervention of the request, resolved by the application (null if none).
     *                     It is the only one the getIntervention tool can read.
     */
    public ToolTrace(InterventionTools.Intervention intervention) {
        this.intervention = intervention;
    }

    public InterventionTools.Intervention intervention() {
        return intervention;
    }

    /** Tool call. Only the tool name is logged, not its arguments (technician input). */
    public void call(String tool, String description) {
        toolCalls.add(description);
        log.info("tool_call tool={}", tool);
    }

    public void document(String name, String content) {
        if (!documents.contains(name)) {
            documents.add(name);
        }
        source(content);
    }

    public void source(String content) {
        if (content != null && !content.isBlank()) {
            sources.add(content);
        }
    }

    public List<String> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<String> documents() {
        return List.copyOf(documents);
    }

    public String groundingSource() {
        return String.join("\n\n", sources);
    }

    public static ToolTrace from(ToolContext context) {
        if (context != null && context.getContext().get(KEY) instanceof ToolTrace trace) {
            return trace;
        }
        throw new IllegalStateException("ToolTrace missing from the ToolContext");
    }
}
