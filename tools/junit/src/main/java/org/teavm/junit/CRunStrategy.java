/*
 *  Copyright 2018 Alexey Andreev.
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.testcontainers.utility.DockerImageName;

class CRunStrategy extends TestRunExecutorStrategy {
    protected static final File TEST_PWD = Paths.get("").toAbsolutePath().toFile();

    private String compilerCommand;
    private ConcurrentMap<String, Compilation> compilationMap = new ConcurrentHashMap<>();

    public static boolean isExecutedInTestContainer() {
        return Optional.ofNullable(System.getProperty(PropertyNames.C_TESTCONTAINER))
                .map("true"::equals)
                .orElse(IS_MAC_OS);
    }

    public static DockerImageName getTestContainerImage() {
        if (!isExecutedInTestContainer()) {
            throw new RuntimeException("Test container image is not defined for this run strategy");
        }
        return DockerImageName.parse(System.getProperty(PropertyNames.C_TESTCONTAINER_IMAGE));
    }

    CRunStrategy(String compilerCommand) {
        super(isExecutedInTestContainer(), getTestContainerImage());
        this.compilerCommand = compilerCommand;
    }

    @Override
    public void runTest(TestRun run) throws IOException {
        try {
            String exeName = "run_test";
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                exeName += ".exe";
            }

            var absoluteSourcesDir = new File(run.getGroup().getBaseDirectory(), run.getGroup().getFileName());
            var sourcesDirRelativeToTestPwd = TEST_PWD.toPath().relativize(absoluteSourcesDir.toPath()).toFile();
            var outputFile = new File(sourcesDirRelativeToTestPwd, exeName);

            List<String> compilerOutput = new ArrayList<>();
            var compilerSuccess = compile(sourcesDirRelativeToTestPwd, compilerOutput, executionContext);
            if (!compilerSuccess) {
                throw new RuntimeException("C compiler error:\n" + mergeLines(compilerOutput));
            }

            try {
                executionContext.makeExecutable(outputFile);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to make output file executable: " + outputFile.getPath(), ex);
            }

            List<String> runtimeOutput = new ArrayList<>();
            List<String> stdout = new ArrayList<>();

            synchronized (this) {
                List<String> runCommand = new ArrayList<>();
                runCommand.add("./" + outputFile.getPath());
                if (run.getArgument() != null) {
                    runCommand.add(run.getArgument());
                }

                var testOk =
                        executionContext.runProcess(runCommand, sourcesDirRelativeToTestPwd, runtimeOutput, stdout);
                var testSuccess = !stdout.isEmpty() && stdout.get(stdout.size() - 1).equals("SUCCESS");
                if (!testOk || !testSuccess) {
                    throw new RuntimeException("Test failed:\n" + mergeLines(runtimeOutput));
                } else {
                    writeLines(runtimeOutput);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String mergeLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void writeLines(List<String> lines) {
        for (String line : lines) {
            System.out.println(line);
        }
    }

    private boolean compile(File inputDir, List<String> compilerOutput, ExecutionContext executionContext) throws
            IOException, InterruptedException {
        Compilation compilation = compilationMap.computeIfAbsent(inputDir.getPath(), k -> new Compilation());
        synchronized (compilation) {
            if (!compilation.started) {
                compilation.started = true;
                compilation.success = runCompiler(inputDir, compilerOutput, executionContext);
            }
        }
        return compilation.success;
    }

    private boolean runCompiler(File inputDir, List<String> output, ExecutionContext executionContext)
            throws IOException, InterruptedException {
        compilerCommand = Paths.get(".", compilerCommand).toString();
        return executionContext.runProcess(List.of(compilerCommand), inputDir, output, new ArrayList<>());
    }

    @Override
    public void cleanup() {
    }

    static class Compilation {
        volatile boolean started;
        volatile boolean success;
    }
}
