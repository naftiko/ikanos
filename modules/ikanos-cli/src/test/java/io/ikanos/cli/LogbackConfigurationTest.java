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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import io.ikanos.Cli;

/**
 * The packaged CLI logs to the console on stderr: on an MCP stdio adapter, stdout carries only
 * JSON-RPC messages (issue #1033).
 */
class LogbackConfigurationTest {

    private static LoggerContext loadPackagedConfiguration() throws Exception {
        URL config = Cli.class.getClassLoader().getResource("logback.xml");
        assertNotNull(config, "logback.xml must be packaged with the CLI");
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(config);
        return context;
    }

    @Test
    void consoleAppenderShouldTargetStderr() throws Exception {
        LoggerContext context = loadPackagedConfiguration();
        try {
            ConsoleAppender<?> console = assertInstanceOf(ConsoleAppender.class,
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE"));
            assertEquals("System.err", console.getTarget());
        } finally {
            context.stop();
        }
    }
}
