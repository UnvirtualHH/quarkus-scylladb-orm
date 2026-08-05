package io.quarkiverse.quarkus.scylladb.orm.it.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips a native integration test when the built binary cannot run on this machine.
 * <p>
 * {@code -Dquarkus.native.container-build=true} compiles inside a Linux builder image, so
 * on macOS or Windows the result is a Linux ELF binary that the host cannot execute — the
 * launch fails with exit code 126, which looks like a product defect and is not one. On
 * CI the build runs on Linux and the test executes normally.
 * <p>
 * Evaluated as a JUnit condition rather than an assumption, because
 * {@code @QuarkusIntegrationTest} launches the process in its own {@code beforeAll} — an
 * assumption inside the test class would run too late.
 */
public class RunnableOnThisHost implements ExecutionCondition {

    private static final byte[] ELF_MAGIC = { 0x7F, 'E', 'L', 'F' };

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Path> runner = findRunner();
        if (runner.isEmpty()) {
            return ConditionEvaluationResult.disabled(
                    "No native runner binary found — run with -Dnative to build one.");
        }

        boolean binaryIsLinux = isElf(runner.get());
        boolean hostIsLinux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

        if (binaryIsLinux != hostIsLinux) {
            return ConditionEvaluationResult.disabled(
                    "The native binary was built for " + (binaryIsLinux ? "Linux" : "this host's OS")
                            + " but this host is " + System.getProperty("os.name")
                            + " — a container build cannot be executed here. CI runs this test on Linux.");
        }
        return ConditionEvaluationResult.enabled("Native binary matches this host.");
    }

    private static Optional<Path> findRunner() {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(target)) {
            return entries.filter(p -> p.getFileName().toString().endsWith("-runner"))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isElf(Path binary) {
        try (InputStream in = Files.newInputStream(binary)) {
            byte[] magic = in.readNBytes(ELF_MAGIC.length);
            return java.util.Arrays.equals(magic, ELF_MAGIC);
        } catch (IOException e) {
            return false;
        }
    }
}
