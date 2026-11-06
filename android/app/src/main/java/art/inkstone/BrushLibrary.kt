package art.inkstone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class BrushLibrary(context:Context){
    private val prefs=context.getSharedPreferences("brush-library",Context.MODE_PRIVATE)
    private fun data()=runCatching{JSONObject(prefs.getString("library","{}")!!)}.getOrDefault(JSONObject())
    fun custom():List<BrushPreset>{val a=data().optJSONArray("brushes")?:JSONArray();return (0 until a.length()).mapNotNull{i->runCatching{decode(a.getJSONObject(i))}.getOrNull()}}
    fun all()=brushPresets+custom()
    fun categories()=(brushCategories+customCategories()+custom().map{it.group}).distinct()
    fun customCategories():List<String>{val a=data().optJSONArray("categories")?:JSONArray();return (0 until a.length()).map{a.getString(it)}}
    fun createCategory(name:String){val n=name.trim().take(60);require(n.isNotEmpty() && n!="Recent");write(custom(),(customCategories()+n).distinct())}
    fun renameCategory(old:String,name:String){require(old in customCategories());val n=name.trim().take(60);require(n.isNotEmpty() && n!="Recent");write(custom().map{if(it.group==old)it.copy(group=n)else it},customCategories().map{if(it==old)n else it}.distinct())}
    fun deleteCategory(name:String){require(name in customCategories());write(custom().map{if(it.group==name)it.copy(group="My Brushes")else it},customCategories().filter{it!=name}+"My Brushes")}
    fun save(p:BrushPreset):BrushPreset{require(p.customId.isNotEmpty());val name=unique(p.name,p.customId);val next=p.copy(name=name);write(custom().filter{it.customId!=p.customId}+next,customCategories());return next}
    fun copy(p:BrushPreset,name:String=p.name+" copy",group:String="My Brushes")=save(p.copy(name=name,group=group,customId=UUID.randomUUID().toString()))
    fun delete(id:String){
        val gone=custom().filter{it.customId==id}.map{it.tip};val rest=custom().filter{it.customId!=id}
        write(rest,customCategories())
        // A tip is shared by duplicates of a brush, so it goes only with the last one.
        for(tip in gone)if(tip.isNotEmpty() && rest.none{it.tip==tip})BrushTips.delete(tip)
    }
    /** Adds imported brushes to [group] without opening Brush Studio. */
    fun addAll(presets:List<BrushPreset>,group:String):List<BrushPreset>{
        require(custom().size+presets.size<=500){"Brush library is full"}
        if(group !in customCategories())createCategory(group)
        return presets.map{copy(it,it.name,group.trim().take(60))}
    }
    private fun unique(name:String,id:String):String{val base=name.trim().take(80).ifEmpty{"My Brush"};val used=all().filter{it.customId!=id || id.isEmpty()}.map{it.name}.toSet();var n=base;var i=2;while(n in used){n="$base $i";i++};return n}
    private fun write(brushes:List<BrushPreset>,categories:List<String>){require(brushes.size<=500){"Brush library is full"};prefs.edit().putString("library",JSONObject().put("brushes",JSONArray(brushes.map{encode(it)})).put("categories",JSONArray(categories.distinct())).toString()).apply()}
    companion object{
        fun encode(p:BrushPreset)=JSONObject().put("id",p.customId).put("name",p.name).put("group",p.group).put("kind",p.kind).put("size",p.size.toDouble()).put("flow",p.flow.toDouble()).put("pressureSize",p.pressureSize.toDouble()).put("pressureOpacity",p.pressureOpacity.toDouble()).put("fade",p.fadeLength.toDouble()).put("opacity",p.opacity.toDouble()).put("stabilization",p.stabilization.toDouble()).put("hardness",p.hardness.toDouble()).put("layout",p.colorLayout).put("colorMode",p.colorMode).put("cycle",p.colorCycle.toDouble()).put("mix",p.wetMix.toDouble()).put("pickup",p.wetPickup.toDouble()).put("wetness",p.wetness.toDouble()).put("palette",JSONArray(p.palette))
            .put("tip",p.tip).put("tipSpacing",p.tipSpacing.toDouble()).put("tipFollow",p.tipFollow.toDouble()).put("tipRotation",p.tipRotation.toDouble()).put("tipJitter",p.tipJitter.toDouble()).put("grainScale",p.grainScale.toDouble()).put("tipSizeJitter",p.tipSizeJitter.toDouble()).put("tipOpacityJitter",p.tipOpacityJitter.toDouble()).put("tipScatter",p.tipScatter.toDouble()).put("grainRolling",p.grainRolling).put("tipSpeedOpacity",p.tipSpeedOpacity.toDouble()).put("tipDynamics",p.tipDynamics.json())
        private fun decode(j:JSONObject):BrushPreset{
            fun n(key:String,default:Double,min:Float=0f,max:Float=1f)=j.optDouble(key,default).toFloat().also{require(it.isFinite() && it in min..max)}
            return BrushPreset(j.getString("name").take(80),LegacyNames.category(j.getString("group").take(60)),j.getInt("kind").also{require(it in 0..14 || it in 32..IMAGE_TIP_KIND)},n("size",16.0,1f,1000f),n("flow",1.0),n("pressureSize",.8),n("pressureOpacity",0.0),n("fade",0.0,0f,2000f),j.getString("id"),n("opacity",1.0),n("stabilization",.12,0f,.95f),n("hardness",1.0),j.optString("colorMode","Solid").also{require(it in listOf("Solid","Rainbow","Palette"))},n("cycle",600.0,20f,20000f),n("mix",0.0),j.optJSONArray("palette")?.let{a->(0 until a.length().coerceAtMost(12)).map{a.getLong(it).toInt()}}?:emptyList(),j.optString("layout","Along").also{require(it in listOf("Along","Across","Bristles"))},n("pickup",.35),n("wetness",.5),j.optString("tip","").also{require(it.isEmpty() || it.matches(Regex("[A-Za-z0-9-]{1,64}")))},n("tipSpacing",.1,.02f,4f),n("tipFollow",0.0,-1f,1f),n("tipRotation",0.0,-7f,7f),n("tipJitter",0.0),n("grainScale",1.0,.05f,16f),n("tipSizeJitter",0.0),n("tipOpacityJitter",0.0),n("tipScatter",0.0,0f,3f),j.optBoolean("grainRolling",false),n("tipSpeedOpacity",0.0),TipDynamics.from(j.optJSONObject("tipDynamics")))
        }
    }
}
