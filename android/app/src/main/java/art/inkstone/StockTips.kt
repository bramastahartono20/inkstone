package art.inkstone

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Inkstone's own tip and grain images, drawn in code. Imported brushes that name an image built into
 * another app (which their files do not contain) get the closest of these, matched by words in the name.
 * Shapes: Inkstone Soft, Medium, Medium Hard and Hard Round, Ink Blot, Bristle and Spray.
 * Grains: Inkstone Paper, Canvas, Speckle, Clouds, Grunge and Charcoal. Grains tile seamlessly.
 * White paints, black is clear.
 */
internal object StockTips{
    const val SHAPE=256
    const val GRAIN=512

    private fun has(n:String,vararg words:String)=words.any{it in n}
    /** A shape for a built-in shape name, or null when none fits. */
    fun shape(name:String?):ByteArray?{
        val n=name?.lowercase()?:return null
        return when{
            has(n,"ultra-soft","soft","airbrush","blur","glow","bokeh","orbs","cloud","nebula","neblua","flare")->softRound(SHAPE)
            has(n,"medium-hard")->round(SHAPE,.6f)
            has(n,"medium")->round(SHAPE,.3f)
            has(n,"brick","flat-brush","square","block")->flat(SHAPE)
            has(n,"spray","splatter","splash","flick","drip","burst","stubble","dots")->spray(SHAPE)
            has(n,"charcoal","willow","crayon","scribble","streak","flat-brush","paint-dab","synthetic","hair","bristle","whisp","shard","rough")->bristle(SHAPE)
            has(n,"ink","mess","blotch","sponge","crusty","torn","gloop","fat","water","bleed")->inkBlot(SHAPE)
            has(n,"hard","round","point","oval","pen")->hardRound(SHAPE)
            else->null
        }
    }
    /** A grain for a built-in grain name, or null when none fits (including Procreate's "Blank"). */
    fun grain(name:String?):ByteArray?{
        val n=name?.lowercase()?:return null
        return when{
            has(n,"blank")->null
            has(n,"canvas","hessian","linen","cage","blinds","pastel-paper")->canvas(GRAIN)
            has(n,"charcoal","chalk","pastel","graphite","willow","bonobo","6b","vine")->charcoal(GRAIN)
            has(n,"splatter","flake","worn-dots","textured-ink","hard-ink","spray")->speckle(GRAIN)
            has(n,"cloud","ocean","refract","membrane","light")->clouds(GRAIN)
            has(n,"paper","munched","mush","cardboard","sketch","recycled")->paper(GRAIN)
            has(n,"grunge","rough","noise","grit","rust","concre","stone","clay","aggate","agate","bark","army","badspray","zombie","leather","peal","peel","wood","brick")->grunge(GRAIN)
            else->null
        }
    }

    private fun bytes(n:Int,f:(Int,Int)->Float)=ByteArray(n*n){i->(f(i%n,i/n).coerceIn(0f,1f)*255f+.5f).toInt().toByte()}

    /** A soft round with a Gaussian falloff that reaches exactly zero at the edge, so dabs never show a rim. */
    fun softRound(n:Int)=bytes(n){x,y->
        val dx=(x+.5f)/n*2f-1f;val dy=(y+.5f)/n*2f-1f;val d2=dx*dx+dy*dy
        val edge=exp(-4.5f);((exp(-4.5f*d2)-edge)/(1f-edge)).coerceAtLeast(0f)
    }
    /** A hard round with a one-pixel antialiased edge. */
    fun hardRound(n:Int)=bytes(n){x,y->
        val dx=x+.5f-n/2f;val dy=y+.5f-n/2f;(n/2f-1f-sqrt(dx*dx+dy*dy)+.5f)
    }

    private fun hash(x:Int,y:Int,seed:Int):Float{var h=x*374761393+y*668265263+seed*1442695041;h=(h xor (h ushr 13))*1274126177;return ((h xor (h ushr 16)) and 0xffff)/65535f}
    /** Smooth value noise on a lattice of [period] × [periodY] cells that wraps, so the image tiles. */
    private fun noise(u:Float,v:Float,period:Int,periodY:Int,seed:Int):Float{
        val x=u*period;val y=v*periodY;val ix=floor(x).toInt();val iy=floor(y).toInt()
        val fx=x-ix;val fy=y-iy;val sx=fx*fx*fx*(fx*(fx*6-15)+10);val sy=fy*fy*fy*(fy*(fy*6-15)+10)
        fun at(a:Int,b:Int)=hash(Math.floorMod(a,period),Math.floorMod(b,periodY),seed)
        val top=at(ix,iy)+(at(ix+1,iy)-at(ix,iy))*sx;val bottom=at(ix,iy+1)+(at(ix+1,iy+1)-at(ix,iy+1))*sx
        return top+(bottom-top)*sy
    }
    private fun fbm(u:Float,v:Float,base:Int,octaves:Int,seed:Int,stretch:Int=1):Float{
        var sum=0f;var amp=.5f;var total=0f;var p=base
        for(o in 0 until octaves){sum+=noise(u,v,p,p*stretch,seed+o*101)*amp;total+=amp;amp*=.5f;p*=2}
        return sum/total
    }
    private fun ramp(v:Float,a:Float,b:Float)=((v-a)/(b-a)).coerceIn(0f,1f)

    /** Worn grunge: broad blotches, eroded edges and fine grit, with pinholes of clear canvas. */
    fun grunge(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n
        val blotch=fbm(u,v,4,5,11);val erosion=fbm(u,v,16,4,23);val grit=fbm(u,v,128,2,37)
        val holes=if(hash(x,y,53)>.985f)0f else 1f
        val body=ramp(blotch*.7f+erosion*.45f,.38f,.66f)
        // Kept mostly light: brushes use grunge to rough up a stroke, not to break it apart.
        (.45f+.55f*(body*(.55f+.45f*grit)+ramp(grit,.7f,.9f)*.25f))*holes
    }
    /** Charcoal on toothy paper: streaky fibres along one axis over a paper tooth that catches the stick. */
    fun charcoal(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n
        // Fibres are six times finer across than along, giving horizontal streaks. Octaves stop near
        // 5 px cells, so nothing aliases into static.
        val fibres=fbm(u,v,4,3,71,6)
        val tooth=fbm(u,v,32,3,89)
        val ridges=1f-abs(fbm(u,v,8,3,97)*2f-1f)
        ramp(tooth*.35f+fibres*.45f+ridges*.2f,.36f,.74f)*(.7f+.3f*tooth)
    }

    /** A round of given [hardness] (0 soft to 1 hard), smoothstepped from the hard core to the rim. */
    fun round(n:Int,hardness:Float)=bytes(n){x,y->
        val dx=(x+.5f)/n*2f-1f;val dy=(y+.5f)/n*2f-1f;val d=sqrt(dx*dx+dy*dy)
        val t=((1f-d)/(1f-hardness).coerceAtLeast(.02f)).coerceIn(0f,1f);t*t*(3f-2f*t)
    }
    /** A flat block: a wide rectangle with slightly worn edges, for flat and chisel brushes. */
    fun flat(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n;val wear=(fbm(u,v,12,2,223)-.5f)*.08f
        ramp(.9f-kotlin.math.abs(u*2f-1f)+wear,0f,.04f)*ramp(.5f-kotlin.math.abs(v*2f-1f)+wear,0f,.04f)
    }
    /** A wet ink dab: round, with an edge that wobbles and frays. */
    fun inkBlot(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n
        val dx=u*2f-1f;val dy=v*2f-1f;val d=sqrt(dx*dx+dy*dy)
        val edge=.82f+.16f*(fbm(u,v,6,3,131)-.5f)*2f
        ramp(edge-d,-.02f,.05f)*(.85f+.15f*fbm(u,v,40,2,137))
    }
    /** A bristle dab: an oval of streaks running along one axis, uneven at the ends. */
    fun bristle(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n
        val dx=u*2f-1f;val dy=(v*2f-1f)*1.25f;val d=sqrt(dx*dx+dy*dy)
        val streaks=fbm(u,v,3,3,151,10);val body=ramp(.95f-d+(fbm(u,v,5,2,157)-.5f)*.3f,0f,.12f)
        body*ramp(streaks,.3f,.62f)
    }
    /** A spray dab: dots of different sizes, denser in the middle. */
    fun spray(n:Int):ByteArray{
        val out=FloatArray(n*n);var seed=0x5eed
        fun rnd():Float{seed=seed*1103515245+12345;return ((seed ushr 8) and 0xffff)/65535f}
        repeat(220){
            val a=rnd()*6.2832f;val r=sqrt(rnd())*.9f;val cx=(.5f+.5f*r*kotlin.math.cos(a))*n;val cy=(.5f+.5f*r*kotlin.math.sin(a))*n
            val rad=n*(.006f+.03f*rnd()*rnd());val x0=(cx-rad-1).toInt().coerceAtLeast(0);val x1=(cx+rad+1).toInt().coerceAtMost(n-1)
            for(y in (cy-rad-1).toInt().coerceAtLeast(0)..(cy+rad+1).toInt().coerceAtMost(n-1))for(x in x0..x1){
                val d=sqrt((x+.5f-cx)*(x+.5f-cx)+(y+.5f-cy)*(y+.5f-cy));out[y*n+x]=maxOf(out[y*n+x],(rad-d+.5f).coerceIn(0f,1f))}
        }
        return ByteArray(n*n){(out[it]*255f+.5f).toInt().toByte()}
    }
    /** Paper: fine fibres and a soft tooth, mostly light so paint shows with a little texture. */
    fun paper(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n
        val fibres=fbm(u,v,24,3,173,3);val tooth=fbm(u,v,64,2,179)
        .72f+.28f*ramp(tooth*.6f+fibres*.4f,.3f,.75f)
    }
    /** Canvas: a woven grid of threads. */
    fun canvas(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n;val threads=16f
        val warp=kotlin.math.abs(kotlin.math.sin(u*threads*3.1416f));val weft=kotlin.math.abs(kotlin.math.sin(v*threads*3.1416f))
        val over=if(((u*threads).toInt()+(v*threads).toInt())%2==0)warp else weft
        (.45f+.55f*over)*(.8f+.2f*fbm(u,v,32,2,191))
    }
    /** Speckle: solid paint with scattered clear flecks. */
    fun speckle(n:Int)=bytes(n){x,y->
        val u=x.toFloat()/n;val v=y.toFloat()/n
        val flecks=fbm(u,v,48,2,199);1f-ramp(flecks,.62f,.7f)*.9f
    }
    /** Clouds: soft, large patches of light and shade. */
    fun clouds(n:Int)=bytes(n){x,y->val u=x.toFloat()/n;val v=y.toFloat()/n;ramp(fbm(u,v,3,5,211),.2f,.8f)}
}
