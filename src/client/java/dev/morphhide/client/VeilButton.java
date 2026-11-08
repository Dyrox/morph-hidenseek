package dev.morphhide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Keyboard-accessible button with a recessed shadow and raised pixel bevel. */
public class VeilButton extends Button {
    public VeilButton(int x,int y,int w,int h,String label,Runnable action){
        super(x,y,w,h,Component.literal(label),b->action.run(),DEFAULT_NARRATION);
    }
    public static void plate(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean lit){
        g.fill(x+3,y+4,x+w+3,y+h+4,0x88000000);
        g.fill(x,y,x+w,y+h,0xFF111719);
        g.fill(x+1,y+1,x+w-1,y+h-2,lit?0xFF445449:0xFF26372F);
        int edge=lit?0xFFF2D493:0xFF9B895E;
        g.fill(x,y,x+w,y+1,edge);g.fill(x,y,x+1,y+h-2,edge);
        g.fill(x+1,y+1,x+w-1,y+2,lit?0xFF80946D:0xFF4D5F46);
        g.fill(x+w-2,y+2,x+w,y+h,0xFF151B16);g.fill(x,y+h-2,x+w,y+h,0xFF0B100D);
    }
    @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float a){
        plate(g,getX(),getY(),getWidth(),getHeight(),isHoveredOrFocused());
        var font=Minecraft.getInstance().font;
        g.centeredText(font,getMessage(),getX()+getWidth()/2,getY()+(getHeight()-8)/2,active?0xFFF1E6CC:0xFF777E72);
    }
}
