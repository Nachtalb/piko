package app.crimera.patches.newx.misc.drawer

import app.crimera.patches.common.destinationRegisterOrNull
import app.crimera.patches.common.isAssignableTo
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val ITEM_STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val ITEM_COMPOSER_DESCRIPTOR = "Landroidx/compose/runtime/Composer;"
private const val ITEM_ICONS_PREFIX = "Lcom/x/icons/"
private const val ARE_EQUAL_OWNER = "Lkotlin/jvm/internal/Intrinsics;"
private const val MAX_ITEM_PATH_STEPS = 20_000

/** One drawer item kind of a model-driven drawer, with the title strings and icon it renders. */
internal data class DrawerItemEntry(
    val itemType: String,
    val titleResourceIds: Set<Int>,
    val iconField: FieldReference?,
)

/**
 * Model-driven drawers (12.45+) render a list of item objects. One `(item, Z, Composer) -> String`
 * mapper resolves each item's title and one `(item) -> Icon` mapper its icon, so the item table
 * is read from those two mappers instead of from per-row renderer calls.
 */
internal data class DrawerItemModel(
    val titleMethod: MethodReference,
    val iconMethod: MethodReference,
    val itemBaseType: String,
    val entries: List<DrawerItemEntry>,
) {
    fun entriesForTitle(resourceId: Int): List<DrawerItemEntry> =
        entries.filter { resourceId in it.titleResourceIds }

    /** The single item kind titled [resourceId] whose icon resolves to a unique icon field. */
    fun requireEntryWithUniqueIcon(label: String, resourceIds: Set<Int>): DrawerItemEntry {
        val matches = entries.filter { entry -> entry.titleResourceIds.any { it in resourceIds } }
            .distinctBy { it.itemType }
        val entry = matches.singleOrNull()
            ?: throw PatchException(
                "Expected exactly one NewX drawer item kind for $label, found ${matches.size}: " +
                    matches.joinToString { it.itemType },
            )
        val icon = entry.iconField
            ?: throw PatchException("NewX drawer item kind for $label has no resolvable icon: ${entry.itemType}")
        val sharing = entries.filter { it.iconField?.toString() == icon.toString() }
        if (sharing.size != 1) {
            throw PatchException(
                "NewX drawer item icon for $label is shared by ${sharing.size} item kinds: " +
                    sharing.joinToString { it.itemType },
            )
        }
        return entry
    }
}

/** Returns the item model of a model-driven drawer, or null on releases with per-row renderers. */
internal fun resolveDrawerItemModelOrNull(): DrawerItemModel? {
    val titleMethods = mutableListOf<Method>()
    val iconMethods = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (!classDef.type.startsWith(DRAWER_SCOPE)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.accessFlags and AccessFlags.STATIC.value == 0) return@forEach
            if (method.implementation == null) return@forEach
            val parameters = method.parameterTypes.map(CharSequence::toString)
            val first = parameters.firstOrNull() ?: return@forEach
            if (!first.startsWith("L") || first.startsWith("Ljava/") || first.startsWith("Landroidx/")) {
                return@forEach
            }
            if (method.returnType == ITEM_STRING_DESCRIPTOR &&
                parameters == listOf(first, "Z", ITEM_COMPOSER_DESCRIPTOR)
            ) {
                titleMethods += method
            }
            if (method.returnType.startsWith(ITEM_ICONS_PREFIX) && parameters == listOf(first)) {
                iconMethods += method
            }
        }
    }
    val pairs = titleMethods.flatMap { title ->
        iconMethods.filter { icon -> icon.parameterTypes.first().toString() == title.parameterTypes.first().toString() }
            .map { icon -> title to icon }
    }
    if (pairs.isEmpty()) return null
    val (titleMethod, iconMethod) = pairs.singleOrNull()
        ?: throw PatchException(
            "Expected one NewX drawer item title/icon mapper pair, found ${pairs.size}: " +
                pairs.joinToString { "${it.first} / ${it.second}" },
        )
    val itemBaseType = titleMethod.parameterTypes.first().toString()
    val itemTypes = (titleMethod.itemTests() + iconMethod.itemTests()).distinct()
    if (itemTypes.isEmpty()) {
        throw PatchException("NewX drawer item mappers test no item kinds: $titleMethod")
    }
    val entries = itemTypes.map { itemType ->
        val titles = titleMethod.explore(itemType).literals.filterTo(linkedSetOf()) { it ushr 24 == 0x7f }
        val icons = iconMethod.explore(itemType).returnedIcons
        DrawerItemEntry(
            itemType = itemType,
            titleResourceIds = titles,
            iconField = icons.distinctBy(FieldReference::toString).singleOrNull(),
        )
    }
    return DrawerItemModel(titleMethod, iconMethod, itemBaseType, entries)
}

/** Item kinds a mapper distinguishes: compared singleton instances and instance-of checks. */
private fun Method.itemTests(): List<String> {
    val instructions = implementation?.instructions?.toList().orEmpty()
    return instructions.mapNotNull { instruction ->
        when (instruction.opcode) {
            Opcode.SGET_OBJECT -> {
                val field = instruction.getReference<FieldReference>() ?: return@mapNotNull null
                field.type.takeIf { it == field.definingClass && !it.startsWith(ITEM_ICONS_PREFIX) }
            }
            Opcode.INSTANCE_OF -> instruction.getReference<TypeReference>()?.type
            else -> null
        }
    }.distinct()
}

private sealed interface ItemValue {
    data object ItemParameter : ItemValue
    data class Singleton(val type: String) : ItemValue
    data class Known(val value: Boolean) : ItemValue
    data class Icon(val field: FieldReference) : ItemValue
}

private class ItemExploration {
    val literals = linkedSetOf<Int>()
    val returnedIcons = mutableListOf<FieldReference>()
}

/**
 * Walks every path the mapper can take for an item of [itemType]: item comparisons are decided,
 * every other condition forks. Collects the integer literals on those paths and the icon fields
 * they return.
 */
private fun Method.explore(itemType: String): ItemExploration {
    val implementation = implementation ?: return ItemExploration()
    val instructions = implementation.instructions.toList()
    val addresses = IntArray(instructions.size)
    var address = 0
    instructions.forEachIndexed { index, instruction ->
        addresses[index] = address
        address += instruction.codeUnits
    }
    val indexByAddress = addresses.withIndex().associate { (index, value) -> value to index }
    val itemRegister = implementation.registerCount - parameterTypes.sumOf { type ->
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    val result = ItemExploration()
    val visited = hashSetOf<Pair<Int, Map<Int, ItemValue>>>()
    val queue = ArrayDeque<Pair<Int, Map<Int, ItemValue>>>()
    queue += 0 to mapOf(itemRegister to ItemValue.ItemParameter)
    var steps = 0
    while (queue.isNotEmpty()) {
        if (++steps > MAX_ITEM_PATH_STEPS) {
            throw PatchException("NewX drawer item mapper is too complex to explore: $this")
        }
        var (index, entryState) = queue.removeFirst()
        if (!visited.add(index to entryState)) continue
        val state = entryState.toMutableMap()
        var pendingResult: ItemValue? = null
        walk@ while (index in instructions.indices) {
            val instruction = instructions[index]
            fun target(offsetInstruction: OffsetInstruction): Int =
                indexByAddress[addresses[index] + offsetInstruction.codeOffset]
                    ?: throw PatchException("NewX drawer item mapper branch has no target: $this @ $index")
            if (instruction is NarrowLiteralInstruction) result.literals += instruction.narrowLiteral
            when (instruction.opcode) {
                Opcode.SGET_OBJECT -> {
                    val field = instruction.getReference<FieldReference>()
                    val register = (instruction as OneRegisterInstruction).registerA
                    when {
                        field == null -> state.remove(register)
                        field.type.startsWith(ITEM_ICONS_PREFIX) -> state[register] = ItemValue.Icon(field)
                        field.type == field.definingClass -> state[register] = ItemValue.Singleton(field.type)
                        else -> state.remove(register)
                    }
                }
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                    val reference = instruction.getReference<MethodReference>()
                    val registers = instruction.argumentRegisters()
                    pendingResult =
                        if (reference?.definingClass == ARE_EQUAL_OWNER && reference.returnType == "Z" &&
                            registers.size == 2
                        ) {
                            val values = registers.map { state[it] }
                            val singleton = values.filterIsInstance<ItemValue.Singleton>().singleOrNull()
                            if (ItemValue.ItemParameter in values && singleton != null) {
                                ItemValue.Known(singleton.type == itemType)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                }
                Opcode.MOVE_RESULT -> {
                    val register = (instruction as OneRegisterInstruction).registerA
                    val value = pendingResult
                    if (value != null) state[register] = value else state.remove(register)
                    pendingResult = null
                }
                Opcode.INSTANCE_OF -> {
                    val two = instruction as TwoRegisterInstruction
                    val checkedType = instruction.getReference<TypeReference>()?.type
                    if (state[two.registerB] == ItemValue.ItemParameter && checkedType != null) {
                        val known = checkedType == itemType ||
                            runCatching { isAssignableTo(itemType, checkedType) }.getOrDefault(false)
                        state[two.registerA] = ItemValue.Known(known)
                    } else {
                        state.remove(two.registerA)
                    }
                }
                Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                    val two = instruction as TwoRegisterInstruction
                    val value = state[two.registerB]
                    if (value != null) state[two.registerA] = value else state.remove(two.registerA)
                }
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val register = (instruction as OneRegisterInstruction).registerA
                    val known = (state[register] as? ItemValue.Known)?.value
                    val jump = target(instruction as OffsetInstruction)
                    if (known == null) {
                        queue += jump to state.toMap()
                    } else {
                        val taken = if (instruction.opcode == Opcode.IF_EQZ) !known else known
                        if (taken) {
                            index = jump
                            continue@walk
                        }
                    }
                }
                Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> {
                    index = target(instruction as OffsetInstruction)
                    continue@walk
                }
                Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                    val payloadIndex = target(instruction as OffsetInstruction)
                    val payload = instructions[payloadIndex] as? SwitchPayload
                        ?: throw PatchException("NewX drawer item mapper switch has no payload: $this @ $index")
                    payload.switchElements.forEach { element ->
                        val caseIndex = indexByAddress[addresses[index] + element.offset]
                            ?: throw PatchException("NewX drawer item mapper switch case has no target: $this")
                        queue += caseIndex to state.toMap()
                    }
                }
                Opcode.RETURN_OBJECT -> {
                    val register = (instruction as OneRegisterInstruction).registerA
                    (state[register] as? ItemValue.Icon)?.let { result.returnedIcons += it.field }
                    break@walk
                }
                Opcode.RETURN, Opcode.RETURN_VOID, Opcode.RETURN_WIDE, Opcode.THROW -> break@walk
                else -> {
                    if (instruction.opcode.name.startsWith("IF_")) {
                        queue += target(instruction as OffsetInstruction) to state.toMap()
                    }
                    if (instruction.opcode.name.startsWith("INVOKE_")) pendingResult = null
                    instruction.destinationRegisterOrNull()?.let { register ->
                        state.remove(register)
                        if (instruction.opcode.name.endsWith("_WIDE")) state.remove(register + 1)
                    }
                }
            }
            index++
        }
    }
    return result
}

internal fun Instruction.argumentRegisters(): List<Int> =
    when (this) {
        is Instruction35c ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is Instruction3rc -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

/**
 * The composable that renders one menu item of a model-driven drawer: it maps the item to its icon
 * and calls the title-based row renderer directly. [itemRegister] and [clickRegister] are the
 * parameter registers holding the item and the row's click callback.
 */
internal data class DrawerMenuItemRenderer(
    val method: MutableMethod,
    val itemRegister: Int,
    val clickRegister: Int,
)

internal fun resolveDrawerMenuItemRenderer(model: DrawerItemModel): DrawerMenuItemRenderer {
    val candidates = buildList {
        classDefForEach { classDef ->
            if (!classDef.type.startsWith(DRAWER_SCOPE)) return@classDefForEach
            classDef.methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                val iconCalls = instructions.indices.filter { instructions[it].invokesSame(model.iconMethod) }
                val rowCalls = instructions.indices.filter { index ->
                    instructions[index].opcode == Opcode.INVOKE_STATIC_RANGE &&
                        instructions[index].getReference<MethodReference>()?.isDrawerRowRenderer() == true
                }
                // The drawer patch emits extra rows after the original row call with the same
                // renderer, so the first call is the item's own row.
                val rowRenderers = rowCalls.mapNotNull { instructions[it].getReference<MethodReference>() }
                    .map { "${it.definingClass}->${it.name}${it.parameterTypes}" }.distinct()
                if (iconCalls.size == 1 && rowRenderers.size == 1) add(Triple(method, iconCalls.single(), rowCalls.first()))
            }
        }
    }
    if (candidates.size != 1) {
        throw PatchException(
            "Expected one NewX drawer menu item renderer, found ${candidates.size}: " +
                candidates.joinToString { it.first.toString() },
        )
    }
    val (method, iconCallIndex, rowCallIndex) = candidates.single()
    val implementation = method.implementation!!
    val instructions = implementation.instructions.toList()
    val parameterStart = implementation.registerCount - method.parameterTypes.sumOf { type ->
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    val itemArgument = instructions[iconCallIndex].argumentRegisters().singleOrNull()
        ?: throw PatchException("NewX drawer icon mapper call has no single argument: $method")
    val rowCall = instructions[rowCallIndex] as Instruction3rc
    val rowParameters = rowCall.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString)
    val clickArgument = rowCall.startRegister + rowParameters.indexOf("Lkotlin/jvm/functions/Function0;")
    val itemRegister = instructions.traceToParameter(iconCallIndex, itemArgument, parameterStart)
        ?: throw PatchException("NewX drawer menu item is not a parameter of $method")
    val clickRegister = instructions.traceToParameter(rowCallIndex, clickArgument, parameterStart)
        ?: throw PatchException("NewX drawer menu item click is not a parameter of $method")
    if (parameterStart < 1) throw PatchException("NewX drawer menu item renderer has no local register: $method")
    val mutableMethod = mutableClassDefBy(method.definingClass).methods.single { candidate ->
        candidate.name == method.name && candidate.returnType == method.returnType &&
            candidate.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
    return DrawerMenuItemRenderer(mutableMethod, itemRegister, clickRegister)
}

private fun Instruction.invokesSame(reference: MethodReference): Boolean {
    val target = getReference<MethodReference>() ?: return false
    return target.definingClass == reference.definingClass && target.name == reference.name &&
        target.returnType == reference.returnType &&
        target.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
}

/** Follows object moves back from [index] until [register] is an untouched parameter register. */
private fun List<Instruction>.traceToParameter(index: Int, register: Int, parameterStart: Int): Int? {
    var current = register
    for (cursor in index - 1 downTo 0) {
        val instruction = this[cursor]
        if (instruction.destinationRegisterOrNull() != current) continue
        if (instruction.opcode !in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
            return null
        }
        current = (instruction as TwoRegisterInstruction).registerB
    }
    return current.takeIf { it >= parameterStart }
}
