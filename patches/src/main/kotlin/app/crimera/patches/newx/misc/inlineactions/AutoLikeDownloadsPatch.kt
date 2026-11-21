package app.crimera.patches.newx.misc.inlineactions

import app.crimera.bytecode.insertHook
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.newx.models.readModelAccessor
import app.crimera.patches.newx.models.resolveFieldAccessor
import app.crimera.patches.newx.models.resolvedNewXInlineActionBarModels
import app.crimera.patches.newx.models.resolvedNewXInlineActionModels
import app.crimera.patches.newx.models.resolvedNewXPostModels
import app.crimera.patches.newx.settings.Categories
import app.crimera.patches.newx.settings.Groups
import app.crimera.patches.newx.settings.newXSettings
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.unsupportedOnMount
import app.crimera.patches.settings.group
import app.crimera.patches.settings.settingStrings
import app.crimera.patches.settings.toggle
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/newx/misc/AutoLikeDownloads;"
private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val LIKE_ACTION = "LIKE"

// Kotlin default-argument mask of the post-action call: default extras (8), no post id (16).
private const val LIKE_DEFAULTS_MASK = 8 or 16

@Suppress("unused")
val newXAutoLikeDownloadsPatch =
    patch(
        name = "NewX: Auto-like downloaded posts",
        description = "Likes a post when you download its media, with the inline download button " +
            "or with NewX's own download buttons.",
        default = false,
    ) {
        unsupportedOnMount(default = false)
        compatibleWith(COMPATIBILITY_NEW_X)
        category("NewX patches")
        // The redirect patch owns the hooks on NewX's own download buttons.
        dependsOn(newXInlineDownloadButtonPatch, newXNativeDownloadRedirectPatch)

        newXSettings {
            category(Categories.POST_ACTIONS_MEDIA) {
                group(Groups.INLINE_DOWNLOAD) {
                    toggle(
                        id = "newx.content.inline_download.auto_like",
                        strings = settingStrings("piko_newx_inline_download_auto_like"),
                        order = 175,
                        defaultValue = true,
                    )
                }
            }
        }

        execute {
            val entryModels = resolvedNewXInlineActionModels()
            val barModels = resolvedNewXInlineActionBarModels()
            val postModels = resolvedNewXPostModels()

            // The inline action bar presenter holds the post-actions repository, the class that
            // registers the durable "LIKE" action in its constructor.
            val presenterClass = mutableClassDefBy(barModels.inlineActionBarDescriptor)
            val repositoryField = requireExactlyOne(
                "NewX inline presenter post-actions repository",
                presenterClass.fields.filter { field ->
                    !AccessFlags.STATIC.isSet(field.accessFlags) &&
                        classDefByOrNull(field.type)?.methods?.any { method ->
                            method.name == "<init>" && method.implementation?.instructions?.any {
                                it.getReference<StringReference>()?.string == LIKE_ACTION
                            } == true
                        } == true
                },
            )
            val repositoryType = repositoryField.type
            // Its static (repository, post, action, durable action, extras, post id, mask) call is
            // what the like button queues.
            val likeMethod = requireExactlyOne(
                "NewX post-action call in $repositoryType",
                classDefByOrNull(repositoryType)!!.methods.filter { method ->
                    val parameters = method.parameterTypes.map(CharSequence::toString)
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                        parameters == listOf(
                            repositoryType,
                            postModels.contextualPostDescriptor,
                            entryModels.postActionTypeDescriptor,
                            "Ljava/lang/String;",
                            "Ljava/util/Map;",
                            parameters.getOrNull(5),
                            "I",
                        ) && parameters.getOrNull(5)?.startsWith("L") == true
                },
            )
            val favoriteField = requireExactlyOne(
                "NewX Favorite post action",
                mutableClassDefBy(entryModels.postActionTypeDescriptor).fields.filter {
                    it.name == "Favorite" && AccessFlags.STATIC.isSet(it.accessFlags)
                },
            )

            val extensionClass = mutableClassDefBy(EXTENSION)
            extensionClass.bridge("patched").insertHook(0, relocateBranchTargets = false) {
                constInt(0, 1)
                returnValue(0)
            }
            extensionClass.portFieldRead(
                "getPostActionRepository",
                presenterClass,
                repositoryField,
            )
            extensionClass.portFieldRead(
                "getInlineActionEntries",
                mutableClassDefBy(postModels.canonicalPostDescriptor),
                barModels.canonicalPostInlineActionEntryField,
            )

            val likeStub = extensionClass.bridge("likePost")
            val likeBridge = likeStub.cloneMutable(additionalRegisters = 7).also {
                extensionClass.methods.remove(likeStub)
                extensionClass.methods.add(it)
            }
            likeBridge.insertHook(0, relocateBranchTargets = false) {
                val repository = likeBridge.p0Register
                move(0, repository, OBJECT_DESCRIPTOR)
                checkCast(0, repositoryType)
                move(1, repository + 1, OBJECT_DESCRIPTOR)
                checkCast(1, postModels.contextualPostDescriptor)
                sget(2, favoriteField)
                constString(3, LIKE_ACTION)
                constInt(4, 0)
                constInt(5, 0)
                constInt(6, LIKE_DEFAULTS_MASK)
                add(BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 7, likeMethod))
                returnVoid()
            }
        }
    }

private fun MutableClass.bridge(name: String): MutableMethod =
    requireExactlyOne("NewX auto-like bridge $name", methods.filter { it.name == name })

/** Fills an `(Object)Object` bridge with a read of [field] from its cast argument. */
private fun MutableClass.portFieldRead(name: String, owner: MutableClass, field: FieldReference) {
    val accessor = owner.resolveFieldAccessor(field, "auto-like $name")
    val helper = bridge(name)
    helper.insertHook(0, relocateBranchTargets = false) {
        val value = helper.p0Register
        checkCast(value, owner.type)
        readModelAccessor(accessor, value)
        returnObject(value)
    }
}
