package com.yubico.webauthn;

import COSE.CoseException;
import com.yubico.webauthn.data.AttestationObject;
import com.yubico.webauthn.data.AttestationType;
import com.yubico.webauthn.data.ByteArray;
import java.io.IOException;
import java.security.cert.CertificateException;


public interface AttestationStatementVerifier {

    AttestationType getAttestationType(AttestationObject attestation) throws IOException, CoseException, CertificateException;

    boolean verifyAttestationSignature(AttestationObject attestationObject, ByteArray clientDataJsonHash);

}
