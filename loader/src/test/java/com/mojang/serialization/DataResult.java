package com.mojang.serialization;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Test stand-in for DataFixerUpper 7+'s DataResult: an interface, with no
 * {@code get()}, no {@code getOrThrow(boolean, Consumer)} and no
 * {@code error(String)}.
 */
public interface DataResult<R> {
    Optional<R> result();

    Optional<Error<R>> error();

    static <R> DataResult<R> success(R value) {
        return new Success<>(value);
    }

    static <R> DataResult<R> error(Supplier<String> message) {
        return new Error<>(message.get(), Optional.empty());
    }

    static <R> DataResult<R> error(Supplier<String> message, R partial) {
        return new Error<>(message.get(), Optional.of(partial));
    }

    record Success<R>(R value) implements DataResult<R> {
        @Override
        public Optional<R> result() {
            return Optional.of(value);
        }

        @Override
        public Optional<Error<R>> error() {
            return Optional.empty();
        }
    }

    record Error<R>(String message, Optional<R> partialValue) implements DataResult<R> {
        @Override
        public Optional<R> result() {
            return Optional.empty();
        }

        @Override
        public Optional<Error<R>> error() {
            return Optional.of(this);
        }
    }
}
