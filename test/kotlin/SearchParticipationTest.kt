package kr.routerescue.demo

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SearchParticipationTest {
    private val data=mutableMapOf<String,String>()
    private val prefs=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args ->
        when (method.name) {
            "getString" -> data[args[0] as String] ?: args[1]
            "edit" -> Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,call,input ->
                when (call.name) {
                    "putString" -> { data[input[0] as String]=input[1] as String; proxy }
                    "commit" -> true
                    else -> error(call.name)
                }
            }
            else -> error(method.name)
        }
    } as SharedPreferences
    private val store=SearchParticipation(prefs)
    private val now=1_800_000_000_000L

    private fun road(report: String): String {
        val id=store.begin(SearchOffer(report,"성수동1가",4,true),true)!!
        assertFalse(store.evidence(id,"passed",false,0,"session"))
        assertTrue(store.evidence(id,"entered",false,1,"session"))
        assertTrue(store.evidence(id,"passed",false,2,"session"))
        return id
    }
    @Test fun soloRequiresDoorAndThreeMinutes() {
        val id=store.begin(SearchOffer("r","성수동1가",6,false),true)!!
        assertTrue(store.evidence(id,"arrived",false,1,"session"))
        assertFalse(store.evidence(id,"exit",true,2,"session"))
        assertTrue(store.evidence(id,"door",true,3,"session"))
        assertTrue(store.evidence(id,"exit",true,4,"session"))
        assertTrue(store.evidence(id,"return",true,180_004,"session"))
        assertTrue(store.submit(id,false,"",null,null,null,now))
        assertEquals(50,store.entries().single().getInt("base"))
        assertFalse(SearchParticipation(prefs).submit(id,false,"",null,null,null,now))
    }
    @Test fun duplicateAndDailyLimitIncludePhotoBonus() {
        val first=road("one")
        assertTrue(store.submit(first,true,"2027-01-15T12:00",1f,1f,"content://photo",now))
        assertTrue(store.approveDemoPhoto(first,now))
        assertFalse(store.approveDemoPhoto(first,now))
        assertTrue(store.submit(road("one"),false,"",null,null,null,now))
        assertTrue(store.entries().last().has("hold"))
        assertTrue(store.submit(road("two"),false,"",null,null,null,now))
        assertTrue(store.submit(road("three"),false,"",null,null,null,now))
        assertTrue(store.entries().last().has("hold"))
        assertEquals(125,store.entries().sumOf { it.optInt("base")+it.optInt("bonus") })
    }
}
