package ai.costguard.platform.rules;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

/** Compiles and evaluates CEL rules over {@link OperationFacts}. Thread-safe. */
public final class OperationRules {

    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("agent_id", SimpleType.STRING)
            .addVar("kind", SimpleType.STRING)
            .addVar("vendor", SimpleType.STRING)
            .addVar("model", SimpleType.STRING)
            .addVar("cost", SimpleType.DOUBLE)
            .addVar("time", SimpleType.STRING)
            .addVar("weekday", SimpleType.INT)
            .setResultType(SimpleType.BOOL)
            .build();

    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    private OperationRules() {
    }

    /** A rule that does not compile is refused when saved, so it can never reach the gateway. */
    public static Rule compile(String expression) {
        try {
            CelAbstractSyntaxTree ast = COMPILER.compile(expression).getAst();
            return new Rule(RUNTIME.createProgram(ast));
        } catch (CelValidationException | CelEvaluationException e) {
            throw new InvalidRuleException(e.getMessage());
        }
    }

    public static final class Rule {

        private final CelRuntime.Program program;

        private Rule(CelRuntime.Program program) {
            this.program = program;
        }

        /** An error while evaluating counts as a match: a rule that cannot decide must not let money through. */
        public boolean matches(OperationFacts facts) {
            try {
                return Boolean.TRUE.equals(program.eval(facts.variables()));
            } catch (CelEvaluationException e) {
                return true;
            }
        }
    }

    public static final class InvalidRuleException extends RuntimeException {

        public InvalidRuleException(String message) {
            super(message);
        }
    }
}
