package org.quiltmc.loader.api;

import java.util.Collection;

/** The descriptive half of a Quilt mod's metadata. */
public interface ModMetadata {
    String id();

    String group();

    String name();

    String description();

    String version();

    Collection<String> licenses();
}
