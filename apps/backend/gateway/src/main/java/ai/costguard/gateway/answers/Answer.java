package ai.costguard.gateway.answers;

/** What the agent got the first time: status, content type and body, as they were. */
public record Answer(int status, String contentType, byte[] body) {
}
