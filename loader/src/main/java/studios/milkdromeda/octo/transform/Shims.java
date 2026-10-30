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
    private static final String TOOL_RULE = "net/minecraft/world/item/component/Tool$Rule";
    private static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
    private static final String MODEL_LOCATION = "net/minecraft/client/resources/model/ModelResourceLocation";
    private static final String BLOCK_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
    private static final String OLD_BLOCK_MODEL = "net/minecraft/client/renderer/block/model/BlockStateModel";
    private static final String NEO_MODEL_EVENT = "net/neoforged/neoforge/client/event/ModelEvent";
    private static final String FORGE_MODEL_EVENT = "net/minecraftforge/client/event/ModelEvent";
    private static final String QUAD = "net/minecraft/client/resources/model/geometry/BakedQuad";
    private static final String BUFFER_BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String RENDER_TYPE_NAME = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";

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
                    + "RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;", "recipeManagerMatching"),
            // NeoForge's own patch to the game: the order its registries were
            // made in, which is the order NeoForge registers mods' content in.
            // Without it the order was alphabetical, blocks came before sound
            // events, and every block that plays its own sound failed.
            Map.entry("net/minecraft/core/registries/BuiltInRegistries.getVanillaRegistrationOrder()Ljava/util/Set;",
                    "vanillaRegistrationOrder"),
            // NeoForge's and Forge's tag factories, patched into the game's tag
            // classes. Every mod tag made through one was a null, and the
            // first block asked whether it was in it ended the server.
            tagFactory("ItemTags"),
            tagFactory("BlockTags"),
            tagFactory("FluidTags"),
            tagFactory("EntityTypeTags"),
            tagFactory("BiomeTags"),
            Map.entry("net/minecraft/world/item/DyeColor.getTag()Lnet/minecraft/tags/TagKey;", "dyeColorTag"),
            // Tool rules named a block tag until 26.x, which takes the blocks themselves.
            Map.entry(TOOL_RULE + ".minesAndDrops(Lnet/minecraft/tags/TagKey;F)L" + TOOL_RULE + ";",
                    "toolRuleMinesAndDrops"),
            Map.entry(TOOL_RULE + ".deniesDrops(Lnet/minecraft/tags/TagKey;)L" + TOOL_RULE + ";", "toolRuleDeniesDrops"),
            Map.entry(TOOL_RULE + ".overrideSpeed(Lnet/minecraft/tags/TagKey;F)L" + TOOL_RULE + ";",
                    "toolRuleOverrideSpeed"),
            // NeoForge's and Forge's creative tab builder, patched in beside
            // the game's, which wants a row and column: mods' tabs go in the
            // top row, and the loader's tab sorting places them.
            Map.entry("net/minecraft/world/item/CreativeModeTab.builder()Lnet/minecraft/world/item/CreativeModeTab$Builder;",
                    "creativeTabBuilder"),
            // The block layers a block entity renderer drew moving parts in;
            // 26.x keeps their equivalents for moving blocks in RenderTypes.
            blockLayer("solid", "renderTypeSolid"),
            blockLayer("cutout", "renderTypeCutout"),
            blockLayer("cutoutMipped", "renderTypeCutout"),
            blockLayer("translucent", "renderTypeTranslucent"),
            blockLayer("tripwire", "renderTypeTranslucent"),
            // A pose turned by a quaternion: 26.x turns about a point instead.
            Map.entry("com/mojang/blaze3d/vertex/PoseStack.mulPose(Lorg/joml/Quaternionf;)V", "poseStackRotate"),
            Map.entry("com/mojang/blaze3d/vertex/PoseStack.mulPose(Lorg/joml/Quaternionfc;)V", "poseStackRotate"),
            // And the list of them, which mods build a buffer per layer from.
            Map.entry(RENDER_TYPE_NAME + ".chunkBufferLayers()Ljava/util/List;", "renderTypeLayers"),
            // The game's block renderer, gone with 26.x's models; the one
            // added back from the mod's release is answered by LegacyModels.
            Map.entry("net/minecraft/client/Minecraft.getBlockRenderer()"
                    + "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;", "blockRenderer"),
            // Models asked for by name, and every baked model by its location:
            // answered from what ExtraModels loaded with the game's models.
            Map.entry(MODEL_MANAGER + ".getModel(L" + MODEL_LOCATION + ";)L" + BLOCK_MODEL + ";", "modelManagerModel"),
            Map.entry(MODEL_MANAGER + ".getModel(Lnet/minecraft/resources/Identifier;)L" + BLOCK_MODEL + ";",
                    "modelManagerModel"),
            Map.entry(MODEL_MANAGER + ".getMissingModel()L" + BLOCK_MODEL + ";", "modelManagerMissing"),
            // A registry as a lookup: since 1.21.2 a registry is one itself.
            Map.entry("net/minecraft/core/Registry.asLookup()Lnet/minecraft/core/HolderLookup$RegistryLookup;",
                    "registryAsLookup"),
            Map.entry("net/minecraft/core/DefaultedRegistry.asLookup()Lnet/minecraft/core/HolderLookup$RegistryLookup;",
                    "registryAsLookup"),
            Map.entry("net/minecraft/core/WritableRegistry.asLookup()Lnet/minecraft/core/HolderLookup$RegistryLookup;",
                    "registryAsLookup"),
            // NeoForge's patch to the model location, which the class added
            // back from the game alone does not have: a loose model's location.
            Map.entry(MODEL_LOCATION + ".standalone(Lnet/minecraft/resources/Identifier;)L" + MODEL_LOCATION + ";",
                    "modelLocationStandalone"),
            // The same, named as 1.21.5 to 1.21.11 had the model type, which is
            // where code added back from those releases' tables points.
            Map.entry(MODEL_MANAGER + ".getModel(L" + MODEL_LOCATION + ";)L" + OLD_BLOCK_MODEL + ";",
                    "modelManagerModel"),
            Map.entry(MODEL_MANAGER + ".getMissingModel()L" + OLD_BLOCK_MODEL + ";", "modelManagerMissing"),
            Map.entry("net/minecraft/client/resources/model/ModelBakery.getBakedTopLevelModels()Ljava/util/Map;",
                    "bakedModels"),
            Map.entry(NEO_MODEL_EVENT + "$BakingCompleted.getModels()Ljava/util/Map;", "bakedModels"),
            Map.entry(NEO_MODEL_EVENT + "$ModifyBakingResult.getModels()Ljava/util/Map;", "bakedModels"),
            Map.entry(FORGE_MODEL_EVENT + "$BakingCompleted.getModels()Ljava/util/Map;", "bakedModels"),
            Map.entry(FORGE_MODEL_EVENT + "$ModifyBakingResult.getModels()Ljava/util/Map;", "bakedModels"),
            Map.entry(NEO_MODEL_EVENT + "$RegisterAdditional.register(L" + MODEL_LOCATION + ";)V", "registerModel"),
            Map.entry(NEO_MODEL_EVENT + "$RegisterAdditional.register(Lnet/minecraft/resources/Identifier;)V",
                    "registerModel"),
            Map.entry(FORGE_MODEL_EVENT + "$RegisterAdditional.register(Lnet/minecraft/resources/Identifier;)V",
                    "registerModel"),
            // A baked quad as its vertex data, which 26.x made a record of
            // positions and a material; read and made by LegacyQuads.
            Map.entry(QUAD + ".getVertices()[I", "quadVertices"),
            Map.entry(QUAD + ".getSprite()L" + SPRITE + ";", "quadSprite"),
            Map.entry(QUAD + ".getTintIndex()I", "quadTint"),
            Map.entry(QUAD + ".isTinted()Z", "quadTinted"),
            Map.entry(QUAD + ".isShade()Z", "quadShade"),
            Map.entry(QUAD + ".hasAmbientOcclusion()Z", "quadAmbientOcclusion"),
            Map.entry(QUAD + ".getDirection()Lnet/minecraft/core/Direction;", "quadDirection"),
            Map.entry(QUAD + ".<init>([IILnet/minecraft/core/Direction;L" + SPRITE + ";Z)V", "newQuad"),
            Map.entry(QUAD + ".<init>([IILnet/minecraft/core/Direction;L" + SPRITE + ";ZZ)V", "newQuadWithOcclusion"),
            // A buffer builder made with the draw mode, which 26.x calls a primitive topology.
            Map.entry(BUFFER_BUILDER + ".<init>(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;"
                    + "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/renderpearl/api/vertex/VertexFormat;)V",
                    "newBufferBuilder"),
            Map.entry(BUFFER_BUILDER + ".<init>(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;"
                    + "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
                    "newBufferBuilder"));



    private static final String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";

    private static Map.Entry<String, String> blockLayer(String layer, String shim) {
        return Map.entry(RENDER_TYPE + "." + layer + "()L" + RENDER_TYPE + ";", shim);
    }

    private static Map.Entry<String, String> tagFactory(String tags) {
        return Map.entry("net/minecraft/tags/" + tags + ".create(Lnet/minecraft/resources/Identifier;)"
                + "Lnet/minecraft/tags/TagKey;", "tagsCreate");
    }

    private Shims() {
    }

    /** @return the shim's name for this member, or {@code null} when there is none */
    static String shimFor(String owner, String name, String descriptor) {
        String exact = SHIMS.get(owner + "." + name + descriptor);

        if (exact != null) {
            return exact;
        }

        // By arity, where the argument types moved too: NeoForge's ModelData
        // changed package with the methods taking it.
        String[] byArity = BY_ARITY.get(owner + "." + name + "/" + Type.getArgumentTypes(descriptor).length);
        return byArity != null && byArity[1].equals(descriptorFor(descriptor, false)) ? byArity[0] : null;
    }

    private static final String MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";

    /** Instance methods matched by owner, name and number of arguments, with the shim's erased descriptor. */
    private static final Map<String, String[]> BY_ARITY = Map.ofEntries(
            // A block model's NeoForge half from before 26.x: its layers, its
            // data and its quads, answered from this game's model.
            Map.entry(MODEL + ".getRenderTypes/3", new String[] { "modelRenderTypes",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }),
            Map.entry(MODEL + ".getModelData/4", new String[] { "modelData", "(Ljava/lang/Object;Ljava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }),
            Map.entry(MODEL + ".getQuads/3", new String[] { "modelQuads",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }),
            Map.entry(MODEL + ".getQuads/5", new String[] { "modelQuadsOfLayer", "(Ljava/lang/Object;Ljava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }),
            // A quad written with a tint, light and overlay, which 26.x writes through a QuadInstance.
            Map.entry("com/mojang/blaze3d/vertex/VertexConsumer.putBulkData/8", new String[] { "putBulkData",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFFFII)V" }),
            Map.entry("com/mojang/blaze3d/vertex/BufferBuilder.putBulkData/8", new String[] { "putBulkData",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFFFII)V" }),
            // NeoForge's forms: with whether to read the quad's own colour,
            // and with a brightness and a light for each corner.
            Map.entry("com/mojang/blaze3d/vertex/VertexConsumer.putBulkData/9", new String[] { "putBulkDataReading",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFFFIIZ)V" }),
            Map.entry("com/mojang/blaze3d/vertex/BufferBuilder.putBulkData/9", new String[] { "putBulkDataReading",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFFFIIZ)V" }),
            Map.entry("com/mojang/blaze3d/vertex/VertexConsumer.putBulkData/10", new String[] { "putBulkDataCorners",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFFFLjava/lang/Object;IZ)V" }),
            Map.entry("com/mojang/blaze3d/vertex/BufferBuilder.putBulkData/10", new String[] { "putBulkDataCorners",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFFFLjava/lang/Object;IZ)V" }));

    /**
     * Static fields removed, rebuilt the same way: a shim taking the owner's
     * {@code Class} and answering the value.
     */
    private static final String CAPABILITIES = "net/neoforged/neoforge/capabilities/";

    private static final Map<String, String> FIELD_SHIMS = Map.ofEntries(
            // NeoForge 26 kept only FluidIngredient.CODEC; Create builds every
            // processing recipe's codec on the map codec, and without it not
            // one of its machine recipes could be read.
            Map.entry("net/neoforged/neoforge/fluids/crafting/FluidIngredient.MAP_CODEC_NONEMPTY:"
                    + "Lcom/mojang/serialization/MapCodec;", "fluidIngredientMapCodec"),
            Map.entry("net/neoforged/neoforge/fluids/FluidStack.FLUID_NON_EMPTY_CODEC:Lcom/mojang/serialization/Codec;",
                    "fluidHolderCodec"),
            // The capabilities NeoForge 26 replaced with its transfer API.
            // Balm hands them to every mod built on it from its constructor,
            // and a null there stopped Waystones and the rest of them.
            capability("ItemHandler", "BLOCK", "Block", "legacyItemBlock"),
            capability("ItemHandler", "ENTITY", "Entity", "legacyItemEntity"),
            capability("ItemHandler", "ENTITY_AUTOMATION", "Entity", "legacyItemEntityAutomation"),
            capability("ItemHandler", "ITEM", "Item", "legacyItemItem"),
            capability("FluidHandler", "BLOCK", "Block", "legacyFluidBlock"),
            capability("FluidHandler", "ENTITY", "Entity", "legacyFluidEntity"),
            capability("FluidHandler", "ITEM", "Item", "legacyFluidItem"),
            capability("EnergyStorage", "BLOCK", "Block", "legacyEnergyBlock"),
            capability("EnergyStorage", "ENTITY", "Entity", "legacyEnergyEntity"),
            capability("EnergyStorage", "ITEM", "Item", "legacyEnergyItem"));

    private static Map.Entry<String, String> capability(String group, String field, String kind, String shim) {
        return Map.entry(CAPABILITIES + "Capabilities$" + group + "." + field + ":L" + CAPABILITIES + kind
                + "Capability;", shim);
    }

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

    /** A constructor shim's descriptor: the arguments by the convention above, the object made as the result. */
    static String constructorDescriptor(String descriptor) {
        StringBuilder out = new StringBuilder("(");

        for (Type argument : Type.getArgumentTypes(descriptor)) {
            out.append(erased(argument));
        }

        return out.append(")Ljava/lang/Object;").toString();
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

    // -------------------------------------------------------------- registries

    /** {@code BuiltInRegistries.getVanillaRegistrationOrder()}: every registry's name, in the order it was made. */
    public static Object vanillaRegistrationOrder(Class<?> owner) {
        Object root = staticField(owner, "REGISTRY");
        java.util.Set<Object> out = new java.util.LinkedHashSet<>();

        for (Object registry : (Iterable<?>) root) {
            Object key = callWith(root, "getKey", new Class<?>[] { Object.class }, registry);

            if (key != null) {
                out.add(key);
            }
        }

        return out;
    }

    // ------------------------------------------------------------------- tags

    /** {@code ItemTags.create(id)} and its kin: a tag key of the registry the owner's tags are for. */
    public static Object tagsCreate(Object id, Class<?> owner) {
        String registry = switch (owner.getSimpleName()) {
            case "BlockTags" -> "BLOCK";
            case "FluidTags" -> "FLUID";
            case "EntityTypeTags" -> "ENTITY_TYPE";
            case "BiomeTags" -> "BIOME";
            default -> "ITEM";
        };
        return tagKey(owner, registry, id);
    }

    /** {@code DyeColor.getTag()}: the common tag for dyes of that colour, {@code c:dyes/red}. */
    public static Object dyeColorTag(Object color) {
        Class<?> identifier = game(color.getClass(), "net.minecraft.resources.Identifier");
        Object id = callStatic(identifier, "fromNamespaceAndPath", new Class<?>[] { String.class, String.class }, "c",
                "dyes/" + call(color, "getName"));
        return tagKey(color.getClass(), "ITEM", id);
    }

    public static Object toolRuleMinesAndDrops(Object tag, float speed, Class<?> owner) {
        return callStatic(owner, "minesAndDrops", new Class<?>[] { game(owner, "net.minecraft.core.HolderSet"),
                float.class }, blocksOf(owner, tag), speed);
    }

    public static Object toolRuleDeniesDrops(Object tag, Class<?> owner) {
        return callStatic(owner, "deniesDrops", new Class<?>[] { game(owner, "net.minecraft.core.HolderSet") },
                blocksOf(owner, tag));
    }

    public static Object toolRuleOverrideSpeed(Object tag, float speed, Class<?> owner) {
        return callStatic(owner, "overrideSpeed", new Class<?>[] { game(owner, "net.minecraft.core.HolderSet"),
                float.class }, blocksOf(owner, tag), speed);
    }

    private static Object tagKey(Class<?> context, String registry, Object id) {
        Class<?> registries = game(context, "net.minecraft.core.registries.Registries");
        Class<?> resourceKey = game(context, "net.minecraft.resources.ResourceKey");
        return callStatic(game(context, "net.minecraft.tags.TagKey"), "create",
                new Class<?>[] { resourceKey, game(context, "net.minecraft.resources.Identifier") },
                staticField(registries, registry), id);
    }

    /** The blocks of a tag, as items are given them while they are made: bound once tags load. */
    private static Object blocksOf(Class<?> context, Object tag) {
        Class<?> builtIn = game(context, "net.minecraft.core.registries.BuiltInRegistries");
        Class<?> registry = game(context, "net.minecraft.core.Registry");
        Object lookup = callStatic(builtIn, "acquireBootstrapRegistrationLookup", new Class<?>[] { registry },
                staticField(builtIn, "BLOCK"));
        return callWith(lookup, "getOrThrow", new Class<?>[] { game(context, "net.minecraft.tags.TagKey") }, tag);
    }

    /** {@code CreativeModeTab.builder()}: a builder for the top row, as the loaders' own makes. */
    public static Object creativeTabBuilder(Class<?> owner) {
        Class<?> row = game(owner, "net.minecraft.world.item.CreativeModeTab$Row");
        return callStatic(owner, "builder", new Class<?>[] { row, int.class }, staticField(row, "TOP"), 0);
    }

    // --------------------------------------------------------------- rendering

    /** {@code poseStack.mulPose(quaternion)}: the same turn, about the origin. */
    public static void poseStackRotate(Object poseStack, Object quaternion) {
        callWith(poseStack, "rotateAround", new Class<?>[] { game(poseStack.getClass(), "org.joml.Quaternionfc"),
                float.class, float.class, float.class }, quaternion, 0f, 0f, 0f);
    }

    /** {@code RenderType.chunkBufferLayers()}: the layers the block layer shims above hand out, once each. */
    public static Object renderTypeLayers(Class<?> owner) {
        java.util.LinkedHashSet<Object> layers = new java.util.LinkedHashSet<>();
        layers.add(renderTypeSolid(owner));
        layers.add(renderTypeCutout(owner));
        layers.add(renderTypeTranslucent(owner));
        layers.remove(null);
        return List.copyOf(layers);
    }

    public static Object renderTypeSolid(Class<?> owner) {
        return renderTypes(owner, "solidMovingBlock");
    }

    public static Object renderTypeCutout(Class<?> owner) {
        return renderTypes(owner, "cutoutMovingBlock");
    }

    public static Object renderTypeTranslucent(Class<?> owner) {
        return renderTypes(owner, "translucentMovingBlock");
    }

    public static void putBulkDataReading(Object consumer, Object pose, Object quad, float red, float green, float blue,
            float alpha, int light, int overlay, boolean readAlpha) {
        studios.milkdromeda.octo.bridge.client.LegacyModels.putBulkData(consumer, pose, quad, red, green, blue, alpha,
                light, overlay);
    }

    /** The per-corner form: the corners' average brightness and the first corner's light, which a flat quad shares. */
    public static void putBulkDataCorners(Object consumer, Object pose, Object quad, Object brightness, float red,
            float green, float blue, float alpha, Object lights, int overlay, boolean readAlpha) {
        float shade = 1f;

        if (brightness instanceof float[] values && values.length > 0) {
            shade = 0f;

            for (float value : values) {
                shade += value;
            }

            shade /= values.length;
        }

        int light = lights instanceof int[] values && values.length > 0 ? values[0] : 0xF000F0;
        studios.milkdromeda.octo.bridge.client.LegacyModels.putBulkData(consumer, pose, quad, red * shade,
                green * shade, blue * shade, alpha, light, overlay);
    }

    /** {@code new BufferBuilder(bytes, mode, format)}: the draw mode becomes the topology of the same name. */
    public static Object newBufferBuilder(Object bytes, Object mode, Object format) {
        Class<?> builder = game(bytes.getClass(), "com.mojang.blaze3d.vertex.BufferBuilder");
        Class<?> topology = game(bytes.getClass(), "com.mojang.renderpearl.api.pipeline.PrimitiveTopology");
        String name = mode instanceof Enum<?> value ? value.name() : "QUADS";

        if (name.equals("LINE_STRIP")) {
            name = "DEBUG_LINE_STRIP";
        }

        Object primitive = staticField(topology, name);
        format = oldLayout(bytes.getClass(), format);

        for (java.lang.reflect.Constructor<?> constructor : builder.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();

            if (parameters.length == 3 && parameters[1] == topology && parameters[2].isInstance(format)) {
                try {
                    return constructor.newInstance(bytes, primitive, format);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("could not make a buffer builder", e);
                }
            }
        }

        throw new IllegalStateException("no buffer builder takes " + format);
    }

    private static volatile Object oldBlockFormat;

    /**
     * The block format laid out as code from before 26.x reads it back. 26.x
     * dropped the normal from its block vertices — 28 bytes, where 1.21 had
     * 32 — and code that copies vertex data out of a buffer it built, Catnip's
     * template meshes among it, reads each vertex at the old offsets and runs
     * off the end. A buffer such code builds in the block format gets the
     * old layout back: position, colour, texture, light, normal.
     */
    static Object oldLayout(Class<?> anchor, Object format) {
        try {
            Class<?> defaults = game(anchor, "com.mojang.blaze3d.vertex.DefaultVertexFormat");

            if (format != staticField(defaults, "BLOCK")) {
                return format;
            }

            Object built = oldBlockFormat;

            if (built == null) {
                Class<?> vertexFormat = game(anchor, "com.mojang.renderpearl.api.vertex.VertexFormat");
                Class<?> gpuFormat = game(anchor, "com.mojang.renderpearl.api.GpuFormat");
                Object builder = callStatic(vertexFormat, "builder", new Class<?>[] { int.class }, 0);
                java.lang.reflect.Method add = builder.getClass().getMethod("addAttribute", String.class, gpuFormat);
                add.invoke(builder, "Position", staticField(gpuFormat, "RGB32_FLOAT"));
                add.invoke(builder, "Color", staticField(gpuFormat, "RGBA8_UNORM"));
                add.invoke(builder, "UV0", staticField(gpuFormat, "RG32_FLOAT"));
                add.invoke(builder, "UV2", staticField(gpuFormat, "RG16_SINT"));
                add.invoke(builder, "Normal", staticField(gpuFormat, "RGBA8_SNORM"));
                built = builder.getClass().getMethod("build").invoke(builder);
                oldBlockFormat = built;
            }

            return built;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return format;
        }
    }

    public static Object quadVertices(Object quad) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.vertices(quad);
    }

    public static Object quadSprite(Object quad) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.sprite(quad);
    }

    public static int quadTint(Object quad) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.tintIndex(quad);
    }

    public static boolean quadTinted(Object quad) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.tintIndex(quad) != -1;
    }

    public static boolean quadShade(Object quad) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.shade(quad);
    }

    public static boolean quadAmbientOcclusion(Object quad) {
        return true;
    }

    public static Object quadDirection(Object quad) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.direction(quad);
    }

    public static Object newQuad(Object vertices, int tintIndex, Object direction, Object sprite, boolean shade) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.make((int[]) vertices, tintIndex, direction, sprite,
                shade);
    }

    public static Object newQuadWithOcclusion(Object vertices, int tintIndex, Object direction, Object sprite,
            boolean shade, boolean ambientOcclusion) {
        return studios.milkdromeda.octo.bridge.client.LegacyQuads.make((int[]) vertices, tintIndex, direction, sprite,
                shade, ambientOcclusion);
    }

    /** {@code registry.asLookup()}: the registry, which is its own lookup now. */
    public static Object registryAsLookup(Object registry) {
        return registry;
    }

    /** {@code ModelResourceLocation.standalone(id)}: the location with NeoForge's {@code standalone} variant. */
    public static Object modelLocationStandalone(Object id, Class<?> owner) {
        for (java.lang.reflect.Constructor<?> constructor : owner.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();

            if (parameters.length == 2 && parameters[1] == String.class && parameters[0].isInstance(id)) {
                try {
                    return constructor.newInstance(id, "standalone");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("could not make a model location for " + id, e);
                }
            }
        }

        throw new IllegalStateException(owner.getName() + " has no (id, variant) constructor");
    }

    public static Object modelManagerModel(Object manager, Object location) {
        return studios.milkdromeda.octo.bridge.client.ExtraModels.model(manager, location);
    }

    public static Object modelManagerMissing(Object manager) {
        return studios.milkdromeda.octo.bridge.client.ExtraModels.missing(manager);
    }

    public static Object bakedModels(Object owner) {
        return studios.milkdromeda.octo.bridge.client.ExtraModels.models(owner);
    }

    public static void registerModel(Object event, Object location) {
        studios.milkdromeda.octo.bridge.client.ExtraModels.request(location);
    }

    public static Object blockRenderer(Object minecraft) {
        return studios.milkdromeda.octo.bridge.client.LegacyModels.blockRenderer(minecraft);
    }

    public static Object modelRenderTypes(Object model, Object state, Object random, Object data) {
        return studios.milkdromeda.octo.bridge.client.LegacyModels.renderTypes(model, state, random, data);
    }

    public static Object modelData(Object model, Object level, Object pos, Object state, Object data) {
        return studios.milkdromeda.octo.bridge.client.LegacyModels.modelData(model, level, pos, state, data);
    }

    public static Object modelQuads(Object model, Object state, Object side, Object random) {
        return studios.milkdromeda.octo.bridge.client.LegacyModels.quads(model, state, side, random);
    }

    public static Object modelQuadsOfLayer(Object model, Object state, Object side, Object random, Object data,
            Object type) {
        return studios.milkdromeda.octo.bridge.client.LegacyModels.quads(model, state, side, random, data, type);
    }

    public static void putBulkData(Object consumer, Object pose, Object quad, float red, float green, float blue,
            float alpha, int light, int overlay) {
        studios.milkdromeda.octo.bridge.client.LegacyModels.putBulkData(consumer, pose, quad, red, green, blue, alpha,
                light, overlay);
    }

    private static Object renderTypes(Class<?> owner, String name) {
        return callStatic(game(owner, "net.minecraft.client.renderer.rendertype.RenderTypes"), name, new Class<?>[0]);
    }

    // ---------------------------------------------------------- capabilities

    private static final String ITEM_HANDLER = "net.neoforged.neoforge.items.IItemHandler";
    private static final String FLUID_HANDLER = "net.neoforged.neoforge.fluids.capability.IFluidHandler";
    private static final String FLUID_HANDLER_ITEM = "net.neoforged.neoforge.fluids.capability.IFluidHandlerItem";
    private static final String ENERGY_STORAGE = "net.neoforged.neoforge.energy.IEnergyStorage";

    /** {@code Capabilities.ItemHandler.BLOCK}, and the nine below it the same way. */
    public static Object legacyItemBlock(Class<?> owner) {
        return legacyCapability(owner, "Block", "item_handler", ITEM_HANDLER, true);
    }

    public static Object legacyItemEntity(Class<?> owner) {
        return legacyCapability(owner, "Entity", "item_handler", ITEM_HANDLER, false);
    }

    public static Object legacyItemEntityAutomation(Class<?> owner) {
        return legacyCapability(owner, "Entity", "item_handler_automation", ITEM_HANDLER, true);
    }

    public static Object legacyItemItem(Class<?> owner) {
        return legacyCapability(owner, "Item", "item_handler", ITEM_HANDLER, false);
    }

    public static Object legacyFluidBlock(Class<?> owner) {
        return legacyCapability(owner, "Block", "fluid_handler", FLUID_HANDLER, true);
    }

    public static Object legacyFluidEntity(Class<?> owner) {
        return legacyCapability(owner, "Entity", "fluid_handler", FLUID_HANDLER, true);
    }

    public static Object legacyFluidItem(Class<?> owner) {
        return legacyCapability(owner, "Item", "fluid_handler_item", FLUID_HANDLER_ITEM, false);
    }

    public static Object legacyEnergyBlock(Class<?> owner) {
        return legacyCapability(owner, "Block", "energy", ENERGY_STORAGE, true);
    }

    public static Object legacyEnergyEntity(Class<?> owner) {
        return legacyCapability(owner, "Entity", "energy", ENERGY_STORAGE, true);
    }

    public static Object legacyEnergyItem(Class<?> owner) {
        return legacyCapability(owner, "Item", "energy", ENERGY_STORAGE, false);
    }

    /**
     * One of the capabilities NeoForge had before its transfer API, made
     * under a name of Octo's own. NeoForge 26 took {@code neoforge:item_handler}
     * for the new resource handlers and refuses a second capability of the
     * same name and another type; under its own name, every mod built for the
     * old one still finds what the others registered.
     *
     * @param kind  {@code Block}, {@code Entity} or {@code Item}
     * @param sided whether it is asked with a direction, rather than nothing
     */
    private static Object legacyCapability(Class<?> owner, String kind, String name, String type, boolean sided) {
        Class<?> identifier = game(owner, "net.minecraft.resources.Identifier");
        Object id = callStatic(identifier, "fromNamespaceAndPath", new Class<?>[] { String.class, String.class },
                "octo", "legacy/" + name);
        Class<?> typeClass;

        try {
            typeClass = Class.forName(type, false, owner.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            typeClass = Object.class;
        }

        return callStatic(game(owner, "net.neoforged.neoforge.capabilities." + kind + "Capability"),
                sided ? "createSided" : "createVoid", new Class<?>[] { identifier, Class.class }, id, typeClass);
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
