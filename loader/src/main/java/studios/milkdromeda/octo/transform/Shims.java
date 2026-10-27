package studios.milkdromeda.octo.transform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.objectweb.asm.Type;

/**
 * Members a library removed, rebuilt from what it has now.
 *
 * <p>Some removals are not renames and have no new shape a call can simply be
 * pointed at, but the old behaviour is a line or two of the new API.
 * DataFixerUpper is the one every mod touches: {@code DataResult.get()},
 * which handed back an {@code Either} of the value or the error, went in 1.20.5
 * along with {@code getOrThrow(boolean, Consumer)} and {@code error(String)},
 * and codec code written before then calls all three. A default there is a
 * null {@code Either} one call later — Jade's config codecs failed on exactly
 * that — where the shim answers what the old method did.
 *
 * <p>A shim is a public static method here. It takes the receiver first, as
 * {@code Object}, for an instance method, and the owner's {@code Class} last
 * for a static one; references are passed as {@code Object} and results come
 * back as {@code Object}, cast to what the caller expects at the call site.
 * The library is reached by reflection, since Octo is not built against it.
 */
public final class Shims {
    private static final String OWNER = "studios/milkdromeda/octo/transform/Shims";

    private static final String ENUM_PROPERTY = "net/minecraft/world/level/block/state/properties/EnumProperty";
    private static final String MAKES_ENUM_PROPERTY = ")L" + ENUM_PROPERTY + ";";

    /** What the mod called, in today's class names, to the shim that answers it. */
    private static final String NBT = "net/minecraft/nbt/";
    private static final String NBT_UTILS = NBT + "NbtUtils";
    private static final String COMPOUND = NBT + "CompoundTag";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String RECIPE_MANAGER = CRAFTING + "RecipeManager";

    private static final Map<String, String> SHIMS = Map.ofEntries(
            Map.entry("com/mojang/serialization/DataResult.get()Lcom/mojang/datafixers/util/Either;", "dataResultGet"),
            Map.entry("com/mojang/serialization/DataResult.getOrThrow(ZLjava/util/function/Consumer;)Ljava/lang/Object;",
                    "dataResultGetOrThrow"),
            Map.entry("com/mojang/serialization/DataResult.error(Ljava/lang/String;)"
                    + "Lcom/mojang/serialization/DataResult;", "dataResultError"),
            Map.entry("com/mojang/serialization/DataResult.error(Ljava/lang/String;Ljava/lang/Object;)"
                    + "Lcom/mojang/serialization/DataResult;", "dataResultErrorWithPartial"),
            // DirectionProperty's factories, called on EnumProperty once the
            // class itself has been relinked there.
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;" + MAKES_ENUM_PROPERTY, "directionProperty"),
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;Ljava/util/function/Predicate;"
                    + MAKES_ENUM_PROPERTY, "directionPropertyWhere"),
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;[Lnet/minecraft/core/Direction;"
                    + MAKES_ENUM_PROPERTY, "directionPropertyOf"),
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;Ljava/util/Collection;" + MAKES_ENUM_PROPERTY,
                    "directionPropertyFrom"),
            // The NBT helpers 1.21.5 dropped for codecs.
            Map.entry(NBT_UTILS + ".writeBlockPos(Lnet/minecraft/core/BlockPos;)L" + NBT + "Tag;", "nbtWriteBlockPos"),
            Map.entry(NBT_UTILS + ".readBlockPos(L" + COMPOUND + ";Ljava/lang/String;)Ljava/util/Optional;",
                    "nbtReadBlockPos"),
            Map.entry(NBT_UTILS + ".createUUID(Ljava/util/UUID;)L" + NBT + "IntArrayTag;", "nbtCreateUuid"),
            Map.entry(NBT_UTILS + ".loadUUID(L" + NBT + "Tag;)Ljava/util/UUID;", "nbtLoadUuid"),
            Map.entry(COMPOUND + ".putUUID(Ljava/lang/String;Ljava/util/UUID;)V", "compoundPutUuid"),
            Map.entry(COMPOUND + ".getUUID(Ljava/lang/String;)Ljava/util/UUID;", "compoundGetUuid"),
            Map.entry(COMPOUND + ".hasUUID(Ljava/lang/String;)Z", "compoundHasUuid"),
            Map.entry(COMPOUND + ".putIntArray(Ljava/lang/String;Ljava/util/List;)V", "compoundPutIntList"),
            Map.entry(NBT + "Tag.getAsString()Ljava/lang/String;", "tagAsString"),
            Map.entry(NBT + "StringTag.getAsString()Ljava/lang/String;", "tagAsString"),
            Map.entry(NBT + "CompoundTag.getAsString()Ljava/lang/String;", "tagAsString"),
            Map.entry(NBT + "TagParser.parseTag(Ljava/lang/String;)L" + COMPOUND + ";", "tagParserParseTag"),
            // Item and fluid stacks, saved and read through their codecs since 1.21.5.
            Map.entry("net/minecraft/world/item/ItemStack.saveOptional(L" + PROVIDER + ";)L" + NBT + "Tag;", "stackSaveOptional"),
            Map.entry("net/minecraft/world/item/ItemStack.save(L" + PROVIDER + ";)L" + NBT + "Tag;", "stackSave"),
            Map.entry("net/minecraft/world/item/ItemStack.save(L" + PROVIDER + ";L" + NBT + "Tag;)L" + NBT + "Tag;", "stackSaveInto"),
            Map.entry("net/minecraft/world/item/ItemStack.parse(L" + PROVIDER + ";L" + NBT + "Tag;)Ljava/util/Optional;", "stackParse"),
            Map.entry("net/minecraft/world/item/ItemStack.parseOptional(L" + PROVIDER + ";L" + COMPOUND + ";)Lnet/minecraft/world/item/ItemStack;", "stackParseOptional"),
            Map.entry("net/neoforged/neoforge/fluids/FluidStack.saveOptional(L" + PROVIDER + ";)L" + NBT + "Tag;", "stackSaveOptional"),
            Map.entry("net/neoforged/neoforge/fluids/FluidStack.save(L" + PROVIDER + ";)L" + NBT + "Tag;", "stackSave"),
            Map.entry("net/neoforged/neoforge/fluids/FluidStack.save(L" + PROVIDER + ";L" + NBT + "Tag;)L" + NBT + "Tag;", "stackSaveInto"),
            Map.entry("net/neoforged/neoforge/fluids/FluidStack.parse(L" + PROVIDER + ";L" + NBT + "Tag;)Ljava/util/Optional;", "stackParse"),
            Map.entry("net/neoforged/neoforge/fluids/FluidStack.parseOptional(L" + PROVIDER + ";L" + COMPOUND + ";)Lnet/neoforged/neoforge/fluids/FluidStack;", "stackParseOptional"),
            // Recipes, looked up by key and through the level's recipe access since 1.21.2.
            Map.entry("net/minecraft/world/level/Level.getRecipeManager()L" + RECIPE_MANAGER + ";", "levelRecipeManager"),
            Map.entry("net/minecraft/server/level/ServerLevel.getRecipeManager()L" + RECIPE_MANAGER + ";",
                    "levelRecipeManager"),
            Map.entry(CRAFTING + "RecipeHolder.id()Lnet/minecraft/resources/Identifier;", "recipeHolderId"),
            Map.entry(CRAFTING + "RecipeType.simple(Lnet/minecraft/resources/Identifier;)L" + CRAFTING + "RecipeType;",
                    "recipeTypeSimple"),
            Map.entry(RECIPE_MANAGER + ".byKey(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;",
                    "recipeManagerByKey"),
            Map.entry(RECIPE_MANAGER + ".getAllRecipesFor(L" + CRAFTING + "RecipeType;)Ljava/util/List;",
                    "recipeManagerAllFor"),
            Map.entry(RECIPE_MANAGER + ".getRecipesFor(L" + CRAFTING + "RecipeType;L" + CRAFTING
                    + "RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;", "recipeManagerMatching"));

    private Shims() {
    }

    /** @return the shim's name for this member, or {@code null} when there is none */
    static String shimFor(String owner, String name, String descriptor) {
        return SHIMS.get(owner + "." + name + descriptor);
    }

    /**
     * Static fields removed, rebuilt the same way: a shim taking the owner's
     * {@code Class} and answering the value.
     */
    private static final Map<String, String> FIELD_SHIMS = Map.of(
            // NeoForge 26 kept only FluidIngredient.CODEC; Create builds every
            // processing recipe's codec on the map codec, and without it not
            // one of its machine recipes could be read.
            "net/neoforged/neoforge/fluids/crafting/FluidIngredient.MAP_CODEC_NONEMPTY:"
                    + "Lcom/mojang/serialization/MapCodec;", "fluidIngredientMapCodec",
            "net/neoforged/neoforge/fluids/FluidStack.FLUID_NON_EMPTY_CODEC:Lcom/mojang/serialization/Codec;",
            "fluidHolderCodec");

    /** @return the shim's name for this static field, or {@code null} when there is none */
    static String fieldShimFor(String owner, String name, String descriptor) {
        return FIELD_SHIMS.get(owner + "." + name + ":" + descriptor);
    }

    static String owner() {
        return OWNER;
    }

    /** The shim's own descriptor, by the convention above. */
    static String descriptorFor(String descriptor, boolean isStatic) {
        StringBuilder out = new StringBuilder("(");

        if (!isStatic) {
            out.append("Ljava/lang/Object;");
        }

        for (Type argument : Type.getArgumentTypes(descriptor)) {
            out.append(erased(argument));
        }

        if (isStatic) {
            out.append("Ljava/lang/Class;");
        }

        return out.append(')').append(erased(Type.getReturnType(descriptor))).toString();
    }

    private static String erased(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? "Ljava/lang/Object;"
                : type.getDescriptor();
    }

    // ------------------------------------------------------------ DataFixerUpper

    /** {@code DataResult.get()}: the value on the left, or the error on the right. */
    public static Object dataResultGet(Object result) {
        Optional<?> value = (Optional<?>) call(result, "result");

        if (value.isPresent()) {
            return either(result, "left", value.get());
        }

        Optional<?> error = (Optional<?>) call(result, "error");
        return either(result, "right", error.orElse(null));
    }

    /**
     * {@code DataResult.getOrThrow(allowPartial, onError)}: the value, or the
     * error reported and then the partial value if allowed, or an exception.
     */
    @SuppressWarnings("unchecked")
    public static Object dataResultGetOrThrow(Object result, boolean allowPartial, Object onError) {
        Optional<?> value = (Optional<?>) call(result, "result");

        if (value.isPresent()) {
            return value.get();
        }

        Optional<?> error = (Optional<?>) call(result, "error");
        String message = error.map(found -> String.valueOf(call(found, "message"))).orElse("unknown error");

        if (onError instanceof Consumer<?> consumer) {
            ((Consumer<String>) consumer).accept(message);
        }

        if (allowPartial && error.isPresent()) {
            Optional<?> partial = (Optional<?>) call(error.get(), "partialValue");

            if (partial.isPresent()) {
                return partial.get();
            }
        }

        throw new RuntimeException(message);
    }

    /** {@code DataResult.error(String)}, now {@code error(Supplier)}. */
    public static Object dataResultError(Object message, Class<?> owner) {
        return callStatic(owner, "error", new Class<?>[] { Supplier.class },
                (Supplier<String>) () -> String.valueOf(message));
    }

    /** {@code DataResult.error(String, partial)}, now {@code error(Supplier, partial)}. */
    public static Object dataResultErrorWithPartial(Object message, Object partial, Class<?> owner) {
        return callStatic(owner, "error", new Class<?>[] { Supplier.class, Object.class },
                (Supplier<String>) () -> String.valueOf(message), partial);
    }

    private static Object either(Object context, String side, Object value) {
        try {
            Class<?> either = Class.forName("com.mojang.datafixers.util.Either", false,
                    context.getClass().getClassLoader());
            return either.getMethod(side, Object.class).invoke(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DataFixerUpper's Either is not reachable", e);
        }
    }

    // ------------------------------------------------------ block state properties

    /**
     * {@code DirectionProperty.create(name)}, every direction. DirectionProperty
     * went in 1.21.2, when a property of directions became an
     * {@code EnumProperty<Direction>} like any other; restored, it would extend
     * a class that is final now. Its factories are EnumProperty's with the enum
     * class filled in.
     */
    public static Object directionProperty(Object name, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class }, name, direction(owner));
    }

    /** {@code DirectionProperty.create(name, filter)}. */
    public static Object directionPropertyWhere(Object name, Object filter, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class, Predicate.class }, name,
                direction(owner), filter);
    }

    /** {@code DirectionProperty.create(name, directions...)}. */
    public static Object directionPropertyOf(Object name, Object directions, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class, Enum[].class }, name,
                direction(owner), directions);
    }

    /** {@code DirectionProperty.create(name, directions)}, from any collection. */
    public static Object directionPropertyFrom(Object name, Object directions, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class, List.class }, name,
                direction(owner), new ArrayList<>((Collection<?>) directions));
    }

    private static Class<?> direction(Class<?> context) {
        try {
            return Class.forName("net.minecraft.core.Direction", false, context.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game's Direction is not reachable", e);
        }
    }

    // ------------------------------------------------------------------- NBT

    /** {@code NbtUtils.writeBlockPos(pos)}: the position as an int array of x, y and z. */
    public static Object nbtWriteBlockPos(Object pos, Class<?> owner) {
        return intArrayTag(owner, new int[] { (int) call(pos, "getX"), (int) call(pos, "getY"),
                (int) call(pos, "getZ") });
    }

    /** {@code NbtUtils.readBlockPos(tag, key)}: the position written that way, if there is one. */
    public static Object nbtReadBlockPos(Object tag, Object key, Class<?> owner) {
        Optional<?> array = (Optional<?>) callWith(tag, "getIntArray", new Class<?>[] { String.class }, key);
        return array.filter(found -> ((int[]) found).length == 3).map(found -> {
            int[] xyz = (int[]) found;
            return construct(game(owner, "net.minecraft.core.BlockPos"),
                    new Class<?>[] { int.class, int.class, int.class }, xyz[0], xyz[1], xyz[2]);
        });
    }

    /** {@code NbtUtils.createUUID(uuid)}: the four ints of it. */
    public static Object nbtCreateUuid(Object uuid, Class<?> owner) {
        return intArrayTag(owner, uuidInts(owner, uuid));
    }

    /** {@code NbtUtils.loadUUID(tag)}: the UUID in an int array tag, or null. */
    public static Object nbtLoadUuid(Object tag, Class<?> owner) {
        return uuidOf(owner, tag);
    }

    /** {@code CompoundTag.putUUID(key, uuid)}. */
    public static void compoundPutUuid(Object tag, Object key, Object uuid) {
        callWith(tag, "putIntArray", new Class<?>[] { String.class, int[].class }, key,
                uuidInts(tag.getClass(), uuid));
    }

    /** {@code CompoundTag.getUUID(key)}: the UUID put there, or null. */
    public static Object compoundGetUuid(Object tag, Object key) {
        return uuidOf(tag.getClass(), callWith(tag, "get", new Class<?>[] { String.class }, key));
    }

    /** {@code CompoundTag.hasUUID(key)}. */
    public static boolean compoundHasUuid(Object tag, Object key) {
        return compoundGetUuid(tag, key) != null;
    }

    /** {@code CompoundTag.putIntArray(key, list)}. */
    public static void compoundPutIntList(Object tag, Object key, Object values) {
        int[] array = ((List<?>) values).stream().mapToInt(value -> ((Number) value).intValue()).toArray();
        callWith(tag, "putIntArray", new Class<?>[] { String.class, int[].class }, key, array);
    }

    /** {@code Tag.getAsString()}: a string tag's string, and anything else as written in commands. */
    public static Object tagAsString(Object tag) {
        Optional<?> string = (Optional<?>) call(tag, "asString");
        return string.isPresent() ? string.get() : String.valueOf(tag);
    }

    /** {@code TagParser.parseTag(text)}, now {@code parseCompoundFully}. */
    public static Object tagParserParseTag(Object text, Class<?> owner) {
        return callStatic(owner, "parseCompoundFully", new Class<?>[] { String.class }, text);
    }

    private static Object intArrayTag(Class<?> context, int[] values) {
        return construct(game(context, "net.minecraft.nbt.IntArrayTag"), new Class<?>[] { int[].class },
                (Object) values);
    }

    private static int[] uuidInts(Class<?> context, Object uuid) {
        return (int[]) callStatic(game(context, "net.minecraft.core.UUIDUtil"), "uuidToIntArray",
                new Class<?>[] { java.util.UUID.class }, uuid);
    }

    private static Object uuidOf(Class<?> context, Object tag) {
        if (tag == null || !tag.getClass().getName().equals("net.minecraft.nbt.IntArrayTag")) {
            return null;
        }

        int[] values = (int[]) call(tag, "getAsIntArray");
        return values.length != 4 ? null : callStatic(game(context, "net.minecraft.core.UUIDUtil"),
                "uuidFromIntArray", new Class<?>[] { int[].class }, (Object) values);
    }

    private static Class<?> game(Class<?> context, String name) {
        try {
            return Class.forName(name, true, context.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game's " + name + " is not reachable", e);
        }
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... arguments) {
        try {
            return type.getConstructor(parameters).newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " could not be made", e);
        }
    }

    /** A public method with parameters, on the receiver's class. */
    private static Object callWith(Object target, String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = target.getClass().getMethod(name, parameters);
            method.setAccessible(true);
            return invoke(method, target, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(target.getClass().getName() + " has no " + name, e);
        }
    }

    // -------------------------------------------------------------- NeoForge

    /** {@code FluidStack.FLUID_NON_EMPTY_CODEC}: a fluid, by its registry name. */
    public static Object fluidHolderCodec(Class<?> owner) {
        Object fluids = staticField(game(owner, "net.minecraft.core.registries.BuiltInRegistries"), "FLUID");
        return callWith(fluids, "holderByNameCodec", new Class<?>[0]);
    }

    /** {@code FluidIngredient.MAP_CODEC_NONEMPTY}: its codec, read as a map. */
    public static Object fluidIngredientMapCodec(Class<?> owner) {
        Object codec = staticField(owner, "CODEC");
        Class<?> codecType = game(owner, "com.mojang.serialization.Codec");
        return callStatic(game(owner, "com.mojang.serialization.MapCodec"), "assumeMapUnsafe",
                new Class<?>[] { codecType }, codec);
    }

    // ---------------------------------------------------------------- stacks

    /**
     * {@code stack.saveOptional(registries)}: the stack as a tag, an empty
     * compound when it is empty. An item or fluid stack, saved the way the
     * game saves one now — through its codec.
     */
    public static Object stackSaveOptional(Object stack, Object registries) {
        if (Boolean.TRUE.equals(call(stack, "isEmpty"))) {
            return construct(game(stack.getClass(), "net.minecraft.nbt.CompoundTag"), new Class<?>[0]);
        }

        return stackSave(stack, registries);
    }

    /** {@code stack.save(registries)}. */
    public static Object stackSave(Object stack, Object registries) {
        Object result = callWith(staticField(stack.getClass(), "CODEC"), "encodeStart",
                new Class<?>[] { game(stack.getClass(), "com.mojang.serialization.DynamicOps"), Object.class },
                nbtOps(stack.getClass(), registries), stack);
        Optional<?> tag = (Optional<?>) call(result, "result");
        return tag.isPresent() ? tag.get()
                : construct(game(stack.getClass(), "net.minecraft.nbt.CompoundTag"), new Class<?>[0]);
    }

    /** {@code stack.save(registries, into)}: saved into the compound given. */
    public static Object stackSaveInto(Object stack, Object registries, Object into) {
        Object saved = stackSave(stack, registries);
        Class<?> compound = game(stack.getClass(), "net.minecraft.nbt.CompoundTag");

        if (compound.isInstance(into) && compound.isInstance(saved)) {
            callWith(into, "merge", new Class<?>[] { compound }, saved);
            return into;
        }

        return saved;
    }

    /** {@code ItemStack.parse(registries, tag)}: the stack, if the tag holds one. */
    public static Object stackParse(Object registries, Object tag, Class<?> owner) {
        Object result = callWith(staticField(owner, "CODEC"), "parse",
                new Class<?>[] { game(owner, "com.mojang.serialization.DynamicOps"), Object.class },
                nbtOps(owner, registries), tag);
        return call(result, "result");
    }

    /** {@code ItemStack.parseOptional(registries, tag)}: the stack, or the empty one. */
    public static Object stackParseOptional(Object registries, Object tag, Class<?> owner) {
        Object empty = staticField(owner, "EMPTY");

        if (tag == null || Boolean.TRUE.equals(call(tag, "isEmpty"))) {
            return empty;
        }

        Optional<?> stack = (Optional<?>) stackParse(registries, tag, owner);
        return stack.isPresent() ? stack.get() : empty;
    }

    /** The NBT ops a stack is written with: with the registries' context when there are some. */
    private static Object nbtOps(Class<?> context, Object registries) {
        Object ops = staticField(game(context, "net.minecraft.nbt.NbtOps"), "INSTANCE");

        if (registries == null) {
            return ops;
        }

        return callWith(registries, "createSerializationContext",
                new Class<?>[] { game(context, "com.mojang.serialization.DynamicOps") }, ops);
    }

    // ---------------------------------------------------------------- recipes

    /**
     * {@code level.getRecipeManager()}: the server's recipe manager, which
     * a server level's {@code recipeAccess()} is. A client level has only the
     * recipes the server sent it, and no manager; that answers null.
     */
    public static Object levelRecipeManager(Object level) {
        Object access = call(level, "recipeAccess");

        if (access != null && access.getClass().getName().equals("net.minecraft.world.item.crafting.RecipeManager")) {
            return access;
        }

        try {
            Object server = call(level, "getServer");
            return server == null ? null : call(server, "getRecipeManager");
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * {@code RecipeType.simple(id)}: a recipe type that is nothing but its
     * name. 26.x only makes them through {@code RecipeType.register}, which
     * puts them in the registry itself; a mod registers its own.
     */
    public static Object recipeTypeSimple(Object id, Class<?> owner) {
        String name = String.valueOf(id);
        return java.lang.reflect.Proxy.newProxyInstance(owner.getClassLoader(), new Class<?>[] { owner },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> method.isDefault()
                            ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, arguments) : null;
                });
    }

    /** {@code holder.id()}, which was the recipe's identifier and is its registry key now. */
    public static Object recipeHolderId(Object holder) {
        Object key = call(holder, "id");
        return key == null ? null : call(key, "identifier");
    }

    /** {@code manager.byKey(id)}, which takes the recipe's registry key now. */
    public static Object recipeManagerByKey(Object manager, Object id) {
        Class<?> resourceKey = game(manager.getClass(), "net.minecraft.resources.ResourceKey");
        Object registry = staticField(game(manager.getClass(), "net.minecraft.core.registries.Registries"), "RECIPE");
        Object key = callStatic(resourceKey, "create", new Class<?>[] { resourceKey, id.getClass() }, registry, id);
        return callWith(manager, "byKey", new Class<?>[] { resourceKey }, key);
    }

    /** {@code manager.getAllRecipesFor(type)}: every recipe of that type. */
    public static Object recipeManagerAllFor(Object manager, Object type) {
        List<Object> out = new ArrayList<>();

        for (Object holder : (Collection<?>) call(manager, "getRecipes")) {
            if (call(call(holder, "value"), "getType") == type) {
                out.add(holder);
            }
        }

        return out;
    }

    /** {@code manager.getRecipesFor(type, input, level)}: those of the type that match. */
    public static Object recipeManagerMatching(Object manager, Object type, Object input, Object level) {
        List<Object> out = new ArrayList<>();
        Class<?> inputType = game(manager.getClass(), "net.minecraft.world.item.crafting.RecipeInput");
        Class<?> levelType = game(manager.getClass(), "net.minecraft.world.level.Level");

        for (Object holder : (List<?>) recipeManagerAllFor(manager, type)) {
            Object recipe = call(holder, "value");

            if (Boolean.TRUE.equals(callWith(recipe, "matches", new Class<?>[] { inputType, levelType }, input,
                    level))) {
                out.add(holder);
            }
        }

        return out;
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            return owner.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getName() + " has no " + name, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A public no-argument method, looked up on the public type that declares it. */
    private static Object call(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            Method found = publicMethod(type, name);

            if (found != null) {
                return invoke(found, target);
            }
        }

        throw new IllegalStateException(target.getClass().getName() + " has no " + name + "()");
    }

    private static Method publicMethod(Class<?> type, String name) {
        for (Class<?> iface : type.getInterfaces()) {
            try {
                return iface.getMethod(name);
            } catch (NoSuchMethodException e) {
                Method inherited = publicMethod(iface, name);

                if (inherited != null) {
                    return inherited;
                }
            }
        }

        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    private static Object callStatic(Class<?> owner, String name, Class<?>[] parameters, Object... arguments) {
        try {
            return invoke(owner.getMethod(name, parameters), null, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(owner.getName() + " has no " + name, e);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
