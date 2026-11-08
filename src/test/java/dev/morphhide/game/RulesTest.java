package dev.morphhide.game;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RulesTest {
 @Test void everyPartyHasHiders(){for(int n=2;n<=24;n++){assertTrue(Rules.hunterCount(n)>=1);assertTrue(Rules.hunterCount(n)<=2);assertTrue(Rules.hunterCount(n)<n);}assertEquals(1,Rules.hunterCount(5));assertEquals(2,Rules.hunterCount(6));}
 @Test void lastAnimalCapturedWinsEvenAtTimeout(){assertEquals("HUNTERS",Rules.winner(0,2,0,false));}
 @Test void disconnectsAndPracticeHaveDifferentVictoryRules(){assertEquals("ANIMALS",Rules.winner(3,0,100,false));assertEquals("",Rules.winner(1,0,100,true));assertEquals("ANIMALS",Rules.winner(1,0,0,true));}
 @Test void survivorsKeepTheRoundRunningUntilTimeout(){assertEquals("",Rules.winner(1,1,1,false));assertEquals("ANIMALS",Rules.winner(1,1,0,false));}
 @Test void displayedTimeDoesNotReachZeroEarly(){assertEquals(1,Rules.remainingSeconds(1));assertEquals(2,Rules.remainingSeconds(21));assertEquals(0,Rules.remainingSeconds(-1));}
}
