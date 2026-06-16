package com.frankloq.compat;

import net.minecraft.world.entity.Entity;

public class CreateModCompat {

    // If it is a Create Contraption Entity, it ignores it so it won't break while crossing a portal
    public static boolean isCreateContraption(Entity entity) {
        if (entity == null) return false;
        
        String className = entity.getClass().getName();
        return className.startsWith("com.simibubi.create") && className.contains("ContraptionEntity");
    }
}