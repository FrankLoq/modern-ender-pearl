package com.frankloq.compat;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Compatibility with the Warp Drive item from the Artifacts mod.
 * This class makes sure that when having Warp Drive equipped
 * it doesn't damage you when you throw an ender pearl through a portal.
 */

public final class WarpDriveCompat {

    private static boolean resolved = false;
    private static Method hasAbilityActive = null;
    private static Object pearlDamageImmunityType = null;

    private WarpDriveCompat() {}

    /**
     * @return true if the player currently has the "ender pearl damage immunity"
     *         ability active (meaning that the player has a Warp Drive equipped).
     *         Always false when Artifacts is not installed or its internal API
     *         could not be hooked, in which case normal damage applies.
     */
    public static boolean hasEnderPearlDamageImmunity(ServerPlayer player) {
        if (!resolve()) return false;

        try {
            return (Boolean) hasAbilityActive.invoke(null, pearlDamageImmunityType, player, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Resolves Artifacts internal API once and caches the result. */
    private static boolean resolve() {
        if (resolved) return hasAbilityActive != null;
        resolved = true;

        if (!ModList.get().isLoaded("artifacts")) return false;

        try {
            Class<?> components = Class.forName("artifacts.registry.ModDataComponents");
            Field supplierField = components.getField("ENDER_PEARL_DAMAGE_IMMUNITY");
            Object supplier = supplierField.get(null);
            pearlDamageImmunityType = ((Supplier<?>) supplier).get();

            Class<?> helper = Class.forName("artifacts.equipment.EquipmentHelper");
            hasAbilityActive = helper.getMethod(
                    "hasAbilityActive",
                    DataComponentType.class,
                    LivingEntity.class,
                    boolean.class);
        } catch (Throwable ignored) {
            hasAbilityActive = null;
            pearlDamageImmunityType = null;
        }

        return hasAbilityActive != null;
    }
}