package ai.brokk.git.gpg;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.UnsupportedSigningFormatException;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Signer;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.TemporaryBuffer;

public class ExternalGpgSigner implements Signer {
    private static final Logger logger = LogManager.getLogger(ExternalGpgSigner.class);
    private static final String PINENTRY_USER_DATA = "PINENTRY_USER_DATA";
    private static final String GPG_TTY = "GPG_TTY";
    private final boolean x509;

    public ExternalGpgSigner() {
        this(false);
    }

    public ExternalGpgSigner(boolean x509) {
        this.x509 = x509;
    }

    @Override
    public boolean canLocateSigningKey(
            Repository repository,
            GpgConfig config,
            PersonIdent committer,
            String signingKey,
            CredentialsProvider credentialsProvider)
            throws CanceledException {
        if (this.x509) {
            return true;
        } else {
            String program = config.getProgram();
            if (!StringUtils.isEmptyOrNull(program)) {
                return true;
            } else {
                program = ExternalGpg.getGpg();
                if (StringUtils.isEmptyOrNull(program)) {
                    return false;
                } else {
                    String keySpec = signingKey;
                    if (signingKey == null) {
                        keySpec = config.getSigningKey();
                    }

                    if (StringUtils.isEmptyOrNull(keySpec)) {
                        keySpec = "<" + committer.getEmailAddress() + ">";
                    }

                    ProcessBuilder process = new ProcessBuilder(new String[0]);
                    process.command(
                            program,
                            "--locate-keys",
                            "--no-auto-key-locate",
                            "--with-colons",
                            "--batch",
                            "--no-tty",
                            keySpec);
                    this.gpgEnvironment(process);

                    try {
                        boolean[] result = new boolean[1];
                        ExternalProcessRunner.run(
                                process,
                                (InputStream) null,
                                (b) -> {
                                    try (BufferedReader r = new BufferedReader(
                                            new InputStreamReader(b.openInputStream(), StandardCharsets.UTF_8))) {
                                        boolean keyFound = false;

                                        String line;
                                        while ((line = r.readLine()) != null) {
                                            if (this.isKeyLine(line)) {
                                                String[] fields = line.split(":");
                                                if (fields.length > 11 && fields[11].indexOf(115) >= 0) {
                                                    keyFound = true;
                                                    break;
                                                }
                                            }
                                        }

                                        result[0] = keyFound;
                                    }
                                },
                                (ExternalProcessRunner.ResultHandler) null);
                        if (!result[0] && !StringUtils.isEmptyOrNull(signingKey)) {
                            logger.warn("No public key found for signing key {}", signingKey);
                        }

                        return result[0];
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                        return false;
                    }
                }
            }
        }
    }

    private boolean isKeyLine(String line) {
        if (this.x509) {
            return line.startsWith("crs:");
        } else {
            return line.startsWith("pub:") || line.startsWith("sub:");
        }
    }

    @Override
    public GpgSignature sign(
            Repository repository,
            GpgConfig config,
            byte[] data,
            PersonIdent committer,
            String signingKey,
            CredentialsProvider credentialsProvider)
            throws CanceledException, IOException, UnsupportedSigningFormatException {
        String program = config.getProgram();
        if (StringUtils.isEmptyOrNull(program)) {
            program = this.x509 ? ExternalGpg.getGpgSm() : ExternalGpg.getGpg();
            if (StringUtils.isEmptyOrNull(program)) {
                throw new IOException("GPG executable not found");
            }
        } else {
            program = ExternalGpg.findExecutable(program);
        }

        String keySpec = signingKey;
        if (signingKey == null) {
            keySpec = config.getSigningKey();
        }

        if (StringUtils.isEmptyOrNull(keySpec)) {
            keySpec = "<" + committer.getEmailAddress() + ">";
        }

        ProcessBuilder process = new ProcessBuilder(new String[0]);
        process.command(program, "-bsau", keySpec, "--status-fd", "2");
        this.gpgEnvironment(process);

        try (ByteArrayInputStream dataIn = new ByteArrayInputStream(data)) {
            class Holder {
                byte[] rawData;
            }

            Holder result = new Holder();
            ExternalProcessRunner.run(
                    process,
                    dataIn,
                    (b) -> {
                        boolean isValid = false;
                        Throwable error = null;

                        try {
                            isValid = this.isValidSignature(b);
                        } catch (PGPException | IOException e) {
                            error = e;
                        }

                        if (!isValid) {
                            throw new IOException(
                                    "No signature generated: " + ExternalProcessRunner.toString(b), error);
                        } else {
                            result.rawData = b.toByteArray();
                        }
                    },
                    (e) -> {
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(e.openInputStream(), StandardCharsets.UTF_8))) {
                            boolean pinentry = false;

                            String line;
                            while ((line = r.readLine()) != null) {
                                if (!pinentry && line.startsWith("[GNUPG:] PINENTRY_LAUNCHED")) {
                                    pinentry = true;
                                    this.checkTerminalPrompt(line);
                                } else if (pinentry) {
                                    if (line.startsWith("[GNUPG:] FAILURE sign")) {
                                        throw new CanceledException("Signing cancelled");
                                    }

                                    if (line.startsWith("[GNUPG:]")) {
                                        pinentry = false;
                                    }
                                }
                            }
                        } catch (IOException ex) {
                            // Ignore
                        }
                    });
            return new GpgSignature(this.stripCrs(result.rawData));
        }
    }

    private byte[] stripCrs(byte[] data) {
        byte[] result = new byte[data.length];
        int i = 0;

        for (int j = 0; j < data.length; ++j) {
            byte b = data[j];
            if (b != 13) {
                result[i++] = b;
            }
        }

        return i == data.length ? data : Arrays.copyOf(result, i);
    }

    private PGPSignature parseSignature(InputStream in) throws IOException, PGPException {
        try (InputStream sigIn = PGPUtil.getDecoderStream(in)) {
            PGPObjectFactory pgpFactory = new BcPGPObjectFactory(sigIn);
            Object obj = pgpFactory.nextObject();
            if (obj instanceof PGPCompressedData) {
                obj = (new BcPGPObjectFactory(((PGPCompressedData) obj).getDataStream())).nextObject();
            }

            if (!(obj instanceof PGPSignatureList)) {
                if (!(obj instanceof PGPSignature)) {
                    return null;
                }

                return (PGPSignature) obj;
            }

            return ((PGPSignatureList) obj).get(0);
        }
    }

    private boolean isValidX509Signature(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
        boolean first = true;
        String last = null;

        while (true) {
            String line = r.readLine();
            if (line == null) {
                return last != null && last.equals("-----END SIGNED MESSAGE-----");
            }

            if (first) {
                if (!line.equals("-----BEGIN SIGNED MESSAGE-----")) {
                    return false;
                }

                first = false;
            }

            last = line;
        }
    }

    private boolean isValidSignature(TemporaryBuffer b) throws IOException, PGPException {
        try (InputStream data = b.openInputStream()) {
            if (!this.x509) {
                return this.parseSignature(data) != null;
            }

            return this.isValidX509Signature(data);
        }
    }

    private void gpgEnvironment(ProcessBuilder process) {
        try {
            Map<String, String> childEnv = process.environment();
            childEnv.remove("PINENTRY_USER_DATA");
            childEnv.remove("GPG_TTY");
        } catch (UnsupportedOperationException | IllegalArgumentException | SecurityException e) {
            logger.warn("Failed to clean up GPG environment", e);
        }
    }

    private void checkTerminalPrompt(String gpgTraceLine) throws IOException {
        String[] parts = gpgTraceLine.split(" ");
        if (parts.length > 3 && "[GNUPG:]".equals(parts[0]) && "PINENTRY_LAUNCHED".equals(parts[1])) {
            String pinentryType = parts[3];
            if ("tty".equals(pinentryType) || "curses".equals(pinentryType)) {
                throw new IOException("GPG requested TTY input, which is not supported: " + gpgTraceLine);
            }
        }
    }
}
