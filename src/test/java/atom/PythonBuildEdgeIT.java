package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The python-build atom's edges, still with a stub interpreter: the wheel/sdist switches, a build that fails, a build
 * that produces nothing, a twine that fails, a custom distDir, and an agent without a python3 on the PATH.
 *
 * <p>Each stub case writes its own argv to {@code argv.txt}, so the assertions are about the command line the atom built
 * rather than about what the atom says it did.
 */
class PythonBuildEdgeIT {

    private static final String BUILD_OK = """
            #!/bin/sh
            printf '%s\\n' "$*" >> argv.txt
            out=dist
            prev=""
            for arg in "$@"; do
              if [ "$prev" = "--outdir" ]; then out="$arg"; fi
              prev="$arg"
            done
            mkdir -p "$out"
            printf 'artifact' > "$out/pkg-1.0-py3-none-any.whl"
            printf 'archive' > "$out/pkg-1.0.tar.gz"
            printf 'zip' > "$out/pkg-1.0.zip"
            printf 'ignored' > "$out/notes.txt"
            exit 0
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** argv stores the arguments space-separated; the distDir is argv[4] for `python -m build --outdir <dir> .`. */
    private static String argv(AtomResult run) {
        String recorded = run.file("argv.txt");
        return recorded == null ? "" : recorded;
    }

    @Test
    @DisplayName("wheel=true, sdist=false asks python -m build for --wheel only")
    void buildsAWheelOnly() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", BUILD_OK)
                .input("sdist", "false")
                .input("distDir", "out")
                .run();

        run.assertOk();
        assertTrue(argv(run).contains("-m build --outdir out --wheel"), "argv: " + argv(run));
        assertFalse(argv(run).contains("--sdist"), "argv: " + argv(run));
        assertEquals("3", run.field("artifacts"), "the wheel, the tar.gz and the zip are the artifacts");
        assertTrue(run.exists("out/pkg-1.0.zip"), "the zip in distDir must be kept");
        assertFalse(run.exists("dist"), "a custom distDir must not also build into dist");
    }

    @Test
    @DisplayName("sdist=true, wheel=false asks python -m build for --sdist only")
    void buildsAnSdistOnly() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", BUILD_OK)
                .input("wheel", "false")
                .run();

        run.assertOk();
        assertTrue(argv(run).contains("-m build --outdir dist --sdist"), "argv: " + argv(run));
        assertFalse(argv(run).contains("--wheel"), "argv: " + argv(run));
    }

    @Test
    @DisplayName("with both switches off python -m build runs without a --wheel or --sdist switch")
    void buildsWithTheToolDefaults() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", BUILD_OK)
                .input("wheel", "false")
                .input("sdist", "false")
                .run();

        run.assertOk();
        assertFalse(argv(run).contains("--wheel"), "argv: " + argv(run));
        assertFalse(argv(run).contains("--sdist"), "argv: " + argv(run));
    }

    @Test
    @DisplayName("a failing python -m build is refused with its exit code and the tail of its output")
    void refusesAFailedBuild() throws Exception {
        String failing = """
                #!/bin/sh
                echo "no pyproject.toml in this directory"
                echo "ERROR Backend subprocess exited when trying to invoke get_requires_for_build_wheel"
                exit 3
                """;
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", failing)
                .run();

        run.assertRefused();
        assertEquals("40002", run.field("errorCode"), "error code: " + run.field("errorCode"));
        assertTrue(run.field("message").contains("python -m build failed (3)"),
                "the exit code must be in the message: " + run.field("message"));
        assertTrue(run.field("message").contains("no pyproject.toml"),
                "the tail of the output must be in the message: " + run.field("message"));
        assertTrue(run.field("message").contains("'build' package"),
                "the message must hint at the missing package: " + run.field("message"));
    }

    @Test
    @DisplayName("a build that exits 0 but leaves distDir empty is refused, not reported as a success with 0 artifacts")
    void refusesABuildThatProducedNothing() throws Exception {
        String empty = """
                #!/bin/sh
                echo "nothing to see here"
                exit 0
                """;
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", empty)
                .input("distDir", "empty-dist")
                .run();

        run.assertRefused();
        assertEquals("40003", run.field("errorCode"));
        assertTrue(run.field("message").contains("produced nothing in empty-dist"),
                "message: " + run.field("message"));
    }

    @Test
    @DisplayName("a failing twine upload is refused with its exit code and output")
    void refusesAFailedUpload() throws Exception {
        String failingTwine = """
                #!/bin/sh
                case "$2" in
                  build)
                    mkdir -p dist
                    printf 'wheel' > dist/pkg-1.0-py3-none-any.whl
                    exit 0
                    ;;
                  twine)
                    echo "HTTPError: 403 Forbidden from https://upload.pypi.org/legacy/"
                    exit 4
                    ;;
                esac
                exit 0
                """;
        AtomResult run = atom.file("setup.py", "py")
                .file(".secrets.json", "{\"pypiUser\": \"robot\", \"pypiPassword\": \"hunter2\"}")
                .bin("python3", failingTwine)
                .input("upload", "true")
                .run();

        run.assertRefused();
        assertEquals("40005", run.field("errorCode"));
        assertTrue(run.field("message").contains("twine upload failed (4)"), "message: " + run.field("message"));
        assertTrue(run.field("message").contains("403 Forbidden"), "message: " + run.field("message"));
        assertFalse(run.console().contains("hunter2"), "the password must never reach the log:\n" + run.console());
    }

    @Test
    @DisplayName("an agent whose PATH has no python3 refuses with 40001 instead of running something else")
    void refusesWhenPythonIsNotOnThePath() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .env("PATH", "/nonexistent")
                .withoutEnvironment()
                .run();

        run.assertRefused();
        assertEquals("40001", run.field("errorCode"), "error code: " + run.field("errorCode"));
        assertTrue(run.field("message").contains("python3 is not on the PATH"),
                "message: " + run.field("message"));
    }
}
