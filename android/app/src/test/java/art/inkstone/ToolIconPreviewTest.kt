package art.inkstone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.File

// Draws the main toolbar icons on the dark and light toolbars into artifacts/icons, for checking by eye.
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolIconPreviewTest{
    @Test fun toolbarIcons(){
        val symbols=listOf("Paint","Paint Mixing","Smudge","Erase","Layers")
        val size=144
        for(light in listOf(false,true)){
            val theme=StudioTheme(light)
            val out=Bitmap.createBitmap(size*symbols.size,size,Bitmap.Config.ARGB_8888);val c=Canvas(out);c.drawColor(theme.toolbar)
            for((i,symbol) in symbols.withIndex()){
                val icon=ToolIcon(RuntimeEnvironment.getApplication(),symbol){}.apply{this.light=light}
                icon.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY))
                icon.layout(0,0,size,size);c.save();c.translate((i*size).toFloat(),0f);icon.draw(c);c.restore()
            }
            val file=File(System.getProperty("inkstone.artifacts"),"icons/toolbar-${if(light)"light" else "dark"}.png");file.parentFile?.mkdirs()
            file.outputStream().use{assertTrue(out.compress(Bitmap.CompressFormat.PNG,100,it))}
            // Every icon must actually paint something different from the toolbar.
            for(i in symbols.indices){
                var marked=0
                for(y in 0 until size step 2)for(x in i*size until (i+1)*size step 2)if(out.getPixel(x,y)!=theme.toolbar)marked++
                assertTrue("${symbols[i]} is empty",marked>40)
            }
        }
    }
}
