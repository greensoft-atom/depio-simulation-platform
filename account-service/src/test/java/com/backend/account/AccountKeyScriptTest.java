package com.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import com.backend.account.token.SigningKeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountKeyScriptTest {

    @Test
    @DisplayName("scripts/account-key.sh's key is taken by SigningKeys, and its public half is the one it prints")
    void theScriptsKey() throws Exception {
        Process p = new ProcessBuilder("bash", Path.of("scripts/account-key.sh").toString()).redirectErrorStream(true).start();
        List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
        assertThat(p.waitFor()).as(String.join("\n", lines)).isZero();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("ACCOUNT_SIGNING_KEY=");
        String key = lines.get(0).substring("ACCOUNT_SIGNING_KEY=".length());
        String pub = lines.get(1).substring(lines.get(1).lastIndexOf(' ') + 1);
        SigningKeys keys = SigningKeys.from(key, null);
        assertThat(keys.current().toECPublicKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(pub));
        assertThat(SigningKeys.from(key, pub).keyIds()).as("its public half, as a retired key, the same key").hasSize(1);
    }
}
