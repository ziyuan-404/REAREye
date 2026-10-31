package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.graphics.Point
import android.os.Process
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import org.xml.sax.InputSource
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Render the installed factory handoff node with the host's own MAML engine. */
internal object NativeRearContinuation {
    const val VIEW_TAG = "reareye_native_front_continuation"
    fun create(context: Context, hint: String): View {
        val themeRoot = "/data/system/theme_magic/users/${Process.myUid() / 100_000}"
        val config = File("$themeRoot/subscreencenter/config/appInfo.json")
        require(config.length() in 1..2_000_000L)
        val apps = JSONArray(config.readText())
        val path = (0 until apps.length()).asSequence().map { apps.getJSONObject(it) }
            .first { it.optString("bindApp") == "com.xiaomi.smarthome" }.getString("mtzPath")
        val source = File(path).canonicalFile
        require(source.path.startsWith("$themeRoot/back_screen_card/") ||
            source.path.startsWith("/system/media/rearscreen/"))
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        val size = Point().also { context.display.getRealSize(it) }
        require(size.x > 0 && size.y > 0)
        val cached = File(context.cacheDir, "reareye-native-continuation-v5-${size.x}x${size.y}-$locale-static.mtz")
        if (!cached.isFile || cached.lastModified() < source.lastModified()) {
            ZipFile(source).use { zip ->
                val factory = DocumentBuilderFactory.newInstance()
                val manifest = requireNotNull(zip.getEntry("manifest.xml"))
                require(manifest.size in 1..2_000_000L)
                val sourceXml = zip.getInputStream(manifest).use { it.readNBytes(2_000_001) }
                    .toString(Charsets.UTF_8)
                require(sourceXml.length <= 2_000_000 &&
                    !sourceXml.contains("<!DOCTYPE", ignoreCase = true) &&
                    !sourceXml.contains("<!ENTITY", ignoreCase = true))
                val original = factory.newDocumentBuilder().parse(InputSource(StringReader(sourceXml)))
                val images = original.getElementsByTagName("Image")
                val image = (0 until images.length).asSequence().map { images.item(it) as Element }
                    .first { it.getAttribute("src") == "assets/flip_icon.png" }
                val nativeGroup = image.parentNode as Element
                require(nativeGroup.tagName == "Group")
                // Keep the factory geometry, font, line clamp and icon. Import only
                // its display node: no Mi Home providers, actions or app state.
                val document = factory.newDocumentBuilder().newDocument()
                val root = document.importNode(original.documentElement, false) as Element
                document.appendChild(root)
                root.setAttribute("useVariableUpdater", "")
                // This extracted node uses view-relative coordinates. Keep one
                // physical-pixel coordinate space so the 40px factory bitmap is
                // not density-downsampled before the final image transform.
                root.setAttribute("defaultScreenWidth", size.x.toString())
                root.setAttribute("defaultResourceDensity", context.resources.displayMetrics.densityDpi.toString())
                val variables = original.getElementsByTagName("Var")
                for (index in 0 until variables.length) {
                    val variable = variables.item(index) as Element
                    if (variable.getAttribute("name") in setOf("scale", "center_x", "max_width"))
                        root.appendChild(document.importNode(variable, true))
                }
                val group = document.importNode(nativeGroup, true) as Element
                group.removeAttribute("visibility")
                root.setAttribute("frameRate", "0")
                val texts = group.getElementsByTagName("Text")
                require(texts.length == 1)
                (texts.item(0) as Element).apply {
                    removeAttribute("textExp")
                    setAttribute("text", hint)
                }
                root.appendChild(group)
                val xml = ByteArrayOutputStream().also {
                    TransformerFactory.newInstance().newTransformer()
                        .transform(DOMSource(document), StreamResult(it))
                }.toByteArray()
                val icon = requireNotNull(zip.getEntry("assets/flip_icon.png"))
                require(icon.size in 1..256_000L)
                // A host-private runtime cache; factory assets never enter the module APK.
                ZipOutputStream(cached.outputStream()).use { output ->
                    output.putNextEntry(ZipEntry("manifest.xml")); output.write(xml); output.closeEntry()
                    output.putNextEntry(ZipEntry("assets/flip_icon.png"))
                    zip.getInputStream(icon).use { it.copyTo(output) }; output.closeEntry()
                }
            }
        }
        // Match the native rear MamlWidget's Config constructor, including its
        // wide-display flag. The generic constructor treats the rear as portrait
        // and derives resource density from its 596px short edge.
        val type = context.classLoader.loadClass("com.miui.maml.component.MamlView")
        val configClass = context.classLoader.loadClass("com.miui.maml.component.MamlView\$Config")
        val viewConfig = configClass.getConstructor().newInstance()
        configClass.getField("path").set(viewConfig, cached.path)
        configClass.getField("mode").setInt(viewConfig, 1)
        configClass.getField("viewWidth").setInt(viewConfig, size.x)
        configClass.getField("viewHeight").setInt(viewConfig, size.y)
        configClass.getField("flags").setInt(viewConfig, configClass.getField("FLAG_WIDE_DEVICE").getInt(null))
        configClass.getField("touchable").setBoolean(viewConfig, false)
        val result = type.getConstructor(Context::class.java, configClass).newInstance(context, viewConfig) as View
        result.tag = VIEW_TAG
        result.isFocusableInTouchMode = false
        result.isFocusable = false
        result.contentDescription = hint.replace('\n', '，')
        result.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                // MamlView only pauses itself on detach. Its native card owner
                // explicitly destroys it; this embedded node has the same duty.
                runCatching { type.getMethod("onDestroy").invoke(v) }
                v.removeOnAttachStateChangeListener(this)
            }
        })
        return result
    }

    fun show(host: ViewGroup, prompt: View, footer: View?, card: View) {
        NativeRearMotion.flip(card) {
            footer?.visibility = View.GONE
            host.removeAllViews()
            host.addView(prompt, ViewGroup.LayoutParams(-1, -1))
        }
    }
}
