package dev.morphhide.client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class TitleEntry extends VeilButton {
    public static final Identifier EMBLEM=Identifier.fromNamespaceAndPath("morphhide","textures/gui/emblem.png");
    public TitleEntry(int x,int y,int w){super(x,y,w,65,dev.morphhide.MorphHide.NAME,QuickLobby::open);setTooltip(Tooltip.create(Component.literal(dev.morphhide.MorphHide.NAME+"\n进入大厅 · 1人可练习，2人以上可对战")));}
    @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float a){
        plate(g,getX(),getY()+10,getWidth(),getHeight()-10,isHoveredOrFocused());
        int size=Math.min(34,getWidth()-6);icon(g,getX()+(getWidth()-size)/2,getY()-2,size);
        if(getWidth()>=72){
            g.centeredText(Minecraft.getInstance().font,"Morph",getX()+getWidth()/2,getY()+34,0xFFFFE2A2);
            g.centeredText(Minecraft.getInstance().font,"Hide n Seek",getX()+getWidth()/2,getY()+45,0xFFFFE2A2);
        }
        else g.centeredText(Minecraft.getInstance().font,"进入",getX()+getWidth()/2,getY()+43,0xFFFFE2A2);
        g.fill(getX()+8,getY()+58,getX()+getWidth()-8,getY()+59,0xFFAD915B);
    }
    public static void icon(GuiGraphicsExtractor g,int x,int y,int size){
        g.blit(RenderPipelines.GUI_TEXTURED,EMBLEM,x,y,0,0,size,size,1254,1254,1254,1254);
    }
}
