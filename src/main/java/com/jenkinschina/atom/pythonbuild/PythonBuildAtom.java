package com.jenkinschina.atom.pythonbuild;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * python as an atom: build the distribution, optionally upload it to an index.
 *
 * <p>No virtualenv management: the agent either has the interpreter and the build tools or the job uses a container.
 * What the atom adds is the two steps people actually script by hand — {@code python -m build} and {@code twine upload}
 * — with the credentials kept out of argv.
 */
public class PythonBuildAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String python = context.getParam("python", "");
        boolean wheel = context.getBooleanParam("wheel", true);
        boolean sdist = context.getBooleanParam("sdist", true);
        boolean upload = context.getBooleanParam("upload", false);
        String repository = context.getParam("repository", "");
        String distDir = context.getParam("distDir", "dist");

        String interpreter = python.isBlank() ? resolve("python3") : python;
        if (interpreter == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40001,
                    "python3 is not on the PATH of this agent; set 'python' to the interpreter");
        }

        List<String> build = new ArrayList<>(List.of(interpreter, "-m", "build", "--outdir", distDir));
        if (wheel && !sdist) {
            build.add("--wheel");
        }
        if (sdist && !wheel) {
            build.add("--sdist");
        }
        build.add(".");
        context.log("[python] " + String.join(" ", build));
        Result built = exec(workspace, context, build, null);
        if (!built.ok()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40002,
                    "python -m build failed (" + built.code() + "): " + built.tail()
                            + " — is the 'build' package installed?");
        }

        List<Path> artifacts = new ArrayList<>();
        Path directory = workspace.resolve(distDir);
        if (Files.isDirectory(directory)) {
            try (var walk = Files.list(directory)) {
                walk.filter(path -> path.toString().endsWith(".whl") || path.toString().endsWith(".tar.gz")
                                || path.toString().endsWith(".zip"))
                        .sorted()
                        .forEach(artifacts::add);
            }
        }
        if (artifacts.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "the build produced nothing in " + distDir);
        }

        AtomResult result = AtomResult.success()
                .data("distDir", distDir)
                .data("artifacts", String.valueOf(artifacts.size()))
                .artifact("dist", distDir);

        if (!upload) {
            return result;
        }

        String user = context.getSecret("pypiUser");
        String password = context.getSecret("pypiPassword");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40004,
                    "upload needs the 'pypiUser' and 'pypiPassword' credentials");
        }
        List<String> command = new ArrayList<>(List.of(interpreter, "-m", "twine", "upload",
                distDir + "/*"));
        if (!repository.isBlank()) {
            command.add("--repository-url");
            command.add(repository);
        }
        context.log("[python] twine upload" + (repository.isBlank() ? "" : " --repository-url " + repository));
        // the credential goes through the environment: argv is visible in `ps` to every user on the agent
        Result uploaded = exec(workspace, context, command,
                java.util.Map.of("TWINE_USERNAME", user, "TWINE_PASSWORD", password));
        if (!uploaded.ok()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "twine upload failed (" + uploaded.code() + "): " + uploaded.tail());
        }
        return result.data("uploaded", "true");
    }

    private static String resolve(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static Result exec(Path workspace, AtomContext context, List<String> command,
                               java.util.Map<String, String> environment) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            if (environment != null) {
                builder.environment().putAll(environment);
            }
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    context.log("[python] " + line);
                    output.append(line).append('\n');
                    if (output.length() > 100_000) {
                        output.delete(0, 50_000);
                    }
                }
            }
            return new Result(process.waitFor(), output.toString());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }

        String tail() {
            String[] lines = output.strip().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().strip();
        }
    }
}
