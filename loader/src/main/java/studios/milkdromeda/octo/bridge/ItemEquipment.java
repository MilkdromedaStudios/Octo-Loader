package studios.milkdromeda.octo.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Items worn the way they were before 1.21.2.
 *
 * <p>Until then an item was worn because it said where: it implemented
 * {@code Equipable}, or was an {@code ArmorItem}, and answered
 * {@code getEquipmentSlot()}. Since, the game asks only the item's
 * {@code equippable} component, so Create's goggles, diving gear and
 * backtanks could be neither put on with a click nor placed in an armour
 * slot. An item that still answers {@code getEquipmentSlot()} is given that
 * component, for the slot it names, when the game works out its components.
 */
public final class ItemEquipment {
    private static final OctoLog LOG = OctoLog.of(ItemEquipment.class);

    /** The item's old {@code getEquipmentSlot()}, or {@code null}. */
    private static final ClassValue<Method> SLOT = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("getEquipmentSlot");

                if (method.getReturnType().getName().equals("net.minecraft.world.entity.EquipmentSlot")
                        && !method.getDeclaringClass().getName().equals("net.minecraft.world.item.Item")) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException | RuntimeException e) {
                // Not an item that says where it is worn.
            }

            return null;
        }
    };

    private ItemEquipment() {
    }

    /** Whether items of this class say, the old way, where they are worn. */
    static boolean saysWhereWorn(Class<?> type) {
        return SLOT.get(type) != null;
    }

    /**
     * Called as an {@code Item} is made, with the initializer that will give
     * it its components: that initializer, followed, for an item that says
     * where it is worn, by one adding the {@code equippable} component.
     */
    public static Object withOldSlot(Object initializer, Object item) {
        if (initializer == null || item == null || !saysWhereWorn(item.getClass())) {
            return initializer;
        }

        try {
            Class<?> initializerType = Class.forName(
                    "net.minecraft.core.component.DataComponentInitializers$Initializer", false,
                    item.getClass().getClassLoader());
            InvocationHandler equip = (proxy, method, arguments) -> {
                if (method.getName().equals("run") && arguments != null && arguments.length == 3) {
                    addEquippable(arguments[0], item);
                    return null;
                }

                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, arguments);
                }

                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "Octo's equippable for " + item.getClass().getName();
                    default -> null;
                };
            };
            Object added = Proxy.newProxyInstance(item.getClass().getClassLoader(), new Class<?>[] { initializerType },
                    equip);
            return initializerType.getMethod("andThen", initializerType).invoke(initializer, added);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("{} could not be made wearable: {}", item.getClass().getName(),
                    Failures.describe(Failures.unwrap(e)));
            return initializer;
        }
    }

    /** {@code builder.set(EQUIPPABLE, Equippable.builder(item.getEquipmentSlot()).build())}. */
    private static void addEquippable(Object builder, Object item) {
        try {
            Object slot = SLOT.get(item.getClass()).invoke(item);

            if (slot == null) {
                return;
            }

            ClassLoader loader = item.getClass().getClassLoader();
            Class<?> slotType = Class.forName("net.minecraft.world.entity.EquipmentSlot", false, loader);
            Class<?> equippableType = Class.forName("net.minecraft.world.item.equipment.Equippable", false, loader);
            Object equippableBuilder = equippableType.getMethod("builder", slotType).invoke(null, slot);
            Object equippable = equippableBuilder.getClass().getMethod("build").invoke(equippableBuilder);
            Class<?> components = Class.forName("net.minecraft.core.component.DataComponents", false, loader);
            Object type = components.getField("EQUIPPABLE").get(null);
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
            builder.getClass().getMethod("set", componentType, Object.class).invoke(builder, type, equippable);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("{} could not be made wearable: {}", item.getClass().getName(),
                    Failures.describe(Failures.unwrap(e)));
        }
    }
}
