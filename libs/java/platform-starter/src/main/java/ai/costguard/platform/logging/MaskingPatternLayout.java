package ai.costguard.platform.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Boot's console pattern with {@link LogMasking} over the whole line, so the message, its arguments and the stack
 * trace are all covered. {@code logback-spring.xml} of this starter puts it on the console appender of every service.
 */
public class MaskingPatternLayout extends PatternLayout {

    @Override
    public String doLayout(ILoggingEvent event) {
        return LogMasking.mask(super.doLayout(event));
    }
}
