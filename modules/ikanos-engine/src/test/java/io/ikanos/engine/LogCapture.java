/**
 * Copyright 2025-2026 Naftiko
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.engine;

import java.util.List;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test helper that captures what the engine logs through Restlet's current logger, so a test can
 * assert on log output. Looks the Logback logger up by name rather than reaching into Restlet's
 * SLF4J bridge, and detaches itself on {@link #close()}.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        this.logger =
                (Logger) LoggerFactory.getLogger(org.restlet.Context.getCurrentLogger().getName());
        appender.start();
        logger.addAppender(appender);
    }

    /** Events captured so far. */
    public List<ILoggingEvent> events() {
        return appender.list;
    }

    /**
     * One string per captured event: the formatted message followed by the attached throwable's
     * class and message, if any. Lets a test assert on everything a single log entry carries.
     */
    public List<String> messages() {
        return appender.list.stream().map(LogCapture::describe).toList();
    }

    private static String describe(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return throwable == null ? event.getFormattedMessage()
                : event.getFormattedMessage() + " | " + throwable.getClassName() + ": "
                        + throwable.getMessage();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
