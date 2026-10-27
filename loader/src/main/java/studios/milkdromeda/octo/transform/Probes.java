package studios.milkdromeda.octo.transform;

/**
 * {@code Class.forName} and {@code ClassLoader.loadClass} as a mod calls them.
 *
 * <p>Asking for a class by name is how mods find out whether another mod is
 * installed: {@code Class.forName("xaero.map.WorldMap")} is Xaero's Minimap
 * looking for Xaero's World Map. Octo stands in for classes a mod names and
 * this game does not have, which is right for a reference the mod would never
 * have reached, and wrong for this: the minimap took the stand-in as World Map
 * being there, built its World Map support, and failed. Its own library did
 * the same with a permissions mod.
 *
 * <p>So {@link ProbeRewriter} sends a mod's calls here, and a stand-in that
 * comes back is reported as what it is, a class that is not there. The JVM
 * answers a name it has already resolved without asking any loader, so this
 * has to happen at the call, not in the loader.
 */
public final class Probes {
    private static final StackWalker CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** A loader that can say which of its classes are stand-ins. */
    public interface StandIns {
        boolean isStandIn(Class<?> type);
    }

    private Probes() {
    }

    /** {@code Class.forName(name)}, which uses its caller's loader. */
    public static Class<?> forName(String name) throws ClassNotFoundException {
        return real(Class.forName(name, true, CALLER.getCallerClass().getClassLoader()), name);
    }

    public static Class<?> forName(String name, boolean initialize, ClassLoader loader)
            throws ClassNotFoundException {
        return real(Class.forName(name, initialize, loader), name);
    }

    public static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        return real(loader.loadClass(name), name);
    }

    private static Class<?> real(Class<?> found, String name) throws ClassNotFoundException {
        if (found.getClassLoader() instanceof StandIns loader && loader.isStandIn(found)) {
            throw new ClassNotFoundException(name);
        }

        return found;
    }
}
