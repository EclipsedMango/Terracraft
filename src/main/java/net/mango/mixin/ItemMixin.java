package net.mango.mixin;

import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class ItemMixin {
	@Inject(at = @At("HEAD"), method = "setDamage", cancellable = true)
	private void setDamage(int damage, CallbackInfo ci) {
		ci.cancel();
	}
}