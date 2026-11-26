package com.backend.account.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.account.RefusedConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SigningKeysTest {

    @Test
    @DisplayName("the signing key is read from its PKCS #8, its public half made from it, and named by its thumbprint")
    void theSigningKey() throws Exception {
        TestKeys.Pair pair = TestKeys.newPair();
        SigningKeys keys = SigningKeys.from(pair.privateKey(), null);
        ECKey current = keys.current();
        assertThat(current.isPrivate()).isTrue();
        assertThat(current.getCurve()).isEqualTo(Curve.P_256);
        assertThat(current.getKeyID()).as("RFC 7638").isEqualTo(current.computeThumbprint().toString());
        assertThat(current.getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(current.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        ECKey published = (ECKey) keys.publicSet().getKeys().get(0);
        assertThat(published.toECPublicKey().getEncoded())
                .as("the public half made from the private key is the pair's own")
                .isEqualTo(java.util.Base64.getDecoder().decode(pair.publicKey()));
    }

    @Test
    @DisplayName("the published set holds the public keys alone: the current one first, then each retired one")
    void thePublishedSet() {
        TestKeys.Pair now = TestKeys.newPair();
        TestKeys.Pair old1 = TestKeys.newPair();
        TestKeys.Pair old2 = TestKeys.newPair();
        SigningKeys keys = SigningKeys.from(now.privateKey(), old1.publicKey() + ", " + old2.publicKey());
        assertThat(keys.publicSet().getKeys()).hasSize(3).noneMatch(JWK::isPrivate);
        assertThat(keys.keyIds().get(0)).isEqualTo(keys.current().getKeyID());
        assertThat(keys.publicSet().toString()).doesNotContain("\"d\"");
        assertThat(keys.keyIds()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a key that is not a P-256 key, not base64 or missing refuses the start, naming the setting")
    void refusals() {
        assertThatThrownBy(() -> SigningKeys.from(null, null)).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("ACCOUNT_SIGNING_KEY");
        assertThatThrownBy(() -> SigningKeys.from("not base64!", null)).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("ACCOUNT_SIGNING_KEY");
        assertThatThrownBy(() -> SigningKeys.from(TestKeys.of("secp384r1").privateKey(), null))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("P-256");
        assertThatThrownBy(() -> SigningKeys.from(TestKeys.newPair().publicKey(), null))
                .as("a public key where the private one goes").isInstanceOf(RefusedConfiguration.class);
        assertThatThrownBy(() -> SigningKeys.from(TestKeys.newPair().privateKey(), "AAAA"))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("ACCOUNT_RETIRED_KEYS");
        assertThatThrownBy(() -> SigningKeys.from(TestKeys.newPair().privateKey(), TestKeys.of("secp384r1").publicKey()))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("P-256");
    }
}
