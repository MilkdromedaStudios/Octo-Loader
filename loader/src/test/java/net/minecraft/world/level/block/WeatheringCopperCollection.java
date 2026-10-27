package net.minecraft.world.level.block;

/** 26.x's collection of a copper block's weathering states, waxed and not, as far as tests need it. */
public record WeatheringCopperCollection<T>(ByState<T> weathering, ByState<T> waxed) {
    public record ByState<T>(T unaffected, T exposed, T weathered, T oxidized) {
    }
}
