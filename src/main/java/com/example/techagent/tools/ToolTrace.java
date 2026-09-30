package com.example.techagent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Trace d'une invocation : outils appeles et documents consultes. Transmise aux outils via le
 * {@link ToolContext} Spring AI, donc propre a chaque requete (pas d'etat partage).
 */
public class ToolTrace {

    public static final String KEY = "toolTrace";

    private final List<String> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> documents = Collections.synchronizedSet(new LinkedHashSet<>());

    public void call(String description) {
        toolCalls.add(description);
    }

    public void document(String name) {
        documents.add(name);
    }

    public List<String> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<String> documents() {
        return List.copyOf(documents);
    }

    static ToolTrace from(ToolContext context) {
        if (context != null && context.getContext().get(KEY) instanceof ToolTrace trace) {
            return trace;
        }
        return new ToolTrace();
    }
}
