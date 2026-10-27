package net.minecraft.octotest;

import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A game block entity, saved and loaded the 26.x way. */
public class Machine3 {
    public int baseLoads;

    protected void saveAdditional(ValueOutput output) {
        ((TagValueOutput) output).buildResult().putInt("base", 1);
    }

    protected void loadAdditional(ValueInput input) {
        baseLoads++;
    }

    public Object getLevel() {
        return null;
    }
}
