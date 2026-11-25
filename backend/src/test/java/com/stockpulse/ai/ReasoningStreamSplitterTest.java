package com.stockpulse.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningStreamSplitterTest {

    @Test
    void markerSplitAcrossDeltasNeverLeaks() {
        List<String> out = new ArrayList<>();
        ReasoningStreamSplitter s = new ReasoningStreamSplitter("===JSON===", out::add);
        for (String d : new String[]{"Hold the ", "price.\n==", "=JS", "ON===", "\n{\"pricing\":{}}"}) {
            s.accept(d);
        }
        assertThat(String.join("", out)).isEqualTo("Hold the price.\n");
        assertThat(s.markerSeen()).isTrue();
        assertThat(s.jsonPart().trim()).isEqualTo("{\"pricing\":{}}");
    }

    @Test
    void withoutMarkerEverythingIsFlushedOnFinish() {
        List<String> out = new ArrayList<>();
        ReasoningStreamSplitter s = new ReasoningStreamSplitter("===JSON===", out::add);
        s.accept("{\"pricing\":");
        s.accept("{}}");
        s.finish();
        assertThat(String.join("", out)).isEqualTo("{\"pricing\":{}}");
        assertThat(s.jsonPart()).isEqualTo("{\"pricing\":{}}");
    }
}
