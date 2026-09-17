// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sirga.studio.engine.model.Scene
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.model.SourceKind
import com.sirga.studio.engine.model.StreamDestination
import com.sirga.studio.engine.render.PreviewSlot
import com.sirga.studio.engine.studio.StudioState
import com.sirga.studio.ui.destinations.Callout
import com.sirga.studio.ui.destinations.DestinationEditor
import com.sirga.studio.ui.destinations.DestinationsPanel
import com.sirga.studio.ui.destinations.DestinationsUi
import com.sirga.studio.ui.destinations.DestinationsViewModel
import com.sirga.studio.ui.destinations.PrimaryAction
import com.sirga.studio.ui.settings.SettingsScreen
import com.sirga.studio.ui.theme.Sirga
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(vm: StudioViewModel, destinationsVm: DestinationsViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dockTab by vm.dockTab.collectAsStateWithLifecycle()
    val destinations by destinationsVm.ui.collectAsStateWithLifecycle()
    val editor by destinationsVm.editor.collectAsStateWithLifecycle()
    val propertiesFor by vm.propertiesFor.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val missingPermissions by vm.missingPermissions.collectAsStateWithLifecycle()
    val screenCaptureActive by vm.screenCaptureActive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sceneToDelete by remember { mutableStateOf<Scene?>(null) }
    var destinationToDelete by remember { mutableStateOf<StreamDestination?>(null) }
    var confirmEndLive by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::addImage)
    }
    val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onScreenCaptureResult(result.resultCode, result.data)
    }
    val requestScreenCapture = { screenCapture.launch(vm.screenCaptureIntent()) }

    // La pantalla no se apaga sola mientras se emite o se graba (ni con el estudio abierto, si así se ajusta)
    val keepOnSettings by vm.settings.collectAsStateWithLifecycle()
    LocalView.current.keepScreenOn = state.isLive || state.isRecording || keepOnSettings.video.keepScreenOn

    // Recolección secuencial: consumir el aviso no cancela el snackbar que se está mostrando
    LaunchedEffect(Unit) {
        launch {
            vm.notice.filterNotNull().collect {
                vm.consumeNotice()
                snackbar.showSnackbar(it)
            }
        }
        launch {
            destinationsVm.notice.filterNotNull().collect {
                destinationsVm.consumeNotice()
                snackbar.showSnackbar(it)
            }
        }
    }

    val needsScreenCapture = !screenCaptureActive && state.sources.values.any { it is Source.Screen || it is Source.InternalAudio }

    val actions = StudioActions(
        vm = vm,
        destinationsVm = destinationsVm,
        onRemoveScene = { id -> sceneToDelete = state.scenes.firstOrNull { it.id == id } },
        onDeleteDestination = { id -> destinationToDelete = destinations.rows.firstOrNull { it.destination.id == id }?.destination },
        // Un toque sin querer no debe cortar el directo: terminar pide confirmación
        onGoLive = {
            if (state.isLive) confirmEndLive = true
            else if (!destinationsVm.goLive()) vm.showDock(DockTab.Destinations)
        },
        onAddSource = { kind ->
            if (kind == SourceKind.Image) imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else vm.addSource(kind)
        },
        needsScreenCapture = needsScreenCapture,
        onRequestScreenCapture = requestScreenCapture,
    )

    Scaffold(containerColor = Sirga.colors.ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (missingPermissions) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Callout("Sin permiso de cámara o micrófono no se puede capturar.", Sirga.colors.record) }
                    PrimaryAction("PERMITIR", onRequestPermissions)
                }
            }
            // El diseño depende del espacio real de la ventana, no de si es móvil o tablet: así también
            // se adapta a la pantalla partida y a las ventanas emergentes
            BoxWithConstraints(Modifier.fillMaxSize()) {
                when {
                    maxWidth >= 1000.dp && maxHeight >= 600.dp -> TabletStudio(state, dockTab, destinations, actions, maxHeight)
                    maxWidth > maxHeight && maxWidth >= 900.dp -> LandscapeStudio(state, dockTab, destinations, actions, maxWidth)
                    else -> PortraitStudio(state, dockTab, destinations, actions, maxHeight)
                }
            }
        }
    }

    editor?.let { DestinationEditor(it, destinationsVm) }

    propertiesFor?.let { sourceId -> SourceProperties(vm, state, sourceId, screenCaptureActive, requestScreenCapture) }

    val pcPicker by vm.pcPicker.collectAsStateWithLifecycle()
    pcPicker?.let { kind ->
        val pcDevices by vm.pcDevices.collectAsStateWithLifecycle()
        val pcStatuses by vm.pcLinkStatus.collectAsStateWithLifecycle()
        PcDevicePickerDialog(
            kind = kind,
            pcSources = state.sources.values.filterIsInstance<Source.PcInput>(),
            devices = pcDevices,
            statuses = pcStatuses,
            onPick = vm::addPcDevice,
            onAddPcSource = { vm.closePcPicker(); vm.addSource(SourceKind.PcInput) },
            onDismiss = vm::closePcPicker,
        )
    }

    if (settingsOpen) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val outputs by vm.audioOutputs.collectAsStateWithLifecycle()
        val thermal by vm.thermal.collectAsStateWithLifecycle()
        SettingsScreen(
            settings = settings,
            outputs = outputs,
            thermal = thermal,
            busy = state.isLive || state.isRecording,
            onUpdate = vm::updateSettings,
            onReset = vm::resetSettings,
            onClose = { vm.openSettings(false) },
        )
    }

    sceneToDelete?.let { scene ->
        ConfirmDelete(
            title = "¿Eliminar «${scene.name}»?",
            message = if (state.scenes.size <= 1) "Necesitas al menos una escena."
            else "Se quitan sus ${scene.items.size} elementos. Las fuentes siguen disponibles en otras escenas.",
            enabled = state.scenes.size > 1,
            onConfirm = { vm.removeScene(scene.id) },
            onDismiss = { sceneToDelete = null },
        )
    }

    if (confirmEndLive) {
        ConfirmDelete(
            title = "¿Terminar el directo?",
            message = if (state.isRecording) "Se corta la emisión en todos los destinos. La grabación sigue." else "Se corta la emisión en todos los destinos.",
            confirmLabel = "Terminar",
            onConfirm = destinationsVm::endLive,
            onDismiss = { confirmEndLive = false },
        )
    }

    destinationToDelete?.let { destination ->
        ConfirmDelete(
            title = "¿Eliminar «${destination.name}»?",
            message = "También se borra su clave guardada. Tendrás que volver a pegarla para usar este destino.",
            onConfirm = { destinationsVm.delete(destination.id) },
            onDismiss = { destinationToDelete = null },
        )
    }
}

@Composable
private fun SourceProperties(vm: StudioViewModel, state: StudioState, sourceId: String, screenCaptureActive: Boolean, onRequestScreenCapture: () -> Unit) {
    val source = state.sources[sourceId] ?: return
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val usbCameras by vm.usbCameras.collectAsStateWithLifecycle()
    val inputs by vm.audioInputs.collectAsStateWithLifecycle()
    val sourceStatus by vm.sourceStatus.collectAsStateWithLifecycle()
    val audioErrors by vm.audioErrors.collectAsStateWithLifecycle()
    val pcLinkStatus by vm.pcLinkStatus.collectAsStateWithLifecycle()
    val pcDevices by vm.pcDevices.collectAsStateWithLifecycle()
    // Una cámara o un micrófono del PC muestra el estado y los dispositivos de su fuente PC
    val pcSourceId = when (source) {
        is Source.PcCamera -> source.pcSourceId
        is Source.PcMicrophone -> source.pcSourceId
        else -> sourceId
    }
    val item = state.editingScene?.items?.firstOrNull { it.sourceId == sourceId && it.id == state.selectedItemId }
        ?: state.editingScene?.items?.firstOrNull { it.sourceId == sourceId }
    val error = (sourceStatus[sourceId] as? com.sirga.studio.engine.capture.CaptureStatus.Error)?.message ?: audioErrors[sourceId]

    SourcePropertiesDialog(
        model = SourcePropertiesModel(
            source = source,
            item = item,
            channel = state.audio.firstOrNull { it.sourceId == sourceId },
            cameras = cameras,
            usbCameras = usbCameras,
            inputs = inputs,
            screenCaptureActive = screenCaptureActive,
            error = error,
            pcLink = pcLinkStatus[pcSourceId],
            pcDevices = pcDevices[pcSourceId].orEmpty(),
            pcSource = state.sources[pcSourceId] as? Source.PcInput,
        ),
        onClose = { vm.openProperties(null) },
        onUpdate = vm::updateSource,
        onRename = { vm.renameSource(sourceId, it) },
        onTransform = { t -> item?.let { vm.setTransform(it.id, t) } },
        onChannel = vm::setChannel,
        onDelete = { vm.deleteSource(sourceId) },
        onRequestScreenCapture = onRequestScreenCapture,
        onAddPcDevice = { device -> vm.addPcDevice(sourceId, device) },
    )
}

private class StudioActions(
    val vm: StudioViewModel,
    val destinationsVm: DestinationsViewModel,
    val onRemoveScene: (String) -> Unit,
    val onDeleteDestination: (String) -> Unit,
    val onGoLive: () -> Unit,
    val onAddSource: (SourceKind) -> Unit,
    val needsScreenCapture: Boolean,
    val onRequestScreenCapture: () -> Unit,
)

/** Horizontal: escenas a la izquierda, lienzo al centro y dock a la derecha (mesa de control). */
@Composable
private fun LandscapeStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions, screenWidth: Dp) {
    val vm = actions.vm
    val narrow = screenWidth < 1000.dp
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, compact = false, onSettings = { vm.openSettings(true) }, thermalPolicy = vm.settings.collectAsStateWithLifecycle().value.thermal.policy, onThermalPolicy = { p -> vm.updateSettings { it.copy(thermal = it.thermal.copy(policy = p)) } })
        Row(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScenesPanel(
                state = state,
                vertical = true,
                onSelect = vm::selectScene,
                onAdd = vm::addScene,
                onRemove = actions.onRemoveScene,
                modifier = Modifier.width(if (narrow) 160.dp else 180.dp).fillMaxHeight().padding(bottom = 8.dp),
            )
            // Lienzo y controles juntos, centrados: sin huecos entre la imagen y los botones
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                CanvasArea(state, vm, Modifier.fillMaxWidth().weight(1f, fill = false))
                TransportBar(state, destinations.enabledCount, vm::setStudioMode, vm::transition, vm::toggleRecording, actions.onGoLive)
            }
            Dock(state, dockTab, destinations, actions, Modifier.width(if (narrow) 300.dp else 320.dp).fillMaxHeight().padding(bottom = 8.dp))
        }
    }
}

/**
 * Tablet o pantalla grande en horizontal: como en un estudio de escritorio, el lienzo arriba y debajo las escenas y el
 * mezclador siempre a la vista; fuentes y destinos en el panel lateral.
 */
@Composable
private fun TabletStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions, screenHeight: Dp) {
    val vm = actions.vm
    val bottomHeight = (screenHeight * 0.32f).coerceIn(220.dp, 320.dp)
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, compact = false, onSettings = { vm.openSettings(true) }, thermalPolicy = vm.settings.collectAsStateWithLifecycle().value.thermal.policy, onThermalPolicy = { p -> vm.updateSettings { it.copy(thermal = it.thermal.copy(policy = p)) } })
        Row(
            Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                CanvasArea(state, vm, Modifier.fillMaxWidth().weight(1f))
                TransportBar(state, destinations.enabledCount, vm::setStudioMode, vm::transition, vm::toggleRecording, actions.onGoLive)
                Row(Modifier.fillMaxWidth().height(bottomHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScenesPanel(
                        state = state,
                        vertical = true,
                        onSelect = vm::selectScene,
                        onAdd = vm::addScene,
                        onRemove = actions.onRemoveScene,
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )
                    Panel(Modifier.weight(1f).fillMaxHeight()) {
                        PanelHeader("MEZCLADOR")
                        MixerContent(state, vm)
                    }
                }
            }
            val sideTab = if (dockTab == DockTab.Mixer) DockTab.Sources else dockTab
            Dock(state, sideTab, destinations, actions, Modifier.width(360.dp).fillMaxHeight(), tabs = listOf(DockTab.Sources, DockTab.Destinations))
        }
    }
}

/** Vertical: lienzo arriba, controles bajo el pulgar y dock ocupando el resto. */
@Composable
private fun PortraitStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions, screenHeight: Dp) {
    val vm = actions.vm
    // En ventanas bajitas (ventana emergente, pantalla partida) no cabe todo: se desplaza la pantalla entera
    // y el dock conserva una altura usable
    val short = screenHeight < 600.dp
    Column(Modifier.fillMaxSize().then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        StatusStrip(state, compact = true, onSettings = { vm.openSettings(true) }, thermalPolicy = vm.settings.collectAsStateWithLifecycle().value.thermal.policy, onThermalPolicy = { p -> vm.updateSettings { it.copy(thermal = it.thermal.copy(policy = p)) } })
        // Un lienzo vertical en un móvil vertical sería más alto que la pantalla y taparía los controles:
        // se limita su altura y la vista previa encaja dentro conservando la proporción
        CanvasArea(state, vm, Modifier.fillMaxWidth().heightIn(max = screenHeight * if (short) 0.6f else 0.42f).padding(horizontal = 8.dp))
        TransportBar(state, destinations.enabledCount, vm::setStudioMode, vm::transition, vm::toggleRecording, actions.onGoLive)
        ScenesPanel(
            state = state,
            vertical = false,
            onSelect = vm::selectScene,
            onAdd = vm::addScene,
            onRemove = actions.onRemoveScene,
            modifier = Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 8.dp),
        )
        Dock(
            state, dockTab, destinations, actions,
            if (short) Modifier.fillMaxWidth().height(360.dp).padding(8.dp) else Modifier.weight(1f).fillMaxWidth().padding(8.dp),
        )
    }
}

/** Fuera del modo estudio hay un solo lienzo; dentro, previo (editable) y programa lado a lado. */
@Composable
private fun CanvasArea(state: StudioState, vm: StudioViewModel, modifier: Modifier) {
    val sourceStatus by vm.sourceStatus.collectAsStateWithLifecycle()
    if (!state.studioMode) {
        PreviewCanvas(
            canvas = state.canvas,
            scene = state.programScene,
            sources = state.sources,
            selectedItemId = state.selectedItemId,
            slot = PreviewSlot.Edit,
            sourceStatus = sourceStatus,
            onSurface = vm::setPreviewSurface,
            onSelect = vm::selectItem,
            onTransform = vm::setTransform,
            modifier = modifier,
        )
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PREVIO", style = Sirga.panelLabel, color = Sirga.colors.accentText, modifier = Modifier.padding(bottom = 4.dp))
            PreviewCanvas(
                canvas = state.canvas,
                scene = state.previewScene,
                sources = state.sources,
                selectedItemId = state.selectedItemId,
                slot = PreviewSlot.Edit,
                sourceStatus = sourceStatus,
                onSurface = vm::setPreviewSurface,
                onSelect = vm::selectItem,
                onTransform = vm::setTransform,
                frameColor = Sirga.colors.accent,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("PROGRAMA", style = Sirga.panelLabel, color = Sirga.colors.live, modifier = Modifier.padding(bottom = 4.dp))
            PreviewCanvas(
                canvas = state.canvas,
                scene = state.programScene,
                sources = state.sources,
                selectedItemId = null,
                slot = PreviewSlot.Program,
                sourceStatus = sourceStatus,
                onSurface = vm::setPreviewSurface,
                onSelect = {},
                onTransform = { _, _ -> },
                frameColor = Sirga.colors.live,
                editable = false,
            )
        }
    }
}

@Composable
private fun Dock(
    state: StudioState,
    tab: DockTab,
    destinations: DestinationsUi,
    actions: StudioActions,
    modifier: Modifier,
    tabs: List<DockTab> = DockTab.entries,
) {
    val vm = actions.vm
    val dvm = actions.destinationsVm
    Panel(modifier) {
        DockTabs(tabs, tab, { it.label }, vm::showDock)
        when (tab) {
            DockTab.Sources -> SourcesPanel(
                state = state,
                onAdd = actions.onAddSource,
                onSelect = vm::selectItem,
                onToggleVisibility = vm::toggleVisibility,
                onToggleLock = vm::toggleLock,
                onRaise = vm::raise,
                onLower = vm::lower,
                onRemove = vm::removeItem,
                onProperties = vm::openProperties,
                needsScreenCapture = actions.needsScreenCapture,
                onRequestScreenCapture = actions.onRequestScreenCapture,
            )
            DockTab.Mixer -> MixerContent(state, vm)
            DockTab.Destinations -> DestinationsPanel(
                ui = destinations,
                onAdd = dvm::openNew,
                onToggle = dvm::setEnabled,
                onTest = dvm::test,
                onEdit = dvm::openEdit,
                onDelete = actions.onDeleteDestination,
            )
        }
    }
}

@Composable
private fun MixerContent(state: StudioState, vm: StudioViewModel) {
    val levels = vm.levels.collectAsStateWithLifecycle()
    val errors by vm.audioErrors.collectAsStateWithLifecycle()
    val inputs by vm.audioInputs.collectAsStateWithLifecycle()
    val monitorStatus by vm.monitorStatus.collectAsStateWithLifecycle()
    MixerPanel(state, { levels.value }, errors, inputs, monitorStatus, vm::setGain, vm::toggleMute, vm::openProperties)
}

@Composable
private fun ConfirmDelete(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    confirmLabel: String = "Eliminar",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(enabled = enabled, onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = Sirga.colors.live)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = Sirga.colors.raised,
    )
}
