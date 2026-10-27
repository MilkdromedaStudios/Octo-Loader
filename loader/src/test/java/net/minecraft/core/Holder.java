package net.minecraft.core;

import java.util.Objects;

import net.minecraft.core.component.DataComponentMap;

/** A registry entry's holder, as 26.x binds its components: late. */
public interface Holder<T> {
    DataComponentMap components();

    class Reference<T> implements Holder<T> {
        private DataComponentMap components;

        public void bindComponents(DataComponentMap components) {
            this.components = components;
        }

        @Override
        public DataComponentMap components() {
            return Objects.requireNonNull(components, "Components not bound yet");
        }
    }
}
