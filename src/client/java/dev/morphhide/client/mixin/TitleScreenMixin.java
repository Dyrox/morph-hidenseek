package dev.morphhide.client.mixin;
import dev.morphhide.client.TitleEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title){super(title);}
    @Inject(method="init",at=@At("TAIL"))
    private void morphhide$entry(CallbackInfo ci){
        int available=width/2-116;
        int w=Math.clamp(available,36,112);
        addRenderableWidget(new TitleEntry(8,Math.max(8,height-86),w));
    }
}
