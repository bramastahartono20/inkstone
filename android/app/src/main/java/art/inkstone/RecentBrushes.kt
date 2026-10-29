package art.inkstone

import android.content.Context
import org.json.JSONArray

internal class RecentBrushes(context:Context){
    private val library=BrushLibrary(context)
    private val prefs=context.getSharedPreferences("recent-brushes",Context.MODE_PRIVATE)
    private fun key(tool:String)=when(tool){"Erase"->"Erase";"Smudge"->"Smudge";else->"Paint"}
    private fun identity(p:BrushPreset)=if(p.customId.isNotEmpty())"custom:${p.customId}"else"builtin:${p.name}"
    fun list(tool:String):List<BrushPreset>{
        val saved=runCatching{JSONArray(prefs.getString(key(tool),"[]"))}.getOrDefault(JSONArray())
        val brushes=library.all()
        val resolved=(0 until saved.length()).mapNotNull{i->val id=saved.optString(i);brushes.firstOrNull{identity(it)==id}?:brushes.firstOrNull{it.name==id}}.distinctBy(::identity).take(20)
        val canonical=JSONArray(resolved.map(::identity)).toString()
        if(canonical!=saved.toString())prefs.edit().putString(key(tool),canonical).apply()
        return resolved
    }
    fun record(tool:String,preset:BrushPreset){synchronized(lock){
        val recent=(listOf(preset)+list(tool)).distinctBy(::identity).take(20)
        prefs.edit().putString(key(tool),JSONArray(recent.map(::identity)).toString()).apply()
    }}
    companion object{private val lock=Any()}
}
