package studios.milkdromeda.octo.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * NeoForge's custom ingredients, held in the game's own {@code Ingredient}.
 *
 * <p>NeoForge patches the ingredient to carry an {@code ICustomIngredient} —
 * a compound of others, a difference, items with given components — and the
 * custom one's {@code toVanilla()} is {@code new Ingredient(this)}. This game
 * has no such constructor, and Create's sequenced assembly recipes, each a
 * compound of the transitional item and the step's own ingredient, all
 * read as nothing.
 *
 * <p>Here the custom ingredient becomes an ingredient of the items it
 * accepts: a set of items that asks the custom one which they are the first
 * time it is needed, after tags are bound, rather than while the recipe is
 * read, when a tag inside it would still be empty. Each such ingredient
 * remembers the custom one it was made from, for code that asks.
 */
public final class CustomIngredients {
    private static final String INGREDIENT = "net.minecraft.world.item.crafting.Ingredient";

    private static final Map<Object, Object> CUSTOM = Collections.synchronizedMap(new WeakHashMap<>());

    private CustomIngredients() {
    }

    /** {@code new Ingredient(custom)}. */
    public static Object make(Object custom) throws ReflectiveOperationException {
        return make(custom, custom.getClass().getClassLoader());
    }

    /** An ingredient that accepts nothing: 1.21.1's {@code Ingredient.EMPTY}. */
    public static Object empty(ClassLoader loader) throws ReflectiveOperationException {
        return make(null, loader);
    }

    private static Object make(Object custom, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> ingredient = Class.forName(INGREDIENT, true, loader);
        Class<?> holderSet = Class.forName("net.minecraft.core.HolderSet", false, loader);
        LazyItems handler = new LazyItems(custom, loader);
        Object items = Proxy.newProxyInstance(loader, new Class<?>[] { holderSet }, handler);
        Object made;

        // The ingredient checks its list is not empty as it is made; it is
        // not asked for its list until then, when there may be none yet.
        handler.making = true;

        try {
            made = ingredient.getMethod("of", holderSet).invoke(null, items);
        } finally {
            handler.making = false;
        }

        if (custom != null) {
            CUSTOM.put(made, custom);
        }

        return made;
    }

    /** {@code ingredient.getCustomIngredient()}: what it was made from, or null for a plain one. */
    public static Object customOf(Object ingredient) {
        return ingredient == null ? null : CUSTOM.get(ingredient);
    }

    /** The items an ingredient accepts as stacks of one: {@code getItems()} as it was until 1.21.2. */
    public static Object stacks(Object ingredient) throws ReflectiveOperationException {
        ClassLoader loader = ingredient.getClass().getClassLoader();
        Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack", false, loader);
        Class<?> holder = Class.forName("net.minecraft.core.Holder", false, loader);
        java.lang.reflect.Constructor<?> fromHolder = stack.getConstructor(holder);
        List<?> holders = ((Stream<?>) ingredient.getClass().getMethod("items").invoke(ingredient)).toList();
        Object out = java.lang.reflect.Array.newInstance(stack, holders.size());

        for (int i = 0; i < holders.size(); i++) {
            java.lang.reflect.Array.set(out, i, fromHolder.newInstance(holders.get(i)));
        }

        return out;
    }

    /**
     * A set of items worked out from a custom ingredient the first time it
     * is asked, and kept once it could be; while tags are not yet bound,
     * asking again tries again.
     */
    private static final class LazyItems implements InvocationHandler {
        private final Object custom;
        private final ClassLoader loader;
        private volatile List<Object> items;
        volatile boolean making;

        LazyItems(Object custom, ClassLoader loader) {
            this.custom = custom;
            this.loader = loader;
        }

        @SuppressWarnings("unchecked")
        private List<Object> items() {
            List<Object> known = items;

            if (known != null) {
                return known;
            }

            if (custom == null) {
                return items = List.of();
            }

            try {
                Stream<Object> stream = (Stream<Object>) custom.getClass().getMethod("items").invoke(custom);
                List<Object> found = stream.distinct().collect(Collectors.toUnmodifiableList());
                items = found;
                return found;
            } catch (InvocationTargetException e) {
                // A tag not bound yet: nothing for now, and asked again later.
                return List.of();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            switch (method.getName()) {
                case "stream":
                    return items().stream();
                case "size":
                    return items().size();
                case "isBound":
                case "canSerializeIn":
                    return true;
                case "unwrap": {
                    Method side = Class.forName("com.mojang.datafixers.util.Either", false, loader)
                            .getMethod(making ? "left" : "right", Object.class);
                    return side.invoke(null, making ? null : items());
                }
                case "unwrapKey":
                    return Optional.empty();
                case "getRandomElement": {
                    List<Object> known = items();

                    if (known.isEmpty()) {
                        return Optional.empty();
                    }

                    int at = (int) arguments[0].getClass().getMethod("nextInt", int.class)
                            .invoke(arguments[0], known.size());
                    return Optional.of(known.get(at));
                }
                case "get":
                    return items().get((int) arguments[0]);
                case "contains":
                    return items().contains(arguments[0]);
                case "iterator":
                    return items().iterator();
                case "spliterator":
                    return items().spliterator();
                case "forEach":
                    items().forEach((java.util.function.Consumer<Object>) arguments[0]);
                    return null;
                case "equals":
                    return proxy == arguments[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Custom[" + custom + "]";
                default:
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, arguments);
                    }

                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }
}
