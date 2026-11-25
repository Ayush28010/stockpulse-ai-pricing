package com.stockpulse.ai;

/** A rendered prompt; {@code template} names which situation-specific document was used. */
public record Prompt(String template, String system, String user) {
}
