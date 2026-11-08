package dev.morphhide.client;

import java.util.HashSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.flag.FeatureFlags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewEntitiesTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void reproducesUnassignedIdFailureFromEmptyEquipmentRendering(){
        Mob mob=mock(Mob.class,CALLS_REAL_METHODS);
        var resolver=new ItemModelResolver(null);
        var error=assertThrows(IllegalStateException.class,()->resolver.updateForLiving(
            new ItemStackRenderState(),ItemStack.EMPTY,ItemDisplayContext.HEAD,mob));
        assertTrue(error.getMessage().contains("before ID assignment"));
    }

    @Test void actualSheepCreatedForClientPreviewCanExtractEquipmentState(){
        Level level=mock(Level.class);
        when(level.enabledFeatures()).thenReturn(FeatureFlags.VANILLA_SET);
        // Match client Level behavior: no server-assigned ID at construction.
        when(level.getNextEntityId()).thenReturn(0);
        Mob original=EntityTypes.SHEEP.create(level,EntitySpawnReason.COMMAND);
        assertNotNull(original);
        var resolver=new ItemModelResolver(null);
        assertThrows(IllegalStateException.class,()->resolver.updateForLiving(
            new ItemStackRenderState(),ItemStack.EMPTY,ItemDisplayContext.HEAD,original));
        Mob fixed=PreviewEntities.create(EntityTypes.SHEEP,level);
        assertNotNull(fixed);
        assertDoesNotThrow(()->resolver.updateForLiving(
            new ItemStackRenderState(),ItemStack.EMPTY,ItemDisplayContext.HEAD,fixed));
    }

    @Test void allSixPreviewSlotsAndReopenedScreensHaveUsableDistinctIds(){
        Level level=mock(Level.class);
        @SuppressWarnings("unchecked") EntityType<Mob> type=mock(EntityType.class);
        when(type.create(level,EntitySpawnReason.COMMAND)).thenAnswer(call->mock(Mob.class,CALLS_REAL_METHODS));
        var resolver=new ItemModelResolver(null);
        var ids=new HashSet<Integer>();
        // Six models, followed by a second screen/level's six fresh models.
        for(int i=0;i<12;i++){
            Mob model=PreviewEntities.create(type,level);
            assertNotNull(model);assertTrue(model.getId()<0);assertTrue(ids.add(model.getId()));
            for(var slot:ItemDisplayContext.values())assertDoesNotThrow(()->resolver.updateForLiving(
                new ItemStackRenderState(),ItemStack.EMPTY,slot,model));
        }
        verifyNoInteractions(level); // GUI models must not be inserted in the live world.
    }

    @Test void unavailableEntityTypeIsStillHandled(){
        Level level=mock(Level.class);
        @SuppressWarnings("unchecked") EntityType<Mob> type=mock(EntityType.class);
        assertNull(PreviewEntities.create(type,level));
    }
}
