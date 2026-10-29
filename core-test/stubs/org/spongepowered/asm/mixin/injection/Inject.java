package org.spongepowered.asm.mixin.injection;
public @interface Inject { String[] method(); At[] at(); boolean cancellable() default false; boolean remap() default true; int require() default -1; }
