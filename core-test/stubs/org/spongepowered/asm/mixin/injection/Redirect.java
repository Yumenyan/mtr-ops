package org.spongepowered.asm.mixin.injection;
public @interface Redirect { String[] method(); At at(); boolean remap() default true; int require() default -1; }
