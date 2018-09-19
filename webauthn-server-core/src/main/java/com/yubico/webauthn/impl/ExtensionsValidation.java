package com.yubico.webauthn.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.upokecenter.cbor.CBORObject;
import com.yubico.webauthn.data.AuthenticatorResponse;
import com.yubico.webauthn.data.PublicKeyCredential;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;


@UtilityClass
public class ExtensionsValidation {

    public static boolean validate(Optional<JsonNode> requested, PublicKeyCredential<? extends AuthenticatorResponse> response) {
        if (requested.isPresent() && !requested.get().isObject()) {
            throw new IllegalArgumentException(String.format(
                "Requested extensions must be a JSON object, was: %s",
                requested.get()
            ));
        }

        Set<String> requestedExtensionIds = requested.map(new Function<JsonNode, Set<String>>() {
            @Override
            public Set<String> apply(JsonNode req) {
                return StreamUtil.toSet(req.fieldNames());
            }
        }).orElseGet(new Supplier<Set<String>>() {
            @Override
            public Set<String> get() {
                return new HashSet<String>();
            }
        });
        Set<String> clientExtensionIds = StreamUtil.toSet(response.getClientExtensionResults().fieldNames());

        if (!requestedExtensionIds.containsAll(clientExtensionIds)) {
            throw new IllegalArgumentException(String.format(
                "Client extensions {%s} are not a subset of requested extensions {%s}.",
                String.join(", ", clientExtensionIds),
                String.join(", ", requestedExtensionIds)
            ));
        }

        Set<String> authenticatorExtensionIds = response.getResponse().getParsedAuthenticatorData().getExtensions()
            .map(new Function<CBORObject, Set<String>>() {
                     @Override
                     public Set<String> apply(CBORObject extensions) {
                         return extensions.getKeys().stream()
                                 .map(new Function<CBORObject, String>() {
                                     @Override
                                     public String apply(CBORObject cborObject) {
                                         return cborObject.AsString();
                                     }
                                 })
                                 .collect(Collectors.toSet());
                     }
                 }
            )
            .orElseGet(new Supplier<Set<String>>() {
                @Override
                public Set<String> get() {
                    return new HashSet<String>();
                }
            });

        if (!requestedExtensionIds.containsAll(authenticatorExtensionIds)) {
            throw new IllegalArgumentException(String.format(
                "Authenticator extensions {%s} are not a subset of requested extensions {%s}.",
                String.join(", ", authenticatorExtensionIds),
                String.join(", ", requestedExtensionIds)
            ));
        }

        return true;
    }

}
