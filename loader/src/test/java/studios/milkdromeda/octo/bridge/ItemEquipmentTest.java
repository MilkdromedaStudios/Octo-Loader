package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.EquipmentSlot;

class ItemEquipmentTest {
    /** An item from before 1.21.2 that says where it is worn, as Create's goggles do. */
    public static class Goggles {
        public EquipmentSlot getEquipmentSlot() {
            return EquipmentSlot.HEAD;
        }
    }

    /** One that inherits it, as Create's diving helmet inherits ArmorItem's. */
    public static class DivingHelmet extends Goggles {
    }

    /** A method of the same name that answers something else. */
    public static class Odd {
        public String getEquipmentSlot() {
            return "head";
        }
    }

    public static class Plain {
    }

    @Test
    void anItemThatSaysItsSlotIsWorn() {
        assertTrue(ItemEquipment.saysWhereWorn(Goggles.class));
        assertTrue(ItemEquipment.saysWhereWorn(DivingHelmet.class));
    }

    @Test
    void otherItemsAreLeftAlone() {
        assertFalse(ItemEquipment.saysWhereWorn(Plain.class));
        assertFalse(ItemEquipment.saysWhereWorn(Odd.class));
    }

    @Test
    void anItemThatSaysNothingKeepsItsInitializer() {
        Object initializer = new Object();

        assertSame(initializer, ItemEquipment.withOldSlot(initializer, new Plain()));
    }
}
