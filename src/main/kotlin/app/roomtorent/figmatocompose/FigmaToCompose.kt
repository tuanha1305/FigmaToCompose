package app.roomtorent.figmatocompose

import BaseNodeMixin
import BlendMixin
import ComponentNode
import DefaultFrameMixin
import ExportSettingsSVG
import FigmaFile
import GroupNodeImpl
import InstanceNode
import LayoutMixin
import RectangleNode
import ShadowEffect
import SolidPaint
import TextNode
import com.beust.klaxon.Klaxon
import com.github.kittinunf.fuel.httpGet
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.io.*
import java.util.HashMap
import kotlin.properties.Delegates

fun readFromFile(filePath: String) = ObjectInputStream(FileInputStream(File(filePath))).readObject() as FigmaFile

class Settings private constructor(){
    companion object
    class Optimizations private constructor() {
        companion object {
            val omitExtraShadows: Boolean = true
        }
    }
}

class ConvertRequest() {
    val test: Boolean? = null
    var rootiestNode: BaseNodeMixin? = null
    var copyToClipboard: Boolean? = null
    var resetDecollisionMap: Boolean? = null
    var separateComposablesForEachComponent: Boolean? = null
}

fun Application.main() {
    println("Starting figma to compose server...")
    install(DefaultHeaders)
    install(CORS) {
      anyHost()
    }
    install(CallLogging)
    val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard

    routing {
        post("/") {

            val nodeJsonToConvert = call.receive<String>();
            try {
                val convertRequest = Klaxon().parse<ConvertRequest>(StringBufferInputStream(nodeJsonToConvert))!!
                if(convertRequest.test == true) call.respond(HttpStatusCode.Accepted, "")
                //          Re-set state. TODO: Remove the need to do this
                composables = hashMapOf()

                if(convertRequest.resetDecollisionMap == true) decollisionMap = HashMap<String, String>()

                val mainComposableContent = makeCompose(convertRequest.rootiestNode ?: throw Exception("Incomplete request")) {
                        fillMaxSize()
                } .removeNoAffectPatterns()

                val identifier = (convertRequest.rootiestNode?.name ?: "unnamed").toKotlinIdentifier()

                val joinedComposables = composables.values.joinToString(separator = "\n")
                val upperMostComposable = """
                        @Composable()
                        @Preview()
                        fun AndroidPreview_$identifier() {
                            Box(Modifier.preferredSize(360.dp, 640.dp)) {
                                $identifier()
                            }
                        }
                        
                        @Composable()
                        @Preview()
                        fun $identifier() {
                            $mainComposableContent
                        }
                """.trimIndent()

                val output = """
                    $joinedComposables
        
                    $upperMostComposable
                """.trimIndent()
                    .removeNoAffectPatterns()

                call.respond(output)
                if(convertRequest.copyToClipboard == true) {
                    println("Processed one, setting clipboard to output :) ")
                    val selection = StringSelection(output)
                    clipboard.setContents(selection, selection)
                }

            } catch (e: Exception) {
                println("We rockin' an axeception: $e")
                e.printStackTrace()
            }
        }
    }
    println("Started server! You can use the plugin to genarate compose code now!")
}


fun Mods(extraModifiers: (Modifier.() -> Unit)? = null, mods: Modifier.() -> Unit = { none() }): String {
    val modifier = Modifier(extraModifiers)
    mods.invoke(modifier)
    return modifier.getBuiltOptimized()
}

//Store a mapping of modified to original.
//If an original doesn't match its associated modified, it is a collision and we can add a number, and check again
//This requires more lookups than it could, which can be fixed if it ever matters.
private var decollisionMap = HashMap<String, String>()
private fun String.toKotlinIdentifier(): String {
    val original = this
    val changed = this.replace(Regex("[\\s-/,.]"), "_")
    var matches = decollisionMap.getOrPut(changed) { original } == original
    var attempts = 0
    while (!matches) {
        matches = decollisionMap.getOrPut(changed + "_" + attempts++) { original } == original
    }
    return if (attempts > 0) changed + "_" + attempts else changed
}

var composables: HashMap<String, String> = hashMapOf()
fun defineComponentFromFrameMixin(node: DefaultFrameMixin): String {
    val name = node.name ?: "unnamed"
    val identifier = name.toKotlinIdentifier()
    composables[identifier] = """
        @Composable()
        fun $identifier(modifier: Modifier = Modifier) {
            ${frameOrAutoLayoutToCompose(node) { addPassedProperties() }}
        }"""
        .trimIndent()

    return identifier
}

fun frameOrAutoLayoutToCompose(node: DefaultFrameMixin, extraModifiers: (Modifier.() -> Unit)?): String {
    return when {
        node.layoutMode != null && node.layoutMode != "NONE" -> autoLayoutToComposeRowColumn(
            node,
            extraModifiers
        )

        else -> frameToComposeConstraintsLayout(
            node,
            extraModifiers
        )
    }
}

/**
 * Creates a box with a modifier referencing the vector asset specified in the export settings for this frame. A resource with this id will be needed
 * Note that android resources are all lower case, so for convenience this will convert the name to lowercase which could cause duplicates
 * TODO: Make it easier to import import assets to android project. Plugins already exist for this though
 */
fun vectorFrameToCompose(node: DefaultFrameMixin): String {
    val exportSettings = node.exportSettings!!.any { it is ExportSettingsSVG }
    return """
    ${"Box"
        .args(Mods {
            preferredSize(node.width, node.height)
            paintVectorPaint("R.drawable.${node.name?.toLowerCase()}" ?: throw Exception(""))
            addStyleMods(node)
        })}
    """.trimIndent()
}

fun makeCompose(node: BaseNodeMixin, extraModifiers: (Modifier.() -> Unit)? = null): String {
    return when (node) {
        //Groups will show a size, but the size is based off its dchildren and editing it in figma changes the child size.
        //So for groups we will make a box with no explicit size.
        is GroupNodeImpl -> {
            "Box()".body(node.children?.joinToString(separator = "\n") { makeCompose(it) } ?: "")
        }
        is DefaultFrameMixin -> when {
            node.exportSettings?.any { it is ExportSettingsSVG } == true -> vectorFrameToCompose(node)

            node is ComponentNode -> with(node) {
                val identifier = defineComponentFromFrameMixin(node)
                "$identifier(${Mods(extraModifiers, mods = {})})"
            }
            node is InstanceNode -> with(node) {
                val identifier = (node.name ?: "unnamed").toKotlinIdentifier()
                if (!composables.containsKey(identifier)) {
                    defineComponentFromFrameMixin(node)
                }
                "$identifier(${Mods(extraModifiers, mods = {})})"
            }
            else -> frameOrAutoLayoutToCompose(node, extraModifiers)
        }
        is RectangleNode -> with(node) {
            """
               Box(${Mods(extraModifiers) {
                preferredSize(
                    width,
                    height
                )
                addStyleMods(node)
            }}){} 
            
            """.trimIndent()
        }
        is TextNode -> with(node) {
            """
                ${node.characters?.let { text -> 
                "Text".args("\"$text\"", Mods(extraModifiers) {
                    wrapContentHeight(when(node.textAlignVertical) { 
                        "TOP" -> Modifier.Alignment.Top
                        "CENTER" -> Modifier.Alignment.CenterVertically
                        "BOTTOM" -> Modifier.Alignment.Bottom
                        
                        else -> throw Exception("Alignment ${node.textAlignVertical} must be new")
                    })
                },
                    "style = currentTextStyle().copy".args(
                        "color = " + when (this.fills?.get(0)?.type) {
                            "SOLID" -> with(this.fills?.get(0) as SolidPaint) {
                                this.color.toComposeColor(node.opacity.toFloat())
                            }
                            else -> "Color.BLACK"
                        },
                        //TODO: RTL text support
                        "textAlign = " + when (this.textAlignHorizontal) {
                            "LEFT" -> "TextAlign.Left"
                            "RIGHT" -> "TextAlign.Right"
                            "CENTER" -> "TextAlign.Center"
                            "JUSTIFIED" -> "TextAlign.Justify"

                            else -> throw Exception("Horizontal text alignment type ${this.textAlignHorizontal} is new to me")
                        },
                        "fontSize = ${node.fontSize}.sp"
                    ),
                    escapeCharacters = true
                )
            }}
            
            """.trimIndent()
        }
        else -> ""
    }
}


private fun Element(name: String, vararg parameters: String, body: () -> String) =
    Element(name, (parameters).joinToString(", "), body.invoke())

private fun Element(name: String, parameters: String, body: String) = """
    $name($parameters) {
       ${body.split("\n").joinToString("\n    ")}
    }
""".trimIndent()


//DONE: When parent is a Frame layout, and the constraints are not Top and Left
//DONE: Learn about equivelants to sConstraints in Compose
//Problem: Constraints in Figma change the x, y, width and height when changes are made to the parent element
// Convert dps into percentages if the Constraints are not top and left?
// Using constraint layouts can be an easy enough solution
fun BaseNodeMixin.localX(parent: BaseNodeMixin? = null as BaseNodeMixin): Double {

    if (this is LayoutMixin) {
        return this.x
    }

//    From when this used to use the api rather than the plugin interface
//    if (parent is LayoutMixin && this is LayoutMixin) {
//        val parentLayout = parent as LayoutMixin
//        val parentX = parentLayout.x.toDouble()
//        val thisX = this.x.toDouble()
//        return (Math.abs(parentX) - Math.abs(thisX)) * -parentX.compareTo(thisX)
//    } else if (this is LayoutMixin) {
//        return this.x
//    }
    throw Exception("Not a a layout")
}


fun BaseNodeMixin.localY(parent: BaseNodeMixin? = null): Double {
    if (this is LayoutMixin) {
        return this.y
    }
    throw Exception("Not a a layout")
}
