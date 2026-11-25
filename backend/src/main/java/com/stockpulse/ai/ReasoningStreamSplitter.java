package com.stockpulse.ai;

import java.util.function.Consumer;

/**
 * Forwards streamed prose to the sink until the JSON marker appears, then buffers the rest for parsing.
 * Holds back a marker-length tail so a marker split across deltas is never leaked to the client.
 */
public class ReasoningStreamSplitter {

    private final String marker;
    private final Consumer<String> sink;
    private final StringBuilder buffer = new StringBuilder();
    private int emitted;
    private int markerIndex = -1;

    public ReasoningStreamSplitter(String marker, Consumer<String> sink) {
        this.marker = marker;
        this.sink = sink;
    }

    public void accept(String delta) {
        buffer.append(delta);
        if (markerIndex >= 0) {
            return;
        }
        int idx = buffer.indexOf(marker, emitted);
        if (idx >= 0) {
            markerIndex = idx;
            flushTo(idx);
            return;
        }
        flushTo(buffer.length() - marker.length() + 1);
    }

    /** Emits any held-back prose when the stream ended without a marker. */
    public void finish() {
        if (markerIndex < 0) {
            flushTo(buffer.length());
        }
    }

    public boolean markerSeen() {
        return markerIndex >= 0;
    }

    /** Text after the marker, or the whole response if the model ignored the format. */
    public String jsonPart() {
        return markerIndex >= 0 ? buffer.substring(markerIndex + marker.length()) : buffer.toString();
    }

    private void flushTo(int end) {
        if (end > emitted) {
            sink.accept(buffer.substring(emitted, end));
            emitted = end;
        }
    }
}
