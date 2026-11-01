package art.inkstone

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlertDialog
import org.junit.runner.RunWith
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryLibraryTest {
    private fun store()=DocumentStore(RuntimeEnvironment.getApplication())
    private fun seed(store:DocumentStore,name:String):String{val id=store.newId();val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);b.eraseColor(Color.CYAN);store.save(id,name,byteArrayOf(1,2,3),b);b.recycle();return id}
    private fun find(v:View,label:String):View?{if(v.contentDescription==label || v is TextView && v.text.toString()==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),label)?.let{return it};return null}
    private fun until(test:()->Boolean){val end=System.nanoTime()+20_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Gallery timed out")}
    private fun render(v:View,name:String){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);v.invalidate();val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getProperty("inkstone.artifacts"),name+".png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun galleryCardsKeepTheirRatioAcrossPortraitLandscapeAndBack(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val id=seed(a.store,"Rotation check")
        val gallery=GalleryView(a);a.setContentView(gallery)
        fun layout(w:Int,h:Int){repeat(3){gallery.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));gallery.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle()}}
        for((w,h) in listOf(800 to 1280,1280 to 800,800 to 1280)){
            layout(w,h)
            val card=gallery.findViewWithTag<ViewGroup>("gallery:art:$id");val frame=card.getChildAt(0)
            val ratio=frame.height.toFloat()/frame.width
            assertTrue("Card ratio $ratio at $w x $h",ratio in .65f.. .88f)
        }
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun tagsAndStacksSurviveResaveDuplicateAndDissolvingWithoutDeletingArt(){
        val s=store();val id=seed(s,"Sketch");val stack=s.createStack("Studies");s.assignStack(listOf(id),stack);s.setTags(listOf(id),listOf(" Portrait ","portrait","Practice"))
        val created=s.list().first{it.id==id}.created;val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);s.save(id,"Sketch",byteArrayOf(9,8),b);b.recycle()
        val loaded=store();val art=loaded.list().first{it.id==id};assertEquals(stack,art.stackId);assertEquals(listOf("Portrait","Practice"),art.tags);assertEquals(created,art.created)
        loaded.renameStack(stack,"Paintings");loaded.duplicate(id);assertEquals(2,loaded.list().count{it.stackId==stack});assertEquals("Paintings",store().stacks().first{it.id==stack}.name)
        loaded.unstack(stack);assertEquals(2,loaded.list().size);assertTrue(loaded.list().all{it.stackId==null});assertArrayEquals(byteArrayOf(9,8),loaded.read(id));assertTrue(loaded.stacks().isEmpty())
        loaded.setTags(listOf(id),emptyList());assertTrue(loaded.list().first{it.id==id}.tags.isEmpty())
    }
    @Test fun sortingAndTagFilteringKeepStackCountsAndRasterVectorArtworks(){
        val arts=listOf(Artwork("a","Zulu",64,64,10,tags=listOf("Sketch"),created=1),Artwork("b","Alpha",64,64,20,"vector",listOf("Portrait"),"s",2),Artwork("c","Beta",64,64,30,tags=listOf("Sketch"),stackId="s",created=3))
        val stacks=listOf(ArtworkStack("s","Studies",5))
        assertEquals(listOf("Studies","Zulu"),GalleryLibrary.entries(arts,stacks,null,null,0).map{it.name})
        assertEquals(listOf("Studies","Zulu"),GalleryLibrary.entries(arts,stacks,null,null,2).map{it.name})
        val filtered=GalleryLibrary.entries(arts,stacks,null,"Sketch",0);assertEquals(2,filtered[0].total);assertEquals(1,filtered[0].members.size)
        assertEquals(listOf("Alpha"),GalleryLibrary.entries(arts,stacks,"s","Portrait",2).map{it.name})
        assertEquals(listOf("Zulu","Studies"),GalleryLibrary.entries(arts,stacks,null,null,1).map{it.name})
        assertEquals(listOf("Alpha","Beta"),GalleryLibrary.entries(arts,stacks,"s",null,2).map{it.name})
    }
    @Test fun gallerySortFilterStackRenameOpenBackAndTagRemovalWorkInUi(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val s=a.store
        val first=seed(s,"Alpha");val second=seed(s,"Zulu");s.setTags(listOf(first),listOf("Sketch"));val stack=s.createStack("Studies");s.assignStack(listOf(second),stack)
        val gallery=GalleryView(a);a.setContentView(gallery);render(gallery,"gallery-library-root")
        fun click(name:String){assertTrue(checkNotNull(find(gallery,name)){name}.performClick());shadowOf(Looper.getMainLooper()).idle()}
        click("Sort artworks");ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,2,2);assertEquals(2,a.getSharedPreferences("gallery-library",0).getInt("sort",-1))
        click("Filter by tag");ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,1,1);assertNull(find(gallery,"Stack Studies, 1 artworks"));assertNotNull(find(gallery,"#Sketch"));render(gallery,"gallery-library-filtered")
        assertTrue(gallery.handleBack());assertNotNull(find(gallery,"Stack Studies, 1 artworks"))
        find(gallery,"Stack Studies, 1 artworks")!!.performLongClick();ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0)
        val dialog=ShadowAlertDialog.getLatestAlertDialog();fun edit(v:View):EditText?{if(v is EditText)return v;if(v is ViewGroup)for(i in 0 until v.childCount)edit(v.getChildAt(i))?.let{return it};return null}
        edit(dialog.window!!.decorView)!!.setText("Paintings");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertEquals("Paintings",s.stacks().first{it.id==stack}.name);click("Stack Paintings, 1 artworks");assertNotNull(find(gallery,"Back to gallery"));assertNotNull(find(gallery,"Zulu"));render(gallery,"gallery-library-stack")
        assertTrue(gallery.handleBack());assertNull(find(gallery,"Outside groups"))
        find(gallery,"Artwork options for Alpha")!!.performClick();ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,3,3);val tags=ShadowAlertDialog.getLatestAlertDialog();edit(tags.window!!.decorView)!!.setText("");tags.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(s.list().first{it.id==first}.tags.isEmpty())
        val session=a.session;c.pause().stop().destroy();until{session==null || session.fullyClosed};until{a.io.isTerminated}
    }
    @Test fun artworkDragMovesAcrossAnyStackAndBackToGallery(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val store=a.store;val id=seed(store,"Sketch");store.setTags(listOf(id),listOf("Practice"));val one=store.createStack("One");val two=store.createStack("Two")
        val gallery=GalleryView(a);a.setContentView(gallery);render(gallery,"gallery-drag-before")
        fun drop(v:View){
            val method=android.view.DragEvent::class.java.declaredMethods.first{it.name=="obtain" && it.parameterTypes.size>1};method.isAccessible=true
            for(action in listOf(android.view.DragEvent.ACTION_DRAG_STARTED,android.view.DragEvent.ACTION_DRAG_ENTERED,android.view.DragEvent.ACTION_DROP,android.view.DragEvent.ACTION_DRAG_ENDED)){
                var at=0;val args=method.parameterTypes.map{type->when(type){Integer.TYPE->action;java.lang.Float.TYPE->if(at++==0)v.width*.5f else v.height*.5f;java.lang.Boolean.TYPE->false;Any::class.java->ArtworkDrag.Token(store,listOf(id),id);else->null}}.toTypedArray();val event=method.invoke(null,*args) as android.view.DragEvent
                val accepted=v.dispatchDragEvent(event);if(action==android.view.DragEvent.ACTION_DROP)assertTrue(accepted)
            }
        }
        drop(find(gallery,"Stack One, 0 artworks")!!);assertEquals(one,store.list().first{it.id==id}.stackId);render(gallery,"gallery-drag-in-stack")
        find(gallery,"Stack One, 1 artworks")!!.performClick();render(gallery,"gallery-drag-open-stack")
        drop(find(gallery,"Drop artwork into Two")!!);assertEquals(two,store.list().first{it.id==id}.stackId);assertTrue(gallery.handleBack());render(gallery,"gallery-drag-other-stack")
        find(gallery,"Stack Two, 1 artworks")!!.performClick();render(gallery,"gallery-drag-return-root")
        drop(find(gallery,"Drop artwork into Gallery")!!);assertNull(store.list().first{it.id==id}.stackId);assertEquals(listOf("Practice"),store.list().first{it.id==id}.tags)
        assertTrue(gallery.handleBack());assertNotNull(find(gallery,"Sketch"))
        val session=a.session;c.pause().stop().destroy();until{session==null || session.fullyClosed};until{a.io.isTerminated}
    }
    @Test fun galleryReorderCrossfadesSnapshotsThenSettlesToRealCards(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val root=android.widget.LinearLayout(a).apply{orientation=android.widget.LinearLayout.VERTICAL}
        val first=View(a).apply{tag="gallery:art:a";setBackgroundColor(Color.RED)};val second=View(a).apply{tag="gallery:art:b";setBackgroundColor(Color.GREEN)}
        root.addView(first,android.widget.LinearLayout.LayoutParams(200,100));root.addView(second,android.widget.LinearLayout.LayoutParams(200,100));a.setContentView(root);render(root,"gallery-motion-before")
        val motion=GalleryMotion.capture(root);root.removeView(second);root.addView(second,0);root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,800);motion.play(root);root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(first.alpha<1f);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));assertEquals(1f,first.alpha,0f);assertEquals(1f,second.alpha,0f);assertEquals(0,second.top)
        val session=a.session;c.pause().stop().destroy();until{session==null || session.fullyClosed};until{a.io.isTerminated}
    }

}
