package com.stockpulse.ai;

/** The LLM answered, but the answer is unusable (malformed JSON, out-of-bounds price, non-integer quantity...). */
public class InvalidAiOutputException extends RuntimeException {

    public InvalidAiOutputException(String message) {
        super(message);
    }
}
