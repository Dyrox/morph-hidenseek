package dev.morphhide.client.mixin;

import dev.morphhide.client.DisguisePreview;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Full disguise: the inventory paper-doll shows your animal instead of an empty player slot. */
@Mixin(InventoryScreen.class)
public abstract class InventoryDisguiseMixin {
    @Redirect(method="extractBackground",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;extractEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V"))
    private void morphhide$showAnimal(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int size,float offsetY,float mouseX,float mouseY,LivingEntity entity){
        var animal=DisguisePreview.forLocalPlayer();
        if(animal==null){InventoryScreen.extractEntityInInventoryFollowsMouse(g,x0,y0,x1,y1,size,offsetY,mouseX,mouseY,entity);return;}
        int fit=(int)Math.min((y1-y0-6)/Math.max(.7f,animal.getBbHeight()),(x1-x0-4)/Math.max(1f,animal.getBbWidth()*1.5f));
        InventoryScreen.extractEntityInInventoryFollowsMouse(g,x0,y0,x1,y1,Math.max(8,fit),offsetY,mouseX,mouseY,animal);
    }
}
