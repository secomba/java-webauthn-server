package com.yubico.webauthn;


import com.yubico.u2f.crypto.Crypto;
import com.yubico.u2f.exceptions.U2fBadInputException;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.Base64UrlException;
import com.yubico.webauthn.impl.ExtensionsValidation;
import com.yubico.webauthn.impl.TokenBindingValidator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;


@Builder
@Slf4j
public class FinishAssertionSteps {

    private static final String CLIENT_DATA_TYPE = "webauthn.get";

    private final AssertionRequest request;
    private final PublicKeyCredential<AuthenticatorAssertionResponse> response;
    private final Optional<ByteArray> callerTokenBindingId;
    private final List<String> origins;
    private final String rpId;
    private final Crypto crypto;
    private final CredentialRepository credentialRepository;

    @Builder.Default
    private final boolean allowMissingTokenBinding = false;
    @Builder.Default
    private final boolean validateTypeAttribute = true;
    @Builder.Default
    private final boolean validateSignatureCounter = true;
    @Builder.Default
    private final boolean allowUnrequestedExtensions = false;

    private interface Step<A extends Step<?, ?>, B extends Step<?, ?>> {
        B nextStep();

        void validate();

        List<String> getPrevWarnings();

        default boolean isFinished() {
            return false;
        }

        default Optional<AssertionResult> result() {
            return Optional.empty();
        }

        default List<String> getWarnings() {
            return Collections.emptyList();
        }

        default List<String> allWarnings() {
            List<String> result = new ArrayList<>(getPrevWarnings().size() + getWarnings().size());
            result.addAll(getPrevWarnings());
            result.addAll(getWarnings());
            return Collections.unmodifiableList(result);
        }

        default B next() {
            validate();
            return nextStep();
        }

        default AssertionResult run() {
            if (isFinished()) {
                return result().get();
            } else {
                return next().run();
            }
        }
    }

    public Step0 begin() {
        return new Step0();
    }

    public AssertionResult run() {
        return begin().run();
    }

    @Value
    public class Step0 implements Step<Step0, Step1> {
        @Override
        public Step1 nextStep() {
            return new Step1(username().get(), userHandle().get(), allWarnings());
        }

        @Override
        public void validate() {
            if (!(
                request.getUsername().isPresent() || response.getResponse().getUserHandle().isPresent()
            )) {
                throw new IllegalArgumentException("At least one of username and user handle must be given; none was.");
            }
            if (!userHandle().isPresent()) {
                throw new IllegalArgumentException(String.format(
                    "No user found for username: %s, userHandle: %s",
                    request.getUsername(), response.getResponse().getUserHandle()
                ));
            }
            if (!username().isPresent()) {
                throw new IllegalArgumentException(String.format(
                    "No user found for username: %s, userHandle: %s",
                    request.getUsername(), response.getResponse().getUserHandle()
                ));
            }
        }

        @Override
        public List<String> getPrevWarnings() {
            return Collections.emptyList();
        }

        private Optional<ByteArray> userHandle() {
            return response.getResponse().getUserHandle()
                .map(new Function<ByteArray, Optional<ByteArray>>() {
                    @Override
                    public Optional<ByteArray> apply(ByteArray byteArray) {
                        return Optional.of(byteArray);
                    }
                })
                .orElseGet(new Supplier<Optional<ByteArray>>() {
                    @Override
                    public Optional<ByteArray> get() {
                        return credentialRepository.getUserHandleForUsername(request.getUsername().get());
                    }
                });
        }

        private Optional<String> username() {
            return request.getUsername()
                .map(new Function<String, Optional<String>>() {
                    @Override
                    public Optional<String> apply(String s) {
                        return Optional.of(s);
                    }
                })
                .orElseGet(new Supplier<Optional<String>>() {
                    @Override
                    public Optional<String> get() {
                        return credentialRepository.getUsernameForUserHandle(response.getResponse().getUserHandle().get());
                    }
                });
        }
    }

    @Value
    public class Step1 implements Step<Step0, Step2> {
        private final String username;
        private final ByteArray userHandle;
        private final List<String> prevWarnings;

        @Override
        public Step2 nextStep() {
            return new Step2(username, userHandle, allWarnings());
        }

        @Override
        public void validate() {
            request.getPublicKeyCredentialRequestOptions().getAllowCredentials().ifPresent(new Consumer<List<PublicKeyCredentialDescriptor>>() {
                @Override
                public void accept(List<PublicKeyCredentialDescriptor> allowed) {
                    if (!(
                            allowed.stream().anyMatch(new Predicate<PublicKeyCredentialDescriptor>() {
                                @Override
                                public boolean test(PublicKeyCredentialDescriptor allow) {
                                    return allow.getId().equals(response.getId());
                                }
                            })
                    )) {
                        throw new IllegalArgumentException("Unrequested credential ID: " + response.getId());
                    }
                }
            });
        }
    }

    @Value
    public class Step2 implements Step<Step1, Step3> {
        private final String username;
        private final ByteArray userHandle;
        private final List<String> prevWarnings;

        @Override
        public Step3 nextStep() {
            return new Step3(username, userHandle, allWarnings());
        }

        @Override
        public void validate() {
            Optional<RegisteredCredential> registration = credentialRepository.lookup(response.getId(), userHandle);

            if (!registration.isPresent()) {
                throw new IllegalArgumentException(String.format("Unknown credential: " + response.getId()));
            }

            if (!userHandle.equals(registration.get().getUserHandle())) {
                throw new IllegalArgumentException(String.format(
                    "User handle ${userHandle} does not own credential ${response.getId}", userHandle, response.getId()));
            }
        }
    }

    @Value
    public class Step3 implements Step<Step2, Step4> {
        private final String username;
        private final ByteArray userHandle;
        private final List<String> prevWarnings;

        @Override
        public Step4 nextStep() {
            return new Step4(username, userHandle, credential(), allWarnings());
        }

        @Override
        public void validate() {
            if (!maybeCredential().isPresent()) {
                throw new IllegalArgumentException(String.format(
                    "Unknown credential. Credential ID: %s, user handle: %s", response.getId(), userHandle
                ));
            }
        }

        private Optional<RegisteredCredential> maybeCredential() {
            return credentialRepository.lookup(response.getId(), userHandle);
        }

        public RegisteredCredential credential() {
            return maybeCredential().get();
        }
    }

    @Value
    public class Step4 implements Step<Step3, Step5> {

        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (clientData().getBytes() == null) {
                throw new IllegalArgumentException("Missing client data.");
            }

            if (authenticatorData().getBytes() == null) {
                throw new IllegalArgumentException("Missing authenticator data.");
            }

            if (signature().getBytes() == null) {
                throw new IllegalArgumentException("Missing signature.");
            }
        }

        @Override
        public Step5 nextStep() {
            return new Step5(username, userHandle, credential, allWarnings());
        }

        public ByteArray authenticatorData() {
            return response.getResponse().getAuthenticatorData();
        }

        public ByteArray clientData() {
            return response.getResponse().getClientDataJSON();
        }

        public ByteArray signature() {
            return response.getResponse().getSignature();
        }
    }

    @Value
    public class Step5 implements Step<Step4, Step6> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        // Nothing to do
        @Override
        public void validate() {
        }

        @Override
        public Step6 nextStep() {
            return new Step6(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step6 implements Step<Step5, Step7> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (clientData() == null) {
                throw new IllegalArgumentException("Missing client data.");
            }
        }

        @Override
        public Step7 nextStep() {
            return new Step7(username, userHandle, credential, clientData(), allWarnings());
        }

        public CollectedClientData clientData() {
            try {
                return response.getResponse().getCollectedClientData();
            } catch (IOException e) {
                throw new IllegalArgumentException("Client data is not valid JSON: " + response.getResponse().getClientDataJSONString());
            } catch (Base64UrlException e) {
                throw new IllegalArgumentException("Malformed client data: " + response.getResponse().getClientDataJSONString());
            }
        }
    }

    @Value
    public class Step7 implements Step<Step6, Step8> {

        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final CollectedClientData clientData;
        private final List<String> prevWarnings;

        private List<String> warnings = new LinkedList<>();

        @Override
        public List<String> getWarnings() {
            return Collections.unmodifiableList(warnings);
        }

        @Override
        public void validate() {
            try {
                if (!
                    CLIENT_DATA_TYPE.equals(clientData.getType())
                    ) {
                    final String message = String.format(
                        "The \"type\" in the client data must be exactly \"%s\", was: %s", CLIENT_DATA_TYPE, clientData.getType()
                    );
                    if (validateTypeAttribute) {
                        throw new IllegalArgumentException(message);
                    } else {
                        warnings.add(message);
                    }
                }
            } catch (NullPointerException e) {
                final String message = "Missing \"type\" attribute in the client data.";
                if (validateTypeAttribute) {
                    throw new IllegalArgumentException(message);
                } else {
                    warnings.add(message);
                }
            }
        }

        @Override
        public Step8 nextStep() {
            return new Step8(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step8 implements Step<Step7, Step9> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            try {
                if (false == request.getPublicKeyCredentialRequestOptions().getChallenge().equals(response.getResponse().getCollectedClientData().getChallenge())) {
                    throw new IllegalArgumentException("Incorrect challenge.");
                }
            } catch (Base64UrlException | IOException e) {
                throw new IllegalArgumentException("Failed to read challenge from client data: " + response.getResponse().getClientDataJSONString());
            }
        }

        @Override
        public Step9 nextStep() {
            return new Step9(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step9 implements Step<Step8, Step10> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            final String responseOrigin;
            try {
                responseOrigin = response.getResponse().getCollectedClientData().getOrigin();
            } catch (IOException | Base64UrlException e) {
                throw new IllegalArgumentException("Failed to read origin from client data: " + response.getResponse().getClientDataJSONString());
            }

            if (origins.stream().noneMatch(new Predicate<String>() {
                @Override
                public boolean test(String o) {
                    return o.equals(responseOrigin);
                }
            })) {
                throw new IllegalArgumentException("Incorrect origin: " + responseOrigin);
            }
        }

        @Override
        public Step10 nextStep() {
            return new Step10(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step10 implements Step<Step9, Step11> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            try {
                TokenBindingValidator.validate(response.getResponse().getCollectedClientData().getTokenBinding(), callerTokenBindingId);
            } catch (IOException | Base64UrlException e) {
                throw new IllegalArgumentException("Failed to read token binding info from client data" + response.getResponse().getClientDataJSONString());
            }
        }

        @Override
        public Step11 nextStep() {
            return new Step11(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step11 implements Step<Step10, Step12> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (false == new ByteArray(crypto.hash(rpId)).equals(response.getResponse().getParsedAuthenticatorData().getRpIdHash())) {
                throw new IllegalArgumentException("Wrong RP ID hash.");
            }
        }

        @Override
        public Step12 nextStep() {
            return new Step12(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step12 implements Step<Step11, Step13> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (request.getPublicKeyCredentialRequestOptions().getUserVerification() == UserVerificationRequirement.REQUIRED) {
                if (!
                    response.getResponse().getParsedAuthenticatorData().getFlags().UV
                    ) {
                    throw new IllegalArgumentException("User Verification is required.");
                }
            }
        }

        @Override
        public Step13 nextStep() {
            return new Step13(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step13 implements Step<Step12, Step14> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (request.getPublicKeyCredentialRequestOptions().getUserVerification() != UserVerificationRequirement.REQUIRED) {
                if (!
                    response.getResponse().getParsedAuthenticatorData().getFlags().UP
                    ) {
                    throw new IllegalArgumentException("User Presence is required.");
                }
            }
        }

        @Override
        public Step14 nextStep() {
            return new Step14(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step14 implements Step<Step13, Step15> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (!allowUnrequestedExtensions) {
                ExtensionsValidation.validate(request.getPublicKeyCredentialRequestOptions().getExtensions(), response);
            }
        }

        @Override
        public List<String> getWarnings() {
            try {
                ExtensionsValidation.validate(request.getPublicKeyCredentialRequestOptions().getExtensions(), response);
                return Collections.emptyList();
            } catch (Exception e) {
                return Collections.unmodifiableList(Collections.singletonList(e.getMessage()));
            }
        }

        @Override
        public Step15 nextStep() {
            return new Step15(username, userHandle, credential, allWarnings());
        }
    }

    @Value
    public class Step15 implements Step<Step14, Step16> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (clientDataJsonHash() == null) {
                throw new IllegalArgumentException("Failed to compute hash of client data");
            }
        }

        @Override
        public Step16 nextStep() {
            return new Step16(username, userHandle, credential, clientDataJsonHash(), allWarnings());
        }

        public ByteArray clientDataJsonHash() {
            return new ByteArray(crypto.hash(response.getResponse().getClientDataJSON().getBytes()));
        }
    }

    @Value
    public class Step16 implements Step<Step15, Step17> {
        private final String username;
        private final ByteArray userHandle;
        private final RegisteredCredential credential;
        private final ByteArray clientDataJsonHash;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            try {
                crypto.checkSignature(
                    credential.publicKey,
                    signedBytes().getBytes(),
                    response.getResponse().getSignature().getBytes()
                );
            } catch (U2fBadInputException e) {
                throw new IllegalArgumentException("Invalid assertion signature.");
            }
        }

        @Override
        public Step17 nextStep() {
            return new Step17(username, userHandle, allWarnings());
        }

        public ByteArray signedBytes() {
            return response.getResponse().getAuthenticatorData().concat(clientDataJsonHash);
        }
    }

    @Value
    public class Step17 implements Step<Step16, Finished> {
        private final String username;
        private final ByteArray userHandle;
        private final List<String> prevWarnings;

        @Override
        public void validate() {
            if (validateSignatureCounter) {
                if (!signatureCounterValid()) {
                    throw new IllegalArgumentException(String.format(
                        "Signature counter must increase. Stored value: %s, received value: %s",
                        storedSignatureCountBefore(), assertionSignatureCount()
                    ));
                }
            }
        }

        private boolean signatureCounterValid() {
            return assertionSignatureCount() == 0
                || assertionSignatureCount() > storedSignatureCountBefore();
        }

        @Override
        public Finished nextStep() {
            return new Finished(username, userHandle, assertionSignatureCount(), signatureCounterValid(), allWarnings());
        }

        private long storedSignatureCountBefore() {
            return credentialRepository.lookup(response.getId(), userHandle)
                .map(new Function<RegisteredCredential, Long>() {
                    @Override
                    public Long apply(RegisteredCredential registeredCredential) {
                        return registeredCredential.getSignatureCount();
                    }
                })
                .orElse(0L);
        }

        private long assertionSignatureCount() {
            return response.getResponse().getParsedAuthenticatorData().getSignatureCounter();
        }
    }

    @Value
    public class Finished implements Step<Step17, Finished> {
        private final String username;
        private final ByteArray userHandle;
        private final long assertionSignatureCount;
        private final boolean signatureCounterValid;
        private final List<String> prevWarnings;

        @Override
        public void validate() { /* No-op */ }

        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public Finished nextStep() {
            return this;
        }

        @Override
        public Optional<AssertionResult> result() {
            return Optional.of(AssertionResult.builder()
                .credentialId(response.getId())
                .signatureCount(assertionSignatureCount)
                .signatureCounterValid(signatureCounterValid)
                .success(true)
                .username(username)
                .userHandle(userHandle)
                .warnings(allWarnings())
                .build()
            );
        }

    }

}
