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
 * The python-build atom with a stub interpreter on {@code PATH}: what {@code python -m build} and
 * {@code python -m twine upload} were called with, and where the credential travelled.
 *
 * <p>The stub records its own argv and the environment it was given, which is the only way to prove the password is
 * not in argv — the point of the atom's environment-based credential handling.
 */
class PythonBuildIT {

    /** A python3 that pretends to build a wheel and to upload it, recording argv and TWINE_USERNAME. */
    private static final String FAKE_PYTHON = """
            #!/bin/sh
            printf '%s\\n' "$*" >> argv.txt
            case "$2" in
              build)
                mkdir -p dist
                printf 'wheel' > dist/pkg-1.0-py3-none-any.whl
                echo "Successfully built pkg-1.0-py3-none-any.whl"
                ;;
              twine)
                echo "Uploading using environment variables"
                printf 'user=%s\\n' "${TWINE_USERNAME:-unset}" >> env.txt
                ;;
            esac
            exit 0
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("the wheel is built where distDir says, and counted")
    void builds() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", FAKE_PYTHON)
                .run();

        run.assertOk();
        assertEquals("1", run.field("artifacts"), String.valueOf(run.document()));
        assertTrue(run.exists("dist/pkg-1.0-py3-none-any.whl"), "wheel missing");
        assertTrue(run.file("argv.txt").contains("-m build"), "python -m build was not the command:\n" + run.file("argv.txt"));
    }

    @Test
    @DisplayName("twine gets the credentials from the environment, never from argv")
    void uploadsWithCredentialsFromTheEnvironment() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .file(".secrets.json", "{\"pypiUser\": \"robot\", \"pypiPassword\": \"hunter2\"}")
                .bin("python3", FAKE_PYTHON)
                .input("upload", "true")
                .input("repository", "https://example.invalid/legacy/")
                .run();

        run.assertOk();
        assertTrue(run.file("env.txt").contains("user=robot"),
                "twine credentials must travel in the environment:\n" + run.file("env.txt"));
        assertFalse(run.file("argv.txt").contains("hunter2"),
                "the password must not be in argv:\n" + run.file("argv.txt"));
        assertTrue(run.file("argv.txt").contains("twine upload"),
                "twine was not called:\n" + run.file("argv.txt"));
        assertTrue(run.file("argv.txt").contains("--repository-url https://example.invalid/legacy/")
                        || run.file("argv.txt").contains("--repository-url"),
                "the repository was not passed:\n" + run.file("argv.txt"));
    }

    @Test
    @DisplayName("uploading without the pypi credentials fails instead of guessing")
    void refusesUploadWithoutCredentials() throws Exception {
        AtomResult run = atom.file("setup.py", "py")
                .bin("python3", FAKE_PYTHON)
                .input("upload", "true")
                .run();

        run.assertRefused();
        assertTrue(String.valueOf(run.document().getOrDefault("message", "")).contains("pypiUser"),
                "message: " + run.document());
    }
}
