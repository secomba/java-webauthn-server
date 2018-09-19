package com.yubico.webauthn.impl;

import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.TokenBindingInfo;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;


public class TokenBindingValidator {

    public static boolean validate(Optional<TokenBindingInfo> clientTokenBinding, Optional<ByteArray> rpTokenBindingId) {
        return rpTokenBindingId.map(new Function<ByteArray, Boolean>() {
            @Override
            public Boolean apply(ByteArray rpToken) {
                return clientTokenBinding.map(new Function<TokenBindingInfo, Boolean>() {
                    @Override
                    public Boolean apply(TokenBindingInfo tbi) {
                        switch (tbi.getStatus()) {
                            case SUPPORTED:
                            case NOT_SUPPORTED:
                                throw new IllegalArgumentException("Token binding ID set by RP but not by client.");

                            case PRESENT:
                                return tbi.getId().map(new Function<ByteArray, Boolean>() {
                                    @Override
                                    public Boolean apply(ByteArray id) {
                                        if (id.equals(rpToken)) {
                                            return true;
                                        } else {
                                            throw new IllegalArgumentException("Incorrect token binding ID.");
                                        }
                                    }
                                }).orElseThrow(new Supplier<IllegalArgumentException>() {
                                    @Override
                                    public IllegalArgumentException get() {
                                        return new IllegalArgumentException("Property \"id\" missing from \"tokenBinding\" object.");
                                    }
                                });
                        }
                        throw new RuntimeException("Unknown token binding status: " + tbi.getStatus());
                    }
                }).orElseThrow(new Supplier<IllegalArgumentException>() {
                    @Override
                    public IllegalArgumentException get() {
                        return new IllegalArgumentException("Token binding ID set by RP but not by client.");
                    }
                });
            }
        }).orElseGet(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                return clientTokenBinding.map(new Function<TokenBindingInfo, Boolean>() {
                    @Override
                    public Boolean apply(TokenBindingInfo tbi) {
                        switch (tbi.getStatus()) {
                            case SUPPORTED:
                            case NOT_SUPPORTED:
                                return true;

                            case PRESENT:
                                throw new IllegalArgumentException("Token binding ID set by client but not by RP.");
                        }
                        throw new RuntimeException("Unknown token binding status: " + tbi.getStatus());
                    }
                }).orElse(true);
            }
        });
    }

}
