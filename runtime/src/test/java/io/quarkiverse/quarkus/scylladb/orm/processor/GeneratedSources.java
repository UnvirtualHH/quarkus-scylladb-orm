package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Runs {@link EntityMapperProcessor} over in-memory sources and hands back what it
 * generated.
 * <p>
 * Without this the processor is only ever exercised by the Maven compiler, in a JVM the
 * test agent never sees — so more than half the extension's code was validated purely by
 * "the generated code compiles and the integration tests pass". That catches broken
 * output, but nothing about <em>which</em> code is emitted, and it needs Docker and a
 * running ScyllaDB to say anything at all.
 * <p>
 * Uses only {@code javax.tools} from the JDK: no compile-testing dependency, and the
 * processor runs inside the surefire JVM so its coverage is measurable.
 */
final class GeneratedSources {

    private GeneratedSources() {
    }

    /** The outcome of one processing run. */
    record Result(Map<String, String> sources, List<String> errors, boolean success) {

        /** The generated source for a fully qualified class name. */
        String source(String qualifiedName) {
            String content = sources.get(qualifiedName);
            if (content == null) {
                throw new AssertionError("No source generated for " + qualifiedName
                        + ". Generated: " + sources.keySet() + ". Errors: " + errors);
            }
            return content;
        }

        /** All errors joined, for assertions on diagnostics. */
        String errorText() {
            return String.join("\n", errors);
        }
    }

    /**
     * Compiles the given sources with the annotation processor attached.
     *
     * @param sources fully qualified class name to source text
     * @return the generated sources plus any diagnostics
     */
    static Result compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler — tests must run on a JDK, not a JRE.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
        CapturingFileManager fileManager = new CapturingFileManager(standard);

        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, code) -> units.add(new InMemorySource(name, code)));

        // -proc:only: we care about what the processor emits, not about producing class
        // files. It also keeps the run fast and avoids compiling the generated sources
        // against a classpath they were never meant to be compiled against here.
        JavaCompiler.CompilationTask task = compiler.getTask(
                new StringWriter(), fileManager, diagnostics,
                List.of("-proc:only", "-classpath", System.getProperty("java.class.path")),
                null, units);
        task.setProcessors(List.of(new EntityMapperProcessor()));

        boolean success = task.call();

        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .toList();

        return new Result(fileManager.generated, errors, success);
    }

    /** Convenience for the common single-entity case. */
    static Result compile(String qualifiedName, String source) {
        return compile(Map.of(qualifiedName, source));
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String qualifiedName, String code) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** Captures everything the processor writes to SOURCE_OUTPUT instead of touching disk. */
    private static final class CapturingFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, String> generated = new LinkedHashMap<>();

        CapturingFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                JavaFileObject.Kind kind, javax.tools.FileObject sibling) throws IOException {
            if (location == StandardLocation.SOURCE_OUTPUT && kind == JavaFileObject.Kind.SOURCE) {
                return new CapturedOutput(className);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        private final class CapturedOutput extends SimpleJavaFileObject {
            private final String className;

            CapturedOutput(String className) {
                super(URI.create("generated:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
                this.className = className;
            }

            @Override
            public Writer openWriter() {
                return new StringWriter() {
                    @Override
                    public void close() {
                        generated.put(className, toString());
                    }
                };
            }

            /**
             * javac parses generated sources again in the following round, so the file
             * object has to be readable and not just writable.
             */
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return generated.getOrDefault(className, "");
            }
        }
    }
}
