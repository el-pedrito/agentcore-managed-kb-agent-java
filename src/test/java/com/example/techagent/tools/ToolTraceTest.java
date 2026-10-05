package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class ToolTraceTest {

    @Test
    void collectsCallsDocumentsAndGroundingSource() {
        ToolTrace trace = new ToolTrace(null);

        trace.call("searchTechnicalDocumentation", "searchTechnicalDocumentation(\"F28\")");
        trace.document("notice.md", "F28 : pression trop basse");
        trace.document("notice.md", "F28 : remettre en pression");
        trace.source("  ");

        assertThat(trace.toolCalls()).containsExactly("searchTechnicalDocumentation(\"F28\")");
        assertThat(trace.documents()).containsExactly("notice.md");
        assertThat(trace.groundingSource()).isEqualTo("F28 : pression trop basse\n\nF28 : remettre en pression");
    }

    @Test
    void theTraceIsMandatoryForEveryToolCall() {
        assertThatThrownBy(() -> ToolTrace.from(new ToolContext(Map.of()))).isInstanceOf(IllegalStateException.class);
    }
}
