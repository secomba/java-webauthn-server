package com.yubico.webauthn.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yubico.u2f.data.messages.key.util.U2fB64Encoding;
import com.yubico.u2f.exceptions.U2fBadInputException;
import com.yubico.webauthn.util.BinaryUtil;
import java.nio.charset.Charset;
import java.util.Optional;
import lombok.NonNull;
import lombok.Value;


@Value
public class AuthenticatorAssertionResponse implements AuthenticatorResponse {

    @JsonProperty
    private ByteArray authenticatorData;

    @JsonProperty
    private ByteArray clientDataJSON;

    @JsonProperty
    private ByteArray signature;

    @JsonProperty
    private Optional<ByteArray> userHandle;

    @JsonCreator
    public AuthenticatorAssertionResponse(
        @NonNull @JsonProperty("authenticatorData") final ByteArray authenticatorData,
        @NonNull @JsonProperty("clientDataJSON") final ByteArray clientDataJson,
        @NonNull @JsonProperty("signature") final ByteArray signature,
        @JsonProperty("userHandle") final ByteArray userHandle
    ) {
        this.authenticatorData = authenticatorData;
        this.clientDataJSON = clientDataJson;
        this.signature = signature;
        this.userHandle = Optional.ofNullable(userHandle);
    }

    public String getClientDataJSONString() {
        return new String(clientDataJSON.getBytes(), Charset.forName("UTF-8"));
    }

}
