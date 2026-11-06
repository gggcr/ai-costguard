package ai.costguard.gateway.ladder;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class Checkladder {

    static final int STEPS =7;
    private final List<CheckStep> steps;

    public Checkladder (List<CheckStepp> steps)