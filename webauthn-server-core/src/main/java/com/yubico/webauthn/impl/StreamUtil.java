package com.yubico.webauthn.impl;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.experimental.UtilityClass;

@UtilityClass
public class StreamUtil {

    public static <T> Stream<T> toStream(Iterator<T> it) {
        Iterable<T> iterable = new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return it;
            }
        };
        return StreamSupport.stream(iterable.spliterator(), false);
    }

    public static <T> Set<T> toSet(Iterator<T> it) {
        return Collections.unmodifiableSet(toStream(it).collect(Collectors.toSet()));
    }

}
