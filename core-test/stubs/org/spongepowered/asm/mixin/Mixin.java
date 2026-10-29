package org.spongepowered.asm.mixin;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Mixin { Class<?>[] value() default {}; boolean remap() default true; }
