package com.jredis.server.config;

import com.jredis.common.ArgSplitter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads a Redis-style config file ({@code directive value...} per line, {@code #} comments, quoted
 * values allowed) and applies command-line overrides ({@code --directive value...}).
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    /**
     * @param args {@code [config-file] [--directive value ...]}
     */
    public static ServerConfig load(String[] args) throws ConfigException {
        ServerConfig config = new ServerConfig();
        int i = 0;
        if (args.length > 0 && !args[0].startsWith("--")) {
            Path file = Paths.get(args[0]);
            config.configFile(file.toAbsolutePath().toString());
            loadFile(config, file);
            i = 1;
        }
        applyOverrides(config, args, i);
        return config;
    }

    public static void loadFile(ServerConfig config, Path file) throws ConfigException {
        List<String> lines;
        try {
            lines = Arrays.asList(decode(Files.readAllBytes(file)).split("\r?\n|\r", -1));
        } catch (IOException e) {
            throw new ConfigException("cannot read config file " + file + ": " + e.getMessage());
        }
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<byte[]> words = ArgSplitter.split(line.getBytes(StandardCharsets.UTF_8));
            if (words == null) {
                throw new ConfigException(file + ":" + lineNo + ": unbalanced quotes");
            }
            String name = new String(words.get(0), StandardCharsets.UTF_8);
            String[] values = new String[words.size() - 1];
            for (int w = 1; w < words.size(); w++) {
                values[w - 1] = new String(words.get(w), StandardCharsets.UTF_8);
            }
            try {
                Directives.apply(config, name, values);
            } catch (ConfigException e) {
                throw new ConfigException(file + ":" + lineNo + ": " + e.getMessage());
            }
        }
    }

    /**
     * UTF-8 (a byte-order mark, as Windows editors write it, is skipped). A file that is not valid
     * UTF-8, e.g. saved as Windows-1252, is read as ISO-8859-1 rather than refused.
     */
    static String decode(byte[] b) {
        int off = b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(b, off, b.length - off, StandardCharsets.ISO_8859_1);
        }
    }

    static void applyOverrides(ServerConfig config, String[] args, int from) throws ConfigException {
        int i = from;
        while (i < args.length) {
            String a = args[i];
            if (!a.startsWith("--") || a.length() == 2) {
                throw new ConfigException("expected --directive, got '" + a + "'");
            }
            String name = a.substring(2);
            List<String> values = new ArrayList<>();
            i++;
            while (i < args.length && !args[i].startsWith("--")) {
                values.add(args[i++]);
            }
            try {
                Directives.apply(config, name, values.toArray(new String[0]));
            } catch (ConfigException e) {
                throw new ConfigException("command line --" + name + ": " + e.getMessage());
            }
        }
    }
}
