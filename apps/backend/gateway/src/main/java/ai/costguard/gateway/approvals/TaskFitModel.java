package ai.costguard.gateway.approvals;

import reactor.core.publisher.Mono;

/** The language model the AI assessor asks. In production a provider of the gateway; in tests a stand-in. */
public interface TaskFitModel {

    /** The model's answer as text. An error, an empty answer or a slow one means a person decides. */
    Mono<String> ask(String system, String user);
}
