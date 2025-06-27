/*
 *  Copyright 2023 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.teavm.junit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import org.apache.commons.io.FilenameUtils;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class TestRunExecutorStrategy implements TestRunStrategy {
    protected static final String OS = System.getProperty("os.name").toLowerCase();
    protected static final Boolean IS_MAC_OS = OS.contains("mac");
    protected static final File TEST_PWD = Paths.get("").toAbsolutePath().toFile();

    private static final Map<Future<String>, GenericContainer<?>> testContainers = new HashMap<>();

    protected ExecutionContext executionContext;

    protected TestRunExecutorStrategy(boolean useTestContainers, DockerImageName testContainersImage) {
        executionContext = useTestContainers
                ? new TestContainerExecutionContext(testContainersImage)
                : new NativeExecutionContext();
    }

    public interface ExecutionContext {
        boolean runProcess(List<String> runCommand, File pwd, List<String> output, List<String> stdout) throws
                InterruptedException, IOException;

        void makeExecutable(File file) throws IOException, InterruptedException;
    }

    public static class NativeExecutionContext implements ExecutionContext {
        @Override
        public boolean runProcess(List<String> runCommand, File pwd, List<String> output, List<String> stdout) throws
                IOException, InterruptedException {
            Process process = new ProcessBuilder()
                    .command(runCommand)
                    .directory(TEST_PWD)
                    .start();

            BufferedReader stdin = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();

            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        String line = stderr.readLine();
                        if (line == null) {
                            break;
                        }
                        lines.add(line);
                    }
                } catch (IOException e) {
                    // do nothing
                }
            });
            thread.setDaemon(true);
            thread.start();

            try {
                while (true) {
                    String line = stdin.readLine();
                    if (line == null) {
                        break;
                    }
                    lines.add(line);
                    stdout.add(line);
                    if (lines.size() > 10000) {
                        output.addAll(lines);
                        process.destroy();
                        return false;
                    }
                }
            } catch (IOException e) {
                // do nothing
            }

            boolean result = process.waitFor() == 0;
            output.addAll(lines);
            return result;
        }

        @Override
        public void makeExecutable(File file) throws IOException {
            if (!file.setExecutable(true)) {
                throw new IOException("Failed to make file executable: " + file.getPath());
            }
        }
    }

    public static class TestContainerExecutionContext implements ExecutionContext {
        private static final String WORKING_DIRECTORY = "/tests";
        private GenericContainer<?> container;

        @SuppressWarnings("resource")
        public TestContainerExecutionContext(DockerImageName dockerImageName) {
            this(new GenericContainer<>(dockerImageName)
                    .withWorkingDirectory(WORKING_DIRECTORY)
                    .withFileSystemBind(TEST_PWD.getAbsoluteFile().toString(), WORKING_DIRECTORY, BindMode.READ_WRITE)
                    .withCommand("sh", "-c",
                            "trap \"echo SIGINT received; exit 0\" INT; echo Waiting for SIGINT; while :; do "
                                    + "sleep 1;"
                                    + " done"));
        }

        public TestContainerExecutionContext(GenericContainer<?> container) {
            synchronized (testContainers) {
                testContainers.putIfAbsent(container.getImage(), container);
                this.container = testContainers.get(container.getImage());
                if (!this.container.isRunning()) {
                    Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
                    this.container.start();
                }
            }
        }

        @Override
        public boolean runProcess(List<String> runCommand, File pwd, List<String> output, List<String> stdout) throws
                IOException, InterruptedException {
            String exec = FilenameUtils.separatorsToUnix(runCommand.get(0))
                    .replaceAll("^\\./", WORKING_DIRECTORY + "/");
            String arguments = String.join(" ", runCommand.subList(1, runCommand.size()));

            Map<String, String> envVars = Map.of("ARGS", arguments, "EXEC", exec);
            String[] command = new String[] { "sh", "-c", "$EXEC $ARGS" };

            ExecConfig execConfig = ExecConfig.builder()
                    .workDir(new File(WORKING_DIRECTORY, pwd.getPath()).getPath())
                    .envVars(envVars)
                    .command(command)
                    .build();

            Container.ExecResult execResult = container.execInContainer(execConfig);

            execResult.getStderr().lines().forEach(output::add);
            execResult.getStdout().lines().forEach(output::add);
            execResult.getStdout().lines().forEach(stdout::add);

            return execResult.getExitCode() == 0;
        }

        @Override
        public void makeExecutable(File file) throws IOException, InterruptedException {
            Container.ExecResult execResult;

            execResult = container.execInContainer("chmod", "+x", file.getPath());

            if (execResult.getExitCode() != 0) {
                throw new IOException(new StringBuilder()
                        .append("Failed to make file executable: ")
                        .append(file.getPath())
                        .append(", Exit code: ")
                        .append(execResult.getExitCode())
                        .append(", Details: ")
                        .append(execResult.getStderr())
                        .toString());
            }
        }
    }
}
