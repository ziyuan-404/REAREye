package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.Context
import android.graphics.Outline
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Icon placement inside a stock picker row. The ROM owns the panel and its gestures. */
internal class NativeRearAppIcons(
    context: Context,
    icons: List<View>,
    private val style: Int,
    private val rowIndex: Int = 0,
) : ViewGroup(context) {
    private data class Cell(val left: Int,val top: Int,val size: Int)
    private var cells = emptyList<Cell>()
    private val press=WeakHashMap<View,Float>()
    private val animators=WeakHashMap<View,ValueAnimator>()
    private var lastScreenY=Int.MIN_VALUE
    private var movingUntil=0L
    private var motionPosted=false
    private val origin=IntArray(2)
    private val visibleArea=android.graphics.Rect()
    private var viewportRef=java.lang.ref.WeakReference<View>(null)
    private val pressInterpolator=PathInterpolator(.4f,0f,.2f,1f)
    private fun followMotion(until:Long) {
        movingUntil=maxOf(movingUntil,until)
        if(!motionPosted&&isAttachedToWindow&&isShown&&windowVisibility==VISIBLE) {
            motionPosted=true;postOnAnimation(motion)
        }
    }
    private val motion=object:Runnable {
        override fun run() {
            motionPosted=false
            if(!isAttachedToWindow||!isShown||windowVisibility!=VISIBLE||!getGlobalVisibleRect(visibleArea))return
            updateVisuals()
            // Follow native Folme only while it is moving. Visibility/pre-draw
            // callbacks restart this; hidden or stationary rows have no timer.
            if(SystemClock.uptimeMillis()<movingUntil)followMotion(movingUntil)
        }
    }
    private val draw=ViewTreeObserver.OnPreDrawListener { updateVisuals();true }
    init {
        clipChildren=false
        clipToPadding=false
        icons.forEach { icon ->
            (icon.parent as? ViewGroup)?.removeView(icon)
            icon.apply {
                outlineProvider=object: ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setOval(0,0,view.width,view.height) }
                }
                clipToOutline=true
                setOnTouchListener { target,event ->
                    val to=when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN->0.9f
                        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->1f
                        else->null
                    }
                    if(to!=null) {
                        animators.remove(target)?.cancel()
                        animators[target]=ValueAnimator.ofFloat(press[target]?:1f,to).apply {
                            // Original flue-icon-press: 170ms, cubic(0.4,0,0.2,1).
                            duration=170;interpolator=pressInterpolator
                            addUpdateListener { press[target]=it.animatedValue as Float;updateVisuals() }
                            start()
                        }
                    }
                    false
                }
            }
            addView(icon)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(draw)
        followMotion(SystemClock.uptimeMillis()+600)
    }
    override fun onVisibilityChanged(changedView:View,visibility:Int) {
        super.onVisibilityChanged(changedView,visibility)
        if(!isAttachedToWindow)return
        if(isShown)followMotion(SystemClock.uptimeMillis()+600)
        else { removeCallbacks(motion);motionPosted=false }
    }
    override fun onWindowVisibilityChanged(visibility:Int) {
        super.onWindowVisibilityChanged(visibility)
        if(!isAttachedToWindow)return
        if(visibility==VISIBLE)followMotion(SystemClock.uptimeMillis()+600)
        else { removeCallbacks(motion);motionPosted=false }
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(draw)
        removeCallbacks(motion)
        motionPosted=false;viewportRef.clear()
        animators.values.toList().forEach { it.cancel() };animators.clear()
        super.onDetachedFromWindow()
    }
    private fun updateVisuals() {
        if(style!=1||width==0||cells.isEmpty()||!isShown||!getGlobalVisibleRect(visibleArea))return
        val viewport=viewportRef.get()?:generateSequence(parent as? View) { it.parent as? View }
            .firstOrNull { it.javaClass.name=="androidx.recyclerview.widget.RecyclerView" }?.also { viewportRef=java.lang.ref.WeakReference(it) }?:return
        if(viewport.height==0)return
        getLocationOnScreen(origin)
        if(origin[1]!=lastScreenY) {
            lastScreenY=origin[1]
            followMotion(SystemClock.uptimeMillis()+250)
        }
        val centerX=width/2f
        // Anchor the lens to the visible screen. Subtracting the moving RecyclerView
        // origin made the stock drawer's translation cancel out, so every icon moved
        // as one rigid plate during entry and exit.
        val centerY=viewport.rootView.height/2f
        val radius=(min(width,viewport.height)*.84f).coerceAtLeast(1f)
        // Ported from FlueVisual (n1): strength=55, range=4,
        // radial fisheye plus vertical-edge horizontal compression.
        cells.forEachIndexed { index,cell ->
            val child=getChildAt(index)
            val x=cell.left+cell.size/2f
            val y=origin[1]+cell.top+cell.size/2f
            val distance=(hypot(x-centerX,y-centerY)/radius).coerceIn(0f,1f)
            val scale=1f-(.55f*.42f)*distance
            val vertical=(abs(y-centerY)/centerY).coerceIn(0f,1f)
            val compression=((3f-2f*vertical)*.55f*.18f*vertical*vertical).coerceIn(0f,.32f)
            child.scaleX=scale*(press[child]?:1f)
            child.scaleY=child.scaleX
            child.alpha=scale.coerceIn(.24f,1f)
            child.translationX=-(x-centerX)*compression
        }
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val honeycomb=style==1
        val columns=if(honeycomb)4 else if(style==4)4 else 3
        val pitch=width.toFloat()/(columns+0.2f)
        val size=(pitch*0.82f).roundToInt()
        // Same alternating short/long rows and sqrt(3)/2 spacing as the ported layout.
        val rowStep=pitch*if(honeycomb)sqrt(3f)/2f else 1f
        val result=ArrayList<Cell>()
        var row=0
        while(result.size<childCount) {
            val count=if(honeycomb&&(row+rowIndex)%2==0)columns-1 else columns
            val remaining=(childCount-result.size).coerceAtMost(count)
            repeat(remaining) { column ->
                val center=width/2f+(column-(count-1)/2f)*pitch
                result.add(Cell((center-size/2).roundToInt(),(row*rowStep).roundToInt(),size))
            }
            row++
        }
        cells=result
        result.forEachIndexed { index,cell -> getChildAt(index).measure(
            MeasureSpec.makeMeasureSpec(cell.size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(cell.size,MeasureSpec.EXACTLY)) }
        // Each honeycomb row is a real stock RecyclerView item. Keep the staggered
        // pitch without making one tall item own the whole drawer's depth effect.
        setMeasuredDimension(width,if(honeycomb) (row*rowStep).roundToInt() else (result.maxOfOrNull { it.top+it.size }?:0)+24)
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        cells.forEachIndexed { index,cell -> getChildAt(index).layout(cell.left,cell.top,cell.left+cell.size,cell.top+cell.size) }
    }
}
